import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/**
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

// ---------------------------------------------------------------------------------------------
// Architectural enforcement.
//
// The module graph is the only layering guard that cannot be bypassed, so it is checked by the
// build rather than by convention. Keeping these modules pure is what lets us (a) run tests on
// the JVM, and (b) add a Kotlin/JS fallback target later as a config change rather than a rewrite.
// ---------------------------------------------------------------------------------------------

// The forbidden-edge table from docs/MIGRATION.md, encoded where it can actually be enforced.
// A module may depend only on the ones above it, so each entry lists everything below.
// Test source sets are checked too — an integration test is not a licence to cross a layer.
val forbiddenByModule = mapOf(
    ":core" to setOf(":bot-api", ":bots", ":match", ":ui", ":app"),
    ":bot-api" to setOf(":bots", ":match", ":ui", ":app"),
    ":bots" to setOf(":match", ":ui", ":app"),
    ":match" to setOf(":bots", ":ui", ":app"),
)

val forbiddenProjects = forbiddenByModule[project.path] ?: setOf(":ui", ":app")
val forbiddenModules = setOf("kotlinx-browser")

val checkModulePurity = tasks.register("checkModulePurity") {
    group = "verification"
    description = "Fails if this platform-free module depends on a browser artifact or a browser-only project."

    val modulePath = project.path
    val roots = configurations
        .matching { it.isCanBeResolved && it.name.endsWith("CompileClasspath") }
        .associate { it.name to it.incoming.resolutionResult.rootComponent }

    doLast {
        val violations = mutableSetOf<String>()

        for ((configurationName, rootProvider) in roots) {
            val seen = mutableSetOf<ResolvedComponentResult>()

            fun visit(component: ResolvedComponentResult) {
                if (!seen.add(component)) return

                when (val id = component.id) {
                    is ProjectComponentIdentifier ->
                        if (id.projectPath in forbiddenProjects) {
                            violations += "$configurationName -> project ${id.projectPath}"
                        }

                    is ModuleComponentIdentifier ->
                        if (id.module in forbiddenModules) {
                            violations += "$configurationName -> ${id.group}:${id.module}"
                        }

                    else -> Unit
                }

                component.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .forEach { visit(it.selected) }
            }

            visit(rootProvider.get())
        }

        if (violations.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Module $modulePath may not depend on:")
                    violations.sorted().forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("See \"Forbidden dependency edges\" in CLAUDE.md. Do not add these, even")
                    appendLine("temporarily, and not in a test source set either — a test dependency is")
                    appendLine("still an edge in the graph. When a module seems to need something below")
                    appendLine("it, the dependency is pointing the wrong way: invert it behind an")
                    appendLine("interface here and inject the implementation from :app.")
                },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkModulePurity)
}
