package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.lab.strength.Bootstrap
import ao.snakewarz.lab.strength.Interval
import ao.snakewarz.lab.strength.Ladder
import ao.snakewarz.lab.strength.bootstrapIntervals
import ao.snakewarz.match.tournament.TournamentFormat
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The ladder as ratings, over everything in the log that is comparable.
 *
 * Ratings rather than win counts because a win count cannot be read across a field: a bot can win
 * more matches than another and still lose to it, having met easier opposition. What a rating adds is
 * a single ordering; what it hides is that the ordering may not exist, and the residual table below
 * is what makes that visible rather than a footnote.
 */
internal class RateCommand(
    val logDirectory: Path,
    val filters: Map<String, String>,
    val pool: Boolean,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val store = MatchLog(logDirectory)
        val runs = store.runs()
        require(runs.isNotEmpty()) { "nothing has been played into $logDirectory yet. Run `play` first." }

        val eligible = runs.filter { matches(it) }
        require(eligible.isNotEmpty()) { "no run in $logDirectory matches those filters. ${describe(runs)}" }

        val groups = eligible.groupBy { it.comparabilityKey }
        require(pool || groups.size == 1) {
            "$logDirectory holds ${groups.size} kinds of run that cannot be compared:\n" +
                groups.values.joinToString("\n") { "  ${summarise(it)}" } +
                "\n\nNarrow it with --board, --budget, --build or --format, or pass --pool to " +
                "average across them anyway and know that the number means less."
        }
        if (groups.size > 1) {
            log("[lab] POOLING ${groups.size} kinds of run. These bots were not all the same bots.")
        }

        val wanted = eligible.mapTo(LinkedHashSet()) { it.id }
        val played = store.matches().filter { it.run in wanted }
        require(played.isNotEmpty()) { "those runs recorded no matches" }

        val format = TournamentFormat.valueOf(eligible.first().format)
        val ladder = Ladder.of(played, registry, format)

        log("[lab] ${played.size} matches, ${ladder.size} entrants, ${summarise(eligible)}")
        log("")
        report(ladder, bootstrapIntervals(ladder, registry, format), played, eligible, log)
    }

    override fun toString(): String = "Rate($logDirectory, $filters)"

    private fun matches(run: RunHeader): Boolean = filters.all { (name, value) ->
        when (name) {
            "board" -> "${run.rows}x${run.cols}" == value
            "budget" -> run.budgetPerTurn.toString() == value
            "format" -> run.format.equals(value, ignoreCase = true) ||
                (value == "head" && run.format == TournamentFormat.HEAD_TO_HEAD.name) ||
                (value == "ffa" && run.format == TournamentFormat.FREE_FOR_ALL.name)

            "build" -> run.build.startsWith(value)
            "openings" -> run.openings.equals(value, ignoreCase = true)
            "since" -> run.id >= value
            else -> error("no such filter: '--$name'")
        }
    }

    private fun report(
        ladder: Ladder,
        intervals: List<Interval>,
        played: List<LoggedMatch>,
        runs: List<RunHeader>,
        log: (String) -> Unit,
    ) {
        val order = ladder.ratings.ranking()
        val width = (0 until ladder.size).maxOf { ladder.label(it).length }.coerceAtLeast(MIN_LABEL)

        log(
            "entrant".padEnd(width) + "   rating   " + Bootstrap.CONFIDENCE.padStart(INTERVAL) +
                "  games   score    us/turn",
        )
        for (entrant in order) {
            val rating = ladder.ratings
            if (!rating.measured(entrant)) {
                log(ladder.label(entrant).padEnd(width) + "        -                    0       -          -")
                continue
            }

            val cost = ladder.microsPerTurn(entrant)
            log(
                ladder.label(entrant).padEnd(width) +
                    "  ${rating.rating(entrant).roundToInt().toString().padStart(RATING)}   " +
                    intervals[entrant].render().padStart(INTERVAL) +
                    "  ${ladder.table.played(entrant).toString().padStart(GAMES)}" +
                    "  ${percent(ladder.table.scoreRate(entrant))}" +
                    "  ${cost?.let { render(it) } ?: "-"}".padStart(COST) +
                    if (rating.priorDetermined(entrant)) "   (unbounded)" else "",
            )
        }

        unbounded(ladder, log)
        residuals(ladder, log, width)
        diversity(played, runs, log)
    }

    /**
     * Names the rungs whose rating is the prior speaking rather than the results.
     *
     * Printed as prose rather than a footnote marker because it is the one thing that invalidates a
     * comparison outright: a contestant that never lost has no upper bound in the evidence at all,
     * and the gap above it is arithmetic keeping the fit finite.
     */
    private fun unbounded(ladder: Ladder, log: (String) -> Unit) {
        val unbounded = (0 until ladder.size).filter {
            ladder.ratings.measured(it) && ladder.ratings.priorDetermined(it)
        }
        if (unbounded.isEmpty()) {
            return
        }

        log("")
        log(
            "[lab] unbounded: ${unbounded.joinToString { ladder.label(it) }} -- never lost, never won, " +
                "or never played the rest of the field. Those ratings are the prior, not a measurement.",
        )
    }

    /**
     * Where a single ordering fails to describe the pairings it was fitted to.
     *
     * A large cell means the row does better against that column than its rating can explain, which
     * is what non-transitivity looks like from inside a rating. Only the worst are printed: the
     * point is to be told there is one, not to read the whole matrix.
     */
    private fun residuals(ladder: Ladder, log: (String) -> Unit, width: Int) {
        val worst = buildList {
            for (one in 0 until ladder.size) {
                for (other in 0 until ladder.size) {
                    if (one != other) {
                        ladder.residual(one, other)?.let { add(Triple(one, other, it)) }
                    }
                }
            }
        }.filter { it.third >= NOTABLE_RESIDUAL }.sortedByDescending { it.third }.take(WORST_RESIDUALS)

        if (worst.isEmpty()) {
            return
        }

        log("")
        log("[lab] the ladder does not fully describe these pairings:")
        for ((one, other, residual) in worst) {
            val expected = ladder.ratings.expectedScore(one, other)
            log(
                "  ${ladder.label(one).padEnd(width)} vs ${ladder.label(other).padEnd(width)}" +
                    "  scored ${percent(expected + residual)} where the ratings expect ${percent(expected)}",
            )
        }
        log("  A rung that beats one above it while losing to one below is real here, not an error.")
    }

    /** The sample size behind all of it — see `Openings`. */
    private fun diversity(played: List<LoggedMatch>, runs: List<RunHeader>, log: (String) -> Unit) {
        val distinct = played.mapTo(LinkedHashSet()) { it.moveStreamHash }.size
        log("")
        log("[lab] $distinct of ${played.size} matches were distinct games")
        if (distinct * 2 >= played.size) {
            return
        }

        // Same two causes [PlayCommand] separates, asked of a log rather than a batch: if any run
        // here still used a fixed opening, re-playing is the fix; if they all diversified already,
        // the entrants are answering the same question and the fix is different opponents.
        log(
            if (runs.any { it.openings.equals(Openings.FIXED.name, ignoreCase = true) }) {
                "[lab] over half the log is repeated games. Replay it with --openings mirrored."
            } else {
                "[lab] over half the log is repeated games, from openings that were already " +
                    "diversified -- these entrants play most seeds identically to each other. The " +
                    "ratings are honest about a field this narrow; widen it rather than lengthen it."
            },
        )
    }

    private fun summarise(runs: List<RunHeader>): String {
        val first = runs.first()
        return "${first.rows}x${first.cols}, budget ${first.budgetPerTurn}, ${first.openings} openings, " +
            "${first.format.lowercase().replace('_', ' ')}, build ${first.build}" +
            if (runs.size > 1) " (${runs.size} runs)" else ""
    }

    private fun describe(runs: List<RunHeader>): String =
        "It holds: " + runs.groupBy { it.comparabilityKey }.values.joinToString("; ") { summarise(it) }

    private fun Interval.render(): String =
        if (low.isNaN()) "-" else "${signed(low)}..${signed(high)}"

    private fun signed(value: Double): String {
        val rounded = value.roundToInt()
        return if (rounded > 0) "+$rounded" else rounded.toString()
    }

    /** Integer arithmetic, following the matrix this sits under. */
    private fun percent(rate: Double): String = "${((abs(rate) * 1000).toInt() + 5) / 10}%".padStart(PERCENT)

    /** A reactive bot really does cost less than a microsecond a turn; `0` would read as unmeasured. */
    private fun render(micros: Double): String = if (micros < 1.0) "<1" else micros.roundToInt().toString()

    private companion object {
        const val MIN_LABEL = 7
        const val RATING = 5
        const val INTERVAL = 13
        const val GAMES = 5
        const val PERCENT = 5
        const val COST = 9

        /** Five points of score. Below that a cell is sampling noise rather than a shape. */
        const val NOTABLE_RESIDUAL = 0.05

        const val WORST_RESIDUALS = 6
    }
}
