package ao.snakewarz.core.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DirectionTest {
    @Test
    fun `left is counter-clockwise on screen, since the row axis points down`() {
        // The legacy RelDirection table, which is the whole of WallHugAi's vocabulary.
        assertEquals(Direction.WEST, Direction.NORTH.turnedLeft)
        assertEquals(Direction.SOUTH, Direction.WEST.turnedLeft)
        assertEquals(Direction.EAST, Direction.SOUTH.turnedLeft)
        assertEquals(Direction.NORTH, Direction.EAST.turnedLeft)
    }

    @Test
    fun `right is the other way`() {
        assertEquals(Direction.EAST, Direction.NORTH.turnedRight)
        assertEquals(Direction.SOUTH, Direction.EAST.turnedRight)
        assertEquals(Direction.WEST, Direction.SOUTH.turnedRight)
        assertEquals(Direction.NORTH, Direction.WEST.turnedRight)
    }

    @Test
    fun `four turns the same way come back to where they started`() {
        for (direction in Direction.entries) {
            assertEquals(direction, direction.turnedLeft.turnedLeft.turnedLeft.turnedLeft)
            assertEquals(direction, direction.turnedRight.turnedRight.turnedRight.turnedRight)
            assertEquals(direction.opposite, direction.turnedLeft.turnedLeft)
            assertEquals(direction, direction.turnedLeft.turnedRight)
        }
    }

    @Test
    fun `a left turn really is ninety degrees, measured on the grid`() {
        // Cheap independent check that the table is not merely self-consistent: a quarter turn
        // rotates (dRow, dCol) to (-dCol, dRow) in a coordinate system whose rows increase downward.
        for (direction in Direction.entries) {
            assertEquals(-direction.dCol, direction.turnedLeft.dRow, "$direction")
            assertEquals(direction.dRow, direction.turnedLeft.dCol, "$direction")
        }
    }

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
