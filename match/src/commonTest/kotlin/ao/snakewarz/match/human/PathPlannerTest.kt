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

        assertTrue(planner.route(openBoard(grid), grid.cellAt(4, 4)))

        assertPath(grid, planner, 4 to 0, 4 to 1, 4 to 2, 4 to 3, 4 to 4)
        assertEquals(4, planner.moveCount)
        for (i in 0 until planner.moveCount) {
            assertEquals(Direction.EAST.ordinal, planner.directions[i], "move $i")
        }
    }

    @Test
    fun `a shortest route is a staircase approximating the straight line`() {
        val grid = Grid(9, 9)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(0, 0))

        assertTrue(planner.route(openBoard(grid), grid.cellAt(3, 5)))

        assertEquals(8, planner.moveCount, "three rows and five columns is eight moves")
        assertEquals(3, (0 until planner.moveCount).count { planner.directions[it] == Direction.SOUTH.ordinal })
        assertEquals(5, (0 until planner.moveCount).count { planner.directions[it] == Direction.EAST.ordinal })

        var straightRun = 1
        var longestRun = 1
        for (i in 1 until planner.moveCount) {
            straightRun = if (planner.directions[i] == planner.directions[i - 1]) straightRun + 1 else 1
            longestRun = maxOf(longestRun, straightRun)
        }
        assertTrue(longestRun <= 2, "a 3-by-5 diagonal should interleave its axes, not make an L")
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
        // A body blocks a route exactly as a wall does when it will not have retracted in time, and
        // both are read through Clearance rather than being cases the search knows about.
        val grid = Grid(5, 5)
        val board = Board(grid, intArrayOf(grid.cellAt(4, 3).index))
        board.apply(SnakeId(0), Direction.WEST)
        repeat(2) { board.apply(SnakeId(0), Direction.NORTH) }

        val snake = board.snake(SnakeId(0))
        assertEquals(grid.cellAt(2, 2), snake.head)
        assertEquals(grid.cellAt(3, 2), snake.tail)
        assertDetourAroundColumnTwo(grid, board)
    }

    @Test
    fun `a route walks through a body square that will have cleared by the time it is reached`() {
        val grid = Grid(7, 7)
        val board = corridorBoard(grid)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(3, 0))

        assertTrue(planner.route(board, grid.cellAt(3, 6)))

        assertEquals(6, planner.moveCount, "straight along row 3, through the gap in column 3")
        assertEquals(grid.cellAt(3, 3), planner.cellAt(3), "the head sitting there has three moves to leave")
    }

    @Test
    fun `a route goes round the same square when it would be reached one move too early`() {
        // Same board, same square, one step closer: clearance is 2, so arrival 2 is refused and the
        // only remaining way across the board is the gap at the top.
        val grid = Grid(7, 7)
        val board = corridorBoard(grid)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(3, 1))

        assertTrue(planner.route(board, grid.cellAt(3, 6)))

        assertEquals(11, planner.moveCount, "up, across the top gap and down again")
        for (i in 0 until planner.cellCount) {
            assertNotEquals(grid.cellAt(3, 3), planner.cellAt(i), "square $i sits on a head that has not moved yet")
        }
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
        assertTrue(planner.route(board, grid.cellAt(2, 4)))

        assertFalse(planner.route(board, grid.cellAt(0, 0)), "the corner is walled off from everything")

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

        assertFalse(planner.route(board, grid.cellAt(-1, 2)), "the padded ring is not a square to aim at")
        assertFalse(planner.route(board, Cell.NONE))
        assertEquals(0, planner.trace(board, Cell.NONE))

        assertEquals(1, planner.cellCount)
        assertTrue(planner.isEmpty)
    }

    @Test
    fun `the anchor itself is a route, and it exists`() {
        // What lets a press on your own head take hold and play nothing, which is how a freehand
        // drawing starts.
        val grid = Grid(5, 5)
        val board = openBoard(grid)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(2, 2))
        assertTrue(planner.route(board, grid.cellAt(2, 4)))

        assertTrue(planner.route(board, grid.cellAt(2, 2)))

        assertEquals(1, planner.cellCount)
        assertTrue(planner.isEmpty)
    }

    @Test
    fun `a route is what its anchor and target say, not what was drawn before it`() {
        val grid = Grid(9, 9)
        val board = openBoard(grid)

        val redrawn = PathPlanner(grid)
        redrawn.begin(grid.cellAt(4, 0))
        assertTrue(redrawn.route(board, grid.cellAt(4, 3)))
        assertTrue(redrawn.route(board, grid.cellAt(4, 7)))

        val straight = PathPlanner(grid)
        straight.begin(grid.cellAt(4, 0))
        assertTrue(straight.route(board, grid.cellAt(4, 7)))

        assertEquals(straight.cellCount, redrawn.cellCount)
        for (i in 0 until straight.cellCount) {
            assertEquals(straight.cellAt(i), redrawn.cellAt(i), "square $i")
        }
        for (i in 0 until straight.moveCount) {
            assertEquals(straight.directions[i], redrawn.directions[i], "move $i")
        }
    }

    @Test
    fun `a trace draws the staircase between two squares`() {
        val grid = Grid(9, 9)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(0, 0))

        assertEquals(8, planner.trace(openBoard(grid), grid.cellAt(3, 5)))

        val expected = intArrayOf(
            Direction.EAST.ordinal,
            Direction.EAST.ordinal,
            Direction.SOUTH.ordinal,
            Direction.EAST.ordinal,
            Direction.SOUTH.ordinal,
            Direction.EAST.ordinal,
            Direction.SOUTH.ordinal,
            Direction.EAST.ordinal,
        )
        assertEquals(expected.size, planner.moveCount)
        for (i in expected.indices) {
            assertEquals(expected[i], planner.directions[i], "move $i")
        }
        for (i in 1 until planner.cellCount) {
            val step = planner.cellAt(i).index - planner.cellAt(i - 1).index
            assertTrue(step == 1 || step == -1 || step == grid.stride || step == -grid.stride, "square $i")
        }
    }

    @Test
    fun `a trace truncates rather than detours`() {
        // No search, so a drag can neither detour nor jump: the line stops at the obstruction and the
        // far side of it is never drawn, however long the pointer stays over there.
        val grid = Grid(7, 7)
        val board = Board(
            grid,
            intArrayOf(grid.cellAt(6, 6).index),
            wallCells = intArrayOf(grid.cellAt(0, 3).index),
        )
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(0, 0))

        assertEquals(2, planner.trace(board, grid.cellAt(0, 6)))

        assertPath(grid, planner, 0 to 0, 0 to 1, 0 to 2)
        assertEquals(0, planner.trace(board, grid.cellAt(0, 6)), "the pointer is still past the wall")
        assertEquals(3, planner.cellCount)
    }

    @Test
    fun `a trace back over the path shortens it`() {
        val grid = Grid(7, 7)
        val board = openBoard(grid)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(3, 3))
        assertEquals(3, planner.trace(board, grid.cellAt(3, 6)))

        assertEquals(0, planner.trace(board, grid.cellAt(3, 4)), "dragging back appends nothing")

        assertPath(grid, planner, 3 to 3, 3 to 4)
        assertEquals(Direction.EAST.ordinal, planner.directions[0])
    }

    @Test
    fun `revalidate cuts the route at the square somebody else took`() {
        val grid = Grid(7, 7)
        // The opponent acts first, so the route is cut by a move the player has not answered yet.
        val board = Board(
            grid,
            intArrayOf(grid.cellAt(3, 0).index, grid.cellAt(2, 2).index),
            turnOrder = intArrayOf(1, 0),
        )
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(3, 0))
        assertTrue(planner.route(board, grid.cellAt(3, 4)))
        assertPath(grid, planner, 3 to 0, 3 to 1, 3 to 2, 3 to 3, 3 to 4)
        assertFalse(planner.revalidate(board), "nothing has moved since it was drawn")

        board.apply(SnakeId(1), Direction.SOUTH)

        assertTrue(planner.revalidate(board))
        assertPath(grid, planner, 3 to 0, 3 to 1)
        assertEquals(1, planner.moveCount)
        assertEquals(Direction.EAST.ordinal, planner.directions[0])
    }

    @Test
    fun `walking the path keeps its anchor under the snake`() {
        val grid = Grid(9, 9)
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(4, 0))
        assertTrue(planner.route(openBoard(grid), grid.cellAt(4, 3)))

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
        assertTrue(planner.route(board, grid.cellAt(3, 4)))

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
            assertTrue(planner.route(board, grid.cellAt(4, 8)))
        }

        assertEquals(9, planner.cellCount)
        assertEquals(8, planner.moveCount)
    }

    /** A board whose one snake sits in the far corner, out of the way of anything being routed. */
    private fun openBoard(grid: Grid): Board =
        Board(grid, intArrayOf(grid.cellAt(grid.rows - 1, grid.cols - 1).index))

    /**
     * A 7x7 split by a wall down column 3, open at row 0 and at row 3 — and a head parked on the row
     * 3 gap that takes two of its own moves to leave.
     */
    private fun corridorBoard(grid: Grid): Board {
        val board = Board(
            grid,
            intArrayOf(grid.cellAt(3, 4).index),
            wallCells = intArrayOf(
                grid.cellAt(1, 3).index,
                grid.cellAt(2, 3).index,
                grid.cellAt(4, 3).index,
                grid.cellAt(5, 3).index,
                grid.cellAt(6, 3).index,
            ),
        )
        board.apply(SnakeId(0), Direction.WEST)
        return board
    }

    /** A 5x5 whose column 2 cannot be crossed on row 2 in time, however it came to be, routes over the top. */
    private fun assertDetourAroundColumnTwo(grid: Grid, board: Board) {
        val planner = PathPlanner(grid)
        planner.begin(grid.cellAt(2, 1))

        assertTrue(planner.route(board, grid.cellAt(2, 3)))

        assertPath(grid, planner, 2 to 1, 1 to 1, 1 to 2, 1 to 3, 2 to 3)
    }

    private fun assertPath(grid: Grid, planner: PathPlanner, vararg squares: Pair<Int, Int>) {
        assertEquals(squares.size, planner.cellCount, "squares on the path")
        for (i in squares.indices) {
            assertEquals(grid.cellAt(squares[i].first, squares[i].second), planner.cellAt(i), "square $i")
        }
    }
}
