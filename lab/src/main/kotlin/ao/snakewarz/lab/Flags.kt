package ao.snakewarz.lab

import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.Replays
import ao.snakewarz.match.tournament.TournamentFormat
import java.nio.file.Path

/**
 * Options as typed, refused rather than coerced — see [LabCommand]'s note on strictness.
 *
 * The set of names a subcommand accepts is **its own**, so `--passes` on a batch is an error rather
 * than a setting nobody reads. A single flat namespace across subcommands would accept every name
 * everywhere, which is exactly the silent-default failure the strict parsing is here to prevent.
 */
internal class Flags(private val values: Map<String, String>, known: Set<String>) {
    init {
        for (name in values.keys) {
            require(name in known) { "no such option here: '--$name'. This subcommand takes: ${known.joinToString()}" }
        }
    }

    fun int(name: String, default: Int): Int {
        val text = values[name] ?: return default
        return text.toIntOrNull() ?: error("--$name wants a whole number, was '$text'")
    }

    fun long(name: String, default: Long): Long {
        val text = values[name] ?: return default
        return text.toLongOrNull() ?: error("--$name wants a whole number, was '$text'")
    }

    fun text(name: String): String? = values[name]

    fun decimal(name: String, default: Double): Double {
        val text = values[name] ?: return default
        return text.toDoubleOrNull() ?: error("--$name wants a number, was '$text'")
    }

    fun format(name: String): TournamentFormat {
        val text = values[name] ?: return TournamentFormat.HEAD_TO_HEAD
        return when (text) {
            "head", "head-to-head" -> TournamentFormat.HEAD_TO_HEAD
            "ffa", "free-for-all" -> TournamentFormat.FREE_FOR_ALL
            else -> error("--$name wants head or ffa, was '$text'")
        }
    }

    fun openings(name: String): Openings {
        val text = values[name] ?: return Openings.MIRRORED
        return when (text) {
            "fixed" -> Openings.FIXED
            "mirrored" -> Openings.MIRRORED
            "complete" -> Openings.COMPLETE
            else -> error("--$name wants fixed, mirrored or complete, was '$text'")
        }
    }

    fun replays(name: String): Replays {
        val text = values[name] ?: return Replays.DECISIVE
        return when (text) {
            "none" -> Replays.NONE
            "decisive" -> Replays.DECISIVE
            "all" -> Replays.ALL
            else -> error("--$name wants none, decisive or all, was '$text'")
        }
    }

    /**
     * A switch, which still takes a value.
     *
     * `--pool true` rather than a bare `--pool`, because every other option here is `--name value`
     * and one exception to that is a parser somebody has to remember the shape of. The value is
     * refused unless it is a boolean, so `--pool yes` is an error rather than a silent false.
     */
    fun flag(name: String): Boolean {
        val text = values[name] ?: return false
        return text.toBooleanStrictOrNull() ?: error("--$name wants true or false, was '$text'")
    }

    /** Where the match log lives, or `null` under `--log none` — a batch nobody wants remembered. */
    fun logDirectory(name: String): Path? {
        val text = values[name] ?: return Path.of(MatchLog.DEFAULT_DIRECTORY)
        return if (text == "none") null else Path.of(text)
    }
}
