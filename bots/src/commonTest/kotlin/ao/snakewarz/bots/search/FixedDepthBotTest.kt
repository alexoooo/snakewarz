package ao.snakewarz.bots.search

import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.scratch.BoardScratch
import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.botapi.scratch.Scratch
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.reactive.policy.PolicyBot
import ao.snakewarz.bots.reactive.policy.PolicyRanker
import ao.snakewarz.bots.reactive.policy.PolicyVariant
import ao.snakewarz.bots.search.puct.LeafEval
import ao.snakewarz.bots.search.puct.PuctBot
import ao.snakewarz.bots.search.puct.TerritoryEval
import ao.snakewarz.bots.setupFor
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixedDepthBotTest {
    @Test
    fun `depth one two and three agree with exhaustive paranoid oracles`() {
        for (depth in 1..3) {
            val board = decisionBoard()
            val setup = setupFor(board, board.toAct)
            val oracle = ExhaustiveOracle(setup, board, depth)
            val expected = oracle.choose()
            val budget = Budget(capFor(depth))
            val bot = FixedDepthBot(setup, depth)

            assertEquals(expected, choose(bot, board, budget), "depth $depth")
            assertEquals(depth, bot.lastCompletedDepth)
            assertFalse(bot.lastFallbackUsed)
            assertFalse(bot.lastForced)
            if (depth <= 2) {
                assertEquals(oracle.staticLeaves, bot.lastStaticLeaves, "depth $depth must be exhaustive")
                assertEquals(oracle.terminalLeaves, bot.lastTerminalLeaves)
            } else {
                assertTrue(bot.lastStaticLeaves <= oracle.staticLeaves)
                assertTrue(bot.lastTerminalLeaves <= oracle.terminalLeaves)
            }
            assertTrue(bot.lastStaticLeaves <= capFor(depth))
        }
    }

    @Test
    fun `exact leaf budget completes and one less returns the exact root policy`() {
        for (depth in 1..3) {
            val probeBoard = fullBranchBoard()
            val probeBudget = Budget(capFor(depth))
            val probe = FixedDepthBot(
                setupFor(probeBoard, probeBoard.toAct),
                depth,
                increasingRootEval(probeBoard),
            )
            val expected = choose(probe, probeBoard, probeBudget)

            val required = probe.lastStaticLeaves
            assertEquals(depth, probe.lastCompletedDepth)
            assertEquals(capFor(depth), required, "depth $depth has a full-width fixture")

            val exactBoard = fullBranchBoard()
            val exactBudget = Budget(required)
            val exact = FixedDepthBot(
                setupFor(exactBoard, exactBoard.toAct),
                depth,
                increasingRootEval(exactBoard),
            )

            assertEquals(expected, choose(exact, exactBoard, exactBudget))
            assertEquals(required, exactBudget.consumed)
            assertEquals(depth, exact.lastCompletedDepth)
            assertFalse(exact.lastFallbackUsed)

            val shortBoard = fullBranchBoard()
            val fallback = PolicyBot(
                setupFor(shortBoard, shortBoard.toAct),
                PolicyVariant.FULL_OWNED,
            ).chooseDirection(turnOn(shortBoard))
            val shortBudget = Budget(required - 1)
            val short = FixedDepthBot(
                setupFor(shortBoard, shortBoard.toAct),
                depth,
                increasingRootEval(shortBoard),
            )

            assertEquals(fallback, choose(short, shortBoard, shortBudget))
            assertEquals(required - 1, shortBudget.consumed)
            assertEquals(0, short.lastCompletedDepth)
            assertTrue(short.lastFallbackUsed)
            assertFalse(short.lastForced)
        }
    }

    @Test
    fun `terminal leaves complete for free at the configured depth`() {
        val board = boardOf(
            5,
            5,
            2 to 2,
            0 to 0,
            rules = RulesConfig(maxTurns = 1),
        )
        val budget = Budget(0)
        val bot = FixedDepthBot(setupFor(board, board.toAct), 3)

        val direction = choose(bot, board, budget)

        assertTrue(direction in board.legalMoves(board.toAct))
        assertEquals(3, bot.lastCompletedDepth)
        assertEquals(0, bot.lastStaticLeaves)
        assertEquals(board.legalMoves(board.toAct).size, bot.lastTerminalLeaves)
        assertEquals(0, budget.consumed)
        assertFalse(bot.lastFallbackUsed)
    }

    @Test
    fun `an empty nested legal set becomes one trapped north edge`() {
        val board = boardOf(
            3,
            3,
            1 to 1,
            0 to 0,
            walls = listOf(0 to 1, 1 to 0),
        )
        assertEquals(2, board.legalMoves(board.toAct).size)
        val budget = Budget(0)
        val bot = FixedDepthBot(setupFor(board, board.toAct), 3)

        val direction = choose(bot, board, budget)

        assertTrue(direction in board.legalMoves(board.toAct))
        assertEquals(3, bot.lastCompletedDepth)
        assertEquals(0, bot.lastStaticLeaves)
        assertEquals(2, bot.lastTerminalLeaves)
        assertEquals(0, budget.consumed)
        assertFalse(bot.lastFallbackUsed)
    }

    @Test
    fun `search and a refused leaf restore scratch and leave the live board unchanged`() {
        for (allowance in listOf(64, 0)) {
            val board = decisionBoard()
            board.apply(board.toAct, Direction.WEST)
            board.apply(board.toAct, Direction.NORTH)
            val beforeHash = board.hash
            val beforeTurn = board.turnIndex
            val beforeActor = board.toAct
            val beforeBodies = Array(board.snakeCount) { slot ->
                val snake = board.snake(SnakeId(slot))
                IntArray(snake.length) { part -> snake.cellAt(part).index }
            }
            val budget = Budget(allowance)
            val turn = turnOn(board, budget = budget)
            val bot = FixedDepthBot(setupFor(board, board.toAct), 3)

            choose(bot, turn)

            assertEquals(beforeHash, board.hash)
            assertEquals(beforeTurn, board.turnIndex)
            assertEquals(beforeActor, board.toAct)
            for (slot in beforeBodies.indices) {
                val snake = board.snake(SnakeId(slot))
                assertEquals(beforeBodies[slot].size, snake.length)
                for (part in beforeBodies[slot].indices) {
                    assertEquals(beforeBodies[slot][part], snake.cellAt(part).index)
                }
            }

            val reset = turn.scratch.playout(0)
            assertEquals(0, reset.undoDepth)
            assertEquals(beforeHash, reset.board.hash)
            assertEquals(beforeActor, reset.toAct)
            assertEquals(beforeTurn, reset.board.turnIndex)
        }
    }

    @Test
    fun `three plies follow shuffled field actors rather than slot arithmetic`() {
        val grid = Grid(7, 7)
        val board = Board(
            grid,
            intArrayOf(
                grid.cellAt(1, 1).index,
                grid.cellAt(5, 1).index,
                grid.cellAt(3, 5).index,
            ),
            turnOrder = intArrayOf(2, 0, 1),
        )
        assertEquals(SnakeId(2), board.toAct)
        assertActorSequence(board, intArrayOf(2, 0, 1))

        val setup = setupFor(board, board.toAct)
        val expected = ExhaustiveOracle(setup, board, 3).choose()
        val bot = FixedDepthBot(setup, 3)

        assertEquals(expected, choose(bot, board, Budget(64)))
        assertEquals(3, bot.lastCompletedDepth)
    }

    @Test
    fun `dead actors are skipped and the same snake may move twice in three plies`() {
        val board = boardOf(7, 7, 3 to 3, 0 to 0, 6 to 6)
        board.apply(board.toAct, Direction.NORTH)
        board.apply(board.toAct, Direction.NORTH)
        assertFalse(board.snake(SnakeId(1)).alive)
        board.apply(board.toAct, Direction.NORTH)
        assertEquals(SnakeId(0), board.toAct)
        assertActorSequence(board, intArrayOf(0, 2, 0))

        val setup = setupFor(board, board.toAct)
        val expected = ExhaustiveOracle(setup, board, 3).choose()
        val bot = FixedDepthBot(setup, 3)

        assertEquals(expected, choose(bot, board, Budget(64)))
        assertEquals(3, bot.lastCompletedDepth)
    }

    @Test
    fun `empty and single roots return immediately including trapped north`() {
        val empty = boardOf(1, 1, 0 to 0)
        val emptyBot = FixedDepthBot(setupFor(empty, empty.toAct), 3)
        val emptyBudget = Budget(64)

        assertEquals(Direction.NORTH, chooseWithoutScratch(emptyBot, empty, emptyBudget))
        assertTrue(emptyBot.lastForced)
        assertEquals(0, emptyBot.lastCompletedDepth)
        assertFalse(emptyBot.lastFallbackUsed)
        assertEquals(0, emptyBudget.consumed)

        val single = boardOf(1, 2, 0 to 0)
        val singleBot = FixedDepthBot(setupFor(single, single.toAct), 3)
        val singleBudget = Budget(64)

        assertEquals(Direction.EAST, chooseWithoutScratch(singleBot, single, singleBudget))
        assertTrue(singleBot.lastForced)
        assertEquals(0, singleBot.lastCompletedDepth)
        assertFalse(singleBot.lastFallbackUsed)
        assertEquals(0, singleBudget.consumed)
    }

    private fun choose(bot: FixedDepthBot, board: Board, budget: Budget): Direction =
        choose(bot, turnOn(board, budget = budget))

    private fun choose(bot: FixedDepthBot, turn: Turn): Direction =
        (bot.chooseMove(turn) as Decision.Move).direction

    private fun chooseWithoutScratch(bot: FixedDepthBot, board: Board, budget: Budget): Direction {
        val scratch = object : Scratch {
            override fun playout(cost: Int): Playout = error("a forced root asked for scratch")
        }
        return choose(bot, Turn(board, board.toAct, board.legalMoves(board.toAct), budget, scratch))
    }

    private fun assertActorSequence(board: Board, expected: IntArray) {
        val arena = BoardScratch(board, Budget(0)).playout(0)
        for (slot in expected) {
            assertEquals(SnakeId(slot), arena.toAct)
            val legal = arena.board.legalMoves(arena.toAct)
            arena.advance(if (legal.isEmpty) Direction.NORTH else legal.nth(0))
        }
    }

    private fun decisionBoard(): Board =
        boardOf(
            6,
            7,
            2 to 2,
            4 to 5,
            walls = listOf(0 to 3, 1 to 3, 3 to 3, 5 to 3),
        )

    private fun fullBranchBoard(): Board =
        boardOf(
            11,
            11,
            2 to 2,
            5 to 5,
            8 to 8,
            rules = RulesConfig(growEveryNthMove = 100),
        )

    private fun increasingRootEval(board: Board): LeafEval {
        val mover = board.toAct
        val legal = board.legalMoves(mover)
        val ordered = IntArray(Direction.entries.size)
        PolicyRanker(board.grid, board.snakeCount).orderInto(board, mover, legal, ordered, 0)

        val scores = DoubleArray(board.grid.cellCount)
        val head = board.snake(mover).head
        for (i in 0 until legal.size) {
            val direction = Direction.entries[ordered[i]]
            scores[board.grid.step(head, direction).index] = (i + 1.0) / (legal.size + 1.0)
        }
        return RootHeadEval(mover, scores)
    }

    private fun capFor(depth: Int): Int = 1 shl (depth * 2)
}

