package ao.snakewarz.bots

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fixed matches, hashed. These numbers are the canary.
 *
 * They catch the classic determinism failure — an iteration over a `HashMap`, which the legacy
 * `GameStateImpl` did and got away with only because `PlayerAvatar.hashCode()` happened to return a
 * monotonic index — along with a Kotlin codegen change, a stdlib algorithm drift, and any accidental
 * edit to a bot's behaviour. Kotlin/Wasm is Beta, so the second of those is a live concern rather
 * than a theoretical one, which is why this suite also runs in a real browser in CI.
 *
 * A failure here is not automatically a bug. It is always a question that has to be answered before
 * the number is updated.
 */
class GoldenMoveStreamTest {
    @Test
    fun `random against random on 20x20`() {
        assertEquals(4969147972122689914L, hashOf("random", "random", seed = 2005))
    }

    @Test
    fun `wall hugger against random on 20x20`() {
        assertEquals(-2193524718431092627L, hashOf("wallhug", "random", seed = 2005))
    }

    @Test
    fun `wall hugger against wall hugger is fixed with no randomness at all`() {
        // Consumes no RNG, so this one is pinned by the rules alone. If it ever moves, the engine
        // moved.
        assertEquals(-6119216452350361752L, hashOf("wallhug", "wallhug", seed = 0))
    }

    @Test
    fun `the space filler against random on 20x20`() {
        assertEquals(-2269829668146017894L, hashOf("space", "random", seed = 2005))
    }

    @Test
    fun `the pressure bot against random on 20x20`() {
        assertEquals(-8093726933972115299L, hashOf("pressure", "random", seed = 2005))
    }

    @Test
    fun `the chaser against random on 20x20`() {
        assertEquals(-836205036734502335L, hashOf("chase", "random", seed = 2005))
    }

    @Test
    fun `the chaser against a room ranker, where its room guard refuses something`() {
        assertEquals(-4075282736796042152L, hashOf("chase", "space", seed = 1, rows = 16, cols = 16))
    }

    @Test
    fun `and that guard is what makes the stream above what it is`() {
        // The 20x20 case is blind to ChaseBot.ROOM_SHARE, and blind in the way that matters: it sat
        // unchanged straight through the change that introduced the guard. A canary that cannot see
        // a deliberate edit to a bot cannot see an accidental one either.
        //
        // Finding a case that is not blind took looking, and what the search turned up is the
        // interesting part: against `random` the guard alters about one match in twenty and against
        // `wallhug` or `pressure` none at all, because a pocket only forms where somebody is laying
        // wall in front of you deliberately. Against `space` on 16x16 it is 14 seeds in 40. That
        // rarity is not a weakness of the guard -- it is worth +14 Elo *because* the few turns it
        // changes are the ones between dying and not.
        //
        // So the pinned case above is one where it fires, and this asserts that it fires rather than
        // leaving it to be true. Re-pin the pair together: a hash, and a reason it is not a constant.
        val guarded = hashOf("chase", "space", seed = 1, rows = 16, cols = 16)
        val unguarded = hashOf(
            "chase",
            "space",
            seed = 1,
            rows = 16,
            cols = 16,
            params = listOf(BotParams(mapOf("roomShare" to "0")), BotParams.EMPTY),
        )

        assertEquals(false, guarded == unguarded)
    }

    @Test
    fun `flat Monte Carlo against random on 12x12`() {
        // A smaller board than the rest, on purpose: this one simulates, and the suite it belongs to
        // also runs in a real browser, where the engine is slower. Twenty rollouts a turn is still
        // hundreds of thousands of simulated moves over a match, which is plenty to pin.
        assertEquals(
            6424283122996719906L,
            hashOf("flat-monte-carlo", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = SEARCH_BUDGET),
        )
    }

    @Test
    fun `UCT against random on 12x12`() {
        // The one that would catch a cross-target divergence in UCB1, which is why its logarithm
        // comes from `portableLog` and not from `kotlin.math`. This suite runs in Chrome too.
        assertEquals(
            4446294306891950002L,
            hashOf("uct", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = SEARCH_BUDGET),
        )
    }

    @Test
    fun `PUCT against random on 12x12`() {
        // The same allowance as the other two searchers now, and that is the point: an allowance is
        // a count of evaluations, so twenty means twenty iterations here as well — where it used to
        // mean twenty simulated moves and buy this bot next to nothing.
        assertEquals(
            -900434540592784873L,
            hashOf("puct", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = SEARCH_BUDGET),
        )
    }

    @Test
    fun `the serpentine sweeper against random on 20x20`() {
        assertEquals(5564294816982454802L, hashOf("burninhell", "random", seed = 2005))
    }

    @Test
    fun `the contributed mixture against random on 20x20`() {
        assertEquals(-613718763449508305L, hashOf("tomsnake", "random", seed = 2005))
    }

    @Test
    fun `the growth cadence reaches the bots, not just the engine`() {
        // Classic Tron is a materially different game; a bot suite that cannot tell the two apart
        // would not notice `growEveryNthMove` being wired up wrong.
        val halfSpeed = hashOf("random", "random", seed = 11)
        val tron = hashOf(
            "random",
            "random",
            seed = 11,
            rules = ao.snakewarz.core.rules.RulesConfig(growEveryNthMove = 1),
        )

        assertEquals(false, halfSpeed == tron)
    }

    private fun hashOf(
        first: String,
        second: String,
        seed: Long,
        rows: Int = 20,
        cols: Int = 20,
        budgetPerTurn: Int = SEARCH_BUDGET,
        rules: ao.snakewarz.core.rules.RulesConfig = ao.snakewarz.core.rules.RulesConfig(),
        params: List<BotParams> = listOf(BotParams.EMPTY, BotParams.EMPTY),
    ): Long {
        val match = HeadlessMatch(
            listOf(ShippedBots.entryOf(BotId(first)), ShippedBots.entryOf(BotId(second))),
            rows = rows,
            cols = cols,
            seed = seed,
            budgetPerTurn = budgetPerTurn,
            rules = rules,
            paramsPerSlot = params,
        )
        match.run()
        return moveStreamHash(match.moves())
    }

    private companion object {
        /**
         * Evaluations a turn — twenty, which is twenty rollouts or twenty appraisals.
         *
         * One figure for all three searchers, which is new: an allowance used to be counted in
         * simulated moves, so `puct` needed ten times the number the other two got to run a
         * comparable search. It buys the same thing for all of them now.
         */
        const val SEARCH_BUDGET = 20
    }
}
