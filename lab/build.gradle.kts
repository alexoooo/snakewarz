plugins {
    id("snakewarz.tool")
}

application {
    mainClass = "ao.snakewarz.lab.MainKt"
}

dependencies {
    // The only module besides :app that sees both a bot registry and the match driver, and the same
    // reason it may: it *injects* ShippedBots into a Tournament that knows nothing but the
    // BotRegistry interface. That is the sanctioned inversion, not a new edge — :match still has
    // never seen a bot class. What is forbidden here is the other direction, and :lab:checkModulePurity
    // enforces it: nothing in :ui or :app, and nothing anywhere depends on :lab.
    implementation(project(":core"))
    implementation(project(":bot-api"))
    implementation(project(":bots"))
    implementation(project(":match"))
}
