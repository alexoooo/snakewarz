package ao.snakewarz.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRegionFromTest {
    @Test
    fun `an empty map is one region`() {
        val reached = openRegionFrom(rows = 6, cols = 7, walls = IntArray(0), from = 0)

        assertEquals(42, reached.count { it })
    }

    @Test
    fun `a wall down the middle splits the board in two`() {
        // Column 2 of a 5x5, top to bottom: nothing crosses it, so a walk from the left reaches ten
        // squares and never the twelve on the right.
        val walls = IntArray(5) { it * 5 + 2 }

        val fromLeft = openRegionFrom(rows = 5, cols = 5, walls = walls, from = 0)

        assertEquals(10, fromLeft.count { it })
        assertTrue(fromLeft[0])
        assertTrue(fromLeft[21])
        assertFalse(fromLeft[3], "a square on the far side of the wall is not reachable")
        for (wall in walls) {
            assertFalse(fromLeft[wall], "a wall is never reached")
        }
    }

    @Test
    fun `a diagonal is not a step, so touching corners do not connect`() {
        // On a 2x2 the two walls leave squares 0 and 3, which meet only at a corner.
        val reached = openRegionFrom(rows = 2, cols = 2, walls = intArrayOf(1, 2), from = 0)

        assertEquals(1, reached.count { it })
        assertTrue(reached[0])
    }

    @Test
    fun `a walk that could not have started is refused rather than answered emptily`() {
        assertFailsWith<IllegalArgumentException>("starting on a wall") {
            openRegionFrom(rows = 3, cols = 3, walls = intArrayOf(4), from = 4)
        }
        assertFailsWith<IllegalArgumentException>("starting off the board") {
            openRegionFrom(rows = 3, cols = 3, walls = IntArray(0), from = 9)
        }
        assertFailsWith<IllegalArgumentException>("a wall off the board") {
            openRegionFrom(rows = 3, cols = 3, walls = intArrayOf(9), from = 0)
        }
    }
}
