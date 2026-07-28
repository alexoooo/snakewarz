package ao.snakewarz.bots.search.puct

import ao.snakewarz.bots.at
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two things this reads that [SpaceOwnership] does not — who moves first, and which squares are
 * about to clear.
 *
 * Everything else about the sweep is that class's, and `SpaceOwnershipTest` is where it is asserted.
 * These are the differences, on the positions that isolate them, plus one check that the parts left
 * alone were in fact left alone.
 */
class TempoOwnershipTest {
    @Test
    fun `an odd corridor's middle square goes to the snake about to move`() {
        // The same 1x5 `SpaceOwnershipTest` uses to show a tie belonging to nobody. Distances from
        // each head are 1,2,3 and 3,2,1, so the middle is a dead heat by squares -- and is not one at
        // all in the game, because one of them moves first. Half-steps say so: the mover's arrival
        // times are even and the other's odd, so the two can never land on the same number.
        val board = boardOf(1, 5, 0 to 0, 0 to 4)
        assertEquals(SnakeId(0), board.toAct, "the fixture assumes the west snake is on the clock")

        val contested = SpaceOwnership(board.grid, 2).measure(board)
        assertEquals(2, contested[0] + contested[1], "the sweep without tempo leaves the middle unowned")

        val space = TempoOwnership(board.grid, 2)
        val owned = space.measure(board)

        assertEquals(2, owned[0], "the mover takes the square it would reach first")
        assertEquals(1, owned[1])
        assertEquals(3, owned[0] + owned[1], "so all three free squares are somebody's")
        assertFalse(space.isolated(0), "they still meet in the middle of the corridor")
        assertFalse(space.isolated(1))
    }

    @Test
    fun `an even corridor divides the same way it always did`() {
        // Nothing to break the tie on, because there is no tie: the tempo correction has to leave a
        // position it does not apply to exactly where it found it.
        val board = boardOf(1, 6, 0 to 0, 0 to 5)

        val owned = TempoOwnership(board.grid, 2).measure(board)

        assertEquals(2, owned[0])
        assertEquals(2, owned[1])
    }

    @Test
    fun `a tail about to retract is ground, not wall`() {
        // Two moves each on a 3x3, so both snakes are two squares long and neither grows next move --
        // which means each tail square clears within the round. A sweep that called them wall would
        // hand the middle of a late board to nobody.
        val board = boardOf(3, 3, 0 to 0, 2 to 2)
        repeat(2) {
            board.apply(SnakeId(0), Direction.EAST)
            board.apply(SnakeId(1), Direction.WEST)
        }

        val vacating = board.at(0, 1)
        assertEquals(vacating, board.snake(SnakeId(0)).tail, "the fixture did not lay the body it meant to")
        assertFalse(board.snake(SnakeId(0)).growsOnNextMove, "and a tail only retracts if it is not growing")
        assertFalse(board.isFree(vacating), "the square is occupied right now, which is the whole point")

        val space = TempoOwnership(board.grid, 2)
        space.measure(board)

        assertEquals(0, space.ownerOf(vacating), "its own snake reaches it first, one move from now")
    }

    @Test
    fun `a tail that is not retracting is wall like any other body square`() {
        // The other half of the same rule, three moves in: both snakes grow on the next move, so
        // nothing clears. A sweep that freed a tail unconditionally would walk through a living body.
        val board = boardOf(3, 3, 0 to 0, 2 to 2)
        board.apply(SnakeId(0), Direction.EAST)
        board.apply(SnakeId(1), Direction.WEST)
        board.apply(SnakeId(0), Direction.EAST)
        board.apply(SnakeId(1), Direction.WEST)
        board.apply(SnakeId(0), Direction.SOUTH)
        board.apply(SnakeId(1), Direction.NORTH)

        val staying = board.at(0, 2)
        assertEquals(staying, board.snake(SnakeId(0)).tail, "the fixture did not lay the body it meant to")
        assertTrue(board.snake(SnakeId(0)).growsOnNextMove, "and this half is only interesting mid-growth")

        val space = TempoOwnership(board.grid, 2)
        space.measure(board)

        assertEquals(TempoOwnership.NOBODY, space.ownerOf(staying), "nobody gets a square that is not clearing")
    }

    @Test
    fun `a snake walled in behind a body still meets nobody`() {
        // The reading SurvivalEval's separated branch turns on, unchanged by either correction. Two
        // columns walked south in step: slot 0 has the fifteen squares of columns 0 to 2 and slot 1
        // is sealed into the corner. Growing every move, so no tail is retracting to muddy it.
        val board = boardOf(5, 5, 0 to 3, 0 to 4, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) {
            board.apply(SnakeId(0), Direction.SOUTH)
            board.apply(SnakeId(1), Direction.SOUTH)
        }

        val space = TempoOwnership(board.grid, 2)
        val owned = space.measure(board)

        assertEquals(15, owned[0], "columns 0 to 2 are nobody else's")
        assertEquals(0, owned[1], "and the shut-in snake reaches nothing")
        assertTrue(space.isolated(0), "there is a solid body between them")
        assertTrue(space.isolated(1))
    }
}
