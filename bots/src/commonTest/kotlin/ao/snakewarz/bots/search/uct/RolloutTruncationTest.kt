package ao.snakewarz.bots.search.uct

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.AppraisalTape
import ao.snakewarz.bots.HeadlessMatch
import ao.snakewarz.bots.SHIPPED_BUDGET
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.ThroughputTest
import ao.snakewarz.bots.at
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.bots.search.RolloutPolicy
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.bots.setupFor
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The truncation experiment, kept because a measured "no" is only worth having if it can be re-run.
 *
 * Rollout truncation — stop the rollout after a few moves and judge where it got to by
 * reachable-space share — was the single highest-value lever left once [UctBot] landed, and it was
 * deferred rather than argued about on the grounds that it is a measurement question. This is the
 * measurement: [UctBot] with `rolloutDepth` set, against the same bot playing its rollouts out in
 * full, at the same allowance, on the ladder's board, over the ladder's twenty matches. The answer
 * and the table it produced are in [UctBot.ROLLOUT_DEPTH].
 *
 * The comparison prints times as well as wins, because the two are not interchangeable. A budget is
 * counted in evaluations, so a truncated iteration and a full one buy exactly one each — all
 * truncation can win now is wall clock, and a truncated search that costs *more* per turn has not
 * made the trade it appears to have made.
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
            wins in NO_CLEAR_EDGE,
            "truncating at $depth won $wins of $ROUNDS, so the trade is no longer the one recorded",
        )

        // No third measurement is needed to settle it, and that is worth saying explicitly. Equal
        // budget now means an equal number of iterations, so truncation buys none of the extra search
        // it was proposed for -- what is left is a leaf that is at best a little better for a turn
        // that costs twice as much. Both halves of that come out of the loop above.
    }

    @Test
    fun `what truncating costs at the shipped allowance is the half the lead has never had`() {
        val depth = BotParams(mapOf(UctBot.ROLLOUT_DEPTH.name to HEADLINE_DEPTH.toString()))

        for (side in SHIPPED_SIDES) {
            val tape = AppraisalTape(side, side)

            // Both settings several times before either is timed: the first passes are interpreted,
            // and the tiering finishing inside the block reads exactly like the machine drifting.
            repeat(WARMUPS) {
                tape.time(uct, BotParams.EMPTY, SHIPPED_BUDGET, passes = 1)
                tape.time(uct, depth, SHIPPED_BUDGET, passes = 1)
            }

            val allowance = EQUAL_CLOCK[side] ?: error("no equal-clock allowance for ${side}x$side")

            val opening = median(tape, BotParams.EMPTY, SHIPPED_BUDGET)
            val truncated = median(tape, depth, SHIPPED_BUDGET)
            val matched = median(tape, depth, allowance)
            val closing = median(tape, BotParams.EMPTY, SHIPPED_BUDGET)
            val control = (opening + closing) / 2

            println(
                "[bench] rolloutDepth $HEADLINE_DEPTH ${side}x$side: $truncated us/turn at " +
                    "$SHIPPED_BUDGET (${truncated * 100 / control}% of the undepthed bot's $control), " +
                    "$matched us/turn at $allowance (${matched * 100 / control}%), " +
                    "over ${tape.appraisals} of ${tape.lineTurns} turns",
            )
            println(
                "[bench] rolloutDepth control ${side}x$side budget $SHIPPED_BUDGET: " +
                    "$opening then $closing us/turn",
            )

            assertTrue(
                closing * LOADED_MACHINE > opening && opening * LOADED_MACHINE > closing,
                "the undepthed bot read $opening us/turn before the block and $closing after, so " +
                    "nothing in this block is a measurement of anything",
            )
            assertTrue(
                matched * LOADED_MACHINE > control && control * LOADED_MACHINE > matched,
                "truncating at $HEADLINE_DEPTH costs $matched us/turn at its $allowance allowance " +
                    "against the undepthed bot's $control on ${side}x$side, which no equal-clock " +
                    "field could be behind",
            )
        }
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

        val result = truncatedPlayout(
            scratch.scratch.playout(),
            setupFor(board, board.toAct).rng,
            50,
            space,
            RolloutPolicy(RolloutPolicy.UNIFORM, board.grid),
        )

        assertTrue(!result.isDraw, "a two-square board resolves, it does not tie")
    }

    // -- internals

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

    /** The median pass, for [AppraisalTape]'s reason — a fast pass is not noise and a minimum keeps it. */
    private fun median(tape: AppraisalTape, params: BotParams, budget: Int): Long =
        tape.time(uct, params, budget, APPRAISAL_PASSES).map { it.mean }.sorted()[APPRAISAL_PASSES / 2]

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
         * A tenth of the shipped allowance, deliberately — and **the sentence that used to justify
         * that has been measured false.**
         *
         * It said the thing being compared is a ratio, strength per unit of budget against
         * wall-clock per unit of budget, and that the ratio does not turn on the allowance. The
         * strength half of it does. `rolloutDepth=25` against the undepthed bot, mirrored openings,
         * 200 distinct games a cell: **51.7% of a thousand rounds at this 100 on a 12x12** — the row
         * in [UctBot.ROLLOUT_DEPTH]'s table — against **58.5% at the shipped 1,000 on the same
         * board, and 67% at 1,000 on a 20x20**, which no allowance had been measured at when that
         * was written. Both have since been re-measured on fresh seeds, priced against a paired
         * clock, and rated in an equal-clock field, and an **8x8** has since had all three for the
         * first time and come back a null; [UctBot.ROLLOUT_DEPTH] carries all of it, and the cost
         * half of it is the block above.
         *
         * **The cost ratio turns on the allowance too, and in the same direction**: 1.0-1.1x here at
         * 100 evaluations against 1.05x, 1.12x and 1.32x at 1,000. So this constant is not neutral
         * with respect to *either* half of the trade, and a figure taken at it is a figure about a
         * hundred-evaluation search.
         *
         * This constant is **not** raised on the strength of it. Ten times the allowance is ten
         * times the runtime of a suite that already plays forty matches, and what the assertion here
         * guards — that the edge is small enough to be swamped by the clock the loop above prints —
         * is a property of this fixture at this allowance. What has to change is the claim, which is
         * now stated where a reader meets it rather than left as a reason nobody re-checked.
         */
        const val BUDGET = 100

        /** Short, medium and long, spanning the range the idea could plausibly pay off over. */
        val DEPTHS = listOf(10, 25, 60)

        /** Which of [DEPTHS] the strength comparison is actually played out at. */
        const val HEADLINE = 1

        /** [DEPTHS]`[`[HEADLINE]`]`, spelled as a constant because the block below is not a loop. */
        const val HEADLINE_DEPTH = 25

        /**
         * The three boards the shipped-allowance block is timed on.
         *
         * 8x8 was left out of the first two and has since been measured, because it is the board
         * `index.html` opens on and therefore the one a player meets. Read its row knowing what it
         * is: [AppraisalTape]'s line there is 47 turns against a 20x20's 210, so a reading is over a
         * fifth of the samples, and `RolloutPolicyTest` records that as the wobbliest row of its own
         * table.
         */
        val SHIPPED_SIDES = listOf(8, 12, 20)

        /** `ThroughputTest.APPRAISAL_PASSES`, for its reason. */
        const val APPRAISAL_PASSES = 5

        /** `RolloutPolicyTest.WARMUPS`, measured there rather than guessed here. */
        const val WARMUPS = 4

        /** `RolloutPolicyTest.LOADED_MACHINE`, and for its reason: this runs inside `./gradlew build`. */
        const val LOADED_MACHINE = 1.8

        /**
         * The allowance `rolloutDepth=25` buys the undepthed bot's [SHIPPED_BUDGET] of wall clock at.
         *
         * **This is the table the field in [UctBot.ROLLOUT_DEPTH] was played at**, and it is asserted
         * against the control above rather than left as arithmetic off the ratio, for
         * `RolloutPolicyTest.EQUAL_CLOCK`'s reason: an allowance that has drifted turns an
         * equal-clock field back into an equal-allowance one and nothing in the field says so. The
         * ratio is a derived number with real spread; what is verified is this.
         *
         * From 1.05x at 8x8, 1.12x at 12x12 and 1.32x at 20x20 — median of three runs on the two
         * larger boards and of eight on the 8x8, which spread 1.04-1.08x. The 8x8 entry verifies at
         * 96-99% of the control's clock over three runs of the block above.
         */
        val EQUAL_CLOCK = mapOf(8 to 950, 12 to 890, 20 to 760)

        /**
         * Wide enough to hold what has actually been measured, and no wider.
         *
         * **This fixture reads 24 of 40, and it is not sampling what [UctBot.ROLLOUT_DEPTH]'s table
         * samples.** Forty boards off one seed base from a fixed spawn is a point rather than an
         * estimate: `:lab` over a thousand rounds of this same pairing puts the rate at 54%, so 21.6
         * is what forty of them are worth on average and one sigma is ±3.2. The reading therefore
         * moves whenever anything re-rolls these forty games, even where the rate underneath is
         * untouched — and [UctBot.EXPLORATION] going from 5.0 to 3.0 is exactly that. It took this
         * fixture from 26 to 24 and left the rate where it was, which is the paired thousand boards
         * that constant's own table records.
         *
         * So a range rather than a floor, and a wide one. What has to hold is that the *edge is
         * small*, small enough to be swamped by the wall clock the loop above prints — not that any
         * particular forty games land on a number. A run outside it in either direction is news.
         */
        val NO_CLEAR_EDGE = 14..30

        const val TIMED_PASSES = 3
    }
}
