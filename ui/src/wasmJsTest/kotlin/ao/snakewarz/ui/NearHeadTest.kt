package ao.snakewarz.ui

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grace radius, which is the difference between drag-to-steer working with a finger and not.
 *
 * Worth a test of its own because the failure is silent and asymmetric: too tight and a press that
 * looked dead on simply does nothing, which reads as the game ignoring you; too loose and a press
 * meant for the far side of the board takes hold of a snake nobody was pointing at.
 */
class NearHeadTest {
    private val grid = Grid(rows = 8, cols = 8)
    private val head: Cell = grid.cellAt(4, 4)

    @Test
    fun `the head itself is the square this is all about`() {
        assertTrue(nearHead(grid, head, head))
    }

    @Test
    fun `every square touching the head counts, diagonals included`() {
        for (row in 3..5) {
            for (col in 3..5) {
                assertTrue(nearHead(grid, grid.cellAt(row, col), head), "($row, $col)")
            }
        }
    }

    @Test
    fun `two squares out is a press somewhere else on the board`() {
        assertFalse(nearHead(grid, grid.cellAt(4, 6), head), "two along")
        assertFalse(nearHead(grid, grid.cellAt(2, 4), head), "two up")
        assertFalse(nearHead(grid, grid.cellAt(6, 6), head), "two diagonally")
    }

    @Test
    fun `a press that is not on the board is near nothing`() {
        // What `BoardRenderer.cellAt` answers for a point outside the canvas, and what the padded
        // wall ring is: neither is a square a finger can be pointing at.
        assertFalse(nearHead(grid, Cell.NONE, head), "off the canvas")
        assertFalse(
            nearHead(grid, grid.step(grid.cellAt(0, 0), Direction.WEST), grid.cellAt(0, 0)),
            "in the padded wall ring, one step off the corner",
        )
    }

    @Test
    fun `a head in the corner keeps its grace on the sides it has`() {
        val corner = grid.cellAt(0, 0)

        assertTrue(nearHead(grid, grid.cellAt(1, 1), corner), "the one diagonal that is on the board")
        assertFalse(nearHead(grid, grid.cellAt(2, 0), corner), "and no further than anywhere else")
    }
}
