package ao.snakewarz.core.rules

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Undo is the load-bearing optimization of the whole engine — it is what lets a search explore
 * without allocating — and it is silent when it is wrong. So it is checked by playing real games and
 * demanding the board come back exactly, rather than by asserting on a handful of fields.
 */
class BoardUndoTest {
    @Test
    fun `unwinding a whole game restores every position bit for bit`() {
        val board = boardOf(8, 8, 0 to 0, 7 to 7, 0 to 7)
        val rng = SplitMix64(20260725L)
        val history = mutableListOf<String>()

        while (board.outcome == null) {
            history += board.signature()
            board.apply(board.toAct, chosenMove(board, rng))
        }

        assertTrue(history.size > 20, "the sample game should be long enough to be worth unwinding")

        while (board.undoDepth > 0) {
            board.undo()
            assertEquals(history.removeLast(), board.signature(), "at depth ${board.undoDepth}")
        }

        assertTrue(history.isEmpty())
        assertEquals(0, board.turnIndex)
    }

    @Test
    fun `a move can be taken back and remade at any point in a game`() {
        val board = boardOf(6, 9, 0 to 0, 5 to 8)
        val rng = SplitMix64(7L)

        while (board.outcome == null) {
            val actor = board.toAct
            val move = chosenMove(board, rng)

            val before = board.signature()
            board.apply(actor, move)
            val after = board.signature()

            board.undo()
            assertEquals(before, board.signature(), "undo did not restore the position")

            board.apply(actor, move)
            assertEquals(after, board.signature(), "replaying the same move did not reproduce it")
        }
    }

    @Test
    fun `incremental occupancy always equals occupancy rebuilt from the bodies`() {
        // Nothing is ever rebuilt at runtime — that is the point — so this is the only place the
        // incremental path is held against a from-scratch one.
        val board = boardOf(7, 7, 0 to 0, 6 to 6, 6 to 0, 0 to 6)
        val rng = SplitMix64(31337L)

        while (board.outcome == null) {
            val rebuilt = board.rebuiltOccupancy()
            assertEquals(rebuilt.hash, board.occupancyHash, "hash at turn ${board.turnIndex}")
            for (index in 0 until board.grid.cellCount) {
                val cell = Cell(index)
                if (board.grid.isPlayable(cell)) {
                    assertEquals(rebuilt.ownerOf(cell), board.ownerOf(cell), "owner of cell $index")
                }
            }
            board.apply(board.toAct, chosenMove(board, rng))
        }
    }

    @Test
    fun `distinct positions get distinct hashes, and identical ones agree`() {
        val board = boardOf(6, 6, 0 to 0, 5 to 5)
        val rng = SplitMix64(2468L)
        val byHash = mutableMapOf<Long, String>()

        while (board.outcome == null) {
            val signature = board.signature()
            val clash = byHash.put(board.hash, signature)
            if (clash != null) {
                assertEquals(clash, signature, "two different positions collided on hash ${board.hash}")
            }
            board.apply(board.toAct, chosenMove(board, rng))
        }

        assertTrue(byHash.size > 20)
    }

    @Test
    fun `the hash separates positions that differ only in whose turn it is`() {
        val ours = boardOf(5, 5, 0 to 0, 4 to 4)
        val theirs = boardOf(5, 5, 0 to 0, 4 to 4, turnOrder = intArrayOf(1, 0))

        assertEquals(ours.occupancyHash, theirs.occupancyHash, "the squares are identical")
        assertTrue(ours.hash != theirs.hash, "but it is a different position, and a search must see that")
    }

    @Test
    fun `undoing the deciding move puts the match back in play`() {
        val board = boardOf(1, 2, 0 to 0, 0 to 1)
        val opening = board.signature()

        board.apply(SnakeId(0), Direction.EAST)
        assertNotNull(board.outcome)

        board.undo()

        assertNull(board.outcome, "a finished match must be re-openable, or a search cannot back out of one")
        assertEquals(2, board.aliveCount)
        assertEquals(opening, board.signature())
    }

    @Test
    fun `undoing a turn-limit draw puts the match back in play`() {
        val board = boardOf(5, 5, 0 to 0, 4 to 4, rules = RulesConfig(maxTurns = 2))
        board.apply(SnakeId(0), Direction.SOUTH)
        board.apply(SnakeId(1), Direction.NORTH)
        assertEquals(MatchEnd.TURN_LIMIT, board.outcome?.end)

        board.undo()

        assertNull(board.outcome)
        assertEquals(1, board.turnIndex)
    }

    @Test
    fun `there is nothing to undo on a fresh board`() {
        val board = boardOf(5, 5, 0 to 0, 4 to 4)

        assertEquals(0, board.undoDepth)
        assertFailsWith<IllegalStateException> { board.undo() }
    }
}
