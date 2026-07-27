package ao.snakewarz.bots

import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotParams
import ao.snakewarz.core.Direction
import ao.snakewarz.core.RulesConfig
import ao.snakewarz.core.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The truncation experiment, kept because a measured "no" is only worth having if it can be re-run.
 *
 * `docs/MIGRATION.md` came out of Phase 4 naming rollout truncation — stop the rollout after a few
 * moves and judge where it got to by reachable-space share — as the single highest-value lever left,
 * and deferring it to Phase 6 on the grounds that it is a measurement question. This is the
 * measurement: [UctBot] with `rolloutDepth` set, against the same bot playing its rollouts out in
 * full, at the same allowance, on the ladder's board, over the ladder's twenty matches.
 *
 * The comparison prints times as well as wins, because the two are not interchangeable. Truncation
 * buys iterations *per unit of budget*, and budget is counted in simulated moves — so a truncated
 * search that also costs more wall-clock per turn has not made the trade it appears to have made.
 *
 * Prefixed `[bench]` like [ThroughputTest], and read the same way:
 *
 * ```
 * ./gradlew :bots:jvmTest --tests '*RolloutTruncationTest*' -i | grep '\[bench\]'
 * ```
 */
class RolloutTruncationTest {
    @Test
    fun `truncating the rollout is measured against playing it out`() {
        val full = Rival("full", BotParams.EMPTY)
        val truncated = DEPTHS.map { Rival("d$it", BotParams(mapOf(UctBot.ROLLOUT_DEPTH.name to it.toString()))) }

        // Time everything once before timing anything for real. Introducing a second implementation
        // of the rollout part-way through a run turns shared call sites polymorphic and the JIT
        // rebuilds them, which showed up as the *unchanged* bot appearing to slow down fourfold
        // between two identical measurements.
        microsPerTurn(full, BUDGET)
        truncated.forEach { microsPerTurn(it, BUDGET) }

        val fullMicros = microsPerTurn(full, BUDGET)
        for (i in DEPTHS.indices) {
            println(
                "[bench] rolloutDepth ${DEPTHS[i]}: " +
                    "${microsPerTurn(truncated[i], BUDGET)} us/turn against full's $fullMicros",
            )
        }

        // Strength is measured at one depth rather than three. A pairing is forty matches and a
        // timing is three, so playing all three depths out would treble the cost of this test to
        // re-derive a row of the table in UctBot's KDoc that has not moved since it was measured.
        // The middle depth is the one to keep: the shortest is the most expensive to run and the
        // longest is the closest to not truncating at all.
        val depth = DEPTHS[HEADLINE]
        val wins = winsFor(truncated[HEADLINE], full)
        println("[bench] rolloutDepth $depth: $wins/$ROUNDS vs full rollouts")

        assertTrue(
            wins in EVEN_ENOUGH,
            "truncating at $depth won $wins of $ROUNDS, which is no longer 'no measurable difference'",
        )

        // No third measurement is needed to settle it, and that is worth saying explicitly. Equal
        // budget is the *generous* comparison for truncation -- a budget is simulated moves, and
        // truncation exists to buy more iterations per move. If it does not win there, and it also
        // costs several times the wall-clock per turn, then there is no allowance at which it is the
        // better use of a millisecond. Both of those come out of the loop above.
    }

    @Test
    fun `a snake walled off from the board owns none of it`() {
        // Classic Tron growth, so a body can be drawn as a wall. Both snakes run down their own
        // column; slot 0 ends up holding the west half and slot 1 ends up holding nothing, because
        // the only square it can still reach is one slot 0 reaches on the same step.
        val board = boardOf(5, 5, 0 to 2, 0 to 4, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) {
            board.apply(SnakeId(0), Direction.SOUTH)
            board.apply(SnakeId(1), Direction.SOUTH)
        }

        val space = SpaceOwnership(board.grid, board.snakeCount)
        val owned = space.measure(board)

        assertEquals(10, owned[0], "the west half is nobody else's")
        assertEquals(0, owned[1], "and the shut-in snake reaches only contested ground")
        assertEquals(SnakeId(0), space.verdict(board).winner)
    }

    @Test
    fun `an even board is split evenly and the middle belongs to nobody`() {
        val board = boardOf(1, 5, 0 to 0, 0 to 4)
        val space = SpaceOwnership(board.grid, board.snakeCount)
        val owned = space.measure(board)

        assertEquals(1, owned[0])
        assertEquals(1, owned[1])
        assertTrue(space.verdict(board).isDraw, "neither snake is ahead, so neither has won")
    }

    @Test
    fun `a plain fill cannot tell the same position apart, which is why this exists`() {
        val board = boardOf(1, 5, 0 to 0, 0 to 4)
        val fill = FloodFill(board.grid)

        assertEquals(
            fill.reachable(board, board.snake(SnakeId(0)).head),
            fill.reachable(board, board.snake(SnakeId(1)).head),
            "two heads in one open region reach the same squares, so a fill says nothing",
        )
    }

