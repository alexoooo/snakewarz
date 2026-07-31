package ao.snakewarz.ui.render

import ao.snakewarz.match.map.MapShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parts of a texture pack that are not a drawing: the shape it is chosen by, and the figure it
 * stipples a board with.
 *
 * Canvas output is not testable here, so what is pinned is the *rule* — that a square's figure is a
 * pure function of that square, and that every shape in the catalogue has a feeling. Both are
 * failures nobody would see in a suite: an RNG in the figure looks fine until a window is resized,
 * and a shape with no entry cannot happen at all while the `when` has no `else`.
 */
class TexturePackTest {
    @Test
    fun `every shape has a feeling, and every feeling has a shape`() {
        // Totality is the compiler's job -- the `when` has no `else`, so a twelfth shape does not
        // build. What no compiler can say is the other direction: a pack no shape is ever drawn
        // with is a treatment nobody will see, and a pack is only worth having if a board wears it.
        val drawn = MapShape.entries.map { TexturePack.of(it) }.toSet()
        assertEquals(TexturePack.entries.toSet(), drawn, "every pack is on some board")

        assertEquals(TexturePack.PLAIN, TexturePack.of(null), "a replay carries walls and no shape")
        assertEquals(TexturePack.PLAIN, TexturePack.of(MapShape.EMPTY), "a bare board is the plain one")
    }

    @Test
    fun `the shapes that scatter their walls read differently from the shapes that lay them`() {
        // Not eleven feelings: a pack groups the shapes whose *character* is the same, and these
        // three groups are the ones a player could name from the board alone.
        assertEquals(TexturePack.MASONRY, TexturePack.of(MapShape.ROOMS))
        assertEquals(TexturePack.MASONRY, TexturePack.of(MapShape.DOUBLE_SPIRAL))
        assertEquals(TexturePack.LATTICE, TexturePack.of(MapShape.PILLARS))
        assertEquals(TexturePack.RUBBLE, TexturePack.of(MapShape.SCATTER))
        assertEquals(TexturePack.RUBBLE, TexturePack.of(MapShape.ISLANDS))
    }

    @Test
    fun `a square's figure is the same answer every time it is asked for`() {
        // The one hard rule. The board bitmap is laid down again on every resize, so a figure that
        // came out differently the second time would be reported as the map having changed -- and
        // the way that happens is an RNG where a hash belongs.
        for (pack in TexturePack.entries) {
            for (row in 0 until SPAN) {
                for (col in 0 until SPAN) {
                    assertEquals(
                        pack.wallInset(row, col),
                        pack.wallInset(row, col),
                        "$pack asked twice about the block at ($row, $col)",
                    )
                    assertEquals(
                        pack.groundShade(row, col),
                        pack.groundShade(row, col),
                        "$pack asked twice about the ground at ($row, $col)",
                    )
                }
            }
        }
    }

    @Test
    fun `a block keeps most of its square, whatever the figure asks for`() {
        // An inset is taken off *both* sides, so anything approaching a half is a wall square with
        // no wall in it -- which on a map is a square that looks passable and is not.
        for (pack in TexturePack.entries) {
            for (row in 0 until SPAN) {
                for (col in 0 until SPAN) {
                    val inset = pack.wallInset(row, col)
                    assertTrue(inset >= 0.0, "$pack inset a block by $inset at ($row, $col)")
                    assertTrue(inset < MAX_INSET, "$pack left a block of ${1 - 2 * inset} at ($row, $col)")
                }
            }
        }
    }

    @Test
    fun `the ground is figured only where a pack says so, and never where it says nothing`() {
        for (row in 0 until SPAN) {
            for (col in 0 until SPAN) {
                assertEquals(0.0, TexturePack.PLAIN.groundShade(row, col), "the plain board is flat")
                assertEquals(0.0, TexturePack.MASONRY.groundShade(row, col), "so is the built one")
            }
        }

        // A lattice is periodic and a speckle is not, which is the whole difference between the two
        // grounds: one says the board was laid out and the other says nobody laid it out at all.
        assertTrue(TexturePack.LATTICE.groundShade(0, 0) > 0.0)
        assertEquals(
            TexturePack.LATTICE.groundShade(0, 0),
            TexturePack.LATTICE.groundShade(LATTICE_PERIOD, LATTICE_PERIOD),
            "a lattice repeats on its own period",
        )
        assertEquals(0.0, TexturePack.LATTICE.groundShade(1, 1), "and is bare between its marks")
    }

    @Test
    fun `only the scattered pack varies square by square`() {
        // The two boards that are different every seed are the two whose blocks are, and the rest
        // are one treatment applied evenly -- a groove that wandered would read as a wobbly wall.
        for (pack in listOf(TexturePack.PLAIN, TexturePack.MASONRY, TexturePack.LATTICE)) {
            val first = pack.wallInset(0, 0)
            for (row in 0 until SPAN) {
                for (col in 0 until SPAN) {
                    assertEquals(first, pack.wallInset(row, col), "$pack varied at ($row, $col)")
                }
            }
        }

        val insets = mutableSetOf<Double>()
        for (row in 0 until SPAN) {
            for (col in 0 until SPAN) {
                insets += TexturePack.RUBBLE.wallInset(row, col)
            }
        }
        assertTrue(insets.size > 1, "rubble that came out one size is not rubble, it was $insets")
    }

    private companion object {
        /** Wider than the largest board the picker offers, so every arithmetic path is walked. */
        const val SPAN = 40

        /** Half a square, taken off both sides: the point at which a block stops being one. */
        const val MAX_INSET = 0.25

        /** `TexturePack`'s own, and deliberately spelled again here rather than exposed for a test. */
        const val LATTICE_PERIOD = 3
    }
}
