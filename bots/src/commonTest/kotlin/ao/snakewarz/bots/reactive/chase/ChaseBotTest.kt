package ao.snakewarz.bots.reactive.chase

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.HeadlessMatch
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.at
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.moveOn
import ao.snakewarz.bots.reactive.space.PressureBot
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChaseBotTest {
    @Test
    fun `it walks the shortest path toward the nearest opponent`() {
        val board = boardOf(1, 9, 0 to 0, 0 to 8)

        assertEquals(Direction.EAST, moveOn(board, factory = ::ChaseBot))
    }

    @Test
    fun `it crosses the board rather than the nearest wall`() {
        // Two dimensions, so the path has a choice and "toward" means something. The opponent is
        // four rows down and one column across; the sweep names the row.
        val board = boardOf(6, 6, 0 to 0, 4 to 1)

        assertEquals(Direction.SOUTH, moveOn(board, factory = ::ChaseBot))
    }

    @Test
    fun `inside close range it stops chasing and starts fighting`() {
        // Two squares away the path stops telling it anything useful, and the hand-off to the
        // space-first bot is what stops a chaser from following an opponent into a corridor.
        val board = boardOf(1, 9, 0 to 3, 0 to 5)

        assertEquals(
            moveOn(board, seed = 7, factory = ::PressureBot),
            moveOn(board, seed = 7, factory = ::ChaseBot),
            "at range two the chaser should be playing exactly the pressure bot's move",
        )
    }

    @Test
    fun `with nobody to chase it still plays well rather than falling over`() {
        val board = boardOf(8, 8, 4 to 4)

        assertEquals(
            moveOn(board, seed = 3, factory = ::PressureBot),
            moveOn(board, seed = 3, factory = ::ChaseBot),
        )
    }

    @Test
    fun `with two opponents it walks toward the nearer one`() {
        // Equidistant, so the reduction's stated tie-break decides and the walk goes west.
        val even = boardOf(1, 13, 0 to 6, 0 to 0, 0 to 12)
        assertEquals(Direction.WEST, moveOn(even, factory = ::ChaseBot), "a tie goes to the lower slot")

        // Move slot 2 two squares closer and the walk turns around.
        val nearer = boardOf(1, 13, 0 to 6, 0 to 0, 0 to 10)
        assertEquals(Direction.EAST, moveOn(nearer, factory = ::ChaseBot))
    }

    @Test
    fun `it holds its own against the space filler`() {
        val chase = ShippedBots.entryOf(BotId("chase"))
        val space = ShippedBots.entryOf(BotId("space"))

        var wins = 0
        for (seed in 1L..20L) {
            if (HeadlessMatch(listOf(chase, space), rows = 14, cols = 14, seed = seed).run().winner == SnakeId(0)) {
                wins++
            }
        }

        assertTrue(wins >= 8, "the chaser won only $wins of 20 against the space filler")
    }
}
