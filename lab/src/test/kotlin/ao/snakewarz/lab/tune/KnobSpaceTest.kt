package ao.snakewarz.lab.tune

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KnobSpaceTest {
    @Test
    fun `a proposal moves a whole number of the knob's own steps`() {
        val knob = BotKnob.Integer("size", "Size", "", default = 50, min = 0, max = 100, step = 5)

        assertEquals(listOf("10", "90"), KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 8))
        assertEquals(listOf("45", "55"), KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 1))
    }

    @Test
    fun `a proposal outside the declared range is not offered`() {
        val knob = BotKnob.Integer("size", "Size", "", default = 5, min = 0, max = 100, step = 5)

        // Below zero is not a smaller value, it is not a value.
        assertEquals(listOf("45"), KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 8))
    }

    @Test
    fun `a decimal is written the way its own step implies`() {
        // Adding 0.05 to itself produces 0.30000000000000004, and that string would go into a replay
        // URL, a log line and a column heading.
        val knob = BotKnob.Decimal("weight", "Weight", "", default = 0.2, min = 0.0, max = 1.0, step = 0.05)

        for (stride in 1..4) {
            for (value in KnobSpace.neighbours(knob, BotParams.EMPTY, stride)) {
                assertTrue(value.length <= "0.05".length, "'$value' is not written to the step's precision")
            }
        }
        assertEquals(listOf("0.15", "0.25"), KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 1))
        assertEquals(listOf("0", "0.6"), KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 8))
    }

    @Test
    fun `a choice offers every other value and ignores the stride`() {
        val knob = BotKnob.Choice("eval", "Eval", "", default = "b", values = listOf("a", "b", "c"))

        assertEquals(listOf("a", "c"), KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 1))
        assertEquals(listOf("a", "c"), KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 8))
    }

    @Test
    fun `a flag offers the other setting`() {
        val knob = BotKnob.Flag("on", "On", "", default = false)

        assertEquals(listOf("true"), KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 3))
        assertEquals(listOf("false"), KnobSpace.neighbours(knob, at(knob, "true"), stride = 3))
    }

    @Test
    fun `a knob pinned at the end of its range has nowhere to go`() {
        val knob = BotKnob.Decimal("weight", "Weight", "", default = 1.0, min = 1.0, max = 1.0, step = 0.05)

        assertTrue(KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 1).isEmpty())
        assertTrue(KnobSpace.exhausted(knob, BotParams.EMPTY, stride = 1))
    }

    @Test
    fun `setting a knob leaves every other knob where it was`() {
        val knob = BotKnob.Decimal("weight", "Weight", "", default = 0.2, min = 0.0, max = 1.0, step = 0.05)
        val existing = BotParams(mapOf("eval" to "survival", "weight" to "0.2"))

        val updated = KnobSpace.with(existing, knob, "0.35")

        assertEquals("survival", updated.string("eval", ""))
        assertEquals("0.35", updated.string("weight", ""))
        assertEquals(listOf("eval", "weight"), updated.names.toList(), "and in the order they arrived")
    }

    @Test
    fun `a stride has to move something`() {
        val knob = BotKnob.Integer("size", "Size", "", default = 5, min = 0, max = 100, step = 5)

        assertFailsWith<IllegalArgumentException> { KnobSpace.neighbours(knob, BotParams.EMPTY, stride = 0) }
    }

    @Test
    fun `a proposal reads back as the value it was written as`() {
        // The round trip that matters: what the search writes is what the bot will be built with, and
        // a knob's own reader is total, so a value it cannot parse would silently become the default
        // and the search would measure nothing.
        val knob = BotKnob.Decimal("cpuct", "cpuct", "", default = 1.5, min = 0.1, max = 10.0, step = 0.1)

        for (stride in 1..12) {
            for (value in KnobSpace.neighbours(knob, BotParams.EMPTY, stride)) {
                assertContains(knob.reject(value)?.let { emptyList<String>() } ?: listOf(value), value)
                assertEquals(value.toDouble(), knob.read(value), 1e-9)
            }
        }
    }

    private fun at(knob: BotKnob.Param<*>, value: String): BotParams = BotParams(mapOf(knob.name to value))
}
