// Root build file holds no logic about the app. Shared configuration lives in build-logic
// convention plugins (snakewarz.pure, snakewarz.browser, snakewarz.tool) so that module build
// files stay ~10 lines.

plugins {
    // Only for the `check` task the next block hangs a dependency on. Nothing is built here.
    base
}

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}

// build-logic lints itself, but an included build's `check` is not part of the root build's — so
// without this edge `./gradlew build` would style-check every module except the one that decides
// how they are all configured. This is the only thing that reaches across the build boundary.
tasks.check {
    dependsOn(gradle.includedBuild("build-logic").task(":ktlintCheck"))
}
