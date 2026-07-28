package ao.snakewarz.botapi.knob

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotKnobTest {
    private val count = BotKnob.Integer("maxNodes", "Tree nodes", "how big", default = 1024, min = 16, max = 4096)
    private val rate =
        BotKnob.Decimal("exploration", "Exploration", "how wide", 5.0, min = 0.1, max = 100.0, step = 0.1)
    private val flag = BotKnob.Flag("reuseTree", "Reuse tree", "keep it", default = false)
    private val eval = BotKnob.Choice(
        "eval", "Evaluation", "how a leaf is judged",
        default = "territory",
        values = listOf("territory", "mobility", "survival"),
    )

    @Test
    fun `an undeclared value reads as the default`() {
        assertEquals(1024, count.read(BotParams.EMPTY))
        assertEquals(5.0, rate.read(BotParams.EMPTY))
        assertEquals(false, flag.read(BotParams.EMPTY))
        assertEquals("territory", eval.read(BotParams.EMPTY))
    }

    @Test
    fun `a declared value is parsed`() {
        assertEquals(2048, count.read(BotParams(mapOf("maxNodes" to "2048"))))
        assertEquals(1.5, rate.read(BotParams(mapOf("exploration" to "1.5"))))
        assertEquals(true, flag.read(BotParams(mapOf("reuseTree" to "true"))))
        assertEquals("survival", eval.read(BotParams(mapOf("eval" to "survival"))))
    }

    @Test
    fun `defaultText round trips back to the default`() {
        // The one seam left now that the constructor holds no literal: a Decimal that renders as
        // something toDoubleOrNull reads back differently would put a wrong number on the form.
        assertEquals(count.default, count.read(BotParams(mapOf(count.name to count.defaultText))))
        assertEquals(rate.default, rate.read(BotParams(mapOf(rate.name to rate.defaultText))))
        assertEquals(flag.default, flag.read(BotParams(mapOf(flag.name to flag.defaultText))))
        assertEquals(eval.default, eval.read(BotParams(mapOf(eval.name to eval.defaultText))))
    }

    @Test
    fun `reading is total, because a corrupt replay fragment has nothing to catch a throw`() {
        // Match builds its bots in a field initializer, outside the try that guards chooseMove, and
        // one route in is whatever somebody pasted into the address bar.
        assertEquals(1024, count.read("lots"))
        assertEquals(5.0, rate.read("wide"))
        assertEquals(false, flag.read("yes"))
        // And a value that was offered by a version this replay predates, or that has since been
        // dropped, reads as the default rather than as whatever now sits at its index.
        assertEquals("territory", eval.read("neural"))
    }

    @Test
    fun `a value outside the range is pulled back into it rather than honoured`() {
        assertEquals(4096, count.read("999999"))
        assertEquals(16, count.read("-5"))
        assertEquals(100.0, rate.read("1e9"))
    }

    @Test
    fun `reject says what is wrong, and says nothing when nothing is`() {
        assertNull(count.reject("2048"))
        assertNull(count.reject(" 2048 "))
        assertNull(rate.reject("1.5"))
        assertNull(flag.reject("true"))
        assertNull(eval.reject("survival"))
        assertNull(eval.reject(" survival "))

        assertNotNull(count.reject("lots"))
        assertNotNull(count.reject("99999"))
        assertNotNull(rate.reject("wide"))
        assertNotNull(flag.reject("yes"))
        // A complaint that names the options, because a form has nowhere else to show them.
        assertEquals("one of territory, mobility, survival", eval.reject("neural"))
    }

    @Test
    fun `isDefault compares values rather than spelling`() {
        assertTrue(rate.isDefault("5"))
        assertTrue(rate.isDefault("5.0"))
        assertTrue(count.isDefault(count.defaultText))
        assertTrue(eval.isDefault("territory"))
        assertEquals(false, rate.isDefault("1.5"))
        assertEquals(false, eval.isDefault("survival"))
        // Unparseable reads as the default, but is not a default *value* somebody typed.
        assertEquals(false, rate.isDefault("wide"))
        assertEquals(false, eval.isDefault("neural"))
    }

    @Test
    fun `a choice offering nothing usable is refused`() {
        assertFailsWith<IllegalArgumentException> { BotKnob.Choice("a", "A", "", "x", emptyList()) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Choice("a", "A", "", "x", listOf("x", "x")) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Choice("a", "A", "", "z", listOf("x", "y")) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Choice("a", "A", "", "x", listOf("x", "")) }
        // Bounded here rather than at the codec, so a payload stays decodable by construction.
        assertFailsWith<IllegalArgumentException> {
            BotKnob.Choice("a", "A", "", "x", listOf("x", "y".repeat(BotKnob.MAX_VALUE_LENGTH + 1)))
        }
    }

    @Test
    fun `a knob declaring an impossible range is refused`() {
        assertFailsWith<IllegalArgumentException> { BotKnob.Integer("a", "A", "", 5, min = 10, max = 20) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Integer("a", "A", "", 5, min = 20, max = 10) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Integer("a", "A", "", 5, min = 0, max = 10, step = 0) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Decimal("a", "A", "", 5.0, 10.0, 20.0, 1.0) }
    }

    @Test
    fun `a knob name has to survive a URL, and a knob needs a label`() {
        assertFailsWith<IllegalArgumentException> { BotKnob.Integer("", "A", "", 1, 0, 2) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Integer("max nodes", "A", "", 1, 0, 2) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Integer("max=nodes", "A", "", 1, 0, 2) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Integer("a".repeat(33), "A", "", 1, 0, 2) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Integer("a", " ", "", 1, 0, 2) }
    }

    @Test
    fun `an allowance declares a range and nothing else`() {
        val search = BotKnob.Search(min = 0, max = 400_000, step = 10_000)

        assertEquals(BotKnob.Search.NAME, search.name)
        assertFailsWith<IllegalArgumentException> { BotKnob.Search(min = -1, max = 10, step = 1) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Search(min = 10, max = 0, step = 1) }
        assertFailsWith<IllegalArgumentException> { BotKnob.Search(min = 0, max = 10, step = 0) }
    }

    @Test
    fun `a knob is tuning until it says otherwise, and an allowance says so by being one`() {
        // The default is the one that matters: a knob nobody thought about stays off the sidebar,
        // where a number a player cannot judge is worse than no number at all.
        assertFalse(BotKnob.Integer("a", "A", "", 1, 0, 2).tradeoff)
        assertFalse(BotKnob.Decimal("a", "A", "", 1.0, 0.0, 2.0, 0.1).tradeoff)
        assertFalse(BotKnob.Flag("a", "A", "", default = false).tradeoff)
        assertFalse(BotKnob.Choice("a", "A", "", default = "x", values = listOf("x", "y")).tradeoff)

        assertTrue(BotKnob.Integer("a", "A", "", 1, 0, 2, tradeoff = true).tradeoff)
        assertTrue(BotKnob.Search(min = 0, max = 10, step = 1).tradeoff, "bigger is stronger, and slower")
    }
}
