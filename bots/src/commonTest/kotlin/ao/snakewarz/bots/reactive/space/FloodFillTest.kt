package ao.snakewarz.bots.reactive.space

import ao.snakewarz.bots.at
import ao.snakewarz.bots.boardOf
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FloodFillTest {
    @Test
    fun `an open board counts every square but the ones a snake is standing on`() {
        val board = boardOf(5, 5, 2 to 2)
        val fill = FloodFill(board.grid)

        // 25 squares, one of them the head. The seed is counted, the head is not reachable.
        assertEquals(24, fill.reachable(board, board.at(1, 2)))
    }

    @Test
    fun `a body across the board splits it, which is the whole reason bots ask`() {
        val board = boardOf(1, 5, 0 to 2)
        val fill = FloodFill(board.grid)

        assertEquals(2, fill.reachable(board, board.at(0, 1)), "west of the snake there are two squares")
        assertEquals(2, fill.reachable(board, board.at(0, 3)), "and two east of it")
    }

    @Test
    fun `the seed is counted even where a snake is standing`() {
        // Callers ask "what would I have if I moved there", and there is where the head would be.
        val board = boardOf(1, 5, 0 to 2)
        val fill = FloodFill(board.grid)

        assertEquals(5, fill.reachable(board, board.at(0, 2)), "the head's own square, plus both sides")
    }

    @Test
    fun `alsoFree opens exactly one square, which is the half-speed growth rule showing up`() {
        // A snake's tail only retracts on alternating turns, so a bot measuring its room on the
        // un-advanced board is wrong by precisely one square. This is the correction.
        val board = boardOf(1, 5, 0 to 2)
        val fill = FloodFill(board.grid)

        assertEquals(2, fill.reachable(board, board.at(0, 1)))
        assertEquals(
            5,
            fill.reachable(board, board.at(0, 1), alsoFree = board.at(0, 2)),
            "letting the fill through the retracting square reaches the far side",
        )
    }

    @Test
    fun `the limit stops at exactly the limit, not at the end of a search layer`() {
        // Legacy checked its cap between breadth-first layers, so it overshot by up to a whole
        // frontier and `ForkAi(6)` never meant six squares.
        val board = boardOf(10, 10, 0 to 0)
        val fill = FloodFill(board.grid)

        for (limit in 1..20) {
            assertEquals(limit, fill.reachable(board, board.at(5, 5), limit = limit), "limit $limit")
        }

        assertEquals(0, fill.reachable(board, board.at(5, 5), limit = 0))
    }

    @Test
    fun `a fill leaves nothing behind for the next one`() {
        // The generation stamp is what makes reuse free; a stale marker would silently shrink every
        // later count, and the bot would simply play worse without anything failing.
        val board = boardOf(1, 5, 0 to 2)
        val fill = FloodFill(board.grid)

        repeat(3) {
            assertEquals(2, fill.reachable(board, board.at(0, 1)))
            assertEquals(2, fill.reachable(board, board.at(0, 3)))
            assertEquals(5, fill.reachable(board, board.at(0, 1), alsoFree = board.at(0, 2)))
            assertEquals(1, fill.reachable(board, board.at(0, 1), limit = 1))
        }
    }

    @Test
    fun `a growing body shrinks the count it leaves behind`() {
        val board = boardOf(4, 4, 0 to 0)
        val fill = FloodFill(board.grid)
        val self = SnakeId(0)

        assertEquals(15, fill.reachable(board, board.at(0, 1)), "one square of sixteen is taken")

        board.apply(self, Direction.EAST)
        assertEquals(1, board.snake(self).length, "the opening move drags rather than grows")
        assertEquals(15, fill.reachable(board, board.at(0, 2)))

        board.apply(self, Direction.EAST)
        assertEquals(2, board.snake(self).length, "and the second grows")
        assertEquals(14, fill.reachable(board, board.at(0, 3)))
    }

    @Test
    fun `a fill must start on the board, and a negative limit is a mistake`() {
        val board = boardOf(4, 4, 0 to 0)
        val fill = FloodFill(board.grid)

        assertFailsWith<IllegalArgumentException> { fill.reachable(board, Cell.NONE) }
        assertFailsWith<IllegalArgumentException> { fill.reachable(board, board.at(0, 1), limit = -1) }
    }
}
