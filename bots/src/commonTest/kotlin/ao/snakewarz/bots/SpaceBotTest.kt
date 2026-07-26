package ao.snakewarz.bots

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.Direction
import ao.snakewarz.core.RulesConfig
import ao.snakewarz.core.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceBotTest {
    @Test
    fun `at a fork it takes the larger side`() {
        // Classic Tron rules here so the body grows every move and can be drawn as a wall in four
        // lines. Column 2 is then solid from top to bottom, and the two halves are 10 and 15.
        val board = boardOf(5, 6, 0 to 2, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) { board.apply(SnakeId(0), Direction.SOUTH) }

        assertEquals(4 to 2, board.headOf(SnakeId(0)))
        assertEquals(5, board.snake(SnakeId(0)).length, "the wall has to reach the bottom to split anything")

        assertEquals(Direction.EAST, moveOn(board, factory = ::SpaceBot), "west is ten squares, east is fifteen")
    }

    @Test
    fun `it will not walk into a pocket it could have seen`() {
        // The move that kills a wall hugger: one square of difference now, five squares of room
        // later. Column 1 is solid, so west is a five-square dead end.
        val board = boardOf(5, 5, 0 to 1, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) { board.apply(SnakeId(0), Direction.SOUTH) }

        assertEquals(Direction.EAST, moveOn(board, factory = ::SpaceBot))
    }

    @Test
    fun `it counts the square its own tail is about to leave`() {
        // A snake of length one vacates as it moves, so on a 1x6 with the snake in the middle the
        // two sides are still joined and both moves are worth six. Ignore the retraction and west
        // looks like two squares against east's three, and the bot would never play west at all.
        //
        // Snakes grow at half speed, so this correction applies on every other turn, not on none.
        val seen = mutableSetOf<Direction>()

        for (seed in 1L..20L) {
            val board = boardOf(1, 6, 0 to 2)
            assertEquals(false, board.snake(SnakeId(0)).growsOnNextMove, "the opening move drags")
            seen += moveOn(board, seed = seed, factory = ::SpaceBot)
        }

        assertEquals(setOf(Direction.WEST, Direction.EAST), seen, "a genuine tie should fall both ways")
    }

    @Test
    fun `it outlives a wall hugger, which is the point of looking ahead`() {
        val space = ShippedBots.entryOf(BotId("space"))
        val wallHug = ShippedBots.entryOf(BotId("wallhug"))

        var wins = 0
        for (seed in 1L..10L) {
            val match = HeadlessMatch(listOf(space, wallHug), rows = 14, cols = 14, seed = seed)
            if (match.run().winner == SnakeId(0)) {
                wins++
            }
        }

        assertTrue(wins >= 8, "the space filler won only $wins of 10 against a wall hugger")
    }
}
