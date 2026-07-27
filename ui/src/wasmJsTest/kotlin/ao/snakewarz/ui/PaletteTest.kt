package ao.snakewarz.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Colour, keyed by slot index and by nothing else — including for a slot index past the last hue.
 *
 * Cycling rather than generating is a decision the class states, and what that decision must not do
 * is hand two *seated* snakes the same colour. Up to [SEATS] the distinctness is a promise; past it
 * the palette repeats on purpose and merely has to keep answering.
 */
class PaletteTest {
    @Test
    fun `every seat the sidebar offers gets its own trail colour`() {
        val colours = (0 until SEATS).map { Palette.bodyColour(it) }

        assertEquals(colours.size, colours.toSet().size, "two seated snakes share a colour: $colours")
    }

    @Test
    fun `a slot past the last hue cycles rather than failing`() {
        // Nothing seats this many, but the palette is indexed by slot and an index is a number.
        val hues = (0..1_000).map { Palette.bodyColour(it) }.toSet()

        assertTrue(hues.size >= SEATS, "the cycle is shorter than the board, so two seats must collide")
        assertEquals(Palette.bodyColour(0), Palette.bodyColour(hues.size), "and it is a cycle, not a run")
        assertTrue(hues.all { it.startsWith("#") }, "still a colour, whatever the index")
    }

    @Test
    fun `a trail keeps its colour under either theme, and a head does not`() {
        // Which is what lets the scoreboard swatches be painted without a palette instance, and why
        // they never need repainting on a theme change.
        val light = Palette.of(dark = false)
        val dark = Palette.of(dark = true)

        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.gridline, dark.gridline)
        for (slot in 0 until SEATS) {
            assertNotEquals(light.head(slot), dark.head(slot), "the head of slot $slot reads against the page")
        }
    }

    @Test
    fun `a fading square stays clear of a corpse`() {
        // A fading square is one that is about to open; a corpse is one that never will. If the two
        // alphas ever met, the board would stop saying which is which.
        assertTrue(Palette.AGING_ALPHA > Palette.DYING_ALPHA)
        assertTrue(Palette.DYING_ALPHA > Palette.CORPSE_ALPHA)
    }

    private companion object {
        /** `Chrome.SCOREBOARD_ROWS`, whose companion is private and stays that way for one test. */
        const val SEATS = 4
    }
}
