package ao.snakewarz.match.human

import ao.snakewarz.botapi.Decision
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.TestRegistry
import ao.snakewarz.match.soloBoard
import ao.snakewarz.match.turnOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InteractiveBotTest {
    @Test
    fun `it declares itself interactive, which nothing else in the project does`() {
        assertTrue(InteractiveBot(InputBuffer()).interactive)
    }

    @Test
    fun `a queued move is played`() {
        val buffer = InputBuffer()
        buffer.push(Direction.SOUTH)

        assertEquals(Decision.Move(Direction.SOUTH), InteractiveBot(buffer).chooseMove(turnOn(soloBoard())))
    }

    @Test
    fun `a mistimed keypress into your own neck is ignored, not fatal`() {
        val board = soloBoard()
        board.apply(SnakeId(0), Direction.SOUTH)
        board.apply(SnakeId(0), Direction.SOUTH)

        val buffer = InputBuffer()
        buffer.push(Direction.NORTH)

        // NORTH is where the neck is, so it is not legal and the buffer drops it. Under
        // CONTINUE_STRAIGHT that leaves nothing to play, so the snake carries on south rather than
        // reversing into itself.
        val bot = InteractiveBot(buffer, StallPolicy.CONTINUE_STRAIGHT)

        assertEquals(Decision.Move(Direction.SOUTH), bot.chooseMove(turnOn(board)))
    }

    @Test
    fun `CONTINUE_STRAIGHT keeps the heading the player chose when nothing is queued`() {
        val board = soloBoard()
        board.apply(SnakeId(0), Direction.EAST)
        val bot = InteractiveBot(InputBuffer(), StallPolicy.CONTINUE_STRAIGHT)

        assertEquals(Decision.Move(Direction.EAST), bot.chooseMove(turnOn(board)))
    }

    @Test
    fun `before the first move there is no heading to continue, so even CONTINUE_STRAIGHT waits`() {
        // It sustains a direction the player picked; it never invents one. The legacy MoveTracker
        // did invent one -- the first available direction -- so a bot played a move nobody chose
        // and then repeated it forever.
        val bot = InteractiveBot(InputBuffer(), StallPolicy.CONTINUE_STRAIGHT)

        assertEquals(Decision.Pending, bot.chooseMove(turnOn(soloBoard())))
    }

    @Test
    fun `the default never moves on its own, even once under way`() {
        // WAIT_FOR_INPUT, because the shipped game is turn-based: the board is never a move ahead
        // of the last key the player pressed.
        val board = soloBoard()
        board.apply(SnakeId(0), Direction.EAST)

        assertEquals(Decision.Pending, InteractiveBot(InputBuffer()).chooseMove(turnOn(board)))
    }

    @Test
    fun `a trapped player is eliminated rather than parking the match for good`() {
        // The one case where waiting is not patience but a deadlock: take() filters illegal input,
        // so with nothing legal left no key the player could press would ever come back from it.
        // A snake still has to move, and the engine calls that death what it is.
        val match = Match(
            MatchSetup.create(1, 1, listOf(PlayableRegistry.HUMAN_ID), seed = 5),
            PlayableRegistry(TestRegistry(emptyList()), InputBuffer()),
        )

        assertIs<StepResult.Advanced>(match.step())

        assertEquals(EliminationReason.TRAPPED, match.view.snake(SnakeId(0)).eliminationReason)
        assertEquals(MatchEnd.ALL_ELIMINATED, match.outcome?.end)
    }

    @Test
    fun `a stalled human parks the match without consuming a turn`() {
        val buffer = InputBuffer()
        val match = Match(
            MatchSetup.create(7, 7, listOf(PlayableRegistry.HUMAN_ID), seed = 4),
            PlayableRegistry(TestRegistry(emptyList()), buffer),
        )

        assertEquals(StepResult.AwaitingInput, match.step())
        assertEquals(StepResult.AwaitingInput, match.step())
        assertEquals(0, match.turnIndex, "polling is free: no turn is spent waiting for a person")

        buffer.push(Direction.SOUTH)
        val moved = match.step()

        assertTrue(moved is StepResult.Advanced && moved.direction == Direction.SOUTH)
        assertEquals(1, match.turnIndex)
    }
}
