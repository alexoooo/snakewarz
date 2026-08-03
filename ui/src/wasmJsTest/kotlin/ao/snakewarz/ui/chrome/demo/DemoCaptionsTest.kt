package ao.snakewarz.ui.chrome.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rotation, which is the one thing here that can be wrong without looking wrong.
 *
 * A demo that plays forever is a demo nobody watches twice, so an [DemoCaptions.after] that skipped a
 * line or fell into a shorter cycle would strand a rule on a page whose whole job is to teach it —
 * and the board would keep looping perfectly while it happened.
 */
class DemoCaptionsTest {
    @Test
    fun `every line comes round, and none of them twice before the rest`() {
        val seen = mutableListOf(0)
        var line = 0
        repeat(DemoCaptions.count - 1) {
            line = DemoCaptions.after(line)
            seen += line
        }

        assertEquals((0 until DemoCaptions.count).toList(), seen, "a full cycle is every line, in order")
        assertEquals(0, DemoCaptions.after(line), "and then it starts over")
    }

    @Test
    fun `every line says something, and no two say the same thing`() {
        val texts = List(DemoCaptions.count) { DemoCaptions.text(it) }

        for ((index, text) in texts.withIndex()) {
            assertTrue(text.isNotBlank(), "line $index is blank")
        }
        assertEquals(texts.size, texts.toSet().size, "a repeated line is a lap that teaches nothing")
    }
}
