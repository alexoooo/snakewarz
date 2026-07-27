package ao.snakewarz.bots

import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * How fast this actually is, in numbers rather than adjectives.
 *
 * It exists to answer one question with data instead of judgement — **what allowance should a match
 * be played at** — and it runs on both targets, because the answer has to hold in a browser and the
 * browser is the slower of the two. Everything it prints is prefixed `[bench]`, so a run shows its
 * working:
 *
 * ```
 * ./gradlew :bots:jvmTest --tests '*ThroughputTest*' -i | grep '\[bench\]'
 * ./gradlew :bots:wasmJsBrowserTest -PbrowserTests=true -i | grep '\[bench\]'
 * ```
 *
 * The assertions are deliberately an order of magnitude looser than the measurements. A benchmark
 * that fails when the machine is busy teaches everyone to ignore it; these fail only on a regression
 * nobody could argue with, and the *printed* numbers are what the tuning decision is made from.
 */
class ThroughputTest {
    @Test
    fun `the engine and the driver run millions of turns a second`() {
        val random = entry("random")

        // Warm up rather than measure the first pass: on the JVM that pass is interpreted, and on
        // wasm the tiering is different again. Neither is the number anybody wants.
        play(listOf(random, random), MEASURED_BOARD, seed = 0, budget = 0, matches = WARMUP_MATCHES)

        val turnsPerSecond = play(
            listOf(random, random),
            MEASURED_BOARD,
            seed = 1,
            budget = 0,
            matches = ENGINE_MATCHES,
        )

        report("engine  ${MEASURED_BOARD}x$MEASURED_BOARD random vs random", turnsPerSecond)
        assertTrue(turnsPerSecond > 100_000, "the engine managed only $turnsPerSecond turns/s")
    }

    @Test
    fun `a search turn at the shipped allowance fits inside a frame`() {
        val uct = entry("uct")
        val space = entry("space")

        // One throwaway match at the smallest allowance, to get the code hot without spending long.
        HeadlessMatch(listOf(uct, space), MEASURED_BOARD, MEASURED_BOARD, seed = 0, budgetPerTurn = BUDGETS[0]).run()

        for (budget in BUDGETS) {
            val microsPerTurn = timeSearchTurn(uct, space, budget)
            report("uct     ${MEASURED_BOARD}x$MEASURED_BOARD budget $budget", microsPerTurn, "us/turn")

            assertTrue(
                microsPerTurn < CEILING_MICROS,
                "a turn at budget $budget took $microsPerTurn us, which no board should",
            )
        }
    }

    @Test
    fun `a ladder pairing is seconds rather than minutes`() {
        // The unit a tournament is actually sold in: twenty matches of one pairing, which is what
        // the panel offers by default and what BotLadderTest measures every rung over.
        val uct = entry("uct")
        val space = entry("space")

        HeadlessMatch(listOf(uct, space), LADDER_BOARD, LADDER_BOARD, seed = 0, budgetPerTurn = SHIPPED_BUDGET).run()

        val started = TimeSource.Monotonic.markNow()
        for (seed in 1L..(PAIRING_ROUNDS / 2)) {
            HeadlessMatch(listOf(uct, space), LADDER_BOARD, LADDER_BOARD, seed, SHIPPED_BUDGET, recording = false).run()
            HeadlessMatch(listOf(space, uct), LADDER_BOARD, LADDER_BOARD, seed, SHIPPED_BUDGET, recording = false).run()
        }
        val elapsed = started.elapsedNow().inWholeMilliseconds

        report("pairing ${LADDER_BOARD}x$LADDER_BOARD uct vs space, $PAIRING_ROUNDS matches", elapsed, "ms")
        assertTrue(elapsed < PAIRING_CEILING_MILLIS, "a pairing took $elapsed ms")
    }

    // -- internals

    /** Plays [matches] complete matches and returns turns per second. */
    private fun play(entries: List<BotEntry>, size: Int, seed: Long, budget: Int, matches: Int): Long {
        val started = TimeSource.Monotonic.markNow()
        var turns = 0L

        for (i in 0 until matches) {
            val match = HeadlessMatch(entries, size, size, seed + i, budget, recording = false)
            match.run()
            turns += match.turns
        }

        val elapsed = started.elapsedNow().inWholeMicroseconds
        return if (elapsed == 0L) Long.MAX_VALUE else turns * 1_000_000 / elapsed
    }

    /** Microseconds per turn *of the searching bot*, which is half the turns in a two-snake match. */
    private fun timeSearchTurn(searcher: BotEntry, opponent: BotEntry, budget: Int): Long {
        val match = HeadlessMatch(listOf(searcher, opponent), MEASURED_BOARD, MEASURED_BOARD, SEED, budget)

        val started = TimeSource.Monotonic.markNow()
        match.run()
        val elapsed = started.elapsedNow().inWholeMicroseconds

        val searched = match.decisions.count { it.id.index == 0 }
        return elapsed / maxOf(searched, 1)
    }

    private fun report(what: String, value: Long, unit: String = "turns/s") {
        println("[bench] $what: $value $unit")
    }

    private fun entry(slug: String): BotEntry = ShippedBots.entryOf(BotId(slug))

    private companion object {
        /** The board `:ui` opens on, so the number is about the game people actually play. */
        const val MEASURED_BOARD = 20

        /** The board the ladder and the shipped tournament defaults use. */
        const val LADDER_BOARD = 12

        const val SEED = 424_242L

        /** `MatchSetup.DEFAULT_BUDGET_PER_TURN`, which `:bots` may not import. Evaluations a turn. */
        const val SHIPPED_BUDGET = 1_000

        val BUDGETS = intArrayOf(250, 1_000, 2_000, 10_000)

        const val WARMUP_MATCHES = 200
        const val ENGINE_MATCHES = 2_000

        const val PAIRING_ROUNDS = 20

        /**
         * Twenty times a 60 Hz frame. A turn that takes longer than this has stopped being a
         * *slower* match and started being an unresponsive page.
         */
        const val CEILING_MICROS = 333_000L

        const val PAIRING_CEILING_MILLIS = 120_000L
    }
}
