package ao.snakewarz.bots

import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The roster is a ladder, and this is the test that says so.
 *
 * Everything else in the suite checks that a bot is well behaved: [BotContractTest] wants legal,
 * deterministic, stateless, terminating and within budget, all of which a bot that plays terribly
 * satisfies perfectly. This is the only assertion here that a *correct and useless* bot would fail,
 * and it is what gives the registration order — which the sidebar shows in — its meaning.
 *
 * Twenty matches a pairing, each seed played from both seats so that moving first is not a free
 * point, at [SHIPPED_BUDGET], the allowance a real match is played under. The thresholds are the
 * measured results with a little slack, not aspirations: a failure is a question about what changed,
 * exactly like a golden hash.
 *
 * Every rung is asserted, the first included. Eight bots above `random` means eight comparisons, and
 * a missing one is invisible — the list reads as complete whether or not it is.
 *
 * ### This is a 12x12 instrument, and the top rung is only true on a 12x12
 *
 * `puct` and `alphabeta` were seated here by P3 on P2's three equal-clock fields, and the ordering
 * they assert — `alphabeta` over `puct` over `uct` — is the *field rating* ordering on all three
 * board sizes measured. **It is not the head-to-head ordering on all three.** At 8x8,
 * `alphabeta:eval=territory` rates +131 above bare `puct` and **loses its own head-to-head to it,
 * 89-111**; `rate` prints that pairing as the largest residual in its field. At 12x12 the same
 * pairing is 70.5% the other way, and at 20x20 65%.
 *
 * That is a genuine board-size intransitivity and not an artefact of one batch: P2 found the same
 * shape in three other places (`eval=learned` swings +40 / −7 / −316 across the three boards, `uct`
 * climbs monotonically with board size, and the cost of a leaf is not monotone in board size
 * either). **Do not read the top rung as "`alphabeta` is stronger than `puct`."** Read it as what
 * this file measures: on the board this file plays, over these twenty seeds, at this allowance.
 *
 * The rung stands because the ladder is a 12x12 instrument and 12x12 is where the ordering is
 * strongest. Seating it was a decision taken knowing the 8x8 reversal, not in ignorance of it — and
 * anybody who reruns these thresholds on another board should expect the top of the ladder to come
 * apart there, and should not treat that as a regression.
 */
class BotLadderTest {
    @Test
    fun `each rung beats the one below it`() {
        // Re-measured at the shipped allowance, 12x12, over the same twenty matches, when `puct` and
        // `alphabeta` were seated: 16, 17, 18, 14, 18, 18, **12, 20**.
        //
        // The six existing rungs came back on their recorded figures to the match, which is the
        // answer to whether seating two bots above them changed what they are asked to beat: it did
        // not, because a rung is a pairing and none of these six can see either new bot.
        //
        // `uct` over `flat-monte-carlo` moved when `UctBot.EXPLORATION` went from 5.0 to 3.0, which
        // is what that sweep was for. `flat-monte-carlo` over `chase` reads 18 where it was first
        // recorded as 16, and neither of those bots can see that knob -- it drifted under an earlier
        // change and went unrecorded. Its threshold is deliberately left where it was: tightening a
        // gate on a pairing nobody has investigated buys brittleness rather than coverage.
        //
        // **`puct` over `uct` at 12 of 20 is the narrowest rung here and its threshold has the least
        // slack of any**, so read that one first if this fails. Twenty matches is a coarse
        // instrument for a 60% pairing; the evidence the rung was seated on is not these twenty. A
        // fresh 100-match head-to-head on this board at this allowance, 100 of 100 distinct games,
        // scores **59%**; P2's three equal-clock fields put `puct` over `uct` by **+54 / +58 / +62**
        // Elo at 8x8 / 12x12 / 20x20 with disjoint intervals on all three. 59% is about +63 Elo, so
        // the three readings agree. A threshold of 11 still asserts a majority and is one match from
        // firing, which is the honest state of this rung rather than a number to loosen.
        //
        // `alphabeta` over `puct` is 20 of 20 here and 75% over 100 fresh matches (72 of 100
        // distinct). See the class KDoc for the board size where that ordering reverses.
        //
        // The first rung is the one that says `random` is the weakest thing here, which is what
        // makes it the right bot to seat by default -- the opening screen of a game nobody has
        // configured yet should be the easiest opponent there is -- and `ShippedBots` keeps the
        // registration order saying so. It is also the only rung whose loser plays no search, so it
        // costs almost nothing to check.
        assertBeats("wallhug", "random", atLeast = 13)
        assertBeats("space", "wallhug", atLeast = 14)
        assertBeats("pressure", "space", atLeast = 15)
        assertBeats("chase", "pressure", atLeast = 11)
        assertBeats("flat-monte-carlo", "chase", atLeast = 12)
        assertBeats("uct", "flat-monte-carlo", atLeast = 15)
        assertBeats("puct", "uct", atLeast = 11)
        assertBeats("alphabeta", "puct", atLeast = 17)
    }

