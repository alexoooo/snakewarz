package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.HeadlessMatch
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.at
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.moveOn
import ao.snakewarz.bots.setupFor
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PuctBotTest {
    @Test
    fun `handed no allowance it spends none and falls back on the flood fill`() {
        // Also the path that would take the tree down if the root were opened behind the budget
        // guard: bestMoveAtRoot reads edges[ROOT], and an unopened node's are -1.
        val board = boardOf(5, 6, 0 to 2, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) { board.apply(SnakeId(0), Direction.SOUTH) }

        val budget = Budget(0)
        assertEquals(
            Direction.EAST,
            moveOn(board, seed = 5, budget = budget, factory = ::PuctBot),
            "east is fifteen squares against west's ten",
        )
        assertEquals(0, budget.consumed, "a search with nothing to spend must spend nothing")
    }

    @Test
    fun `it never outruns whatever it is given, at any evaluation`() {
        // The awkward boundaries. One evaluation is one unit, so the low end of this range is a
        // search of a handful of iterations rather than of none -- and zero still has to fall back
        // rather than run one evaluation on credit.
        for (eval in EVALS) {
            for (allowance in intArrayOf(0, 1, 2, 3, 4, 5, 8, 13, 21, 55, 100, 1_000)) {
                val board = boardOf(7, 7, 3 to 3, 0 to 0)
                val budget = Budget(allowance)

                val bot = puctOn(board, eval, seed = 17)
                val decision = bot.chooseMove(turnOn(board, board.toAct, budget))
                val move = (decision as Decision.Move).direction

                assertTrue(move in board.legalMoves(SnakeId(0)), "$eval at $allowance produced the illegal $move")
                assertTrue(budget.consumed <= allowance, "$eval at $allowance overspent to ${budget.consumed}")
            }
        }
    }

    @Test
    fun `an allowance buys the same search whichever evaluation is spending it`() {
        // The point of counting evaluations rather than simulated moves, stated as an assertion.
        // `expert` sweeps the whole board and `mobility` reads sixteen squares, and the two take
        // wildly different wall clock -- but one iteration is one unit, so at the same allowance
        // they build the same tree and a matrix comparing them is comparing the value functions.
        val opening = boardOf(12, 12, 0 to 0, 11 to 11)

        val sizes = EVALS.map { eval ->
            val bot = puctOn(opening, eval)
            bot.chooseMove(turnOn(opening, opening.toAct, Budget(ALLOWANCE)))
            bot.nodesSearched
        }

        for (size in sizes) {
            assertTrue(size in (ALLOWANCE - 2)..(ALLOWANCE + 2), "$ALLOWANCE evaluations built $sizes")
        }
    }

    @Test
    fun `the evaluation it is told to use is the one it uses`() {
        // Same tree size now, so the tell has to be the moves rather than the node count: three
        // readings of the same position that disagree about what to play. If the knob were being
        // ignored, all three streams would be identical.
        val puct = ShippedBots.entryOf(BotId("puct"))
        val random = ShippedBots.entryOf(BotId("random"))

        val streams = EVALS.map { eval ->
            val match = HeadlessMatch(
                listOf(puct, random),
                rows = 12,
                cols = 12,
                seed = 5,
                budgetPerTurn = ALLOWANCE,
                paramsPerSlot = listOf(BotParams(mapOf(PuctBot.EVAL.name to eval)), BotParams.EMPTY),
            )
            match.run()
            match.moves()
        }

        assertEquals(streams.distinct().size, streams.size, "the three evaluations played the same match")
    }

    @Test
    fun `at a static evaluation it needs no randomness at all`() {
        // The claim UctBot cannot make. PUCT orders its unvisited children by the prior rather than
        // by a randomised score, and ExpertEval draws nothing, so the whole turn is arithmetic.
        val first = puctOn(boardOf(9, 9, 4 to 4, 0 to 0), PuctBot.EXPERT, seed = 1)
        val second = puctOn(boardOf(9, 9, 4 to 4, 0 to 0), PuctBot.EXPERT, seed = 987_654)

        assertEquals(
            moveFrom(first, boardOf(9, 9, 4 to 4, 0 to 0)),
            moveFrom(second, boardOf(9, 9, 4 to 4, 0 to 0)),
            "two different streams disagreed, so something drew from one",
        )
    }

    @Test
    fun `the same position and seed produce the same move, rollouts included`() {
        val first = moveFrom(puctOn(boardOf(9, 9, 4 to 4, 0 to 0), PuctBot.ROLLOUT, 99), boardOf(9, 9, 4 to 4, 0 to 0))
        val second = moveFrom(puctOn(boardOf(9, 9, 4 to 4, 0 to 0), PuctBot.ROLLOUT, 99), boardOf(9, 9, 4 to 4, 0 to 0))

        assertEquals(first, second)
    }

    @Test
    fun `thinking does not move the real board`() {
        for (eval in EVALS) {
            val board = boardOf(9, 9, 4 to 4, 0 to 0)
            val before = board.hash

            puctOn(board, eval, seed = 4).chooseMove(turnOn(board, board.toAct, Budget(ALLOWANCE)))

            assertEquals(before, board.hash, "$eval moved the live arena")
            assertEquals(0, board.turnIndex)
        }
    }

    @Test
    fun `it finds the side of the board that is not a dead end`() {
        val board = boardOf(5, 5, 0 to 1, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) { board.apply(SnakeId(0), Direction.SOUTH) }

        for (eval in EVALS) {
            assertEquals(
                Direction.EAST,
                moveFrom(puctOn(board, eval, seed = 2), board, Budget(ALLOWANCE)),
                "$eval walked into the three-square pocket",
            )
        }
    }

    @Test
    fun `it beats a random mover very nearly always`() {
        val puct = ShippedBots.entryOf(BotId("puct"))
        val random = ShippedBots.entryOf(BotId("random"))

        var wins = 0
        for (seed in 1L..10L) {
            val match = HeadlessMatch(
                listOf(puct, random),
                rows = 12,
                cols = 12,
                seed = seed,
                budgetPerTurn = ALLOWANCE,
            )
            if (match.run().winner == SnakeId(0)) {
                wins++
            }
        }

        assertTrue(wins >= 9, "PUCT won only $wins of 10 against a random mover")
    }

    @Test
    fun `it plays a three-way match without falling over`() {
        // Value backup is per actor for the reason UctTree's KDoc gives, and this is the shape that
        // reason is about: with three snakes, "bad for them" stops meaning "good for me".
        val puct = ShippedBots.entryOf(BotId("puct"))
        val space = ShippedBots.entryOf(BotId("space"))
        val random = ShippedBots.entryOf(BotId("random"))

        for (seed in 1L..5L) {
            val match = HeadlessMatch(
                listOf(puct, space, random),
                rows = 12,
                cols = 12,
                seed = seed,
                budgetPerTurn = ALLOWANCE,
            )
            val outcome = match.run()

            assertTrue(match.moves().isNotEmpty(), "seed $seed played no moves")
            assertTrue(outcome.winner.index in -1..2, "seed $seed ended with $outcome")
        }
    }

    // -- internals

    private fun puctOn(board: Board, eval: String, seed: Long = 1): PuctBot =
        PuctBot(setupFor(board, board.toAct, seed, BotParams(mapOf(PuctBot.EVAL.name to eval))))

    private fun moveFrom(bot: PuctBot, board: Board, budget: Budget = Budget(ALLOWANCE)): Direction =
        (bot.chooseMove(turnOn(board, board.toAct, budget)) as Decision.Move).direction

    private companion object {
        val EVALS = listOf(PuctBot.ROLLOUT, PuctBot.MOBILITY, PuctBot.EXPERT)

        /** Evaluations a turn: a fifth of the shipped allowance, which is a real search and is quick. */
        const val ALLOWANCE = 200
    }
}
