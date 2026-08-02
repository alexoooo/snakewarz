package ao.snakewarz.ui.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Colour, keyed by slot index and by nothing else — including for a slot index past the last hue,
 * and now across every theme and both schemes.
 *
 * Cycling rather than generating is a decision the class states, and what that decision must not do
 * is hand two *seated* snakes the same colour. Up to [SEATS] the distinctness is a promise; past it
 * the palette repeats on purpose and merely has to keep answering.
 *
 * Everything below sweeps [Theme.ALL] rather than naming a theme, so a fourth one is enrolled by
 * being listed and cannot ship a board whose walls are invisible or two snakes the same colour.
 */
class ThemeTest {
    @Test
    fun `every seat the picker offers gets its own trail colour`() {
        forEachTheme { theme, where ->
            val colours = (0 until SEATS).map { theme.body(it) }

            assertEquals(colours.size, colours.toSet().size, "$where: two seated snakes share one of $colours")
        }
    }

    @Test
    fun `a slot past the last hue cycles rather than failing`() {
        forEachTheme { theme, where ->
            // Nothing seats this many, but the palette is indexed by slot and an index is a number.
            val hues = (0..1_000).map { theme.body(it) }.toSet()

            assertTrue(hues.size >= SEATS, "$where: the cycle is shorter than the board, so two seats collide")
            assertEquals(theme.body(0), theme.body(hues.size), "$where: and it is a cycle, not a run")
            assertTrue(hues.all { it.startsWith("#") }, "$where: still a colour, whatever the index")
        }
    }

    @Test
    fun `a trail belongs to the theme and a head belongs to the scheme`() {
        // The split the whole type is arranged around: what a snake *is* survives the sun going
        // down, so a scoreboard swatch needs no repainting on a scheme change — only on a theme one.
        for (id in Theme.ALL) {
            val light = Theme.of(id, dark = false)
            val dark = Theme.of(id, dark = true)

            assertNotEquals(light.background, dark.background, id)
            assertNotEquals(light.gridline, dark.gridline, id)
            for (slot in 0 until SEATS) {
                assertEquals(light.body(slot), dark.body(slot), "$id: the trail of slot $slot names its snake")
                assertNotEquals(light.head(slot), dark.head(slot), "$id: the head of slot $slot reads on the page")
            }
        }
    }

    @Test
    fun `two themes are two looks`() {
        // Otherwise the picker offers a choice that changes nothing, which is worse than not
        // offering one: a trail hue is what a player identifies a snake by, and the board is the
        // largest thing on the screen.
        for (dark in listOf(false, true)) {
            val boards = Theme.ALL.map { Theme.of(it, dark).background }
            val trails = Theme.ALL.map { id -> (0 until SEATS).map { Theme.of(id, dark).body(it) } }

            assertEquals(boards.size, boards.toSet().size, "two themes paint the same board, dark=$dark")
            assertEquals(trails.size, trails.toSet().size, "two themes paint the same snakes, dark=$dark")
        }
    }

    @Test
    fun `a wall is board, and is none of the things standing on it`() {
        // It has to be a third colour on both counts: the same as the background and the map is
        // invisible, the same as a trail hue at any strength and a wall reads as a snake that is out.
        forEachTheme { theme, where ->
            assertNotEquals(theme.background, theme.wall, where)
            assertNotEquals(theme.gridline, theme.wall, where)
            for (slot in 0 until SEATS) {
                assertNotEquals(theme.body(slot), theme.wall, "$where: the trail of slot $slot")
                assertNotEquals(theme.head(slot), theme.wall, "$where: the head of slot $slot")
            }
        }
    }

    @Test
    fun `a wall has an edge to be read by`() {
        // Relief rather than a second colour, so it only has to differ from the block it outlines —
        // without it a room's wall is one slab and the shape of the map is legible only where it
        // meets open board.
        forEachTheme { theme, where ->
            assertNotEquals(theme.wall, theme.wallEdge, where)
            assertNotEquals(theme.background, theme.wallEdge, where)
        }
    }

    @Test
    fun `an id nothing offers falls back on the default`() {
        // The id comes out of localStorage, so it can be whatever a future version wrote or whatever
        // somebody typed. There is a correct thing to do with it and it is not to take the page down.
        for (dark in listOf(false, true)) {
            val fallback = Theme.of("no such theme", dark)

            assertEquals(Theme.DEFAULT_ID, fallback.id, "dark=$dark")
            assertEquals(Theme.of(Theme.DEFAULT_ID, dark).background, fallback.background, "dark=$dark")
        }

        assertEquals(Theme.DEFAULT_ID, Theme.ALL.first(), "and the default is the option the page opens on")
    }

    // -- internals

    /** Every shipped theme under both schemes, with a name for whichever one fails. */
    private fun forEachTheme(check: (Theme, String) -> Unit) {
        for (id in Theme.ALL) {
            for (dark in listOf(false, true)) {
                check(Theme.of(id, dark), "$id ${if (dark) "dark" else "light"}")
            }
        }
    }

    private companion object {
        /** `SetupPanel.SEATS`, whose companion is private and stays that way for one test. */
        const val SEATS = 4
    }
}
