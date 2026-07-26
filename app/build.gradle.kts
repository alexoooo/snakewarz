import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("snakewarz.browser")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "snakewarz.js"
            }
        }
    }

    sourceSets {
        // The only module that sees the whole graph, and the only one that may: main() is where the
        // BotRegistry implementation is injected into a driver that only knows the interface.
        getByName("wasmJsMain").dependencies {
            implementation(project(":match"))
            implementation(project(":bots"))
            implementation(project(":ui"))
            implementation(libs.kotlinx.browser)
        }
    }
}
