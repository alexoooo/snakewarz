plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.ktlint.gradlePlugin)
}

// The convention plugins hold the same call for every other module, but nothing can apply a
// convention plugin to the build that compiles it — so the version pin is repeated here, and only
// here. The root build's `check` reaches this build's `ktlintCheck`, which is what puts it in CI.
ktlint {
    version.set(libs.versions.ktlint)

    // `kotlin-dsl` generates the typed accessors and one adapter class per convention plugin into
    // the main source set, and they are machine-written Kotlin nobody can fix. Lint what is
    // hand-written.
    //
    // Patterns rather than a `Spec` over the absolute path, so nothing machine-specific reaches the
    // task's inputs. They match each source directory's own relative paths, which is why they read
    // as package paths instead of `**/build/**`.
    filter {
        exclude("gradle/kotlin/dsl/**")
        exclude("Snakewarz_*Plugin.kt")
    }
}
