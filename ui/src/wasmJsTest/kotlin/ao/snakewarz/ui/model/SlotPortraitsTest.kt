package ao.snakewarz.ui.model

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.human.PlayableRegistry
import ao.snakewarz.ui.render.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam, from the side that decides what a seat actually shows.
 *
 * The pair is the whole feature: shipped art where somebody drew some, and a drawn mark where nobody
 * did — so a registry `:ui` has never heard of gets a face per entrant rather than a broken image,
 * and a bot contributed tomorrow works on the day it is registered.
 */
class SlotPortraitsTest {
    @Test
    fun `a bot the page ships art for shows it`() {
        val faces = SlotPortraits(setupFor("random", "uct"), SHIPPED, THEME)

        assertEquals("portrait/random.svg", faces[0])
        assertEquals("portrait/uct.svg", faces[1])
    }

    @Test
    fun `a human seat has no portrait or generated mark`() {
        val faces = SlotPortraits(setupFor(PlayableRegistry.HUMAN_ID.slug, "uct"), SHIPPED, THEME)

        assertNull(faces[0])
        assertEquals("portrait/uct.svg", faces[1])
    }

    @Test
    fun `a bot it does not falls back to a mark rather than a broken image`() {
        val faces = SlotPortraits(setupFor("random", "never-shipped"), SHIPPED, THEME)

        assertTrue(faces[1]!!.startsWith("data:image/svg+xml;"), "${faces[1]}")
    }

    @Test
    fun `a registry nobody drew anything for still gets a face per seat`() {
        // The day-one case for a fork: the seam answers `null` to everything and every seat is still
        // told apart on the scoreboard.
        val faces = SlotPortraits(setupFor("alpha", "beta", "gamma"), Portraits { null }, THEME)

        val marks = (0..2).map { faces[it] }
        assertEquals(marks.size, marks.toSet().size, "$marks")
    }

    @Test
    fun `two seats of the same unknown bot are one mark in two colours`() {
        val faces = SlotPortraits(setupFor("never-shipped", "never-shipped"), SHIPPED, THEME)

        assertNotEquals(faces[0], faces[1])
    }

    @Test
    fun `a theme moves a drawn mark and leaves a drawn portrait alone`() {
        // Which is what the session's cache is keyed on. A shipped file is a file whatever the board
        // is coloured; a mark carries the seat's trail hue and has to be redrawn with it.
        val dusk = Theme.of("dusk", dark = false)
        val setup = setupFor("uct", "never-shipped")

        assertEquals(
            SlotPortraits(setup, SHIPPED, THEME)[0],
            SlotPortraits(setup, SHIPPED, dusk)[0],
        )
        assertNotEquals(
            SlotPortraits(setup, SHIPPED, THEME)[1],
            SlotPortraits(setup, SHIPPED, dusk)[1],
        )
    }

    @Test
    fun `a scheme change is not a theme change, so nothing is redrawn for it`() {
        // `Theme.body` is what a snake *is* and does not move when the sun goes down.
        val setup = setupFor("never-shipped")

        assertEquals(
            SlotPortraits(setup, SHIPPED, Theme.of("classic", dark = false))[0],
            SlotPortraits(setup, SHIPPED, Theme.of("classic", dark = true))[0],
        )
    }

    @Test
    fun `a slot off the end of the match has no face, rather than an empty src`() {
        // The scoreboard has a fixed number of cards and a match does not fill them all. An `<img>`
        // pointed at "" fetches the page itself and draws a broken icon.
        assertNull(SlotPortraits(setupFor("random"), SHIPPED, THEME)[3])
    }

    private fun setupFor(vararg slugs: String): MatchSetup =
        MatchSetup.create(rows = 10, cols = 10, slots = slugs.map(::BotId), seed = 1)

    private companion object {
        val THEME = Theme.of(Theme.DEFAULT_ID, dark = true)

        /** `:app`'s seam, stated as the two shipped slugs this file names and nothing else. */
        val SHIPPED = Portraits { slug ->
            if (slug == "random" || slug == "uct") "portrait/$slug.svg" else null
        }
    }
}
