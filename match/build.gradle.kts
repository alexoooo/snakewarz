plugins {
    id("snakewarz.pure")
}

kotlin {
    sourceSets {
        // Note what is *not* here: :bots. The driver resolves every slot through the BotRegistry
        // interface and :app injects the implementation, which is what keeps the replay codec free
        // of bot classes — a replay decodes to slugs, and the codec has no opinion about them.
        // Not in the test source set either. Enforced by :match:checkModulePurity.
        getByName("commonMain").dependencies {
            api(project(":core"))
            api(project(":bot-api"))
        }
    }
}