    @Test
    fun `a rollout that ends on its own is reported rather than judged`() {
        // Depth is generous and the board is a corridor, so the match really finishes inside it. A
        // judged verdict here would be a draw, and the real result is not one.
        val board = boardOf(1, 2, 0 to 0, 0 to 1)
        val space = SpaceOwnership(board.grid, board.snakeCount)
        val scratch = turnOn(board, board.toAct, ao.snakewarz.core.Budget(1_000))

        val result = truncatedPlayout(scratch.scratch.playout(), setupFor(board, board.toAct).rng, 50, space)

        assertTrue(!result.isDraw, "a two-square board resolves, it does not tie")
    }

    // -- internals ------------------------------------------------------------------------------

    /**
     * One [UctBot] configuration under a name, for the printed table.
     *
     * It used to be a whole fabricated [BotEntry] wrapping a hand-rebuilt `BotSetup`, because the
     * shipped registry had no way to offer a bot its parameters. It has one now — the same one the
     * sidebar uses — so this is a label and a `BotParams` and nothing else.
     */
    private class Rival(val label: String, val params: BotParams)

    private fun winsFor(
        challenger: Rival,
        defender: Rival,
        challengerBudget: Int = BUDGET,
        defenderBudget: Int = BUDGET,
    ): Int {
        var wins = 0
        for (seed in 1L..(ROUNDS / 2)) {
            if (play(challenger, defender, challengerBudget, defenderBudget, seed) == SnakeId(0)) {
                wins++
            }
            if (play(defender, challenger, defenderBudget, challengerBudget, seed) == SnakeId(1)) {
                wins++
            }
        }
        return wins
    }

    private fun play(first: Rival, second: Rival, firstBudget: Int, secondBudget: Int, seed: Long): SnakeId =
        HeadlessMatch(
            entries = listOf(uct, uct),
            rows = SIZE,
            cols = SIZE,
            seed = seed,
            recording = false,
            budgetPerSlot = intArrayOf(firstBudget, secondBudget),
            paramsPerSlot = listOf(first.params, second.params),
        ).run().winner

    /**
     * What one turn of [searcher] costs.
     *
     * Seated against a bot that is handed **no allowance at all**, so that what is being timed is one
     * search and not a round of two. Two searchers in one match makes the elapsed time the sum of
     * both, which is a number about the pairing rather than about either bot.
     */
    private fun microsPerTurn(searcher: Rival, budget: Int): Long {
        var best = Long.MAX_VALUE

        repeat(TIMED_PASSES) {
            val match = HeadlessMatch(
                entries = listOf(uct, ShippedBots.entryOf(BotId("space"))),
                rows = SIZE,
                cols = SIZE,
                seed = SEED,
                budgetPerSlot = intArrayOf(budget, 0),
                paramsPerSlot = listOf(searcher.params, BotParams.EMPTY),
            )

            val started = TimeSource.Monotonic.markNow()
            match.run()
            val elapsed = started.elapsedNow().inWholeMicroseconds

            // The fastest pass, not the mean: every source of noise here only ever adds time.
            val perTurn = elapsed / maxOf(match.decisions.count { it.id.index == 0 }, 1)
            if (perTurn < best) {
                best = perTurn
            }
        }

        return best
    }

    private companion object {
        /** The shipped entry, configured per slot rather than fabricated per variant. */
        val uct: BotEntry = ShippedBots.entryOf(BotId("uct"))

        const val SIZE = 12
        const val SEED = 424_242L

        /**
         * Forty rather than the ladder's twenty. This test has to be able to say "no difference"
         * convincingly, and twenty matches put a one-sigma band of ±2.2 wins around an even pairing —
         * wide enough that 12-8 means nothing at all.
         */
        const val ROUNDS = 40

        /**
         * A tenth of the shipped allowance, deliberately.
         *
         * What is being compared is a ratio — strength per unit of budget against wall-clock per
         * unit of budget — and that ratio does not turn on the allowance. Running it at the shipped
         * 40,000 would multiply the time this test takes by four and change none of its conclusions.
         */
        const val BUDGET = 10_000

        /** Short, medium and long, spanning the range the idea could plausibly pay off over. */
        val DEPTHS = listOf(10, 25, 60)

        /** Which of [DEPTHS] the strength comparison is actually played out at. */
        const val HEADLINE = 1

        /**
         * Two sigma either side of even, over forty matches.
         *
         * A range rather than a floor, because what is being asserted is that truncation makes *no
         * difference*. A run that fell outside it in either direction would be news.
         */
        val EVEN_ENOUGH = 14..26

        const val TIMED_PASSES = 3
    }
}
