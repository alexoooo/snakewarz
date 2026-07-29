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
        // rather than run one evaluation on credit. The solver is swept alongside because it changes
        // where the search stops: it adds an exit of its own, and a settled root at an allowance of
        // one is the case where the two could disagree about whether an iteration is owed.
        for (eval in EVALS) {
            for (solver in listOf(false, true)) {
                for (allowance in intArrayOf(0, 1, 2, 3, 4, 5, 8, 13, 21, 55, 100, 1_000)) {
                    val board = boardOf(7, 7, 3 to 3, 0 to 0)
                    val budget = Budget(allowance)

                    val bot = puctOn(board, eval, seed = 17, solver = solver)
                    val decision = bot.chooseMove(turnOn(board, board.toAct, budget))
                    val move = (decision as Decision.Move).direction

                    val what = "$eval at $allowance, solver=$solver"
                    assertTrue(move in board.legalMoves(SnakeId(0)), "$what produced the illegal $move")
                    assertTrue(budget.consumed <= allowance, "$what overspent to ${budget.consumed}")
                }
            }
        }
    }

    @Test
    fun `an allowance buys the same search whichever evaluation is spending it`() {
        // The point of counting evaluations rather than simulated moves, stated as an assertion.
        // `survival` takes the whole board apart and `mobility` reads sixteen squares, and the two
        // take wildly different wall clock -- but one iteration is one unit, so at the same allowance
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
        // Same tree size now, so the tell has to be the moves rather than the node count: five
        // readings of the same position that disagree about what to play. If the knob were being
        // ignored, all five streams would be identical.
        //
        // The seed is load-bearing and the reason is worth knowing before changing it. `survival` and
        // `horizon` count the same regions in squares and in moves, and where every region is one
        // open block the second is twice the first -- a factor that cancels exactly in a share, so
        // the two play the same match. Over 8x8 to 14x14 on six seeds they agree on nine boards of
        // twenty-four and differ on the rest; this is one of the rest.
        val puct = ShippedBots.entryOf(BotId("puct"))
        val random = ShippedBots.entryOf(BotId("random"))

        val streams = EVALS.map { eval ->
            val match = HeadlessMatch(
                listOf(puct, random),
                rows = 12,
                cols = 12,
                seed = 1,
                budgetPerTurn = ALLOWANCE,
                paramsPerSlot = listOf(BotParams(mapOf(PuctBot.EVAL.name to eval)), BotParams.EMPTY),
            )
            match.run()
            match.moves()
        }

        assertEquals(streams.distinct().size, streams.size, "two of the evaluations played the same match")
    }

    @Test
    fun `it needs no randomness at all, whichever evaluation it is given`() {
        // The claim UctBot cannot make, and it is now true of every setting rather than of one. PUCT
        // orders its unvisited children by the prior rather than by a randomised score, and none of
        // the three evaluations draws, so a whole turn is arithmetic. Two seeds, one answer.
        for (eval in EVALS) {
            val first = puctOn(boardOf(9, 9, 4 to 4, 0 to 0), eval, seed = 1)
            val second = puctOn(boardOf(9, 9, 4 to 4, 0 to 0), eval, seed = 987_654)

            assertEquals(
                moveFrom(first, boardOf(9, 9, 4 to 4, 0 to 0)),
                moveFrom(second, boardOf(9, 9, 4 to 4, 0 to 0)),
                "$eval disagreed with itself across two streams, so something drew from one",
            )
        }
    }

    @Test
    fun `the fallback with no allowance is the one place a stream is still read, and it is stable`() {
        // Handed nothing, the answer comes from SpaceBot, which breaks ties from the slot's own
        // stream. Same seed, same move -- otherwise a replay of a starved match would not reproduce.
        val board = boardOf(9, 9, 4 to 4, 0 to 0)

        val first = moveFrom(puctOn(board, PuctBot.TERRITORY, seed = 99), board, Budget(0))
        val second = moveFrom(puctOn(board, PuctBot.TERRITORY, seed = 99), board, Budget(0))

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
        // reason is about: with three snakes, "bad for them" stops meaning "good for me". The solver
        // is the second thing here that has to answer for a third snake, and it answers max^n --
        // PuctTree.proveFromChildren names the assumption -- so it runs both ways round.
        val puct = ShippedBots.entryOf(BotId("puct"))
        val space = ShippedBots.entryOf(BotId("space"))
        val random = ShippedBots.entryOf(BotId("random"))

        for (solver in listOf("false", "true")) {
            for (seed in 1L..5L) {
                val match = HeadlessMatch(
                    listOf(puct, space, random),
                    rows = 12,
                    cols = 12,
                    seed = seed,
                    budgetPerTurn = ALLOWANCE,
                    paramsPerSlot = listOf(BotParams(mapOf(PuctBot.SOLVER.name to solver))) +
                        List(2) { BotParams.EMPTY },
                )
                val outcome = match.run()

                assertTrue(match.moves().isNotEmpty(), "seed $seed played no moves at solver=$solver")
                assertTrue(outcome.winner.index in -1..2, "seed $seed ended with $outcome at solver=$solver")
            }
        }
    }

    @Test
    fun `the solver it is told to run is one it actually runs`() {
        // Same tell as the evaluation above: if the knob were being ignored the two streams would be
        // identical. The seed is not load-bearing -- all of seeds 1 to 24 diverge here -- but a whole
        // match diverging is a low bar, and the rate underneath it is the thing worth knowing before
        // anybody measures this knob. Counted per decision on the same position it is one choice in
        // seventy against another `puct`, and PuctBot.SOLVER carries the table. So a head-to-head is
        // a thin instrument for it and a field is the one to reach for -- see docs/Workflow.md.
        val puct = ShippedBots.entryOf(BotId("puct"))

        val streams = listOf("false", "true").map { solver ->
            val match = HeadlessMatch(
                listOf(puct, puct),
                rows = 12,
                cols = 12,
                seed = 3,
                budgetPerTurn = ALLOWANCE,
                paramsPerSlot = listOf(BotParams(mapOf(PuctBot.SOLVER.name to solver)), BotParams.EMPTY),
            )
            match.run()
            match.moves()
        }

        assertTrue(streams[0] != streams[1], "the solver played the match its own control played")
    }

    @Test
    fun `the RAVE it is told to run is one it actually runs, and it still stays inside its allowance`() {
        // The solver's test above, for the other gated mechanism -- plus the budget claim, because
        // the contract suite only ever seats this bot at its declared defaults and RAVE is off
        // there. The awkward allowances are the ones where a descent is one or two plies long and
        // the AMAF set of the root is therefore empty or a single move.
        val puct = ShippedBots.entryOf(BotId("puct"))
        val raving = BotParams(mapOf(PuctBot.RAVE.name to "50"))

        for (allowance in intArrayOf(0, 1, 2, 3, 5, 13, 100, ALLOWANCE)) {
            val board = boardOf(7, 7, 3 to 3, 0 to 0)
            val budget = Budget(allowance)
            val bot = PuctBot(setupFor(board, board.toAct, seed = 11, params = raving))
            val move = (bot.chooseMove(turnOn(board, board.toAct, budget)) as Decision.Move).direction

            assertTrue(move in board.legalMoves(SnakeId(0)), "RAVE at $allowance produced the illegal $move")
            assertTrue(budget.consumed <= allowance, "RAVE at $allowance overspent to ${budget.consumed}")
        }

        val streams = listOf(raving, BotParams.EMPTY).map { params ->
            val match = HeadlessMatch(
                listOf(puct, puct),
                rows = 12,
                cols = 12,
                seed = 3,
                budgetPerTurn = ALLOWANCE,
                paramsPerSlot = listOf(params, BotParams.EMPTY),
            )
            match.run()
            match.moves()
        }

        assertTrue(streams[0] != streams[1], "RAVE played the match its own control played")
    }

    // -- internals

    private fun puctOn(board: Board, eval: String, seed: Long = 1, solver: Boolean = false): PuctBot =
        PuctBot(
            setupFor(
                board,
                board.toAct,
                seed,
                BotParams(mapOf(PuctBot.EVAL.name to eval, PuctBot.SOLVER.name to solver.toString())),
            ),
        )

    private fun moveFrom(bot: PuctBot, board: Board, budget: Budget = Budget(ALLOWANCE)): Direction =
        (bot.chooseMove(turnOn(board, board.toAct, budget)) as Decision.Move).direction

    private companion object {
        val EVALS =
            listOf(
                PuctBot.TERRITORY, PuctBot.MOBILITY, PuctBot.SURVIVAL, PuctBot.HORIZON, PuctBot.CHAMBER,
                // The contract suite only ever seats this bot at its declared defaults, so a value of
                // `eval` other than TERRITORY is covered here or nowhere: budget zero, a 1x1 board and
                // "spends exactly what it declares" are all claims about each of these separately.
                PuctBot.LEARNED,
            )

        /** Evaluations a turn: a fifth of the shipped allowance, which is a real search and is quick. */
        const val ALLOWANCE = 200
    }
}
