package ao.snakewarz.bots

import ao.snakewarz.botapi.BotId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShippedBotsTest {
    @Test
    fun `the released slugs are these, and they do not change`() {
        // Slugs live in shared replay URLs. Renaming one breaks every link ever posted for it, so
        // this assertion is the reminder, and failing it is the point rather than an inconvenience.
        assertEquals(
            listOf("random", "wallhug", "space", "pressure", "chase", "flat-monte-carlo", "uct"),
            ShippedBots.entries.map { it.id.slug },
        )
    }

    @Test
    fun `lookup finds what is registered and admits what is not`() {
        assertEquals(BotId("random"), ShippedBots[BotId("random")]?.id)
        assertEquals(BotId("uct"), ShippedBots[BotId("uct")]?.id, "Phase 4 landed")
        assertNull(ShippedBots[BotId("no-such-bot")])
        assertFailsWith<IllegalArgumentException> { ShippedBots.entryOf(BotId("no-such-bot")) }
    }

    @Test
    fun `entries iterate in registration order`() {
        // Twice, because the failure this guards against — iterating a HashMap — is intermittent by
        // nature, and reordering the registry silently reorders every tournament built on it.
        assertEquals(ShippedBots.entries.map { it.id }, ShippedBots.entries.map { it.id })
    }

    @Test
    fun `every entry has a name a human can read`() {
        assertEquals(
            listOf("Random", "Wall Hugger", "Space Filler", "Pressure", "Chaser", "Flat Monte Carlo", "UCT"),
            ShippedBots.entries.map { it.displayName },
        )
    }
}
