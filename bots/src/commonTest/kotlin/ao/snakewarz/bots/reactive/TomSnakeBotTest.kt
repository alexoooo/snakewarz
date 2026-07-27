package ao.snakewarz.bots.reactive

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
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

class TomSnakeBotTest {
    @Test
    fun `on the fork branch it plays exactly the pressure bot's move`() {
        // The 1x7 corridor from PressureBotTest: both directions leave six squares, so the space
        // term ties and proximity decides outright. Nothing is tied at the end, so neither bot
        // reaches for its tie-break -- which is what makes the comparison sound. On a tied board it
        // would not be: the mixture coin has already advanced the shared stream by one draw, so a
        // standalone PressureBot would reservoir-sample differently and the two would disagree for
        // a reason that has nothing to do with the delegation.
        val board = boardOf(1, 7, 0 to 3, 0 to 6)

        assertEquals(
            moveOn(board, seed = 7, factory = ::PressureBot),
            moveOn(board, seed = 7, factory = withFork("1.0")),
        )
        assertEquals(Direction.EAST, moveOn(board, seed = 7, factory = withFork("1.0")))
    }

    @Test
    fun `on the random branch it is not playing the pressure bot's move`() {
        // Proves the other branch is reachable and is somebody else, without pinning it to a
        // particular draw. Pressure plays east here every time; the random branch cannot.
        val board = boardOf(1, 7, 0 to 3, 0 to 6)

        val moves = (1L..20L).map { moveOn(board, seed = it, factory = withFork("0.0")) }.toSet()
        assertTrue(moves.size > 1, "the random branch played $moves on every seed")
    }

    @Test
    fun `the fork branch is the strong one, which is the whole point of the mixture`() {
        // The wiring test. Same class, same seeds, same board, only the share differs -- so this
        // fails if the two delegates are ever swapped, which nothing else here would catch.
        // Measured 20 of 20, and 95 of 100 over both seatings.
        val wins = winsFor(entryWithFork("1.0"), entryWithFork("0.0"))
        assertTrue(wins >= 17, "pure fork beat pure random in only $wins of 20")
    }

    @Test
    fun `one turn in five of appraisal is worth having`() {
        // The shipped ratio against its own random branch, which is the honest comparison: it
        // isolates the 20% and holds everything else fixed. Measured 14 of 20, and 68 of 100 over
        // both seatings. Against the shipped `random` the same edge measures a much thinner 56 of
        // 100 -- so the ratio buys something, but not much, and this is the assertion that says how
        // much rather than pretending it is more.
        val wins = winsFor(ShippedBots.entryOf(BotId("tomsnake")), entryWithFork("0.0"))
        assertTrue(wins >= 12, "the shipped mixture beat its own random branch in only $wins of 20")
    }

    @Test
    fun `handed no allowance it spends none`() {
        // Both delegates are budget-free, so this should hold by construction -- and it is exactly
        // the kind of property that stops holding the day somebody mixes a search bot in.
        val entry = ShippedBots.entryOf(BotId("tomsnake"))
        val match = HeadlessMatch(listOf(entry, entry), rows = 10, cols = 10, seed = 99, budgetPerTurn = 0)
        match.run()

        assertTrue(match.decisions.isNotEmpty())
        assertEquals(0, match.decisions.maxOf { it.budgetConsumed })
    }

    @Test
    fun `a solo board decides on room alone rather than dividing by zero`() {
        // Inherited from PressureBot, and worth one line here because the fork branch is the only
        // path that can reach the mean-distance term at all.
        val board = boardOf(8, 8, 4 to 4)

        val move = moveOn(board, factory = withFork("1.0"))
        assertTrue(move in board.legalMoves(SnakeId(0)), "$move is not legal")
    }

    private fun winsFor(challenger: BotEntry, defender: BotEntry): Int {
        var wins = 0
        for (seed in 1L..20L) {
            if (HeadlessMatch(listOf(challenger, defender), 12, 12, seed, budgetPerTurn = 200)
                    .run().winner == SnakeId(0)
            ) {
                wins++
            }
        }
        return wins
    }

    private fun entryWithFork(forkShare: String): BotEntry =
        BotEntry(BotId("tomsnake"), "Tom Snake", BotFactory(withFork(forkShare)))

    /** [TomSnakeBot] built with one parameter overridden, the way a URL would hand it over. */
    private fun withFork(forkShare: String): (BotSetup) -> Bot = { setup ->
        TomSnakeBot(
            BotSetup(
                self = setup.self,
                grid = setup.grid,
                rules = setup.rules,
                opponents = setup.opponents,
                rng = setup.rng,
                params = BotParams(mapOf("forkShare" to forkShare)),
            ),
        )
    }
}
