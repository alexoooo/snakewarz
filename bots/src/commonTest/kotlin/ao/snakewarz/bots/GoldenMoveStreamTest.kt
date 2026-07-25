package ao.snakewarz.bots

import ao.snakewarz.botapi.BotId
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
    fun `the growth cadence reaches the bots, not just the engine`() {
        // Classic Tron is a materially different game; a bot suite that cannot tell the two apart
        // would not notice `growEveryNthMove` being wired up wrong.
        val halfSpeed = hashOf("random", "random", seed = 11)
        val tron = hashOf(
            "random",
            "random",
            seed = 11,
            rules = ao.snakewarz.core.RulesConfig(growEveryNthMove = 1),
        )

        assertEquals(false, halfSpeed == tron)
    }

    private fun hashOf(
        first: String,
        second: String,
        seed: Long,
        rows: Int = 20,
        cols: Int = 20,
        rules: ao.snakewarz.core.RulesConfig = ao.snakewarz.core.RulesConfig(),
    ): Long {
        val match = HeadlessMatch(
            listOf(ShippedBots.entryOf(BotId(first)), ShippedBots.entryOf(BotId(second))),
            rows = rows,
            cols = cols,
            seed = seed,
            rules = rules,
        )
        match.run()
        return moveStreamHash(match.moves())
    }
}
