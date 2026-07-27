import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * Architectural enforcement, shared by both convention plugins.
 *
 * The module graph is the only layering guard that cannot be bypassed, so it is checked by the build
 * rather than by convention. Test source sets are checked too — an integration test is not a licence
 * to cross a layer, and a test dependency is still an edge in the resolved graph.
 *
 * @param forbiddenProjects project paths this module may not reach, transitively or otherwise
 * @param forbiddenModules external artifact names it may not reach, by `module` rather than by group
 */
fun Project.registerModulePurityCheck(forbiddenProjects: Set<String>, forbiddenModules: Set<String>) {
    val modulePath = path
    val roots = configurations
        // Case-insensitively, because a Kotlin/JVM module names its main one `compileClasspath` while
        // a multiplatform one names every last of them `<target>CompileClasspath`. Matching only the
        // capitalised spelling would check :lab's tests and miss :lab itself.
        .matching { it.isCanBeResolved && it.name.endsWith("CompileClasspath", ignoreCase = true) }
        .associate { it.name to it.incoming.resolutionResult.rootComponent }

    val checkModulePurity = tasks.register("checkModulePurity") {
        group = "verification"
        description = "Fails if this module depends on a project or an artifact below it in the module graph."

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
}
