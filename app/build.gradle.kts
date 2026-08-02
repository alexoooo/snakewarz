import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.time.Instant
import java.time.temporal.ChronoUnit

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

/*
 * The timestamp belongs to the resources every browser path serves, including the development
 * server. Resource processing is deliberately never up to date or cached: each playable browser
 * build writes the instant its page was assembled instead of restoring another build's label.
 */
tasks.named<ProcessResources>("wasmJsProcessResources") {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    doLast {
        val index = destinationDir.resolve("index.html")
        val releasedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val releaseElement = """<time id="release-build" datetime="$releasedAt">Release · $releasedAt</time>"""
        val releaseElementPattern = Regex("""<time id="release-build" datetime="[^"]*">[^<]*</time>""")
        val page = index.readText()

        check(releaseElementPattern.containsMatchIn(page)) { "Missing #release-build in ${index.path}" }
        index.writeText(page.replace(releaseElementPattern, releaseElement))
    }
}
