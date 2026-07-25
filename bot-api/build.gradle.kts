plugins {
    id("snakewarz.pure")
}

kotlin {
    sourceSets {
        // `api`, not `implementation`: every signature here is written in :core's vocabulary —
        // BoardView, SnakeId, DirectionSet — so a bot author gets those types by depending on
        // :bot-api alone.
        getByName("commonMain").dependencies {
            api(project(":core"))
        }
    }
}
