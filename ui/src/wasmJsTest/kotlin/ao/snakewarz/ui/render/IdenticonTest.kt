package ao.snakewarz.ui.render

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The mark a bot with no shipped art gets, which has to be the *same* mark forever.
 *
 * A player identifies an opponent by its face, so a drawn one that moved between two versions — or
 * between two targets — would be worse than no face at all. The literal below is what says it has
 * not moved: it pins the hash and the layout together, and fails on a change to either.
 */
class IdenticonTest {
    @Test
    fun `the same bot in the same seat is the same mark`() {
        assertEquals(identicon("wombat", COLOUR), identicon("wombat", COLOUR))
    }

    @Test
    fun `two bots are two marks`() {
        assertNotEquals(identicon("wombat", COLOUR), identicon("badger", COLOUR))
    }

    @Test
    fun `the same bot in two seats is one mark in two colours`() {
        // Which is why these are keyed by slot rather than by slug: two unknown bots of the same
        // kind in one match still have to be told apart on the scoreboard.
        assertNotEquals(identicon("wombat", COLOUR), identicon("wombat", "#4a86d8"))
    }

    @Test
    fun `it is an svg data uri, and the seat colour is in it`() {
        val mark = identicon("wombat", COLOUR)

        assertTrue(mark.startsWith(PREFIX), mark)
        val svg = decode(mark)
        assertTrue(svg.startsWith("<svg xmlns="), svg)
        assertTrue(svg.endsWith("</svg>"), svg)
        assertTrue(svg.contains(COLOUR), svg)
    }

    @Test
    fun `the mark is mirrored, which is what makes a mask read as a face`() {
        // Every block off the centre column has a partner at the reflected x, so each column that
        // appears at all appears as often as the one opposite it.
        for (slug in listOf("wombat", "badger", "one more", "")) {
            val columns = COLUMN_X.findAll(decode(identicon(slug, COLOUR)))
                .map { it.groupValues[1].toInt() }
                .toList()

            for (x in columns.distinct()) {
                assertEquals(
                    columns.count { it == x },
                    columns.count { it == MIRROR - x },
                    "'$slug': column $x has no reflection",
                )
            }
        }
    }

    @Test
    fun `a known slug still draws the mark it always drew`() {
        // Not a literal to update. If this moves, every bot with no art has a different face than
        // the one somebody has been playing against, and what moved it has to be named first.
        assertEquals(
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 33 33'>" +
                "<rect width='33' height='33' rx='6' fill='#16191d'/>" +
                "<g fill='#2f9e68'>" +
                "<rect x='4' y='4' width='5' height='5'/><rect x='24' y='4' width='5' height='5'/>" +
                "<rect x='14' y='4' width='5' height='5'/>" +
                "<rect x='14' y='9' width='5' height='5'/>" +
                "<rect x='4' y='19' width='5' height='5'/><rect x='24' y='19' width='5' height='5'/>" +
                "<rect x='9' y='24' width='5' height='5'/><rect x='19' y='24' width='5' height='5'/>" +
                "</g></svg>",
            decode(identicon("wombat", COLOUR)),
        )
    }

    // -- internals

    private fun decode(mark: String): String = Base64.decode(mark.removePrefix(PREFIX)).decodeToString()

    private companion object {
        const val PREFIX = "data:image/svg+xml;base64,"

        /** `Theme`'s first classic trail, spelled out so this does not move when a palette does. */
        const val COLOUR = "#2f9e68"

        /** The x a block at x is reflected to, from `identicon.kt`'s geometry. */
        const val MIRROR = 28

        val COLUMN_X = Regex("<rect x='(\\d+)'")
    }
}
