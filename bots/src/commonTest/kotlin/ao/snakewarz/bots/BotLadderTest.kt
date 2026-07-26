package ao.snakewarz.bots

import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.SnakeId
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
 */
class BotLadderTest {
    @Test
    fun `each rung beats the one below it`() {
        // Measured, at 12x12 over twenty matches: 17, 18, 14, 14, 16.
        assertBeats("space", "wallhug", atLeast = 15)
        assertBeats("pressure", "space", atLeast = 15)
        assertBeats("chase", "pressure", atLeast = 12)
        assertBeats("flat-monte-carlo", "chase", atLeast = 12)
        assertBeats("uct", "flat-monte-carlo", atLeast = 13)
    }

    @Test
    fun `the tree is worth nothing until it has room to grow, and a lot afterwards`() {
        // UCT and flat Monte Carlo share a rollout policy and an allowance. The only difference is
        // that one of them remembers what it learned -- and at a thousand simulated moves a turn
        // there is nothing to remember, because a rollout runs a hundred-odd moves and four of them
        // are spent giving each opening its first visit. The tree only starts paying once there are
        // iterations left over to deepen it.
        //
        // This is why the shipped default matters and why the contract suite's smaller allowance is
        // not evidence about strength. Measured: 9 of 20 at a thousand, 16 of 20 at ten thousand.
        val cramped = winsFor("uct", "flat-monte-carlo", budget = 1_000)
        assertTrue(cramped in 6..14, "at a thousand a turn the two should be hard to tell apart, was $cramped")

        assertBeats("uct", "flat-monte-carlo", atLeast = 13)
    }

    /** Plays [challenger] against [defender] over [ROUNDS] matches, both seatings, on a 12x12. */
    private fun assertBeats(challenger: String, defender: String, atLeast: Int) {
        val wins = winsFor(challenger, defender)
        assertTrue(wins >= atLeast, "$challenger beat $defender in only $wins of $ROUNDS")
    }

    private fun winsFor(challenger: String, defender: String, budget: Int = BUDGET): Int {
        val first = entry(challenger)
        val second = entry(defender)
        var wins = 0

        for (seed in 1L..(ROUNDS / 2)) {
            if (HeadlessMatch(listOf(first, second), ROWS, COLS, seed, budget).run().winner == SnakeId(0)) {
                wins++
            }
            if (HeadlessMatch(listOf(second, first), ROWS, COLS, seed, budget).run().winner == SnakeId(1)) {
                wins++
            }
        }

        return wins
    }

    private fun entry(slug: String): BotEntry = ShippedBots.entryOf(BotId(slug))

    private companion object {
        const val ROUNDS = 20
        const val ROWS = 12
        const val COLS = 12

        /** `MatchSetup.DEFAULT_BUDGET_PER_TURN`, which `:bots` may not import. */
        const val BUDGET = 10_000
    }
}
