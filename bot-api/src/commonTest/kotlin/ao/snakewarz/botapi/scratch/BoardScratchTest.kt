package ao.snakewarz.botapi.scratch

import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.MoveOutcome
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoardScratchTest {
    @Test
    fun `a playout leaves the live board untouched`() {
        val board = boardOf(5, 5, 0 to 0, 4 to 4)
        val before = board.hash
        val scratch = BoardScratch(board, Budget(100))

        val playout = scratch.playout()
        playout.advance(Direction.SOUTH)
        playout.advance(Direction.NORTH)

        assertEquals(before, board.hash, "a bot thinking must not move the real match")
        assertEquals(0, board.turnIndex)
    }

    @Test
    fun `there is one playout, handed back reset`() {
        val board = boardOf(5, 5, 0 to 0, 4 to 4)
        val scratch = BoardScratch(board, Budget(100))

        val first = scratch.playout()
        first.advance(Direction.SOUTH)
        assertEquals(1, first.board.turnIndex)

        val second = scratch.playout()
        assertSame(first, second, "a pool with no release call cannot be made safe, so there is none")
        assertEquals(0, second.board.turnIndex, "and it comes back at the live position")
    }

    @Test
    fun `undo restores the position bit for bit, hash included`() {
        val board = boardOf(6, 6, 0 to 0, 5 to 5)
        val playout = BoardScratch(board, Budget(1000)).playout()

        val hashes = mutableListOf(playout.board.hash)
        val rng = SplitMix64(7)
        repeat(20) {
            val legal = playout.board.legalMoves(playout.toAct)
            playout.advance(rng.pick(legal) ?: Direction.NORTH)
            hashes += playout.board.hash
        }

        while (playout.undoDepth > 0) {
            hashes.removeAt(hashes.size - 1)
            playout.undo()
            assertEquals(hashes.last(), playout.board.hash, "unwinding at depth ${playout.undoDepth}")
        }
    }

    @Test
    fun `reset returns to the live position, however far the line went`() {
        val board = boardOf(6, 6, 0 to 0, 5 to 5)
        val scratch = BoardScratch(board, Budget(1000))

        // Advance the real match first, so "live" is not the same thing as "the opening position".
        board.apply(SnakeId(0), Direction.SOUTH)
        board.apply(SnakeId(1), Direction.NORTH)

        val playout = scratch.playout()
        val rng = SplitMix64(5)
        repeat(5) { playout.advance(rng.pick(playout.board.legalMoves(playout.toAct)) ?: Direction.NORTH) }
        playout.reset()

        assertEquals(board.hash, playout.board.hash)
        assertEquals(board.turnIndex, playout.board.turnIndex)
        assertEquals(0, playout.undoDepth, "a reset line has no history to unwind")
    }

    @Test
    fun `an exhausted budget ends the playout, so a rollout loop terminates on its own`() {
        // This is the whole point of the design: the loop condition *is* the budget check, so
        // enforcement is structural rather than a promise every bot author has to keep.
        val board = boardOf(20, 20, 0 to 0, 19 to 19)
        val budget = Budget(8)
        val playout = BoardScratch(board, budget).playout()

        var steps = 0
        val rng = SplitMix64(11)
        while (playout.outcome == null) {
            playout.advance(rng.pick(playout.board.legalMoves(playout.toAct)) ?: Direction.NORTH)
            steps++
        }

        assertEquals(8, steps, "the loop stopped because the allowance ran out, not because the game ended")
        assertEquals(BoardScratch.EXHAUSTED, playout.outcome)
        assertTrue(budget.exhausted)
    }

    @Test
    fun `a rollout to a real finish reports the real outcome`() {
        val board = boardOf(4, 4, 0 to 0, 3 to 3)
        val playout = BoardScratch(board, Budget(10_000)).playout()

        val rng = SplitMix64(3)
        while (playout.outcome == null) {
            playout.advance(rng.pick(playout.board.legalMoves(playout.toAct)) ?: Direction.NORTH)
        }

        val outcome = assertNotNull(playout.outcome)
        assertEquals(false, outcome === BoardScratch.EXHAUSTED, "a 4x4 game finishes long before 10,000 moves")
    }

    @Test
    fun `playing on after the playout is over is a mistake, not a silent no-op`() {
        val board = boardOf(1, 2, 0 to 0, 0 to 1)
        val playout = BoardScratch(board, Budget(100)).playout()

        assertEquals(MoveOutcome.TRAPPED, playout.advance(Direction.EAST))
        assertNotNull(playout.outcome)
        assertFailsWith<IllegalStateException> { playout.advance(Direction.WEST) }
    }

    @Test
    fun `the budget is shared with the turn, so simulation and search draw on one allowance`() {
        val board = boardOf(8, 8, 0 to 0, 7 to 7)
        val budget = Budget(50)
        val playout = BoardScratch(board, budget).playout()

        repeat(10) { playout.advance(Direction.SOUTH.takeIf { playout.toAct.index == 0 } ?: Direction.NORTH) }

        assertEquals(10, budget.consumed)
        assertEquals(40, budget.remaining)
        assertNull(playout.outcome)
    }
}

private fun boardOf(rows: Int, cols: Int, vararg spawns: Pair<Int, Int>): Board {
    val grid = Grid(rows, cols)
    return Board(grid, IntArray(spawns.size) { grid.cellAt(spawns[it].first, spawns[it].second).index })
}
