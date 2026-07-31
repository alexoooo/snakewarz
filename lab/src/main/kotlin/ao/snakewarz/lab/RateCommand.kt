package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.EMPTY_MAP
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.lab.log.mapKey
import ao.snakewarz.lab.strength.Bootstrap
import ao.snakewarz.lab.strength.Interval
import ao.snakewarz.lab.strength.Ladder
import ao.snakewarz.lab.strength.bootstrapIntervals
import ao.snakewarz.lab.strength.winShareIntervals
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
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
 *
 * ### Free for all prints a second ordering, and it is not decoration
 *
 * The rating is fitted to what `pairwiseOutcomes` scores, and past two seats that is **outlasting**
 * rather than winning. So a free-for-all table also carries the raw win share and a block saying
 * whether the two order the field the same way — see [Ladder.winShare]. Head to head it prints
 * neither, because there the engine ends the match at the first death and the two questions have one
 * answer.
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
                "\n\nNarrow it with --board, --map, --budget, --build or --format, or pass --pool " +
                "to average across them anyway and know that the number means less."
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
        report(ladder, bootstrapIntervals(ladder, registry, format), format, played, eligible, log)
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
            "map" -> run.map == value || run.map == keyOfShape(run, value)
            else -> error("no such filter: '--$name'")
        }
    }

    /**
     * What [slug] fingerprints to on this run's own board, or `null` if it names no shape it could.
     *
     * A log records the walls it played and never the name of a shape, so a `--map cross` narrows by
     * **redrawing** cross at each run's geometry and comparing. That is what keeps the flag honest
     * across a redrawn generator: a run played on the old cross stops matching `--map cross` rather
     * than being pooled with the new one under a name that no longer describes it.
     *
     * `scatter` resolves at its shipped density, because a density is not one of the run's recorded
     * columns. A run played at another one is still reachable by its key, which the summary prints.
     */
    private fun keyOfShape(run: RunHeader, slug: String): String? {
        val shape = MapShape.ofSlug(slug) ?: return null
        if (run.rows < shape.minimumSide || run.cols < shape.minimumSide) {
            return null
        }
        return mapKey(generateMap(run.rows, run.cols, shape, seed = run.seed).walls())
    }

    private fun report(
        ladder: Ladder,
        intervals: List<Interval>,
        format: TournamentFormat,
        played: List<LoggedMatch>,
        runs: List<RunHeader>,
        log: (String) -> Unit,
    ) {
        val order = ladder.ratings.ranking()
        val width = (0 until ladder.size).maxOf { ladder.label(it).length }.coerceAtLeast(MIN_LABEL)

        // The win columns are free-for-all only, and their absence head to head is the finding
        // rather than an omission: there the engine resolves the field the instant one snake dies,
        // so outlasting *is* winning and a second column would restate the first. See
        // `Ladder.winShare` and `pairwiseOutcomes`.
        val victory = if (format == TournamentFormat.FREE_FOR_ALL) winShareIntervals(ladder) else null

        log(
            "entrant".padEnd(width) + "   rating   " + Bootstrap.CONFIDENCE.padStart(INTERVAL) +
                "  games   score" + (if (victory != null) "     win      win ${Bootstrap.CONFIDENCE}" else "") +
                "    us/turn",
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
                    (victory?.let { "  ${percent(ladder.winShare(entrant) ?: 0.0)}  ${it[entrant].asShare()}" } ?: "") +
                    "  ${cost?.let { render(it) } ?: "-"}".padStart(COST) +
                    if (rating.priorDetermined(entrant)) "   (unbounded)" else "",
            )
        }

        unbounded(ladder, log)
        if (victory != null) {
            longevityAgainstVictory(ladder, victory, order, log, width)
        }
        residuals(ladder, log, width)
        diversity(played, runs, log)
    }

    /**
     * Whether the two orderings a free-for-all produces are the same ordering.
     *
     * Printed for every free-for-all, never on request, for the reason the diversity line is: the
     * rating above is fitted to *outlasting* and the game is decided by *winning*, and the two are
     * only the same question at two seats. A rung that rates above another while winning fewer
     * matches than it is the whole shape of the problem — a bot can climb a free-for-all rating by
     * refusing to contest ground and dying second of the two losers.
     *
     * Only inversions whose win-share intervals are **disjoint** are named. Every field of any width
     * has adjacent rungs whose win shares cross on noise, and a block that listed those would be
     * ignored within a week.
     *
     * **What it cannot tell you is whether the rules disagree or the schedule was unbalanced**, and
     * the difference matters. A win is a three-way event, so a win share is conditioned on the pairs
     * of opponents an entrant happened to sit with; a log built out of a *covering* design balances
     * every entrant's opponents one at a time and still gives them different company. P7 saw exactly
     * that: a Steiner triple system over the nine shipped bots fires this block on `chase` against
     * `space`, and the complete design over the same nine on the same board does not. So read a
     * firing as *check the schedule, then check the rule*, in that order.
     */
    private fun longevityAgainstVictory(
        ladder: Ladder,
        victory: List<Interval>,
        order: List<Int>,
        log: (String) -> Unit,
        width: Int,
    ) {
        val rated = order.filter { ladder.ratings.measured(it) && ladder.winShare(it) != null }
        val inverted = buildList {
            for (above in rated.indices) {
                for (below in above + 1 until rated.size) {
                    val one = rated[above]
                    val other = rated[below]
                    if (victory[one].high < victory[other].low) {
                        add(one to other)
                    }
                }
            }
        }

        log("")
        if (inverted.isEmpty()) {
            log("[lab] free for all: the rating is fitted to who OUTLASTED whom, not to who won.")
            log("  No rung here rates above another while winning conclusively fewer matches.")
            return
        }

        log("[lab] free for all: the rating is fitted to who OUTLASTED whom, and it disagrees with")
        log("  the `win` column beside it. Longevity is not victory, and here they order differently:")
        for ((one, other) in inverted.take(WORST_RESIDUALS)) {
            log(
                "  ${ladder.label(one).padEnd(width)} rates above ${ladder.label(other).padEnd(width)}" +
                    "  and wins ${percent(ladder.winShare(one) ?: 0.0)} against ${percent(ladder.winShare(other) ?: 0.0)}",
            )
        }
        log("  Quote the win column beside any strength claim taken off this table, or neither.")
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

    /**
     * The conditions a group of runs shares, map included.
     *
     * The map is named every time, `empty` included, because a figure quoted without it is a figure
     * somebody will later assume was taken on a bare rectangle — and half of them will have been.
     * The shape's own slug is printed where the walls still reproduce it, so the line reads as a
     * board rather than as a checksum; the checksum stays beside it, because it and not the name is
     * what the run was pooled by.
     */
    private fun summarise(runs: List<RunHeader>): String {
        val first = runs.first()
        return "${first.rows}x${first.cols}, ${mapLabel(first)}, budget ${first.budgetPerTurn}, " +
            "${first.openings} openings, ${first.format.lowercase().replace('_', ' ')}, " +
            "build ${first.build}" + if (runs.size > 1) " (${runs.size} runs)" else ""
    }

    private fun mapLabel(run: RunHeader): String {
        if (run.map == EMPTY_MAP) {
            return "map $EMPTY_MAP"
        }
        val named = MapShape.entries.firstOrNull { keyOfShape(run, it.slug) == run.map }
        return "map ${named?.let { "${it.slug} (${run.map})" } ?: run.map}"
    }

    private fun describe(runs: List<RunHeader>): String =
        "It holds: " + runs.groupBy { it.comparabilityKey }.values.joinToString("; ") { summarise(it) }

    private fun Interval.render(): String =
        if (low.isNaN()) "-" else "${signed(low)}..${signed(high)}"

    /** A win-share bar, which is a fraction rather than an Elo and so wants no sign. */
    private fun Interval.asShare(): String =
        if (low.isNaN()) "-".padStart(SHARE) else "${percent(low).trim()}..${percent(high).trim()}".padStart(SHARE)

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
        const val SHARE = 9
        const val COST = 9

        /** Five points of score. Below that a cell is sampling noise rather than a shape. */
        const val NOTABLE_RESIDUAL = 0.05

        const val WORST_RESIDUALS = 6
    }
}
