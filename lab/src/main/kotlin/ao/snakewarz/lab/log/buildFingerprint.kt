package ao.snakewarz.lab.log

import java.util.concurrent.TimeUnit

/**
 * Which build of the bots a run measured, as `<short sha>` or `<short sha>+dirty`.
 *
 * **The column the whole loop turns on.** An expanded spec pins a bot's *settings*; nothing in it
 * pins a bot's *code*. Pool a run from before a change with a run from after it and the ratings
 * average across two different bots wearing one name — which is exactly the improvement the change
 * was made to measure, deleted by the measuring.
 *
 * `+dirty` is the honest half. A tuning session is mostly run against edits that are not committed
 * yet, and two dirty trees at the same commit are not the same bots; a reader that sees `+dirty`
 * knows the sha is a lower bound on what changed, not a description of it.
 *
 * Best effort by design: no git, no repository, or a git that takes too long all give [UNKNOWN]
 * rather than stopping a batch. A measurement whose provenance is unclear is worth having and worth
 * saying so about.
 */
internal fun buildFingerprint(): String {
    val sha = git("rev-parse", "--short", "HEAD") ?: return UNKNOWN
    val changes = git("status", "--porcelain") ?: return sha
    return if (changes.isEmpty()) sha else "$sha+dirty"
}

internal const val UNKNOWN: String = "unknown"

/** Seconds. Long enough for a cold repository, short enough that nobody waits on provenance. */
private const val GIT_TIMEOUT = 10L

private fun git(vararg arguments: String): String? =
    try {
        val process = ProcessBuilder("git", *arguments)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor(GIT_TIMEOUT, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
    } catch (absent: java.io.IOException) {
        // No git on the path, or nowhere to run it. Provenance is nice to have and never worth a throw.
        null
    }
