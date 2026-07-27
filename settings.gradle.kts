rootProject.name = "snakewarz"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Modules are added as their phase lands. See docs/MIGRATION.md for the full graph
// and, more importantly, for the forbidden dependency edges.
include(":core")
include(":bot-api")
include(":bots")
include(":match")
include(":ui")
include(":app")

// Not part of the app and not on its classpath: a JVM command line for running batches headlessly,
// which is the one thing a browser is a bad place to do. Nothing may depend on it.
include(":lab")
