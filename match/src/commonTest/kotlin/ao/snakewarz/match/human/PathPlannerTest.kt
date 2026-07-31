package ao.snakewarz.match.human

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PathPlannerTest {
    @Test
    fun `a straight run over open board is the squares between, in order`() {
        val grid = Grid(9, 9)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(4, 0))

        assertTrue(planner.extend(openBoard(grid), grid.cellAt(4, 4)))

        assertPath(grid, planner, 4 to 0, 4 to 1, 4 to 2, 4 to 3, 4 to 4)
        assertEquals(4, planner.moveCount)
        for (i in 0 until planner.moveCount) {
            assertEquals(Direction.EAST.ordinal, planner.directions[i], "move $i")
        }
    }

    @Test
    fun `a route around a wall is found`() {
        // The reason a route is searched for rather than drawn straight: a map can make the line
        // between two squares impossible, and :core deliberately knows nothing about connectivity.
        val grid = Grid(5, 5)
        val board = Board(
            grid,
            intArrayOf(grid.cellAt(0, 0).index),
            wallCells = intArrayOf(
                grid.cellAt(2, 2).index,
                grid.cellAt(3, 2).index,
                grid.cellAt(4, 2).index,
            ),
        )

        assertDetourAroundColumnTwo(grid, board)
    }

    @Test
    fun `a route around a snake body is found`() {
        // A body blocks a route exactly as a wall does, and both are read through the one question
        // BoardView.isFree answers -- so neither is a case the planner has to know about.
        val grid = Grid(5, 5)
        val board = Board(grid, intArrayOf(grid.cellAt(0, 2).index))
        repeat(4) { board.apply(SnakeId(0), Direction.SOUTH) }

        assertEquals(3, board.snake(SnakeId(0)).length, "snakes grow at half speed, so four moves is three squares")
        assertDetourAroundColumnTwo(grid, board)
    }

    @Test
    fun `an unreachable target leaves the path exactly as it was`() {
        val grid = Grid(5, 5)
        val board = Board(
            grid,
            intArrayOf(grid.cellAt(4, 4).index),
            wallCells = intArrayOf(grid.cellAt(0, 1).index, grid.cellAt(1, 0).index),
        )
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(2, 2))
        assertTrue(planner.extend(board, grid.cellAt(2, 4)))

        assertFalse(planner.extend(board, grid.cellAt(0, 0)), "the corner is walled off from everything")

        assertPath(grid, planner, 2 to 2, 2 to 3, 2 to 4)
        assertEquals(Direction.EAST.ordinal, planner.directions[0])
        assertEquals(Direction.EAST.ordinal, planner.directions[1])
    }

    @Test
    fun `a target off the board is refused rather than fatal`() {
        // A drag runs past the edge constantly, and the pointer keeps reporting while it does.
        val grid = Grid(5, 5)
        val board = openBoard(grid)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(2, 2))

        assertFalse(planner.extend(board, grid.cellAt(-1, 2)), "the padded ring is not a square to aim at")
        assertFalse(planner.extend(board, Cell.NONE))

        assertEquals(1, planner.cellCount)
        assertTrue(planner.isEmpty)
    }

    @Test
    fun `a route never crosses the path already drawn`() {
        // A snake walking its own route would be walking into its own body, so the route has to go
        // the long way round rather than back along itself.
        val grid = Grid(7, 7)
        val board = openBoard(grid)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(3, 3))
        assertTrue(planner.extend(board, grid.cellAt(3, 6)))

        assertTrue(planner.extend(board, grid.cellAt(3, 0)))

        assertEquals(grid.cellAt(3, 0), planner.cellAt(planner.cellCount - 1))
        assertEquals(11, planner.moveCount, "three squares out, then eight back around the far side")
        for (i in 0 until planner.cellCount) {
            for (j in i + 1 until planner.cellCount) {
                assertNotEquals(planner.cellAt(i), planner.cellAt(j), "square $i is square $j all over again")
            }
        }
    }

    @Test
    fun `a drag that pauses and resumes draws what one that did not would have`() {
        val grid = Grid(9, 9)
        val board = openBoard(grid)

        val paused = PathPlanner(grid)
        paused.begin(grid.cellAt(4, 0))
        assertTrue(paused.extend(board, grid.cellAt(4, 3)))
        assertTrue(paused.extend(board, grid.cellAt(4, 7)))

        val straight = PathPlanner(grid)
        straight.begin(grid.cellAt(4, 0))
        assertTrue(straight.extend(board, grid.cellAt(4, 7)))

        assertEquals(straight.cellCount, paused.cellCount)
        for (i in 0 until straight.cellCount) {
            assertEquals(straight.cellAt(i), paused.cellAt(i), "square $i")
        }
        for (i in 0 until straight.moveCount) {
            assertEquals(straight.directions[i], paused.directions[i], "move $i")
        }
    }

    @Test
    fun `walking the path keeps its anchor under the snake`() {
        val grid = Grid(9, 9)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(4, 0))
        assertTrue(planner.extend(openBoard(grid), grid.cellAt(4, 3)))

        planner.advance()

        assertPath(grid, planner, 4 to 1, 4 to 2, 4 to 3)
        assertEquals(Direction.EAST.ordinal, planner.directions[0])

        planner.advance()
        planner.advance()
        assertEquals(1, planner.cellCount, "the anchor is the square the snake now stands on")
        assertTrue(planner.isEmpty, "and there is nothing left to play, which parks the match")

        planner.advance()
        assertEquals(0, planner.cellCount, "a step the plan did not spell out is the end of the plan")
    }

    @Test
    fun `the moves a path spells out walk the squares it drew`() {
        // The one thing keeping two representations of a route honest: the cells :ui paints and the
        // directions the queue plays are written by the same pass over the same search.
        val grid = Grid(7, 7)
        val board = Board(
            grid,
            intArrayOf(grid.cellAt(6, 6).index),
            wallCells = intArrayOf(grid.cellAt(3, 3).index),
        )
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(3, 2))
        assertTrue(planner.extend(board, grid.cellAt(3, 4)))

        val buffer = InputBuffer(InputBuffer.PATH_CAPACITY)
        buffer.replace(planner.directions, planner.moveCount)

        assertEquals(planner.moveCount, buffer.size)
        var walked = planner.cellAt(0)
        for (i in 1 until planner.cellCount) {
            val direction = assertNotNull(buffer.take(DirectionSet.ALL), "move $i")
            walked = grid.step(walked, direction)
            assertEquals(planner.cellAt(i), walked, "square $i")
        }
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `routing thousands of times allocates nothing that piles up`() {
        // A shape assertion rather than a measurement: every buffer is constructor-allocated off the
        // grid and the visited set is a generation stamp, so a run this long is only slow if
        // somebody has since put a collection on this path.
        val grid = Grid(9, 9)
        val board = openBoard(grid)
        val planner = PathPlanner(grid)

        repeat(5_000) {
            planner.begin(grid.cellAt(4, 0))
            assertTrue(planner.extend(board, grid.cellAt(4, 8)))
        }

        assertEquals(9, planner.cellCount)
        assertEquals(8, planner.moveCount)
    }

    /** A board whose one snake sits in the far corner, out of the way of anything being routed. */
    private fun openBoard(grid: Grid): Board =
        Board(grid, intArrayOf(grid.cellAt(grid.rows - 1, grid.cols - 1).index))

    /** Column 2 of a 5x5 is impassable from row 2 down, however it came to be, so a route goes over the top. */
    private fun assertDetourAroundColumnTwo(grid: Grid, board: Board) {
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(2, 1))

        assertTrue(planner.extend(board, grid.cellAt(2, 3)))

        assertPath(grid, planner, 2 to 1, 1 to 1, 1 to 2, 1 to 3, 2 to 3)
    }

    private fun assertPath(grid: Grid, planner: PathPlanner, vararg squares: Pair<Int, Int>) {
        assertEquals(squares.size, planner.cellCount, "squares on the path")
        for (i in squares.indices) {
            assertEquals(grid.cellAt(squares[i].first, squares[i].second), planner.cellAt(i), "square $i")
        }
    }
}
