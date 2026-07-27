import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * Mechanical style enforcement, shared by all three convention plugins.
 *
 * ktlint reads `.editorconfig`, so indent width, line length and star-import policy are specified
 * once, there, for the IDE and the build at the same time — nothing about them is repeated here.
 *
 * The plugin wires `ktlintCheck` into `check`, which is what puts it in CI without a workflow
 * change: the one job runs `build`. `ktlintFormat` fixes what is mechanically fixable.
 *
 * @see registerModulePurityCheck for the other half of what `check` enforces — that one is about
 *   the module graph rather than about style, and it is the one with teeth.
 */
fun Project.applyKtlint() {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")

    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

    extensions.configure<KtlintExtension> {
        version.set(libs.findVersion("ktlint").get().requiredVersion)
    }
}
