package ao.snakewarz.bots.reactive.policy

import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.botapi.scratch.Scratch
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.search.puct.TempoOwnership
import ao.snakewarz.bots.setupFor
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PolicyBotTest {
    @Test
    fun `a policy neither asks for scratch nor consumes an allowance`() {
        val board = boardOf(5, 5, 2 to 2, 0 to 0)
        val budget = Budget(17)
        val scratch = object : Scratch {
            override fun playout(cost: Int): Playout = error("a no-tree policy asked for a playout")
        }
        val turn = Turn(board, board.toAct, board.legalMoves(board.toAct), budget, scratch)

        PolicyBot(setupFor(board, board.toAct), PolicyVariant.FULL_OWNED).chooseMove(turn)

        assertEquals(0, budget.consumed)
    }

    @Test
    fun `every case returns a legal move and an empty set returns north`() {
        val board = boardOf(5, 5, 2 to 2, 0 to 0, walls = listOf(1 to 2, 3 to 1))
        for (variant in PolicyVariant.entries) {
            val bot = PolicyBot(setupFor(board, board.toAct), variant)
            val decision = bot.chooseMove(turnOn(board)) as Decision.Move

            assertTrue(decision.direction in board.legalMoves(board.toAct), variant.key)
            assertTrue(bot.rawMaxima.isNotEmpty, variant.key)
        }

        val trapped = boardOf(1, 1, 0 to 0)
        val bot = PolicyBot(setupFor(trapped, trapped.toAct), PolicyVariant.FULL)

        assertEquals(Direction.NORTH, (bot.chooseMove(turnOn(trapped)) as Decision.Move).direction)
        assertEquals(DirectionSet.EMPTY, bot.rawMaxima)
    }

    @Test
    fun `the shared ranker preserves every policy choice and orders every legal action`() {
        val board = boardOf(5, 5, 2 to 2, 0 to 0, walls = listOf(1 to 2, 3 to 1))
        val legal = board.legalMoves(board.toAct)
        val ordered = IntArray(Direction.entries.size)

        for (variant in PolicyVariant.entries) {
            val bot = PolicyBot(setupFor(board, board.toAct), variant)
            val expected = bot.chooseDirection(turnOn(board))
            val ranker = PolicyRanker(board.grid, board.snakeCount, variant)
            val count = ranker.orderInto(board, board.toAct, legal, ordered, 0)

            assertEquals(expected.ordinal, ordered[0], variant.key)
            assertEquals(bot.rawMaxima, ranker.rawMaxima, variant.key)
            assertEquals(legal.size, count, variant.key)
            var orderedBits = 0
            for (i in 0 until count) {
                orderedBits = orderedBits or (1 shl ordered[i])
            }
            assertEquals(legal.bits, orderedBits, variant.key)
        }
    }

    @Test
    fun `a retracting tail opens the old head and a growing turn leaves it as a wall`() {
        val board = boardOf(1, 5, 0 to 2)
        val bot = PolicyBot(setupFor(board, board.toAct), PolicyVariant.LOCAL_ROOM)

        bot.chooseDirection(turnOn(board))
        assertEquals(DirectionSet.of(Direction.EAST, Direction.WEST), bot.rawMaxima)

        board.apply(SnakeId(0), Direction.EAST)
        assertTrue(board.snake(SnakeId(0)).growsOnNextMove)

        assertEquals(Direction.WEST, bot.chooseDirection(turnOn(board)))
        assertEquals(DirectionSet.of(Direction.WEST), bot.rawMaxima)
    }

    @Test
    fun `shortest paths follow a wall corridor rather than Manhattan distance`() {
        // The opponent is northwest. North closes the geometric gap, but the wall has its only gap
        // at the bottom and the live-board path correctly starts south.
        //
        //   O . # . .
        //   . . # . .
        //   . . # H .
        //   . . # . .
        //   . . . . .
        val board = boardOf(
            5,
            5,
            2 to 3,
            0 to 0,
            rules = TRON,
            walls = listOf(0 to 2, 1 to 2, 2 to 2, 3 to 2),
        )
        val bot = PolicyBot(setupFor(board, board.toAct), PolicyVariant.GUARDED_PATH)

        assertEquals(Direction.SOUTH, bot.chooseDirection(turnOn(board)))
        assertEquals(DirectionSet.of(Direction.SOUTH), bot.rawMaxima)
    }

    @Test
    fun `the room guard admits exactly half and rejects anything smaller`() {
        // The opponent is west. With two squares toward it and four east, the chase direction sits
        // exactly on the 0.5 boundary. Giving the roomy side one more square makes it ineligible.
        val boundary = boardOf(1, 8, 0 to 3, 0 to 0, rules = TRON)
        val below = boardOf(1, 9, 0 to 3, 0 to 0, rules = TRON)

        assertEquals(Direction.WEST, choose(boundary, PolicyVariant.GUARDED_PATH))
        assertEquals(Direction.EAST, choose(below, PolicyVariant.GUARDED_PATH))
    }

    @Test
    fun `local-room rejects a locally inviting pocket that local enters`() {
        // West has two connected liberties and four squares total. East has one immediate liberty
        // opening into eleven squares, so only the room guard should reverse the local choice.
        //
        //   # . . # # . .
        //   # . . # # . .
        //   # . . H . . .
        //   # # # # # . .
        //   # # # # # . .
        val open = arrayOf(
            1 to 1, 1 to 2, 2 to 1, 2 to 2,
            2 to 3, 2 to 4,
            0 to 5, 0 to 6, 1 to 5, 1 to 6, 2 to 5, 2 to 6, 3 to 5, 3 to 6, 4 to 5, 4 to 6,
        )
        val board = boardOf(5, 7, 2 to 3, rules = TRON, walls = wallsOutside(5, 7, open))

        assertEquals(Direction.WEST, choose(board, PolicyVariant.LOCAL))
        assertEquals(Direction.EAST, choose(board, PolicyVariant.LOCAL_ROOM))
    }

    @Test
    fun `the local reading marks down a neck despite its extra liberty`() {
        // West has three liberties but two groups; east has two liberties in one group.
        //
        //   . . . . .
        //   . . H . .
        //   # . # # #
        //   . . . . .
        //   . . . . .
        val board = boardOf(
            5,
            5,
            1 to 2,
            rules = TRON,
            walls = listOf(2 to 0, 2 to 2, 2 to 3, 2 to 4),
        )
        val bot = PolicyBot(setupFor(board, board.toAct), PolicyVariant.LOCAL)

        assertEquals(Direction.EAST, bot.chooseDirection(turnOn(board)))
        assertEquals(DirectionSet.of(Direction.EAST), bot.rawMaxima)
    }

    @Test
    fun `the local reading follows a reachable tail`() {
        val board = boardOf(5, 5, 3 to 1, rules = TRON)
        for (direction in listOf(Direction.EAST, Direction.EAST, Direction.NORTH, Direction.NORTH)) {
            board.apply(SnakeId(0), direction)
        }

        val bot = PolicyBot(setupFor(board, board.toAct), PolicyVariant.LOCAL)

        assertEquals(Direction.WEST, bot.chooseDirection(turnOn(board)))
        assertEquals(DirectionSet.of(Direction.WEST), bot.rawMaxima)
    }

    @Test
    fun `the unresolved wall bonus fires only beside a wall`() {
        // The interior wall removes one liberty from north and west. At +0.5 it exactly restores
        // that liberty, so those moves join rather than beat the open pair.
        val board = boardOf(5, 5, 2 to 2, rules = TRON, walls = listOf(1 to 1))
        val plain = PolicyBot(setupFor(board, board.toAct), PolicyVariant.FULL)
        val wall = PolicyBot(setupFor(board, board.toAct), PolicyVariant.FULL_WALL)

        plain.chooseDirection(turnOn(board))
        wall.chooseDirection(turnOn(board))

        assertEquals(DirectionSet.of(Direction.SOUTH, Direction.EAST), plain.rawMaxima)
        assertEquals(DirectionSet.ALL, wall.rawMaxima)
    }

    @Test
    fun `owned area frees a retracting length one head and preserves a cycle around a neck`() {
        val split = boardOf(1, 7, 0 to 2)
        val full = PolicyBot(setupFor(split, split.toAct), PolicyVariant.FULL)
        val owned = PolicyBot(setupFor(split, split.toAct), PolicyVariant.FULL_OWNED)

        full.chooseDirection(turnOn(split))
        assertEquals(DirectionSet.of(Direction.EAST, Direction.WEST), full.rawMaxima)
        owned.chooseDirection(turnOn(split))
        assertEquals(DirectionSet.of(Direction.EAST, Direction.WEST), owned.rawMaxima)

        val cycle = boardOf(2, 5, 0 to 2)
        val around = PolicyBot(setupFor(cycle, cycle.toAct), PolicyVariant.FULL_OWNED)
        around.chooseDirection(turnOn(cycle))

        assertEquals(DirectionSet.of(Direction.EAST, Direction.WEST), around.rawMaxima)
    }

    @Test
    fun `owned components count open map ground rather than playable geometry`() {
        val board = boardOf(3, 3, 1 to 1, walls = listOf(0 to 0))
        val ownership = TempoOwnership(board.grid, 1)
        ownership.measure(board)

        val destinations = IntArray(Direction.entries.size)
        val legal = board.legalMoves(board.toAct)
        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            destinations[direction.ordinal] = board.grid.step(board.snake(board.toAct).head, direction).index
        }
        val areas = IntArray(Direction.entries.size)
        MoverOwnedComponents(board.grid).into(
            ownership,
            board.toAct.index,
            board.snake(board.toAct).head,
            legal,
            destinations,
            areas,
        )

        assertEquals(8, board.openCount)
        for (i in 0 until legal.size) {
            assertEquals(board.openCount - 1, areas[legal.nth(i).ordinal])
            assertNotEquals(board.grid.playableCount - 1, areas[legal.nth(i).ordinal])
        }
    }

    @Test
    fun `disabled readings are neutral when their facts are constant`() {
        val open = boardOf(5, 5, 2 to 2)
        assertSameReading(open, PolicyVariant.LOCAL, PolicyVariant.LOCAL_ROOM)
        assertSameReading(open, PolicyVariant.FULL, PolicyVariant.FULL_WALL)

        val cycle = boardOf(2, 5, 0 to 2)
        assertSameReading(cycle, PolicyVariant.FULL, PolicyVariant.FULL_OWNED)
    }

    @Test
    fun `close-range path differences are clamped before comparison`() {
        val board = boardOf(3, 3, 1 to 1, 0 to 0, rules = TRON)
        val bot = PolicyBot(setupFor(board, board.toAct), PolicyVariant.GUARDED_PATH)

        bot.chooseDirection(turnOn(board))

        assertEquals(board.legalMoves(board.toAct), bot.rawMaxima)
    }

    @Test
    fun `a tied policy is position-derived and independent of setup randomness`() {
        var foundNonFirst = false
        for (row in 2..4) {
            for (col in 2..4) {
                val board = boardOf(7, 7, row to col)
                val first = PolicyBot(setupFor(board, board.toAct, seed = 1), PolicyVariant.LOCAL)
                val second = PolicyBot(setupFor(board, board.toAct, seed = 999), PolicyVariant.LOCAL)

                val selected = first.chooseDirection(turnOn(board))
                assertEquals(DirectionSet.ALL, first.rawMaxima)
                assertEquals(selected, second.chooseDirection(turnOn(board)))
                assertEquals(first.rawMaxima, second.rawMaxima)
                if (selected != board.legalMoves(board.toAct).nth(0)) {
                    foundNonFirst = true
                }
            }
        }
        assertTrue(foundNonFirst, "the hash tie-break never departed from Direction declaration order")
    }

    private fun choose(board: Board, variant: PolicyVariant): Direction =
        PolicyBot(setupFor(board, board.toAct), variant).chooseDirection(turnOn(board))

    private fun assertSameReading(board: Board, first: PolicyVariant, second: PolicyVariant) {
        val left = PolicyBot(setupFor(board, board.toAct), first)
        val right = PolicyBot(setupFor(board, board.toAct), second)

        assertEquals(left.chooseDirection(turnOn(board)), right.chooseDirection(turnOn(board)))
        assertEquals(left.rawMaxima, right.rawMaxima)
    }

    private fun wallsOutside(rows: Int, cols: Int, open: Array<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val mask = BooleanArray(rows * cols)
        for ((row, col) in open) {
            mask[row * cols + col] = true
        }

        val walls = ArrayList<Pair<Int, Int>>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (!mask[row * cols + col]) {
                    walls += row to col
                }
            }
        }
        return walls
    }

    private companion object {
        val TRON = RulesConfig(growEveryNthMove = 1)
    }
}
