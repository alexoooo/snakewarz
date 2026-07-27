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
    fun `the next playout returns to the live position, however far the line went`() {
        val board = boardOf(6, 6, 0 to 0, 5 to 5)
        val scratch = BoardScratch(board, Budget(1000))

        // Advance the real match first, so "live" is not the same thing as "the opening position".
        board.apply(SnakeId(0), Direction.SOUTH)
        board.apply(SnakeId(1), Direction.NORTH)

        val playout = scratch.playout()
        val rng = SplitMix64(5)
        repeat(5) { playout.advance(rng.pick(playout.board.legalMoves(playout.toAct)) ?: Direction.NORTH) }

        val next = scratch.playout()
        assertEquals(board.hash, next.board.hash)
        assertEquals(board.turnIndex, next.board.turnIndex)
        assertEquals(0, next.undoDepth, "a fresh line has no history to unwind")
    }

    @Test
    fun `an allowance buys evaluations, and asking for one is what spends it`() {
        // This is the whole point of the design: the charge lands on the playout rather than on the
        // move, so an allowance means the same amount of search whatever a bot does inside one.
        val board = boardOf(20, 20, 0 to 0, 19 to 19)
        val budget = Budget(8)
        val scratch = BoardScratch(board, budget)

        var evaluations = 0
        val rng = SplitMix64(11)
        while (true) {
            val playout = scratch.playout()
            if (playout.outcome != null) {
                break
            }
            evaluations++
            repeat(30) { playout.advance(rng.pick(playout.board.legalMoves(playout.toAct)) ?: Direction.NORTH) }
        }

        assertEquals(8, evaluations, "the loop stopped because the allowance ran out, not because a game did")
        assertEquals(BoardScratch.EXHAUSTED, scratch.playout().outcome)
        assertTrue(budget.exhausted)
    }

    @Test
    fun `a refused playout charges nothing, so an unaffordable cost cannot overdraw`() {
        val board = boardOf(8, 8, 0 to 0, 7 to 7)
        val budget = Budget(10)
        val scratch = BoardScratch(board, budget)

        assertNull(scratch.playout(6).outcome, "six of ten is affordable")
        assertEquals(6, budget.consumed)

        assertSame(BoardScratch.EXHAUSTED, scratch.playout(6).outcome, "six more is not")
        assertEquals(6, budget.consumed, "and a refusal is free")

        assertNull(scratch.playout(4).outcome, "what is left still buys what fits")
        assertEquals(10, budget.consumed)
    }

    @Test
    fun `a playout the allowance refuses is still handed back at the live position`() {
        // A bot that reads `board` before `outcome` must not see the previous iteration's line, which
        // is a position nowhere in the match.
        val board = boardOf(6, 6, 0 to 0, 5 to 5)
        val scratch = BoardScratch(board, Budget(1))

        val afforded = scratch.playout()
        repeat(4) { afforded.advance(Direction.SOUTH.takeIf { afforded.toAct.index == 0 } ?: Direction.NORTH) }

        val refused = scratch.playout()
        assertSame(BoardScratch.EXHAUSTED, refused.outcome)
        assertEquals(board.hash, refused.board.hash)
    }

    @Test
    fun `a rollout that has been paid for runs to a real finish`() {
        // Once the evaluation is bought it cannot be cut short by the allowance, so nothing has to
        // tell an exhausted line from a real one half way through crediting it.
        val board = boardOf(4, 4, 0 to 0, 3 to 3)
        val playout = BoardScratch(board, Budget(1)).playout()

        val rng = SplitMix64(3)
        while (playout.outcome == null) {
            playout.advance(rng.pick(playout.board.legalMoves(playout.toAct)) ?: Direction.NORTH)
        }

        val outcome = assertNotNull(playout.outcome)
        assertEquals(false, outcome === BoardScratch.EXHAUSTED, "the game ended, the allowance did not")
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
    fun `simulated moves are free, because the evaluation they belong to was already paid for`() {
        val board = boardOf(8, 8, 0 to 0, 7 to 7)
        val budget = Budget(50)
        val playout = BoardScratch(board, budget).playout()

        repeat(10) { playout.advance(Direction.SOUTH.takeIf { playout.toAct.index == 0 } ?: Direction.NORTH) }

        assertEquals(1, budget.consumed, "one evaluation, however many moves it ran")
        assertEquals(49, budget.remaining)
        assertNull(playout.outcome)
    }
}

private fun boardOf(rows: Int, cols: Int, vararg spawns: Pair<Int, Int>): Board {
    val grid = Grid(rows, cols)
    return Board(grid, IntArray(spawns.size) { grid.cellAt(spawns[it].first, spawns[it].second).index })
}