/** Makes each later root action strictly better, preventing a legitimate depth-three cutoff. */
private class RootHeadEval(
    private val self: SnakeId,
    private val scores: DoubleArray,
) : LeafEval {
    override val cost: Int get() = 1

    override fun valuesInto(playout: Playout, into: DoubleArray) {
        into.fill(LeafEval.LOSS)
        into[self.index] = scores[playout.board.snake(self).head.index]
    }
}

/** Full-width test oracle; unlike production depth three, it deliberately performs no cutoffs. */
private class ExhaustiveOracle(
    setup: BotSetup,
    board: Board,
    private val depth: Int,
) {
    private val self = setup.self.index
    private val slotCount = setup.opponentCount + 1
    private val ranker = PolicyRanker(setup.grid, slotCount)
    private val eval = TerritoryEval(
        setup.grid,
        slotCount,
        PuctBot.TERRITORY_WEIGHT.default,
        PuctBot.MOBILITY_WEIGHT.default,
        PuctBot.TRAP_PENALTY.default,
        PuctBot.SEPARATION_BONUS.default,
    )
    private val arena = BoardScratch(board, Budget(0)).playout(0)
    private val values = DoubleArray(slotCount)
    private val ordered = IntArray(DEPTHS * Direction.entries.size)

    var staticLeaves: Int = 0
        private set

    var terminalLeaves: Int = 0
        private set

    fun choose(): Direction {
        val legal = arena.board.legalMoves(arena.toAct)
        val count = ranker.orderInto(arena.board, arena.toAct, legal, ordered, 0)
        var best = Direction.entries[ordered[0]]
        var bestValue = -INFINITE

        for (i in 0 until count) {
            val direction = Direction.entries[ordered[i]]
            arena.advance(direction)
            val outcome = arena.outcome
            val value = if (outcome == null) value(depth - 1, 1) else terminal(outcome, 1)
            arena.undo()

            if (value > bestValue) {
                bestValue = value
                best = direction
            }
        }
        return best
    }

    private fun value(remainingDepth: Int, ply: Int): Double {
        if (remainingDepth == 0) {
            staticLeaves++
            eval.valuesInto(arena, values)
            return paranoidMargin()
        }

        val mover = arena.toAct
        val maximizing = mover.index == self
        val base = ply * Direction.entries.size
        val count = ranker.orderInto(arena.board, mover, arena.board.legalMoves(mover), ordered, base)
        var best = if (maximizing) -INFINITE else INFINITE

        for (i in 0 until count) {
            val direction = Direction.entries[ordered[base + i]]
            arena.advance(direction)
            val outcome = arena.outcome
            val candidate = if (outcome == null) {
                value(remainingDepth - 1, ply + 1)
            } else {
                terminal(outcome, ply + 1)
            }
            arena.undo()

            if (maximizing) {
                if (candidate > best) {
                    best = candidate
                }
            } else if (candidate < best) {
                best = candidate
            }
        }
        return best
    }

    private fun paranoidMargin(): Double {
        var rival = LeafEval.LOSS
        for (slot in 0 until slotCount) {
            if (slot != self && values[slot] > rival) {
                rival = values[slot]
            }
        }
        return values[self] - rival
    }

    private fun terminal(outcome: MatchOutcome, ply: Int): Double {
        terminalLeaves++
        return when {
            outcome.isDraw -> 0.0
            outcome.winner.index == self -> MATE - ply
            else -> -(MATE - ply)
        }
    }

    private companion object {
        const val DEPTHS = 3
        const val MATE = 1000.0
        const val INFINITE = 2000.0
    }
}
