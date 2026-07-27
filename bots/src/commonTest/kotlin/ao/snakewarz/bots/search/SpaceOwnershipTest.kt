package ao.snakewarz.bots.search

import ao.snakewarz.bots.at
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.search.puct.ExpertEval
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SpaceOwnership.isolated] — whether a snake's ground still runs into anybody else's.
 *
 * The counts themselves are covered by `RolloutTruncationTest`, which is where they were needed
 * first. This is about the question [ExpertEval] asks, and about the wrong way to answer it: a
 * separated snake's game is arithmetic rather than a fight, and reading one as the other is the
 * difference between a value that saturates and a value that does not.
 */
class SpaceOwnershipTest {
    @Test
    fun `an even corridor divides with nothing contested, and both snakes are still in the way`() {
        // The counter-example that decides how contact is detected. Distances from the west head are
        // 1,2,3,4 and from the east head 4,3,2,1, so every square is owned strictly and *no square is
        // ever a tie*. Two snakes one move from a head-on collision would read as separated if
        // contact were inferred from CONTESTED, which is why it is inferred from frontier adjacency.
        val board = boardOf(1, 6, 0 to 0, 0 to 5)
        val space = SpaceOwnership(board.grid, board.snakeCount)

        val owned = space.measure(board)

        assertEquals(2, owned[0])
        assertEquals(2, owned[1])
        assertEquals(4, owned[0] + owned[1], "all four free squares are owned, so nothing was contested")
        assertFalse(space.isolated(0), "the snakes meet in the middle of the corridor")
        assertFalse(space.isolated(1))
    }

    @Test
    fun `an odd corridor contests its middle, and the two readings agree there`() {
        val board = boardOf(1, 5, 0 to 0, 0 to 4)
        val space = SpaceOwnership(board.grid, board.snakeCount)

        val owned = space.measure(board)

        assertEquals(2, owned[0] + owned[1], "one of the three free squares is a tie and belongs to nobody")
        assertFalse(space.isolated(0), "where a square is contested, the frontiers plainly touched")
        assertFalse(space.isolated(1))
    }

    @Test
    fun `a snake walled in behind two bodies meets nobody at all`() {
        // Two columns walked south in step: slot 0 fills column 3, slot 1 fills column 4. Slot 1 ends
        // in the corner with its own body north of it, slot 0's body west, and walls on the other two
        // sides -- so it reaches nothing, and slot 0 has the fifteen squares of columns 0 to 2 to
        // itself. Neither can get at the other, and neither ever will.
        val board = boardOf(5, 5, 0 to 3, 0 to 4, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) {
            board.apply(SnakeId(0), Direction.SOUTH)
            board.apply(SnakeId(1), Direction.SOUTH)
        }

        val space = SpaceOwnership(board.grid, board.snakeCount)
        val owned = space.measure(board)

        assertEquals(15, owned[0], "columns 0 to 2 are nobody else's")
        assertEquals(0, owned[1], "and the shut-in snake reaches nothing")
        assertTrue(space.isolated(0), "there is a solid body between them")
        assertTrue(space.isolated(1))
    }

    @Test
    fun `a board with no free square leaves nobody to meet`() {
        val board = boardOf(1, 2, 0 to 0, 0 to 1)
        val space = SpaceOwnership(board.grid, board.snakeCount)

        val owned = space.measure(board)

        assertEquals(0, owned[0])
        assertEquals(0, owned[1])
        assertTrue(space.isolated(0), "no ground to run into anybody on")
        assertTrue(space.isolated(1))
    }

    @Test
    fun `a corpse is nobody's opposition`() {
        val board = boardOf(3, 3, 0 to 0, 2 to 2)
        board.eliminate(SnakeId(0), EliminationReason.RESIGNED)

        val space = SpaceOwnership(board.grid, board.snakeCount)
        val owned = space.measure(board)

        assertEquals(0, owned[0], "a dead snake owns nothing")
        assertEquals(7, owned[1], "everything but the two squares under a body")
        assertTrue(space.isolated(0), "a dead snake seeds nothing, so nobody is in its way")
        assertTrue(space.isolated(1), "and a body left on the board is an obstacle, not an opponent")
    }

    @Test
    fun `contact is read again on every sweep rather than accumulated`() {
        // The buffers are allocated once per bot per match and reused for every leaf of a search, so
        // a flag left set by a previous position would make a snake look engaged for the rest of the
        // match. Same board size throughout, because those buffers are sized for one grid.
        val space = SpaceOwnership(Grid(1, 5), 2)

        space.measure(boardOf(1, 5, 0 to 0, 0 to 4))
        assertFalse(space.isolated(0), "a corridor with a snake at each end is contested")

        // Head to head at the west end: slot 0 is boxed against the wall by slot 1, which then has
        // the rest of the corridor to itself. Nothing free is reachable by both.
        space.measure(boardOf(1, 5, 0 to 0, 0 to 1))
        assertTrue(space.isolated(0), "the previous sweep's contact is not this sweep's")
        assertTrue(space.isolated(1))
    }
}
