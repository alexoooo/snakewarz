import org.gradle.api.artifacts.VersionCatalogsExtension

/*
 * Convention for a *measuring instrument*: a JVM command-line tool, never deployed and never on the
 * wasm bundle's classpath.
 *
 * The one module allowed a clock and a `println`. `:core`, `:bot-api`, `:bots` and `:match` are
 * forbidden both, which is what makes a match reproducible — a bot that could read a clock would
 * make its own iteration count depend on the machine. A tool that reports how long a batch took has
 * to read one, so it lives outside all four rather than inside any of them.
 *
 * Deliberately not `snakewarz.pure`: that would force a `wasmJs { browser() }` compile of something
 * with a `main(args)`, and register a Karma task for a module that has no browser tests to run.
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val jvmToolchainVersion = libs.findVersion("jvmToolchain").get().requiredVersion.toInt()

kotlin {
    explicitApi()
    jvmToolchain(jvmToolchainVersion)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

applyKtlint()

// The same architectural enforcement the other two conventions get, and for a module that sees more
// than any of them it matters more: :lab reaches both a bot registry and the match driver, which
// nothing below it may do. What it may not reach is anything that draws.
registerModulePurityCheck(
    forbiddenProjects = setOf(":ui", ":app"),
    forbiddenModules = setOf("kotlinx-browser"),
)
