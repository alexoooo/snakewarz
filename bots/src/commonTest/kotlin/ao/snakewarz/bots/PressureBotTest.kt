package ao.snakewarz.bots

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.Direction
import ao.snakewarz.core.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PressureBotTest {
    @Test
    fun `with the room equal it closes on the opponent`() {
        // A 1x7 corridor: both directions leave six squares, so the space term ties and the
        // proximity term decides. East is two away from the opponent, west is four.
        val board = boardOf(1, 7, 0 to 3, 0 to 6)

        assertEquals(Direction.EAST, moveOn(board, factory = ::PressureBot))
    }

    @Test
    fun `it leans on an opponent without trading heads`() {
        // The clamp, which is the one piece of legacy's heuristic that looks like a mistake and is
        // not. On a 20x20 the board is open enough that all four moves leave identical room, so
        // proximity decides outright -- and east, which closes to a single square, is scored as if
        // it were 0.1 of the diagonal away rather than 0.035. North and south, genuinely two away
        // at 0.079, beat it.
        //
        // Delete the clamp and this bot plays east here, arrives beside the opponent, and dies to
        // whoever moves next.
        val board = boardOf(20, 20, 10 to 10, 10 to 12)

        val move = moveOn(board, factory = ::PressureBot)
        assertTrue(
            move == Direction.NORTH || move == Direction.SOUTH,
            "expected a move that closes without touching, got $move",
        )
    }

    @Test
    fun `a solo board ranks on room alone rather than dividing by zero`() {
        // Legacy averaged the opponent distances over `others.size()`, which is 0 here, and NaN
        // then loses every comparison it appears in. The contract suite opens with a solo board.
        val board = boardOf(8, 8, 4 to 4)

        val move = moveOn(board, factory = ::PressureBot)
        assertTrue(move in board.legalMoves(SnakeId(0)), "$move is not legal")
    }

    @Test
    fun `it beats the plain space filler more often than not`() {
        // Same space heuristic, plus a reason to be somewhere. If this ever stops holding, the
        // proximity term is doing nothing and should be deleted rather than kept for decoration.
        val pressure = ShippedBots.entryOf(BotId("pressure"))
        val space = ShippedBots.entryOf(BotId("space"))

        var wins = 0
        for (seed in 1L..20L) {
            if (HeadlessMatch(listOf(pressure, space), rows = 14, cols = 14, seed = seed).run().winner == SnakeId(0)) {
                wins++
            }
        }

        assertTrue(wins >= 11, "pressure won only $wins of 20 against the space filler")
    }
}
