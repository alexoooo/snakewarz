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
include(":app")
