package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.gauntlet.GauntletTrialLevel
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.Replays
import ao.snakewarz.lab.log.recordBatch
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig
import java.nio.file.Path
import kotlin.time.TimeSource

/**
 * Whether the single-player gauntlet actually gets harder, measured one level at a time.
 *
 * A gauntlet's rungs move three things at once — the algorithm, the board and the allowance — so
 * nothing already in `:lab` can settle their order. `rate` cannot: different geometries are different
 * `RunHeader.comparabilityKey`s and it refuses to pool them, correctly, because a rating is
 * conditioned on the board as much as on the field. `ab` cannot: it compares two entrants on **one**
 * board, and two adjacent levels never share one.
 *
 * So the instrument is a **common reference played against every level on that level's own ground**.
 * One entrant, one allowance, one match per level; the reference's score is then a reading of each
 * level and the readings are comparable because the thing held still is the reference rather than
 * the board. What it cannot give is an Elo — a score against a fixed opponent is not a rating, and
 * two levels a point apart here are not separated by that.
 *
 * ### Two ways to misread the output, both worth knowing before the numbers
 *
 * **The ends saturate, and that is the design rather than a defect.** A reference that beats level 1
 * nearly always and loses to the top of the table nearly always is what [DEFAULT_REFERENCE] aims at,
 * so the top and the bottom of the column are pinned near 100% and 0% and the resolution is all in
 * the middle. A reference that separated every rung would have to be several different strengths.
 *
 * **Read the distinct line.** A deterministic opponent under fixed openings can repeat only four
 * games however many rounds are asked for. That is why [Openings.MIRRORED] is the default here as
 * everywhere, and why every row carries how many of its matches were different games.
 */
