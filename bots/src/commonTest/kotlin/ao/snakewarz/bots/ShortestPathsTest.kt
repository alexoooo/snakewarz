package ao.snakewarz.bots

import ao.snakewarz.core.Board
import ao.snakewarz.core.Cell
import ao.snakewarz.core.Direction
import ao.snakewarz.core.RulesConfig
import ao.snakewarz.core.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShortestPathsTest {
    @Test
    fun `on an open board the distances are Manhattan`() {
        val board = boardOf(5, 5, 0 to 0)
        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(0, 0))

        assertEquals(0, paths.distanceTo(board.at(0, 0)))
        assertEquals(4, paths.distanceTo(board.at(0, 4)))
        assertEquals(4, paths.distanceTo(board.at(4, 0)))
        assertEquals(8, paths.distanceTo(board.at(4, 4)))
    }

    @Test
    fun `the first step is the direction the walk left by`() {
        val board = boardOf(5, 5, 0 to 0)
        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(0, 0))

        assertEquals(Direction.EAST, paths.firstStepTo(board.at(0, 4)))
        assertEquals(Direction.SOUTH, paths.firstStepTo(board.at(4, 0)))
        assertNull(paths.firstStepTo(board.at(0, 0)), "there is no step to take at the origin")
    }

    @Test
    fun `every first step actually shortens the walk`() {
        // The property that matters to a chaser, asserted over the whole board rather than at a
        // couple of hand-picked squares: the named direction is one step from the origin, and it
        // leaves one less ground to cover. The snake sits in the corner the sweep starts from, so
        // every other square is free and its distance is exactly Manhattan.
        val board = boardOf(6, 7, 0 to 0)
        val paths = ShortestPaths(board.grid)
        val origin = board.at(0, 0)
        paths.scanFrom(board, origin)

        for (row in 0 until board.grid.rows) {
            for (col in 0 until board.grid.cols) {
                val target = board.at(row, col)
                assertEquals(row + col, paths.distanceTo(target), "distance to ($row, $col)")

                val step = paths.firstStepTo(target) ?: continue
                val landed = board.grid.step(origin, step)

                assertEquals(1, paths.distanceTo(landed), "stepping $step toward ($row, $col) leaves the origin")
                assertEquals(
                    row + col - 1,
                    manhattan(board, landed, target),
                    "stepping $step toward ($row, $col) closes the gap",
                )
            }
        }
    }

    private fun manhattan(board: Board, from: Cell, to: Cell): Int {
        val grid = board.grid
        val rows = grid.rowOf(from) - grid.rowOf(to)
        val cols = grid.colOf(from) - grid.colOf(to)
        return (if (rows < 0) -rows else rows) + (if (cols < 0) -cols else cols)
    }

    @Test
    fun `a body lengthens the walk, and a sealed region ends it`() {
        // Classic Tron rules so the body grows every move: column 1 becomes solid top to bottom and
        // the board is cut in two.
        val board = boardOf(3, 3, 0 to 1, rules = RulesConfig(growEveryNthMove = 1))
        repeat(2) { board.apply(SnakeId(0), Direction.SOUTH) }
        assertEquals(3, board.snake(SnakeId(0)).length)

        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(0, 0))

        assertEquals(2, paths.distanceTo(board.at(2, 0)), "the near side is walked normally")
        assertEquals(ShortestPaths.UNREACHABLE, paths.distanceTo(board.at(0, 2)), "the far side is sealed off")
        assertNull(paths.firstStepTo(board.at(0, 2)))
    }

    @Test
    fun `an occupied goal is answered by the square beside it`() {
        // The goal a chaser walks toward is an opponent's head, which is never walked *on*. Legacy
        // expressed that as a loop condition, which cost one search per opponent; here one sweep
        // answers for all of them.
        val board = boardOf(5, 5, 0 to 0, 0 to 4)
        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(0, 0))

        val theirHead = board.snake(SnakeId(1)).head
        assertEquals(ShortestPaths.UNREACHABLE, paths.distanceTo(theirHead), "you cannot stand on a head")
        assertEquals(4, paths.distanceBeside(theirHead), "but you can stand next to it in four steps")
        assertEquals(Direction.EAST, paths.firstStepBeside(theirHead))
    }

    @Test
    fun `beside an unreachable head is unreachable, not zero`() {
        // The legacy defect in one assertion: an empty path had size 0, which is smaller than every
        // real distance, so a walled-off opponent looked like the closest thing on the board.
        val board = boardOf(1, 5, 0 to 3, 0 to 0, 0 to 1)
        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(0, 3))

        val sealed = board.snake(SnakeId(1)).head
        assertEquals(ShortestPaths.UNREACHABLE, paths.distanceBeside(sealed))
        assertNull(paths.firstStepBeside(sealed))
    }

    @Test
    fun `standing beside the goal already names no step`() {
        val board = boardOf(1, 3, 0 to 0, 0 to 1)
        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(0, 0))

        val theirHead = board.snake(SnakeId(1)).head
        assertEquals(1, paths.distanceBeside(theirHead))
        assertNull(paths.firstStepBeside(theirHead), "the origin is already the square beside it")
    }

    @Test
    fun `one sweep does not colour the next`() {
        val board = boardOf(5, 5, 0 to 0)
        val paths = ShortestPaths(board.grid)

        repeat(3) {
            paths.scanFrom(board, board.at(0, 0))
            assertEquals(8, paths.distanceTo(board.at(4, 4)))

            paths.scanFrom(board, board.at(4, 4))
            assertEquals(0, paths.distanceTo(board.at(4, 4)))
            assertEquals(7, paths.distanceTo(board.at(0, 1)))
            assertEquals(8, paths.distanceBeside(board.at(0, 0)), "the far corner is where the snake is")
        }
    }

    @Test
    fun `asking before sweeping is a mistake, not a board of zeroes`() {
        val board = boardOf(4, 4, 0 to 0)
        val paths = ShortestPaths(board.grid)

        assertFailsWith<IllegalStateException> { paths.distanceTo(board.at(2, 2)) }
        assertFailsWith<IllegalArgumentException> { paths.scanFrom(board, Cell.NONE) }
    }
}
