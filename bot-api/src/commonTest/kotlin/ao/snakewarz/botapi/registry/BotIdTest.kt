package ao.snakewarz.botapi.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BotIdTest {
    @Test
    fun `a slug is lowercase letters, digits and hyphens`() {
        assertEquals("uct", BotId("uct").slug)
        assertEquals("flat-monte-carlo", BotId("flat-monte-carlo").slug)
        assertEquals("bot2", BotId("bot2").slug)
    }

    @Test
    fun `anything that would need escaping in a URL is rejected`() {
        // Ids go into replay URLs verbatim. Validating here means the codec never has to escape,
        // and a bad id fails at registration rather than in somebody's shared link.
        for (bad in listOf("", "Uct", "wall hug", "wall_hug", "über", "a/b", "bot?")) {
            assertFailsWith<IllegalArgumentException>("'$bad' must not be a legal id") { BotId(bad) }
        }
    }

    @Test
    fun `a slug is bounded, so a corrupt payload cannot ask a decoder for an enormous string`() {
        assertEquals(BotId.MAX_LENGTH, BotId("a".repeat(BotId.MAX_LENGTH)).slug.length)
        assertFailsWith<IllegalArgumentException> { BotId("a".repeat(BotId.MAX_LENGTH + 1)) }
    }

    @Test
    fun `ids compare by slug`() {
        assertEquals(BotId("random"), BotId("random"))
        assertEquals(BotId("random").hashCode(), BotId("random").hashCode())
        assertEquals(false, BotId("random") == BotId("wallhug"))
    }
}
