package ao.snakewarz.bots

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
    fun `flat Monte Carlo against random on 12x12`() {
        // A smaller board and a smaller allowance than the rest, on purpose: this one simulates,
        // and the suite it belongs to also runs in a real browser, where the engine is slower. It
        // is still hundreds of thousands of simulated moves, which is plenty to pin.
        assertEquals(
            135969093263537927L,
            hashOf("flat-monte-carlo", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = 500),
        )
    }

    @Test
    fun `UCT against random on 12x12`() {
        // The one that would catch a cross-target divergence in UCB1, which is why its logarithm
        // comes from `portableLog` and not from `kotlin.math`. This suite runs in Chrome too.
        assertEquals(
            4890617335203011984L,
            hashOf("uct", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = 500),
        )
    }

    @Test
    fun `PUCT against random on 12x12`() {
        // Ten times the allowance the other two searchers get on the same board, because this one
        // charges itself for its evaluation: at `expert` a leaf costs `grid.playableCount`, so five
        // hundred would buy three iterations a turn and pin next to nothing. Five thousand is about
        // thirty-five, which is a search — and still trivial for the browser job this suite runs in.
        assertEquals(
            -1952801837547873716L,
            hashOf("puct", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = 5_000),
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
        budgetPerTurn: Int = 1_000,
        rules: ao.snakewarz.core.rules.RulesConfig = ao.snakewarz.core.rules.RulesConfig(),
    ): Long {
        val match = HeadlessMatch(
            listOf(ShippedBots.entryOf(BotId(first)), ShippedBots.entryOf(BotId(second))),
            rows = rows,
            cols = cols,
            seed = seed,
            budgetPerTurn = budgetPerTurn,
            rules = rules,
        )
        match.run()
        return moveStreamHash(match.moves())
    }
}
