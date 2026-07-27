package ao.snakewarz.bots.search

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.HeadlessMatch
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.at
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.moveOn
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlatMonteCarloBotTest {
    @Test
    fun `handed no allowance it spends none and still plays the space filler's move`() {
        // The contract suite asserts the first half of this for every bot. The second half is what
        // makes it a real fallback rather than a shrug: at zero budget this bot is exactly as good
        // as the best thing that costs nothing.
        val board = boardOf(5, 6, 0 to 2, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) { board.apply(SnakeId(0), Direction.SOUTH) }

        val budget = Budget(0)
        assertEquals(
            Direction.EAST,
            moveOn(board, seed = 5, budget = budget, factory = ::FlatMonteCarloBot),
            "east is fifteen squares against west's ten",
        )
        assertEquals(0, budget.consumed)
    }

    @Test
    fun `a budget that runs out mid-sample does not tilt the answer toward one direction`() {
        // Legacy drained one candidate at a time, so a truncated budget gave the first direction a
        // full sample and the last one none. Round-robin keeps the counts within one of each other
        // however awkwardly the allowance lands -- checked here by the only signal available from
        // outside: the move must not always be the lowest-ordinal legal one.
        val seen = mutableSetOf<Direction>()

        for (allowance in 1..60) {
            val board = boardOf(9, 9, 4 to 4)
            seen += moveOn(board, seed = allowance.toLong(), budget = Budget(allowance), factory = ::FlatMonteCarloBot)
        }

        assertTrue(seen.size > 1, "every truncated budget produced the same move: $seen")
    }

    @Test
    fun `it never outruns whatever it is given`() {
        // The awkward boundaries: one unit buys part of a rollout, so `advance` has to be guarded
        // by a fresh `outcome` read every single time or this throws.
        for (allowance in intArrayOf(0, 1, 2, 3, 5, 8, 13, 21, 100)) {
            val board = boardOf(7, 7, 3 to 3, 0 to 0)
            val budget = Budget(allowance)

            val move = moveOn(board, seed = 11, budget = budget, factory = ::FlatMonteCarloBot)

            assertTrue(move in board.legalMoves(SnakeId(0)), "budget $allowance produced the illegal $move")
            assertTrue(budget.consumed <= allowance, "budget $allowance was overspent to ${budget.consumed}")
        }
    }

    @Test
    fun `it finds the only move that survives`() {
        // Column 1 is solid to the bottom, so west is a five-square dead end and east is fifteen.
        // A few dozen rollouts are plenty to see the difference.
        val board = boardOf(5, 5, 0 to 1, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) { board.apply(SnakeId(0), Direction.SOUTH) }

        assertEquals(Direction.EAST, moveOn(board, seed = 2, budget = Budget(4_000), factory = ::FlatMonteCarloBot))
    }

    @Test
    fun `it beats the bot it uses as a fallback`() {
        // If simulating never beat not simulating, the budget would be pure cost.
        val monteCarlo = ShippedBots.entryOf(BotId("flat-monte-carlo"))
        val random = ShippedBots.entryOf(BotId("random"))

        var wins = 0
        for (seed in 1L..10L) {
            val match = HeadlessMatch(listOf(monteCarlo, random), rows = 12, cols = 12, seed = seed)
            if (match.run().winner == SnakeId(0)) {
                wins++
            }
        }

        assertTrue(wins >= 9, "flat Monte Carlo won only $wins of 10 against a random mover")
    }
}
