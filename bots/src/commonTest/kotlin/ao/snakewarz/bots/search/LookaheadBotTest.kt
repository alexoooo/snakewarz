package ao.snakewarz.bots.search

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.setupFor
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import kotlin.test.Test
import kotlin.test.assertEquals

class LookaheadBotTest {
    @Test
    fun `the depth knob selects each measured fixed tree`() {
        val caps = intArrayOf(4, 16, 64, 256, 1_024)
        for (depth in 1..5) {
            val board = boardOf(
                7,
                8,
                2 to 2,
                5 to 6,
                walls = listOf(0 to 4, 1 to 4, 3 to 4, 5 to 4, 6 to 4),
            )
            val params = BotParams(mapOf("depth" to depth.toString()))
            val setup = setupFor(board, board.toAct, params = params)
            val expected = direction(FixedDepthBot(setup, depth), board, caps[depth - 1])
            val actual = LookaheadBot(setup)

            assertEquals(expected, direction(actual, board, caps[depth - 1]), "depth $depth")
            assertEquals("LookaheadBot(depth=$depth)", actual.toString())
        }
    }

    @Test
    fun `the declarations expose allowance and keep depth as a measured parameter`() {
        val search = LookaheadBot.KNOBS[0] as BotKnob.Search
        val depth = LookaheadBot.KNOBS[1] as BotKnob.Integer

        assertEquals(4, search.min)
        assertEquals(1_024, search.max)
        assertEquals(4, search.step)
        assertEquals(false, depth.tradeoff)
        assertEquals(3, depth.default)
        assertEquals(1, depth.min)
        assertEquals(5, depth.max)
    }

    private fun direction(bot: Bot, board: Board, cap: Int): Direction =
        (bot.chooseMove(turnOn(board, budget = Budget(cap))) as Decision.Move).direction
}
