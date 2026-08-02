package ao.snakewarz.app

import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.match.human.PlayableRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * That every seat this page can offer has a picture drawn for it.
 *
 * `:ui` cannot check this — it has never heard of `ShippedBots`, which is the point of the seam —
 * and nothing else in the program sees the registry and the resource directory at once. Without it a
 * bot registered with no art is invisible: the identicon covers for it, correctly, and nobody is
 * ever told that a portrait is missing.
 *
 * What no test here can reach is the directory itself, since a wasm test cannot list files. A slug
 * with an entry below and no `.webp` beside the page is a broken image, and the browser is what says
 * so.
 */
class PortraitUrlTest {
    @Test
    fun `every shipped bot has one, and so does the human seat`() {
        for (slug in seats()) {
            assertNotNull(portraitUrl(slug), "no portrait is shipped for '$slug'")
        }
    }

    @Test
    fun `and nothing is shipped for a bot no registry offers`() {
        // The other direction of the same drift: a slug retired from the registry leaves a file
        // behind, and an entry that points at nothing is what somebody will find in the network tab.
        assertEquals(seats(), SHIPPED_PORTRAITS.filterNotTo(LinkedHashSet()) { it.startsWith("gauntlet-") })
        assertEquals(7, SHIPPED_PORTRAITS.count { it.startsWith("gauntlet-") })
    }

    @Test
    fun `a bot nobody drew falls through to nothing, which is what the identicon is for`() {
        assertNull(portraitUrl("never-shipped"))
        assertNull(portraitUrl(""))
    }

    @Test
    fun `the address is relative, because the site is served out of a subdirectory`() {
        // An absolute path resolves against the domain root, where GitHub Pages serves the user's
        // own site rather than this project's.
        assertEquals("art/portrait/uct.webp", portraitUrl("uct"))
        assertEquals("art/portrait/gauntlet-final-boss.webp", portraitUrl("gauntlet-final-boss"))
    }

    private fun seats(): Set<String> =
        ShippedBots.entries.mapTo(LinkedHashSet()) { it.id.slug }.apply { add(PlayableRegistry.HUMAN_ID.slug) }
}
