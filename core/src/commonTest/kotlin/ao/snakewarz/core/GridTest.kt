package ao.snakewarz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GridTest {
    @Test
    fun `cell addressing round trips for every playable square`() {
        val grid = Grid(rows = 7, cols = 11)

        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val cell = grid.cellAt(row, col)
                assertEquals(row, grid.rowOf(cell), "row of $cell")
                assertEquals(col, grid.colOf(cell), "col of $cell")
            }
        }
    }

    @Test
    fun `every playable square maps to a distinct index inside the backing array`() {
        val grid = Grid(rows = 7, cols = 11)

        val indices = buildSet {
            for (row in 0 until grid.rows) {
                for (col in 0 until grid.cols) {
                    add(grid.cellAt(row, col).index)
                }
            }
        }

        assertEquals(grid.playableCount, indices.size, "distinct indices")
        assertTrue(indices.all { it in 0 until grid.cellCount }, "all indices within the backing array")
    }

    @Test
    fun `stepping moves exactly one square in the expected direction`() {
        val grid = Grid(rows = 5, cols = 5)
        val centre = grid.cellAt(2, 2)

        for (direction in Direction.entries) {
            val next = grid.step(centre, direction)
            assertEquals(2 + direction.dRow, grid.rowOf(next), "row after $direction")
            assertEquals(2 + direction.dCol, grid.colOf(next), "col after $direction")
        }
    }

    @Test
    fun `stepping off any edge lands in the wall ring rather than wrapping`() {
        val grid = Grid(rows = 4, cols = 6)

        // This is the whole point of the padded layout: no bounds check is needed anywhere,
        // because off-board and occupied are the same array read. Wrapping would be a silent
        // gameplay bug, so assert it explicitly on all four edges.
        for (col in 0 until grid.cols) {
            assertFalse(grid.isPlayable(grid.step(grid.cellAt(0, col), Direction.NORTH)), "north edge at col $col")
            assertFalse(
                grid.isPlayable(grid.step(grid.cellAt(grid.rows - 1, col), Direction.SOUTH)),
                "south edge at col $col",
            )
        }
        for (row in 0 until grid.rows) {
            assertFalse(grid.isPlayable(grid.step(grid.cellAt(row, 0), Direction.WEST)), "west edge at row $row")
            assertFalse(
                grid.isPlayable(grid.step(grid.cellAt(row, grid.cols - 1), Direction.EAST)),
                "east edge at row $row",
            )
        }
    }

    @Test
    fun `playable squares are playable and the wall ring is not`() {
        val grid = Grid(rows = 3, cols = 4)

        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                assertTrue(grid.isPlayable(grid.cellAt(row, col)), "($row, $col) should be playable")
            }
        }

        val playable = (0 until grid.cellCount).count { grid.isPlayable(Cell(it)) }
        assertEquals(grid.playableCount, playable, "exactly rows*cols squares are playable")
    }

    @Test
    fun `a board whose padded size would overflow an Int is refused`() {
        // Left unchecked, cellCount wraps negative: every ceiling downstream then passes it and the
        // allocation it was guarding fails with a NegativeArraySizeException instead.
        assertFailsWith<IllegalArgumentException> { Grid(rows = 50_000, cols = 50_000) }
        assertFailsWith<IllegalArgumentException> { Grid(rows = 1, cols = Int.MAX_VALUE) }
    }

    @Test
    fun `stepping east then west returns to the original square`() {
        val grid = Grid(rows = 6, cols = 6)
        val start = grid.cellAt(3, 3)

        for (direction in Direction.entries) {
            val there = grid.step(start, direction)
            val back = grid.step(there, direction.opposite)
            assertEquals(start, back, "$direction then ${direction.opposite}")
        }
    }
}
