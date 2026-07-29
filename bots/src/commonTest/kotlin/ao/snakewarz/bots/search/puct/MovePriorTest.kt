package ao.snakewarz.bots.search.puct

import ao.snakewarz.bots.boardOf
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each reading of the prior actually sees, on shapes small enough to draw.
 *
 * The prior is not verifiable through the bot: `PuctBot` normalises it, feeds it to a tree, and the
 * move that comes out is a statement about a thousand iterations. So the readings are pinned here,
 * one shape per question — and the first test is the one the whole design rests on, that the shipped
 * defaults are the prior this bot has always played rather than a re-derivation of it.
 */
class MovePriorTest {
    @Test
    fun `at its defaults it is the liberty prior, arithmetic included`() {
        // Not "close to" and not "ranks the same". GoldenMoveStreamTest's puct hash is a hash of
        // this arithmetic, so anything short of the same Doubles is a golden failure waiting to be
        // mistaken for a codegen change.
        val board = boardOf(7, 7, 3 to 3)
        val prior = priorOf(board)

        val expected = DoubleArray(Direction.entries.size)
        val legal = board.legalMoves(SnakeId(0))
        var total = 0.0
        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val destination = board.grid.step(board.snake(SnakeId(0)).head, direction)
            var liberties = 0
            for (way in Direction.entries) {
                if (board.isFree(board.grid.step(destination, way))) {
                    liberties++
                }
            }
            val score = 1.0 + 0.5 * liberties
            expected[direction.ordinal] = score
            total += score
        }
        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            expected[ordinal] = expected[ordinal] / total
        }

        assertEquals(expected.toList(), prior.toList())
    }

    @Test
    fun `a prior is a distribution over the legal set and nothing else`() {
        val board = boardOf(9, 9, 4 to 4)

        for (temperature in doubleArrayOf(0.0, 0.05, 0.5, 4.0)) {
            val prior = priorOf(board, pinch = 0.4, wall = 0.3, tail = 0.2, temperature = temperature)
            val legal = board.legalMoves(SnakeId(0))

            var total = 0.0
            for (i in 0 until legal.size) {
                val share = prior[legal.nth(i).ordinal]
                assertTrue(share > 0.0, "at t=$temperature a legal move got $share, and would never be tried")
                total += share
            }
            assertTrue(abs(total - 1.0) <= 1e-12, "at t=$temperature the priors summed to $total")
        }
    }

    @Test
    fun `the pinch reading fires on a neck and not on open ground`() {
        // A wall across the middle with one gap in it, offset from the head so that the head's own
        // body is not what the reading is looking at. Stepping west lands on the square joining the
        // two rooms; stepping east stays in the northern one.
        //
        //   . . . . .
        //   . . H . .        head at (1, 2)
        //   # . # # #        the gap at (2, 1) is the only way south
        //   . . . . .
        //   . . . . .
        val board = boardOf(5, 5, 1 to 2, 2 to 0, 2 to 2, 2 to 3, 2 to 4, rules = TRON)
        val head = SnakeId(0)

        val plain = priorOf(board, mover = head)
        val pinched = priorOf(board, mover = head, pinch = 1.0)

        assertTrue(
            pinched[Direction.WEST.ordinal] < plain[Direction.WEST.ordinal],
            "the square above the gap was not marked down: ${pinched.toList()} against ${plain.toList()}",
        )
        assertTrue(
            pinched[Direction.EAST.ordinal] > plain[Direction.EAST.ordinal],
            "open ground did not gain share from the neck",
        )
    }

    @Test
    fun `the pinch reading does not reward a dead end for being uncut`() {
        // One free neighbour is one group and no free neighbour is no group at all -- which has to
        // read as zero rather than as minus one, or a move into a sealed square would come out
        // *ahead* of a move into the open. The head has two moves here, a pocket and clear ground,
        // and neither is a cut, so the penalty must change nothing whatever it is set to.
        //
        //   . . . . .
        //   . . # # .
        //   . . H . #        east from (2, 2) is a pocket with nothing beyond it
        //   . . # # .
        //   . . . . .
        val board = boardOf(5, 5, 2 to 2, 1 to 2, 1 to 3, 2 to 4, 3 to 2, 3 to 3, rules = TRON)
        val head = SnakeId(0)

        assertEquals(
            priorOf(board, mover = head).toList(),
            priorOf(board, mover = head, pinch = 1.0).toList(),
            "a shape with one way out, or none, is not a cut",
        )
    }

    @Test
    fun `the tail reading prefers the step that closes on the snake's own tail`() {
        // A snake that has turned a corner, so its tail is reachable by going round rather than
        // straight back through its own neck -- which is the only shape where the reading has
        // anything to say.
        //
        //   . . . . .
        //   . . o H .        head at (1, 3), and west closes on the tail
        //   . . . o .
        //   . o o o .        tail at (3, 1)
        val board = boardOf(5, 5, 3 to 1, rules = TRON)
        for (way in listOf(Direction.EAST, Direction.EAST, Direction.NORTH, Direction.NORTH)) {
            board.apply(SnakeId(0), way)
        }

        val plain = priorOf(board)
        val following = priorOf(board, tail = 0.5)

        assertTrue(
            following[Direction.WEST.ordinal] > plain[Direction.WEST.ordinal],
            "the step toward the tail was not preferred: ${following.toList()} against ${plain.toList()}",
        )
        assertTrue(
            following[Direction.NORTH.ordinal] < plain[Direction.NORTH.ordinal],
            "the step away from the tail did not give up share",
        )
    }

    @Test
    fun `tail following is skipped for a snake standing on its own tail`() {
        // At length one the tail is the head, every move reads as moving away, and a constant is not
        // neutral in a proportional prior -- it would shift every share.
        val board = boardOf(7, 7, 3 to 3)

        assertEquals(priorOf(board).toList(), priorOf(board, tail = 0.9).toList())
    }

    @Test
    fun `the wall reading counts the edges of the board and not the snakes`() {
        // Head one square in from the north edge, so north lands against the wall and south does not.
        val board = boardOf(7, 7, 1 to 3)

        val plain = priorOf(board)
        val hugging = priorOf(board, wall = 0.5)

        assertTrue(
            hugging[Direction.NORTH.ordinal] > plain[Direction.NORTH.ordinal],
            "the edge square was not preferred: ${hugging.toList()} against ${plain.toList()}",
        )
        assertTrue(
            hugging[Direction.SOUTH.ordinal] < plain[Direction.SOUTH.ordinal],
            "the inland square did not give up share",
        )
    }

    @Test
    fun `the temperature is the only thing that separates two identical rankings`() {
        // Both forms are monotone in the score, so the ordering cannot move. What moves is the gap,
        // and PUCT spends its allowance in proportion to exactly that.
        // Head against the north edge, so the move inland leads to three free squares and the two
        // along the edge lead to two. One clear best move, and nothing else to explain a change.
        val board = boardOf(7, 7, 0 to 3)
        val head = SnakeId(0)

        val proportional = priorOf(board, mover = head)
        val sharp = priorOf(board, mover = head, temperature = 0.1)
        val flat = priorOf(board, mover = head, temperature = 4.0)

        val legal = board.legalMoves(head)
        val best = (0 until legal.size).maxBy { proportional[legal.nth(it).ordinal] }
        val bestOrdinal = legal.nth(best).ordinal

        assertTrue(
            sharp[bestOrdinal] > proportional[bestOrdinal],
            "a low temperature did not sharpen: ${sharp.toList()} against ${proportional.toList()}",
        )
        assertTrue(
            flat[bestOrdinal] < proportional[bestOrdinal],
            "a high temperature did not flatten: ${flat.toList()} against ${proportional.toList()}",
        )
    }

    @Test
    fun `a trapped mover is left to the tree, which owns that case`() {
        val board = boardOf(1, 1, 0 to 0)
        val priors = DoubleArray(Direction.entries.size) { -1.0 }

        MovePrior(board.grid, 0.5, 0.0, 0.0, 0.0, 0.0)
            .into(board, SnakeId(0), DirectionSet.EMPTY, priors)

        assertEquals(List(Direction.entries.size) { -1.0 }, priors.toList())
    }

    // -- internals

    private fun priorOf(
        board: Board,
        mover: SnakeId = board.toAct,
        liberty: Double = 0.5,
        pinch: Double = 0.0,
        wall: Double = 0.0,
        tail: Double = 0.0,
        temperature: Double = 0.0,
    ): DoubleArray {
        val priors = DoubleArray(Direction.entries.size)
        MovePrior(board.grid, liberty, pinch, wall, tail, temperature)
            .into(board, mover, board.legalMoves(mover), priors)
        return priors
    }

    private companion object {
        /**
         * Classic Tron for the hand-drawn shapes.
         *
         * The blocking snakes are one square each and must stay that way: at the shipped growth
         * cadence they would be a square of wall that is about to move, which is a different picture
         * from the one drawn in the comment above each fixture.
         */
        val TRON = RulesConfig(growEveryNthMove = 1)
    }
}
