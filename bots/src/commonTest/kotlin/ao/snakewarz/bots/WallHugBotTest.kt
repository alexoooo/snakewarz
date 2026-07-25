package ao.snakewarz.bots

import ao.snakewarz.botapi.BoardScratch
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Board
import ao.snakewarz.core.Budget
import ao.snakewarz.core.Direction
import ao.snakewarz.core.Grid
import ao.snakewarz.core.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals

class WallHugBotTest {
    @Test
    fun `it goes straight while it can`() {
        val board = boardOf(5, 5, 2 to 0)
        val bot = WallHugBot()

        assertEquals(Direction.NORTH, ask(bot, board), "the opening move is the lowest-ordinal legal one")
        board.apply(SnakeId(0), Direction.EAST)

        repeat(3) {
            assertEquals(Direction.EAST, ask(bot, board))
            board.apply(SnakeId(0), Direction.EAST)
        }
    }

    @Test
    fun `at a wall it turns left before it turns right`() {
        // Legacy tried FOREWARD, LEFT, RIGHT in that order, and this is the whole of its behaviour.
        val board = boardOf(5, 5, 2 to 0)

        repeat(4) { board.apply(SnakeId(0), Direction.EAST) }
        assertEquals(2 to 4, board.snake(SnakeId(0)).let { board.grid.rowOf(it.head) to board.grid.colOf(it.head) })

        assertEquals(Direction.NORTH, ask(WallHugBot(), board), "east is a wall, so left")
    }

    @Test
    fun `it turns right only when left is blocked too`() {
        val board = boardOf(3, 3, 0 to 0, 1 to 1)

        // Snake 0 runs east along the top row into the corner: forward is a wall, left is a wall.
        board.apply(SnakeId(0), Direction.EAST)
        board.apply(SnakeId(1), Direction.EAST)
        board.apply(SnakeId(0), Direction.EAST)

        assertEquals(Direction.SOUTH, ask(WallHugBot(), board))
    }

    @Test
    fun `boxed in it reverses, which at length one is an escape and otherwise a clean death`() {
        val board = boardOf(1, 3, 0 to 1)

        board.apply(SnakeId(0), Direction.EAST)
        assertEquals(1, board.snake(SnakeId(0)).length, "the opening move drags rather than grows")

        // Facing east into a wall, north and south are walls too — and behind is empty, because a
        // snake of length one has no neck. Legacy set no direction at all here.
        assertEquals(Direction.WEST, ask(WallHugBot(), board))
    }

    @Test
    fun `it fills the board rather than dying early`() {
        // The behaviour that makes it a useful sparring partner: a spiral, not a suicide.
        val match = HeadlessMatch(listOf(wallHug(), wallHug()), rows = 12, cols = 12, seed = 1)
        match.run()

        assertEquals(true, match.moves().size > 40, "two wall huggers on a 12x12 lasted ${match.moves().size} moves")
    }

    private fun wallHug() = ShippedBots.entryOf(ao.snakewarz.botapi.BotId("wallhug"))

    private fun ask(bot: WallHugBot, board: Board): Direction {
        val id = board.toAct
        val decision = bot.chooseMove(
            Turn(board, id, board.legalMoves(id), Budget(0), BoardScratch(board, Budget(0))),
        )
        return (decision as Decision.Move).direction
    }

    private fun boardOf(rows: Int, cols: Int, vararg spawns: Pair<Int, Int>): Board {
        val grid = Grid(rows, cols)
        return Board(grid, IntArray(spawns.size) { grid.cellAt(spawns[it].first, spawns[it].second).index })
    }
}
