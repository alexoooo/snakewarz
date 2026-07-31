package ao.snakewarz.match.human

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClearanceTest {
    @Test
    fun `every body square clears on the move the engine actually vacates it`() {
        // The oracle: predict first, then drive the same snake forward on a real Board and watch what
        // comes free. The only test here that would notice a +1 drifting into the arithmetic.
        val grid = Grid(9, 9)
        val board = Board(grid, intArrayOf(grid.cellAt(4, 0).index))
        val id = SnakeId(0)
        repeat(6) { board.apply(id, Direction.EAST) }

        val snake = board.snake(id)
        assertEquals(4, snake.length, "snakes grow at half speed, so six moves is four squares")

        val clearance = Clearance(grid)
        clearance.refresh(board)
        val squares = IntArray(snake.length) { snake.cellAt(it).index }
        val predicted = IntArray(squares.size) { predictedClearance(clearance, board, Cell(squares[it])) }
        val observed = IntArray(squares.size)

        // A walk that neither leaves the board nor doubles back, so every square below is vacated by
        // the tail retracting rather than by anything the walk itself did.
        val walk = arrayOf(
            Direction.EAST,
            Direction.EAST,
            Direction.SOUTH,
            Direction.SOUTH,
            Direction.SOUTH,
            Direction.SOUTH,
            Direction.WEST,
        )
        for (move in walk.indices) {
            board.apply(id, walk[move])
            for (i in squares.indices) {
                if (observed[i] == 0 && board.isFree(Cell(squares[i]))) {
                    observed[i] = move + 1
                }
            }
        }

        for (i in squares.indices) {
            assertTrue(observed[i] > 0, "square $i from the tail never came free within the walk")
            assertEquals(observed[i], predicted[i], "square $i from the tail")
        }
    }

    @Test
    fun `the tail is not enterable at arrival one although the next move retracts it`() {
        // Fact 4 reproduced rather than special-cased: Board.apply reads isFree(target) before
        // body.popTail(), so a snake may not enter the square its own tail is about to leave.
        val grid = Grid(5, 5)
        val board = Board(grid, intArrayOf(grid.cellAt(2, 0).index))
        val id = SnakeId(0)
        repeat(2) { board.apply(id, Direction.EAST) }

        val snake = board.snake(id)
        assertEquals(2, snake.length)
        assertFalse(snake.growsOnNextMove, "the very next move retracts the tail")

        val clearance = Clearance(grid)
        clearance.refresh(board)

        assertFalse(clearance.enterableAt(board, snake.tail, 1), "one move away is one move too early")
        assertTrue(clearance.enterableAt(board, snake.tail, 2))
    }

    @Test
    fun `a trail that never retracts never clears, and refresh still terminates`() {
        // (movesMade + m) % 1 is always 0, so a loop hunting for a retraction would never find one.
        val grid = Grid(5, 5)
        val board = Board(
            grid,
            intArrayOf(grid.cellAt(2, 0).index),
            rules = RulesConfig(growEveryNthMove = 1),
        )
        val id = SnakeId(0)
        repeat(3) { board.apply(id, Direction.EAST) }

        val snake = board.snake(id)
        assertEquals(4, snake.length, "classic Tron: the trail is permanent")

        val clearance = Clearance(grid)
        clearance.refresh(board)

        for (j in 0 until snake.length) {
            assertFalse(
                clearance.enterableAt(board, snake.cellAt(j), FAR_FUTURE),
                "square $j from the tail of a permanent trail",
            )
        }
    }

    @Test
    fun `a wall never clears`() {
        val grid = Grid(5, 5)
        val wall = grid.cellAt(2, 2)
        val board = Board(grid, intArrayOf(grid.cellAt(0, 0).index), wallCells = intArrayOf(wall.index))

        val clearance = Clearance(grid)
        clearance.refresh(board)

        assertFalse(clearance.enterableAt(board, wall, FAR_FUTURE))
    }

    @Test
    fun `a corpse never clears`() {
        // A dead snake stops retracting and its body freezes where it fell, which is the planner's
        // second stated optimism read from the other side.
        val grid = Grid(5, 5)
        val board = Board(grid, intArrayOf(grid.cellAt(0, 0).index, grid.cellAt(4, 4).index))
        board.apply(SnakeId(0), Direction.NORTH)

        val corpse = board.snake(SnakeId(0))
        assertFalse(corpse.alive, "north from row 0 walks into the border ring")

        val clearance = Clearance(grid)
        clearance.refresh(board)

        assertFalse(clearance.enterableAt(board, corpse.head, FAR_FUTURE))
    }

    /** The move count [Clearance] is predicting for [cell], read back through the one question it answers. */
    private fun predictedClearance(clearance: Clearance, board: Board, cell: Cell): Int {
        for (arrival in 1..FAR_FUTURE) {
            if (clearance.enterableAt(board, cell, arrival)) {
                return arrival - 1
            }
        }
        error("square ${cell.index} never clears within $FAR_FUTURE moves")
    }

    private companion object {
        /** Further ahead than any body on these boards could possibly clear. */
        const val FAR_FUTURE = 200
    }
}