    @Test
    fun `the tree pays at both allowances, and the exploration constant sets how much`() {
        // UCT and flat Monte Carlo share a rollout policy and an allowance. The only difference is
        // that one of them remembers what it learned -- and how much of that memory survives a small
        // allowance is set by `UctBot.EXPLORATION`, not by the allowance alone.
        //
        // This test used to assert the opposite, that at a hundred evaluations the two are hard to
        // tell apart: four of them go on giving each opening its first visit, leaving nothing to
        // deepen. That was read off a divisor of 5.0, which is greedy enough to commit to whichever
        // opening happened to win its one first rollout. Measured here over the same twenty seeds on
        // the same build, with only the divisor moved:
        //
        // | evaluations a turn | exploration 5.0 | exploration 3.0 |
        // |---|---|---|
        // | 100 | 13 of 20 | 16 of 20 |
        // | 1,000 | 16 of 20 | 18 of 20 |
        //
        // So the tree is worth having at both, and the constant decides how much of it survives being
        // cramped. The recorded "10 of 20" it replaces was stale before this change, which is the
        // argument for a threshold rather than an equality: a figure nothing asserts drifts unnoticed.
        val cramped = winsFor("uct", "flat-monte-carlo", budget = CRAMPED_BUDGET)
        assertTrue(cramped >= 13, "at a tenth of the allowance the tree paid in only $cramped of $ROUNDS")

        assertBeats("uct", "flat-monte-carlo", atLeast = 15)
    }

    @Test
    fun `the shipped allowance is worth what it costs`() {
        // The assertion behind MatchSetup.DEFAULT_BUDGET_PER_TURN. The number was chosen from timings
        // -- how much search fits in a frame -- and a timing alone cannot say whether the search is
        // worth having. Same bot, same board, same seeds, ten times the allowance on one side.
        //
        // Measured: 16 of 20. Which is also the honest ceiling on the argument for raising it
        // further: ten times the budget still leaves a tenth of it four wins in twenty, so the curve
        // is flat enough that the frame budget, not strength, is the thing to set this by.
        val wins = winsFor("uct", "uct", SHIPPED_BUDGET, CRAMPED_BUDGET)
        assertTrue(wins >= 14, "the full allowance beat a tenth of it in only $wins of $ROUNDS")
    }

    /** Plays [challenger] against [defender] over [ROUNDS] matches, both seatings, on a 12x12. */
    private fun assertBeats(challenger: String, defender: String, atLeast: Int) {
        val wins = winsFor(challenger, defender)
        assertTrue(wins >= atLeast, "$challenger beat $defender in only $wins of $ROUNDS")
    }

    private fun winsFor(challenger: String, defender: String, budget: Int = SHIPPED_BUDGET): Int =
        winsFor(challenger, defender, budget, budget)

    private fun winsFor(challenger: String, defender: String, challengerBudget: Int, defenderBudget: Int): Int {
        val first = entry(challenger)
        val second = entry(defender)
        var wins = 0

        for (seed in 1L..(ROUNDS / 2)) {
            if (play(first, second, challengerBudget, defenderBudget, seed) == SnakeId(0)) {
                wins++
            }
            if (play(second, first, defenderBudget, challengerBudget, seed) == SnakeId(1)) {
                wins++
            }
        }

        return wins
    }

    private fun play(first: BotEntry, second: BotEntry, firstBudget: Int, secondBudget: Int, seed: Long): SnakeId =
        HeadlessMatch(
            entries = listOf(first, second),
            rows = ROWS,
            cols = COLS,
            seed = seed,
            recording = false,
            budgetPerSlot = intArrayOf(firstBudget, secondBudget),
        ).run().winner

    private fun entry(slug: String): BotEntry = ShippedBots.entryOf(BotId(slug))

    private companion object {
        const val ROUNDS = 20
        const val ROWS = 12
        const val COLS = 12

        /** A tenth of the shipped allowance, which is what the raise to it has to be worth beating. */
        const val CRAMPED_BUDGET = SHIPPED_BUDGET / 10
    }
}
