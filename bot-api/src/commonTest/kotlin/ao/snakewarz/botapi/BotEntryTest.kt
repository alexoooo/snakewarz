package ao.snakewarz.botapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BotEntryTest {
    private val nothing = BotFactory { error("this entry is never instantiated") }

    private val search = BotKnob.Search(min = 0, max = 1000, step = 100)
    private val depth = BotKnob.Integer("depth", "Depth", "how far", default = 4, min = 1, max = 32)

    @Test
    fun `a bot declares nothing by default`() {
        val entry = BotEntry(BotId("plain"), "Plain", nothing)

        assertTrue(entry.knobs.isEmpty())
        assertTrue(entry.params.isEmpty())
        assertNull(entry.search)
    }

    @Test
    fun `search is the allowance and params are the rest`() {
        val entry = BotEntry(BotId("searcher"), "Searcher", nothing, listOf(search, depth))

        assertSame(search, entry.search)
        assertEquals(listOf<BotKnob>(depth), entry.params)
    }

    @Test
    fun `a bot needs a display name`() {
        assertFailsWith<IllegalArgumentException> { BotEntry(BotId("plain"), " ", nothing) }
    }

    @Test
    fun `a knob name declared twice is refused`() {
        val same = BotKnob.Integer("depth", "Depth again", "", default = 1, min = 0, max = 2)

        assertFailsWith<IllegalArgumentException> {
            BotEntry(BotId("plain"), "Plain", nothing, listOf(depth, same))
        }
    }

    @Test
    fun `two allowances are refused`() {
        assertFailsWith<IllegalArgumentException> {
            BotEntry(BotId("plain"), "Plain", nothing, listOf(search, BotKnob.Search(0, 10, 1)))
        }
    }

    @Test
    fun `a param may not take the allowance's reserved name`() {
        // They would land on the same form row and one of them would win silently.
        val impostor = BotKnob.Integer(BotKnob.Search.NAME, "Budget", "", default = 1, min = 0, max = 2)

        assertFailsWith<IllegalArgumentException> {
            BotEntry(BotId("plain"), "Plain", nothing, listOf(impostor))
        }
    }

    @Test
    fun `more knobs than fit on a form are refused`() {
        val many = List(BotKnob.MAX_PER_BOT + 1) {
            BotKnob.Integer("knob$it", "Knob $it", "", default = 1, min = 0, max = 2)
        }

        assertFailsWith<IllegalArgumentException> { BotEntry(BotId("plain"), "Plain", nothing, many) }
    }
}
