package ao.snakewarz.core.rules

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardStateTest {
    @Test
    fun `a snapshot is frozen and a live view is not`() {
        val board = boardOf(5, 5, 0 to 0, 4 to 4)
        board.apply(SnakeId(0), Direction.SOUTH)

        val snapshot = board.snapshot()
        val live = board.snake(SnakeId(0))
        val frozenBody = snapshot.snake(SnakeId(0)).bodyIn(board.grid)

        board.apply(SnakeId(1), Direction.NORTH)
        board.apply(SnakeId(0), Direction.SOUTH)

        assertEquals(frozenBody, snapshot.snake(SnakeId(0)).bodyIn(board.grid), "the snapshot must not move")
        assertEquals(1, snapshot.turnIndex)
        assertEquals(3, board.turnIndex)
        assertEquals(listOf(1 to 0, 2 to 0), live.bodyIn(board.grid), "the live view tracks the arena")
    }

    @Test
    fun `a snapshot carries the metadata a stats panel needs`() {
        val board = boardOf(3, 3, 0 to 0, 0 to 1)
        board.apply(SnakeId(0), Direction.EAST)

        val snapshot = board.snapshot()
        val loser = snapshot.snake(SnakeId(0))

        assertEquals(EliminationReason.SUICIDE, loser.eliminationReason)
        assertFalse(loser.alive)
        assertEquals(0, loser.movesMade, "a fatal move is not a move made")
        assertNull(loser.lastDirection)
        assertEquals(1, snapshot.aliveCount)
        assertEquals(MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING), snapshot.outcome)
    }

    @Test
    fun `a snapshot renders the board as ascii`() {
        val board = boardOf(3, 3, 0 to 0, 2 to 2)
        board.apply(SnakeId(0), Direction.EAST)
        board.apply(SnakeId(1), Direction.NORTH)
        board.apply(SnakeId(0), Direction.SOUTH)

        // 0 is snake 0's body; A and B are the two heads.
        val rendered = board.snapshot().toString().lines()
        assertEquals(".0.", rendered[1])
        assertEquals(".AB", rendered[2])
        assertEquals("...", rendered[3])
    }

    @Test
    fun `copyFrom reproduces a position and then goes its own way`() {
        val source = boardOf(7, 7, 0 to 0, 6 to 6)
        val rng = SplitMix64(555L)
        repeat(15) { if (source.outcome == null) source.apply(source.toAct, chosenMove(source, rng)) }

        val copy = boardOf(7, 7, 0 to 0, 6 to 6)
        copy.copyFrom(source)

        assertEquals(source.signature(), copy.signature())
        assertEquals(0, copy.undoDepth, "a search arena has no interest in its source's history")

        val sourceBefore = source.signature()
        if (copy.outcome == null) {
            copy.apply(copy.toAct, chosenMove(copy, rng))
        }
        assertEquals(sourceBefore, source.signature(), "the copy must share no state with its source")
    }

    @Test
    fun `copyFrom refuses boards of a different shape`() {
        val source = boardOf(7, 7, 0 to 0, 6 to 6)

        assertFailsWith<IllegalArgumentException> { boardOf(6, 6, 0 to 0, 5 to 5).copyFrom(source) }
        assertFailsWith<IllegalArgumentException> { boardOf(7, 7, 0 to 0).copyFrom(source) }
        assertFailsWith<IllegalArgumentException> {
            boardOf(7, 7, 0 to 0, 6 to 6, rules = RulesConfig(growEveryNthMove = 1)).copyFrom(source)
        }
    }

    @Test
    fun `reset returns to the opening position`() {
        val board = boardOf(7, 7, 0 to 0, 6 to 6, 0 to 6)
        val opening = board.signature()
        val rng = SplitMix64(909L)
        repeat(20) { if (board.outcome == null) board.apply(board.toAct, chosenMove(board, rng)) }

        board.reset()

        assertEquals(opening, board.signature())
        assertEquals(0, board.undoDepth)
    }

    @Test
    fun `only the snake to act may move, and only while the match is running`() {
        val board = boardOf(1, 2, 0 to 0, 0 to 1)

        assertFailsWith<IllegalArgumentException> { board.apply(SnakeId(1), Direction.WEST) }

        board.apply(SnakeId(0), Direction.EAST)
        assertTrue(board.outcome != null)

        assertFailsWith<IllegalStateException> { board.apply(SnakeId(1), Direction.WEST) }
        assertFailsWith<IllegalStateException> { board.eliminate(SnakeId(1), EliminationReason.RESIGNED) }
    }

    @Test
    fun `collisions are not routed through eliminate`() {
        // Keeping the two apart is what stops a driver quietly recording a bug as a suicide.
        val board = boardOf(5, 5, 0 to 0, 4 to 4)

        assertFailsWith<IllegalArgumentException> { board.eliminate(SnakeId(0), EliminationReason.SUICIDE) }
        assertFailsWith<IllegalArgumentException> { board.eliminate(SnakeId(0), EliminationReason.TRAPPED) }
        board.eliminate(SnakeId(0), EliminationReason.FORFEIT)
    }

    @Test
    fun `a board refuses a setup it cannot play`() {
        val grid = Grid(5, 5)

        assertFailsWith<IllegalArgumentException>("no snakes") {
            Board(grid, IntArray(0))
        }
        assertFailsWith<IllegalArgumentException>("two snakes on one square") {
            Board(grid, intArrayOf(grid.cellAt(1, 1).index, grid.cellAt(1, 1).index))
        }
        assertFailsWith<IllegalArgumentException>("spawned in the wall ring") {
            Board(grid, intArrayOf(0))
        }
        assertFailsWith<IllegalArgumentException>("turn order is not a permutation") {
            Board(grid, intArrayOf(grid.cellAt(0, 0).index, grid.cellAt(4, 4).index), turnOrder = intArrayOf(0, 0))
        }
        assertFailsWith<IllegalArgumentException>("turn order is the wrong length") {
            Board(grid, intArrayOf(grid.cellAt(0, 0).index), turnOrder = intArrayOf(0, 1))
        }
    }

    @Test
    fun `a board past the journal ceiling is refused before it allocates`() {
        // Sized so the distinction is the whole test: two billion squares clears the journal ceiling
        // by two orders of magnitude, so allocating for it first would raise an OutOfMemoryError and
        // this assertion would fail. It passes only while the require runs ahead of the arrays.
        val grid = Grid(46_000, 46_000)

        assertFailsWith<IllegalArgumentException> {
            Board(grid, intArrayOf(grid.cellAt(0, 0).index))
        }
    }

    @Test
    fun `the rules reject configurations that could not terminate`() {
        assertFailsWith<IllegalArgumentException> { RulesConfig(growEveryNthMove = 0) }
        assertFailsWith<IllegalArgumentException> { RulesConfig(maxTurns = 0) }
    }
}