internal class GauntletCommand(
    val table: String,
    val levels: List<GauntletTrialLevel>,
    val reference: Contestant,
    val rounds: Int,
    val seed: Long,
    val openings: Openings,
    val threads: Int,
    val logDirectory: Path?,
) : LabCommand {
    init {
        require(levels.isNotEmpty()) { "gauntlet table '$table' has no levels" }
        require(levels.map { it.index } == (1..levels.size).toList()) {
            "gauntlet table '$table' levels are not consecutively numbered from one"
        }
    }

    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        log(
            "[lab] gauntlet '$table': $reference against ${levels.size} levels, " +
                "$rounds rounds each, $openings openings",
        )

        // Every level is checked against the reference before the first match rather than as it comes
        // up: a run that failed at level 6 would have spent the compute of five levels first, and
        // printed five rows nobody can act on.
        val opponents = levels.map { seatingFor(it, registry) }

        val started = TimeSource.Monotonic.markNow()
        val scores = DoubleArray(levels.size)

        log("")
        log(header())
        for (level in levels) {
            scores[level.index - 1] = play(level, opponents[level.index - 1], registry, log)
        }

        log("")
        log("[lab] ${started.elapsedNow().inWholeSeconds}s over ${levels.size * rounds} matches")
        verdict(scores, log)
    }

    override fun toString(): String =
        "Gauntlet($table, $reference, $rounds rounds, $openings, $threads threads)"

    /**
     * The level as an entrant, refused if the reference cannot be told from it.
     *
     * An allowance only separates two seats of one bot when that bot **declares** one — so
     * `--against chase` against a `chase` level is the same bot twice however the two budgets read,
     * and the row would measure the seating rather than the level. Asked of the declaration rather
     * than of the slug, so a contributed bot is covered the day it is registered.
     *
     * This is a live case rather than a defensive one: a reference can also appear in the table, and
     * only its allowance or settings make it a different entrant.
     */
    private fun seatingFor(level: GauntletTrialLevel, registry: BotRegistry): Contestant {
        val opponent = level.opponent
        registry.entryOf(opponent.bot)
        val sameBot = opponent.bot == reference.bot && opponent.params == reference.params
        val allowanceSeparates = registry.entryOf(reference.bot).search != null &&
            opponent.budgetPerTurn != reference.budgetPerTurn

        require(!sameBot || allowanceSeparates) {
            "level ${level.index} seats ${opponent.label} and the reference is ${reference.label}, " +
                "which plays the same game -- that measures the seating rather than the level. " +
                "Give --against something else."
        }
        return opponent
    }

    /** One level: the reference against it, on its own board, map and allowance. Returns the score. */
    private fun play(
        level: GauntletTrialLevel,
        opponent: Contestant,
        registry: BotRegistry,
        log: (String) -> Unit,
    ): Double {
        val opponentBudget = checkNotNull(opponent.budgetPerTurn) {
            "level ${level.index} did not pin its opponent allowance"
        }
        val config = TournamentConfig(
            contestants = listOf(reference, opponent),
            rows = level.rows,
            cols = level.cols,
            rounds = rounds,
            seed = seed,
            budgetPerTurn = opponentBudget,
            walls = level.walls(),
        )
        val batch = Arena(config, registry, openings, threads, keepRecords = logDirectory != null).run()
        logDirectory?.let {
            recordBatch(MatchLog(it), batch, registry, openings.name, threads, Replays.DECISIVE)
        }

        val score = batch.table.scoreRate(REFERENCE_SEAT)
        val distinct = batch.leastDiverse
        log(
            row(
                "${level.index}",
                opponent.bot.slug,
                "${level.rows}x${level.cols}",
                level.shape.slug,
                "${level.mapSeed}",
                "$opponentBudget",
                "${percent(score)}%",
                "${distinct?.distinct ?: 0}/${distinct?.played ?: 0}",
            ) + if (batch.forfeits > 0) "  ${batch.forfeits} FORFEITS -- a bot threw" else "",
        )
        return score
    }

    /**
     * The deliverable: whether the reference does worse the further up the gauntlet it goes.
     *
     * A rise is reported with the pair that produced it rather than as a pass or a fail, because the
     * answer to one is to reorder the table and the answer to the other is nothing at all. [NOISE] is
     * what a rise has to clear to be worth naming, and it is a property of [rounds] rather than of
     * the gauntlet.
     */
    private fun verdict(scores: DoubleArray, log: (String) -> Unit) {
        val rises = (1 until scores.size).filter { scores[it] > scores[it - 1] + NOISE }

        if (rises.isEmpty()) {
            log("[lab] MONOTONIC: the reference scores no better on any level than on the one below it.")
            return
        }
        log("[lab] NOT MONOTONIC -- the reference does better on ${rises.size} level(s) than on the one below:")
        for (rise in rises) {
            log(
                "[lab]   level ${rise + 1} (${percent(scores[rise])}%) is easier than " +
                    "level $rise (${percent(scores[rise - 1])}%)",
            )
        }
        log("[lab] The number is the finding: reorder table '$table' to match it, then re-run this.")
    }

    private fun header(): String =
        row("#", "opponent", "board", "map", "map seed", "budget", "ref", "distinct")

    private fun row(vararg cells: String): String = buildString {
        append("[lab] ")
        append(cells[0].padStart(2))
        append("  ").append(cells[1].padEnd(SLUG_WIDTH))
        append("  ").append(cells[2].padStart(BOARD_WIDTH))
        append("  ").append(cells[3].padEnd(SHAPE_WIDTH))
        append("  ").append(cells[4].padStart(MAP_SEED_WIDTH))
        append("  ").append(cells[5].padStart(BUDGET_WIDTH))
        append("  ").append(cells[6].padStart(SCORE_WIDTH))
        append("  ").append(cells[7].padStart(DISTINCT_WIDTH))
    }

    companion object {
        const val SHIPPED_TABLE: String = "shipped"

        /**
         * The reference when nobody names one: a mid-gauntlet searcher on a small allowance.
         *
         * Chosen by running the gauntlet against candidates rather than argued. It has to beat level 1
         * nearly always and lose to the top of the table nearly always or the column says nothing, and
         * the ten shipped slugs are exactly the ten opponents the levels draw on — so the reference is
         * necessarily one of them at a different allowance rather than an eleventh bot.
         *
         * At [REFERENCE_BUDGET] it read 100/95/100/92/75/60/60/20/7/3 across the ten-level table, which
         * resolved the whole gauntlet: the reactive bots saturate at the bottom and every searcher
         * stronger than this saturates at the top. **That reading is stale as of 2026-07-31** — eight
         * of the eleven rows now name a board that did not exist when it was taken, three shapes having
         * been redrawn and three added. The shape of the argument holds; the figures are what this
         * command exists to take again.
         *
         * It also draws randomness, which the reactive levels do not: against a bot that plays the
         * same game every time, a reference that did the same would hand five of these rows the four
         * distinct games `docs/Workflow.md` warns about. Every row of that run was 60 of 60 distinct.
         */
        const val DEFAULT_REFERENCE: String = "uct"

        /**
         * What the reference may spend when its spec does not say.
         *
         * Pinned rather than left to each level's own figure, and that is the whole measurement: a
         * reference handed level 10's allowance and level 1's allowance is two different bots, and
         * the column would then be reading itself as much as the gauntlet.
         */
        const val REFERENCE_BUDGET: Int = 100

        /** The reference is seated first in every level's pairing, so its score is column zero. */
        private const val REFERENCE_SEAT = 0

        /**
         * How much a score may climb before the rise is worth naming.
         *
         * Five points of score, which is one match in twenty and about half a standard error at the
         * default round count. Below it a rise is the sample rather than the gauntlet, and a check that
         * fired on those would cry wolf every run.
         */
        private const val NOISE = 0.05

        private const val SLUG_WIDTH = 16
        private const val BOARD_WIDTH = 5
        private const val SHAPE_WIDTH = 13
        private const val MAP_SEED_WIDTH = 8
        private const val BUDGET_WIDTH = 6
        private const val SCORE_WIDTH = 6
        private const val DISTINCT_WIDTH = 7

        /** Rounded half-up in integers, matching `TournamentTable`'s own percentages. */
        private fun percent(rate: Double): Int = ((rate * 1000).toInt() + 5) / 10
    }
}
