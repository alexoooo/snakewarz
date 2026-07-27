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
 * point, at [BUDGET], the allowance a real match is played under. The thresholds are the measured
 * results with a little slack, not aspirations: a failure is a question about what changed, exactly
 * like a golden hash.
 *
 * Every rung is asserted, the first included. Six bots above `random` means six comparisons, and a
 * missing one is invisible — the list reads as complete whether or not it is.
 */
class BotLadderTest {
    @Test
    fun `each rung beats the one below it`() {
        // Measured at the shipped allowance, 12x12 over twenty matches: 16, 17, 18, 14, 16, 15.
        //
        // The first rung is the one that says `random` is the weakest thing here, which is what
        // makes it the right bot to seat by default -- the opening screen of a game nobody has
        // configured yet should be the easiest opponent there is -- and `ShippedBots` requires the
        // registration order to say so. It is also the only rung whose loser plays no search, so it
        // costs almost nothing to check.
        assertBeats("wallhug", "random", atLeast = 13)
        assertBeats("space", "wallhug", atLeast = 14)
        assertBeats("pressure", "space", atLeast = 15)
        assertBeats("chase", "pressure", atLeast = 11)
        assertBeats("flat-monte-carlo", "chase", atLeast = 12)
        assertBeats("uct", "flat-monte-carlo", atLeast = 12)
    }

    @Test
    fun `the tree is worth nothing until it has room to grow, and a lot afterwards`() {
        // UCT and flat Monte Carlo share a rollout policy and an allowance. The only difference is
        // that one of them remembers what it learned -- and at a few thousand simulated moves a turn
        // there is nothing to remember, because a rollout runs a hundred-odd moves and four of them
        // are spent giving each opening its first visit. The tree only starts paying once there are
        // iterations left over to deepen it.
        //
        // This is why the shipped default matters and why the contract suite's smaller allowance is
        // not evidence about strength. Measured: 10 of 20 at a tenth, 15 of 20 at the full allowance.
        val cramped = winsFor("uct", "flat-monte-carlo", budget = CRAMPED_BUDGET)
        assertTrue(cramped in 6..14, "at a tenth of the allowance the two should be hard to tell apart, was $cramped")

        assertBeats("uct", "flat-monte-carlo", atLeast = 12)
    }

    @Test
    fun `the shipped allowance is worth what it costs`() {
        // The assertion behind MatchSetup.DEFAULT_BUDGET_PER_TURN. The number was chosen from timings
        // -- how much search fits in a frame -- and a timing alone cannot say whether the search is
        // worth having. Same bot, same board, same seeds, ten times the allowance on one side.
        //
        // Measured: 17 of 20. Which is also the honest ceiling on the argument for raising it
        // further: ten times the budget is three wins in twenty, so the curve is flat enough that
        // the frame budget, not strength, is the thing to set this by.
        val wins = winsFor("uct", "uct", BUDGET, CRAMPED_BUDGET)
        assertTrue(wins >= 14, "the full allowance beat a tenth of it in only $wins of $ROUNDS")
    }

    /** Plays [challenger] against [defender] over [ROUNDS] matches, both seatings, on a 12x12. */
    private fun assertBeats(challenger: String, defender: String, atLeast: Int) {
        val wins = winsFor(challenger, defender)
        assertTrue(wins >= atLeast, "$challenger beat $defender in only $wins of $ROUNDS")
    }

    private fun winsFor(challenger: String, defender: String, budget: Int = BUDGET): Int =
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

        /** `MatchSetup.DEFAULT_BUDGET_PER_TURN`, which `:bots` may not import. */
        const val BUDGET = 40_000

        /** A tenth of the shipped allowance, which is what the raise to it has to be worth beating. */
        const val CRAMPED_BUDGET = BUDGET / 10
    }
}
