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
        getByName("wasmJsMain").dependencies {
            implementation(project(":core"))
            implementation(libs.kotlinx.browser)
        }
    }
}
