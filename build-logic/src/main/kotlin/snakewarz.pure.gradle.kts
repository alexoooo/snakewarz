import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Convention for a *platform-free* module: pure common Kotlin, no browser and no JVM APIs.
 *
 * Ships as `wasmJs`. Also compiles for `jvm()` **purely to run tests fast** — that target is never
 * deployed and contributes nothing to the wasm bundle. It doubles as a second compiler proving the
 * module really is platform-free.
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

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        getByName("commonTest").dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Browser tests need Karma + a real Chrome and take seconds to start. The fast JVM suite runs on
// every push; the browser conformance suite is one dedicated CI job (-PbrowserTests=true).
tasks.matching { it.name == "wasmJsBrowserTest" }.configureEach {
    enabled = browserTests
}

applyKtlint()

// ---------------------------------------------------------------------------------------------
// Architectural enforcement.
//
// Keeping these modules pure is what lets us (a) run tests on the JVM, and (b) add a Kotlin/JS
// fallback target later as a config change rather than a rewrite. The check itself lives in
// ModulePurity.kt, because snakewarz.browser enforces the same table for :ui.
// ---------------------------------------------------------------------------------------------

// The forbidden-edge table from CLAUDE.md, encoded where it can actually be enforced.
// A module may depend only on the ones above it, so each entry lists everything below.
val forbiddenByModule = mapOf(
    ":core" to setOf(":bot-api", ":bots", ":match", ":ui", ":app", ":lab"),
    ":bot-api" to setOf(":bots", ":match", ":ui", ":app", ":lab"),
    ":bots" to setOf(":match", ":ui", ":app", ":lab"),
    ":match" to setOf(":bots", ":ui", ":app", ":lab"),
)

registerModulePurityCheck(
    forbiddenProjects = forbiddenByModule[project.path] ?: setOf(":ui", ":app"),
    forbiddenModules = setOf("kotlinx-browser"),
)
