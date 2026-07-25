package ao.snakewarz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DirectionTest {
    @Test
    fun `opposite is symmetric and never the identity`() {
        for (direction in Direction.entries) {
            assertNotEquals(direction, direction.opposite, "$direction is its own opposite")
            assertEquals(direction, direction.opposite.opposite, "$direction opposite twice")
        }
    }

    @Test
    fun `opposite negates both offsets`() {
        for (direction in Direction.entries) {
            assertEquals(-direction.dRow, direction.opposite.dRow, "$direction row offset")
            assertEquals(-direction.dCol, direction.opposite.dCol, "$direction col offset")
        }
    }

    @Test
    fun `north decreases the row, matching the legacy top-left origin`() {
        assertEquals(-1, Direction.NORTH.dRow)
        assertEquals(1, Direction.SOUTH.dRow)
        assertEquals(1, Direction.EAST.dCol)
        assertEquals(-1, Direction.WEST.dCol)
    }
}
