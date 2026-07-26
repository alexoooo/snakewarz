package ao.snakewarz.bots

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.Budget
import ao.snakewarz.core.Direction
import ao.snakewarz.core.RulesConfig
import ao.snakewarz.core.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UctBotTest {
    @Test
    fun `handed no allowance it spends none and falls back on the flood fill`() {
        val board = boardOf(5, 6, 0 to 2, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) { board.apply(SnakeId(0), Direction.SOUTH) }

        val budget = Budget(0)
        assertEquals(
            Direction.EAST,
            moveOn(board, seed = 5, budget = budget, factory = ::UctBot),
            "east is fifteen squares against west's ten",
        )
        assertEquals(0, budget.consumed, "a search with nothing to spend must spend nothing")
    }

    @Test
    fun `it never outruns whatever it is given`() {
        // The awkward boundaries. `BoardScratch.apply` throws if the playout is already over, and a
        // budget expiring mid-descent makes it over -- so every advance has to sit behind a fresh
        // `outcome` read. One unit is the nastiest case: it buys exactly one move of the descent.
        for (allowance in intArrayOf(0, 1, 2, 3, 4, 5, 8, 13, 21, 55, 100, 1_000)) {
            val board = boardOf(7, 7, 3 to 3, 0 to 0)
            val budget = Budget(allowance)

            val move = moveOn(board, seed = 17, budget = budget, factory = ::UctBot)

            assertTrue(move in board.legalMoves(SnakeId(0)), "budget $allowance produced the illegal $move")
            assertTrue(budget.consumed <= allowance, "budget $allowance was overspent to ${budget.consumed}")
        }
    }

    @Test
    fun `it finds the side of the board that is not a dead end`() {
        val board = boardOf(5, 5, 0 to 1, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) { board.apply(SnakeId(0), Direction.SOUTH) }

        assertEquals(Direction.EAST, moveOn(board, seed = 2, budget = Budget(5_000), factory = ::UctBot))
    }

    @Test
    fun `the same position and seed produce the same move`() {
        val first = moveOn(boardOf(9, 9, 4 to 4, 0 to 0), seed = 99, budget = Budget(3_000), factory = ::UctBot)
        val second = moveOn(boardOf(9, 9, 4 to 4, 0 to 0), seed = 99, budget = Budget(3_000), factory = ::UctBot)

        assertEquals(first, second)
    }

    @Test
    fun `thinking does not move the real board`() {
        // HeadlessMatch checks this for every bot on every turn; asserting it here as well is worth
        // the two lines, because a search reaching the driver's arena is the worst bug available.
        val board = boardOf(9, 9, 4 to 4, 0 to 0)
        val before = board.hash

        moveOn(board, seed = 4, budget = Budget(3_000), factory = ::UctBot)

        assertEquals(before, board.hash)
        assertEquals(0, board.turnIndex)
    }

    @Test
    fun `the shipped allowance buys a tree worth having, and a tenth of it does not`() {
        // The number this phase most wants recorded, and the reason `BotLadderTest` measures
        // strength at ten thousand rather than at the contract suite's thousand. One node is
        // created per iteration, so the count *is* the iteration count.
        //
        // At a thousand a turn the tree never gets past its own first layer -- four openings, one
        // visit each, and a dozen iterations left over -- which is why UCT and flat Monte Carlo are
        // indistinguishable down there. At ten thousand there is an actual search.
        //
        // If rollouts ever get truncated, these numbers move by an order of magnitude, and that is
        // exactly the change worth being told about.
        val opening = boardOf(20, 20, 0 to 0, 19 to 19)

        val cramped = UctBot(setupFor(opening, opening.toAct, seed = 1))
        cramped.chooseMove(turnOn(opening, opening.toAct, Budget(1_000)))
        assertTrue(cramped.nodesSearched in 8..40, "a thousand a turn built ${cramped.nodesSearched} nodes")

        val roomy = UctBot(setupFor(opening, opening.toAct, seed = 1))
        roomy.chooseMove(turnOn(opening, opening.toAct, Budget(10_000)))
        assertTrue(roomy.nodesSearched in 90..260, "ten thousand a turn built ${roomy.nodesSearched} nodes")
    }

    @Test
    fun `it beats a random mover very nearly always`() {
        val uct = ShippedBots.entryOf(BotId("uct"))
        val random = ShippedBots.entryOf(BotId("random"))

        var wins = 0
        for (seed in 1L..10L) {
            val match = HeadlessMatch(listOf(uct, random), rows = 12, cols = 12, seed = seed)
            if (match.run().winner == SnakeId(0)) {
                wins++
            }
        }

        assertTrue(wins >= 9, "UCT won only $wins of 10 against a random mover")
    }

    @Test
    fun `it plays a three-way match without falling over`() {
        // The case the dropped duel reduction exists for: with three snakes the turn order rotates
        // and "bad for them" stops meaning "good for me". Nothing here asserts strength -- only
        // that the search runs the real N-player game to a conclusion.
        val uct = ShippedBots.entryOf(BotId("uct"))
        val space = ShippedBots.entryOf(BotId("space"))
        val random = ShippedBots.entryOf(BotId("random"))

        for (seed in 1L..5L) {
            val match = HeadlessMatch(listOf(uct, space, random), rows = 12, cols = 12, seed = seed)
            val outcome = match.run()

            assertTrue(match.moves().isNotEmpty(), "seed $seed played no moves")
            assertTrue(outcome.winner.index in -1..2, "seed $seed ended with $outcome")
        }
    }
}
