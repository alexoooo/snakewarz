package ao.snakewarz.bots.search

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.scratch.BoardScratch
import ao.snakewarz.bots.HeadlessMatch
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.cornerSpawns
import ao.snakewarz.bots.reactive.space.SpaceBot
import ao.snakewarz.bots.setupFor
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How deep an exact search gets here, which is the whole question about this bot.
 *
 * `BotContractTest` already gates it as a bot. What that cannot say is whether alpha-beta is the
 * right shape of search for this game, and the number deciding it is plies per turn: the facts that
 * settle a filling endgame sit a hundred plies out, so a search reaching eight is answering a
 * different question from the one the game asks. This prints that number rather than merely bounding
 * it — the assertions are loose on purpose and the `[depth]` lines are the result.
 */
class AlphaBetaBotTest {
    @Test
    fun `it reaches the plies a branching factor near three allows`() {
        // Self-play at the shipped allowance, both boards. One seed each: a match is a few hundred
        // decisions, so what is reported is the spread inside one game rather than across seeds.
        for ((rows, cols) in listOf(12 to 12, 20 to 20)) {
            val depths = selfPlayDepths(rows, cols, budgetPerTurn = SHIPPED_BUDGET, seed = 20260728)
            val searched = depths.filter { it > 0 }
            assertTrue(searched.isNotEmpty(), "${rows}x$cols played no searched turn at all")

            println(
                "[depth] ${rows}x$cols budget=$SHIPPED_BUDGET: ${searched.size} searched turns, " +
                    "mean ${tenths(searched)} plies, min ${searched.min()}, max ${searched.max()}, " +
                    "forced ${depths.size - searched.size}",
            )

            assertTrue(searched.min() >= 2, "${rows}x$cols never completed a two-ply pass")
        }
    }

    @Test
    fun `a bigger allowance is never a shallower search`() {
        // The deepening loop's own claim, and the one way it could be silently broken: a pass the
        // allowance cuts short must not be adopted as a depth, or a larger budget would sometimes
        // report a deeper number it never finished.
        val small = selfPlayDepths(12, 12, budgetPerTurn = SMALL_BUDGET, seed = 4242).filter { it > 0 }
        val large = selfPlayDepths(12, 12, budgetPerTurn = SHIPPED_BUDGET, seed = 4242).filter { it > 0 }

        println("[depth] 12x12 budget=$SMALL_BUDGET: mean ${tenths(small)} plies over ${small.size} turns")
        assertTrue(mean(large) > mean(small), "ten times the allowance bought no extra depth")
    }

    @Test
    fun `above two snakes it plays the paranoid reduction, legally`() {
        // Alpha-beta needs one opponent and a free-for-all has several, so every other snake is
        // searched as one minimising coalition. That is a *reduction*, not a restriction: a trapped
        // rival is still alive and still to act, so it is handed a direction like anybody else, and
        // nothing here may resign. The contract suite seats two, so three and four are checked here.
        for (seats in 3..4) {
            val entry = ShippedBots.entryOf(BotId("alphabeta"))
            val match = HeadlessMatch(List(seats) { entry }, rows = 12, cols = 12, seed = 909, budgetPerTurn = 200)
            match.run()

            for (recorded in match.decisions) {
                val decision = recorded.decision
                assertTrue(
                    decision is Decision.Move && (recorded.legal.isEmpty || decision.direction in recorded.legal),
                    "$seats snakes: answered $decision with ${recorded.legal} available",
                )
            }
        }
    }

    @Test
    fun `handed no allowance it answers with the flood fill`() {
        // The zero-allowance path is the first thing the contract suite asks about, and the shipped
        // answer to it is `SpaceBot` — so this asserts the delegation rather than merely that
        // something legal came back. Both draw from the same forked stream, so the tie-break matches.
        val board = boardOf(10, 10, 0 to 0, 9 to 9)
        val self = board.toAct

        assertEquals(unbudgetedMove(board, self) { SpaceBot(it) }, unbudgetedMove(board, self) { AlphaBetaBot(it) })
    }

    private fun unbudgetedMove(board: Board, self: SnakeId, factory: (BotSetup) -> Bot): Direction {
        val budget = Budget(0)
        val turn = Turn(board, self, board.legalMoves(self), budget, BoardScratch(board, budget))
        return (factory(setupFor(board, self)).chooseMove(turn) as Decision.Move).direction
    }

    /**
     * Plays this bot against itself and returns the plies each decision completed.
     *
     * Zero means the move was forced and never searched. The board hash is checked either side of
     * every decision, which is what says the descent's applies and undos — and the replay a paid leaf
     * does onto the arena its own payment reset — unwind to exactly where they started.
     */
    private fun selfPlayDepths(rows: Int, cols: Int, budgetPerTurn: Int, seed: Long): List<Int> {
        val grid = Grid(rows, cols)
        val board = Board(grid, cornerSpawns(grid, SEATS))
        val matchRng = SplitMix64(seed)
        val budgets = Array(SEATS) { Budget(budgetPerTurn) }
        val scratches = Array(SEATS) { BoardScratch(board, budgets[it]) }
        val bots = Array(SEATS) { slot ->
            AlphaBetaBot(
                BotSetup(
                    self = SnakeId(slot),
                    grid = grid,
                    rules = board.rules,
                    opponents = IntArray(SEATS - 1) { other -> if (other < slot) other else other + 1 },
                    rng = matchRng.fork(slot),
                    params = BotParams.EMPTY,
                ),
            )
        }

        val depths = mutableListOf<Int>()
        while (board.outcome == null) {
            val id = board.toAct
            val budget = budgets[id.index]
            budget.reset()

            val before = board.hash
            val bot = bots[id.index]
            val decision = bot.chooseMove(Turn(board, id, board.legalMoves(id), budget, scratches[id.index]))
            check(board.hash == before) { "the search left the live board somewhere else" }

            depths += bot.depthReached
            board.apply(id, (decision as Decision.Move).direction)
        }
        return depths
    }

    private fun mean(depths: List<Int>): Double = depths.sum().toDouble() / depths.size

    private fun tenths(depths: List<Int>): Double = (mean(depths) * 10).toInt() / 10.0

    private companion object {
        /** `MatchSetup.DEFAULT_BUDGET_PER_TURN`, spelled out because `:bots` may not see `:match`. */
        const val SHIPPED_BUDGET = 1000

        /** A tenth of it, which is where the deepening loop should be visibly shallower. */
        const val SMALL_BUDGET = 100

        const val SEATS = 2
    }
}
