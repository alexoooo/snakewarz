plugins {
    id("snakewarz.browser")
}

kotlin {
    sourceSets {
        // Note what is *not* here: :bots. The renderer paints a BoardView and the chrome names slots
        // through the BotRegistry interface, so nothing in :ui can tell a wall hugger from a human.
        // :app picks the registry. Enforced by :ui:checkModulePurity.
        getByName("wasmJsMain").dependencies {
            api(project(":core"))
            api(project(":match"))
            implementation(libs.kotlinx.browser)
        }
    }
}
