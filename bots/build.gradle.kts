plugins {
    id("snakewarz.pure")
}

kotlin {
    sourceSets {
        // No :match, no :ui, no :app — ever. A bot must not be able to reach the clock, the driver
        // or another slot's RNG. Enforced by :bots:checkModulePurity.
        getByName("commonMain").dependencies {
            api(project(":core"))
            api(project(":bot-api"))
        }
    }
}
