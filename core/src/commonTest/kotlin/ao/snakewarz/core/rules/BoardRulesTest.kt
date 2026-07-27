package ao.snakewarz.core.rules

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardRulesTest {
    @Test
    fun `body length follows the half-speed growth cadence`() {
        // The golden test. Traced by hand from SnakeImpl.advance, which flips willGrow on every call
        // starting from false — so the tail retracts only on alternating turns and lengths run
        // 1, 1, 2, 2, 3, 3, 4. A rewrite that grows on every move looks right and plays differently.
        val board = boardOf(1, 12, 0 to 0)
        val snake = board.snake(SnakeId(0))
        val lengths = mutableListOf(snake.length)

        repeat(6) {
            assertEquals(MoveOutcome.MOVED, board.apply(SnakeId(0), Direction.EAST))
            lengths += snake.length
        }

        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4), lengths)
        assertEquals(listOf(0 to 3, 0 to 4, 0 to 5, 0 to 6), snake.bodyIn(board.grid))
    }

    @Test
    fun `growEveryNthMove of one gives classic Tron, where the trail is permanent`() {
        val board = boardOf(1, 12, 0 to 0, rules = RulesConfig(growEveryNthMove = 1))
        val snake = board.snake(SnakeId(0))
        val lengths = mutableListOf(snake.length)

        repeat(6) {
            board.apply(SnakeId(0), Direction.EAST)
            lengths += snake.length
        }

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), lengths)
        assertEquals(0 to 0, snake.bodyIn(board.grid).first(), "the tail never moves")
    }

    @Test
    fun `growsOnNextMove tells the truth about the next move`() {
        val board = boardOf(1, 12, 0 to 0)
        val snake = board.snake(SnakeId(0))

        val predicted = mutableListOf<Boolean>()
        val actual = mutableListOf<Boolean>()
        repeat(6) {
            predicted += snake.growsOnNextMove
            val before = snake.length
            board.apply(SnakeId(0), Direction.EAST)
            actual += snake.length > before
        }

        assertEquals(actual, predicted)
        assertEquals(listOf(false, true, false, true, false, true), predicted)
    }

    @Test
    fun `legal moves exclude walls and every body, including your own`() {
        val board = boardOf(3, 3, 0 to 0, 2 to 2)

        assertEquals(
            DirectionSet.of(Direction.SOUTH, Direction.EAST),
            board.legalMoves(SnakeId(0)),
            "a corner is walled on two sides",
        )

        board.apply(SnakeId(0), Direction.EAST)
        board.apply(SnakeId(1), Direction.NORTH)
        board.apply(SnakeId(0), Direction.SOUTH)

        // Snake 0 is now at (1, 1) with its neck at (0, 1); snake 1 sits at (1, 2).
        assertEquals(
            DirectionSet.of(Direction.SOUTH, Direction.WEST),
            board.legalMoves(SnakeId(0)),
            "the neck and the opponent block the rest",
        )
    }

    @Test
    fun `a snake with somewhere to go that dies anyway is a suicide`() {
        val board = boardOf(3, 3, 0 to 0, 0 to 1)

        assertEquals(DirectionSet.of(Direction.SOUTH), board.legalMoves(SnakeId(0)))
        assertEquals(MoveOutcome.SUICIDE, board.apply(SnakeId(0), Direction.EAST))
        assertEquals(EliminationReason.SUICIDE, board.snake(SnakeId(0)).eliminationReason)
    }

    @Test
    fun `a snake with nowhere to go is trapped rather than blamed`() {
        val board = boardOf(1, 2, 0 to 0, 0 to 1)

        assertEquals(DirectionSet.EMPTY, board.legalMoves(SnakeId(0)))
        assertEquals(MoveOutcome.TRAPPED, board.apply(SnakeId(0), Direction.EAST))
        assertEquals(EliminationReason.TRAPPED, board.snake(SnakeId(0)).eliminationReason)
    }

    @Test
    fun `reversing is legal at length one and fatal once there is a neck`() {
        // There is deliberately no "cannot reverse" rule. Reversal is fatal only because the neck is
        // in the way, so at length one it simply works — emergent, intended, and easy to break.
        val board = boardOf(5, 5, 2 to 2)
        val id = SnakeId(0)

        assertEquals(MoveOutcome.MOVED, board.apply(id, Direction.NORTH))
        assertEquals(1, board.snake(id).length, "the opening move drags rather than grows")
        assertEquals(MoveOutcome.MOVED, board.apply(id, Direction.SOUTH), "no neck, so no obstacle")
        assertEquals(listOf(1 to 2, 2 to 2), board.snake(id).bodyIn(board.grid))

        board.apply(id, Direction.SOUTH)
        board.apply(id, Direction.SOUTH)
        assertEquals(3, board.snake(id).length)
        assertEquals(listOf(2 to 2, 3 to 2, 4 to 2), board.snake(id).bodyIn(board.grid))

        assertEquals(MoveOutcome.SUICIDE, board.apply(id, Direction.NORTH), "the neck is now in the way")
    }

    @Test
    fun `a snake may not move into the square its own tail is about to leave`() {
        // Legality is tested against the board *before* the retraction, which is what the legacy
        // engine did. The alternative — letting the tail clear first — is a different game.
        val board = boardOf(5, 5, 0 to 0)
        val id = SnakeId(0)

        for (direction in listOf(
            Direction.EAST,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST,
            Direction.NORTH,
            Direction.EAST,
        )) {
            assertEquals(MoveOutcome.MOVED, board.apply(id, direction), "curling with $direction")
        }

        val snake = board.snake(id)
        assertEquals(listOf(1 to 2, 1 to 1, 0 to 1, 0 to 2), snake.bodyIn(board.grid), "curled into a 2x2 block")
        assertFalse(snake.growsOnNextMove, "the next move would retract the tail")

        assertEquals(MoveOutcome.SUICIDE, board.apply(id, Direction.SOUTH), "the tail square is still occupied")
    }

    @Test
    fun `the last snake standing wins immediately, even trapped`() {
        // A deliberate change from the legacy engine, which asked the survivor for one more move and
        // called the match a draw if that move was also fatal.
        val board = boardOf(1, 2, 0 to 0, 0 to 1)

        board.apply(SnakeId(0), Direction.EAST)

        assertEquals(DirectionSet.EMPTY, board.legalMoves(SnakeId(1)), "the winner has nowhere to go either")
        assertEquals(MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING), board.outcome)
        assertEquals(1, board.aliveCount)
    }

    @Test
    fun `a solo snake that dies ends the match with no winner`() {
        val board = boardOf(1, 1, 0 to 0)

        assertNull(board.outcome, "a solo match is not over before it starts")
        assertEquals(MoveOutcome.TRAPPED, board.apply(SnakeId(0), Direction.EAST))
        assertEquals(MatchOutcome(SnakeId.NONE, MatchEnd.ALL_ELIMINATED), board.outcome)
        assertTrue(board.outcome!!.isDraw)
    }

    @Test
    fun `reaching the turn limit is a draw`() {
        val board = boardOf(5, 5, 0 to 0, 4 to 4, rules = RulesConfig(maxTurns = 4))

        board.apply(SnakeId(0), Direction.SOUTH)
        board.apply(SnakeId(1), Direction.NORTH)
        board.apply(SnakeId(0), Direction.SOUTH)
        assertNull(board.outcome)

        board.apply(SnakeId(1), Direction.NORTH)

        assertEquals(MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT), board.outcome)
        assertEquals(4, board.turnIndex)
    }

    @Test
    fun `a dead snake leaves its body behind and its turn is skipped`() {
        val board = boardOf(3, 3, 0 to 0, 1 to 1, 2 to 2)

        board.apply(SnakeId(0), Direction.SOUTH)
        assertEquals(SnakeId(1), board.toAct)

        assertEquals(MoveOutcome.SUICIDE, board.apply(SnakeId(1), Direction.WEST))
        assertEquals(2, board.aliveCount)
        assertEquals(
            SnakeId(1),
            board.ownerOf(board.grid.cellAt(1, 1)),
            "a corpse is still an obstacle — that is what makes three-way matches interesting",
        )

        assertEquals(SnakeId(2), board.toAct)
        board.apply(SnakeId(2), Direction.WEST)
        assertEquals(SnakeId(0), board.toAct, "the dead slot is skipped, not nulled out")

        assertEquals(MoveOutcome.SUICIDE, board.apply(SnakeId(0), Direction.EAST), "killed by a corpse")
        assertEquals(MatchOutcome(SnakeId(2), MatchEnd.LAST_SNAKE_STANDING), board.outcome)
    }

    @Test
    fun `turn order is the given permutation, not the slot order`() {
        val board = boardOf(5, 5, 0 to 0, 0 to 2, 0 to 4, turnOrder = intArrayOf(2, 0, 1))

        val acted = mutableListOf<Int>()
        repeat(6) {
            acted += board.toAct.index
            board.apply(board.toAct, Direction.SOUTH)
        }

        assertEquals(listOf(2, 0, 1, 2, 0, 1), acted)
    }

    @Test
    fun `resigning removes a snake without moving it`() {
        val board = boardOf(5, 5, 0 to 0, 4 to 4)
        val head = board.snake(SnakeId(0)).head

        board.eliminate(SnakeId(0), EliminationReason.RESIGNED)

        assertEquals(EliminationReason.RESIGNED, board.snake(SnakeId(0)).eliminationReason)
        assertEquals(head, board.snake(SnakeId(0)).head, "a resignation does not move the snake")
        assertEquals(SnakeId(0), board.ownerOf(head), "nor does it clear its body")
        assertEquals(MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING), board.outcome)
    }
}
