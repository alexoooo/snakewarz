import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Convention for a browser-only module: may touch the DOM, the canvas and the clock.
 *
 * `wasmJs` only — no JVM target, because these modules cannot compile without browser APIs.
 * Everything that does not strictly need the DOM belongs in a `snakewarz.pure` module instead.
 */

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val jvmToolchainVersion = libs.findVersion("jvmToolchain").get().requiredVersion.toInt()
val browserTests = providers.gradleProperty("browserTests").map(String::toBoolean).getOrElse(false)

kotlin {
    explicitApi()
    jvmToolchain(jvmToolchainVersion)

    // Reaching JS is the definition of a browser module: `fillStyle` takes a `JsAny?`, so painting a
    // single rectangle needs `toJsString()`. Opting in once here rather than annotating every call
    // site, and deliberately *not* in snakewarz.pure, where the same warning would be a design
    // failure rather than noise.
    compilerOptions {
        optIn.add("kotlin.js.ExperimentalWasmJsInterop")
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        getByName("wasmJsTest").dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.matching { it.name == "wasmJsBrowserTest" }.configureEach {
    enabled = browserTests
}

applyKtlint()

// A browser module may touch the DOM, but it is still layered. `:ui` renders a BoardView and a
// MatchRecord and must not know which bot produced them — that is what keeps a replay a list of
// slugs and lets :app be the only place the registry is chosen. `:app` sits on top of everything
// and so forbids nothing.
registerModulePurityCheck(
    forbiddenProjects = if (project.path == ":ui") setOf(":bots", ":app") else emptySet(),
    forbiddenModules = emptySet(),
)
