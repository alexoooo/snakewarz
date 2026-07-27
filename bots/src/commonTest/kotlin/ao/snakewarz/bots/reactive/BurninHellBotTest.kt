package ao.snakewarz.bots.reactive

import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.scratch.BoardScratch
import ao.snakewarz.bots.HeadlessMatch
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.at
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.headOf
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BurninHellBotTest {
    @Test
    fun `it takes north whenever north is open`() {
        val board = boardOf(5, 5, 2 to 2)

        assertEquals(Direction.NORTH, ask(board))
    }

    @Test
    fun `with north gone it takes south`() {
        // On the top row, so north is the wall. South is open because a snake of length one has no
        // neck yet, which is the only reason this position can prefer south at all.
        val board = boardOf(5, 5, 0 to 2)

        assertEquals(Direction.SOUTH, ask(board))
    }

    @Test
    fun `on the top row running north it turns east, which is what makes the sweep serpentine`() {
        // The whole bot is this move. Running north into the top wall, north is gone and south is
        // its own neck, so east is the first thing left -- and one square east it will find north
        // walled and dive south again. Columns, not a spiral.
        val board = boardOf(5, 5, 2 to 2)

        board.apply(SnakeId(0), Direction.NORTH)
        board.apply(SnakeId(0), Direction.NORTH)
        assertEquals(0 to 2, board.headOf(SnakeId(0)), "it should be on the top row facing north")

        assertEquals(Direction.EAST, ask(board))
    }

    @Test
    fun `west is the last resort rather than a preference`() {
        // A one-row corridor with the snake at the east end: north and south are walls, east is a
        // wall, and at length one there is no neck behind it.
        val board = boardOf(1, 3, 0 to 1)

        board.apply(SnakeId(0), Direction.EAST)
        assertEquals(Direction.WEST, ask(board))
    }

    @Test
    fun `the sweep is systematic rather than a walk`() {
        // A random walk on a 12x12 dies in a few dozen moves. A column sweep covers the board, and
        // only dies where it runs back into what it already laid down.
        val match = HeadlessMatch(listOf(burninHell(), burninHell()), rows = 12, cols = 12, seed = 1)
        match.run()

        assertTrue(
            match.moves().size > 40,
            "two sweepers on a 12x12 lasted only ${match.moves().size} moves",
        )
    }

    @Test
    fun `it consumes no randomness at all`() {
        // The second bot after the wall hugger whose move stream is pinned by the rules alone. If
        // this ever fails, something in it started drawing -- and its golden hash stopped meaning
        // what it says.
        val first = HeadlessMatch(listOf(burninHell(), burninHell()), rows = 9, cols = 11, seed = 1)
        val second = HeadlessMatch(listOf(burninHell(), burninHell()), rows = 9, cols = 11, seed = 987654321)
        first.run()
        second.run()

        assertEquals(first.moves(), second.moves(), "the seed changed the sweep")
    }

    @Test
    fun `on a board where nothing is legal it still answers with a move`() {
        // Legacy set no direction here and was never asked; this engine asks. The contract suite
        // opens on exactly this board.
        val board = boardOf(1, 1, 0 to 0)

        assertEquals(Direction.NORTH, ask(board))
    }

    @Test
    fun `it beats the wall hugger it most resembles`() {
        // Both fill the board without looking ahead, and the sweep strictly dominates the spiral: a
        // spiral encloses itself, a column sweep does not until it has used the board up. Measured
        // at 100 of 100 over both seatings; the threshold is what a real regression would break.
        assertTrue(winsAgainst("wallhug") >= 16, "the sweeper won only ${winsAgainst("wallhug")} of 20")
    }

    @Test
    fun `it beats random comfortably`() {
        // Measured 16 of 20 here, and 84 of 100 over both seatings on the same board.
        assertTrue(winsAgainst("random") >= 13, "the sweeper won only ${winsAgainst("random")} of 20")
    }

    private fun winsAgainst(slug: String): Int {
        val opponent = ShippedBots.entryOf(BotId(slug))
        var wins = 0
        for (seed in 1L..20L) {
            if (HeadlessMatch(listOf(burninHell(), opponent), rows = 14, cols = 14, seed = seed)
                    .run().winner == SnakeId(0)
            ) {
                wins++
            }
        }
        return wins
    }

    private fun burninHell() = ShippedBots.entryOf(BotId("burninhell"))

    private fun ask(board: Board): Direction {
        val id = board.toAct
        val decision = BurninHellBot().chooseMove(
            Turn(board, id, board.legalMoves(id), Budget(0), BoardScratch(board, Budget(0))),
        )
        return (decision as Decision.Move).direction
    }
}
