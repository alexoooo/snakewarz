import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/**
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
