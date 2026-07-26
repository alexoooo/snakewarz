package ao.snakewarz.match

import ao.snakewarz.botapi.Decision
import ao.snakewarz.core.Direction
import ao.snakewarz.core.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
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

        // NORTH is where the neck is, so it is not legal and the buffer drops it. With nothing left
        // to play the snake carries on south rather than reversing into itself.
        assertEquals(Decision.Move(Direction.SOUTH), InteractiveBot(buffer).chooseMove(turnOn(board)))
    }

    @Test
    fun `with nothing queued it keeps the heading the player chose`() {
        val board = soloBoard()
        board.apply(SnakeId(0), Direction.EAST)

        assertEquals(Decision.Move(Direction.EAST), InteractiveBot(InputBuffer()).chooseMove(turnOn(board)))
    }

    @Test
    fun `before the first move there is no heading to continue, so it waits`() {
        // CONTINUE_STRAIGHT sustains a direction the player picked; it never invents one. The legacy
        // MoveTracker did invent one -- the first available direction -- so a bot played a move
        // nobody chose and then repeated it forever.
        assertEquals(Decision.Pending, InteractiveBot(InputBuffer()).chooseMove(turnOn(soloBoard())))
    }

    @Test
    fun `WAIT_FOR_INPUT never moves on its own, even once under way`() {
        val board = soloBoard()
        board.apply(SnakeId(0), Direction.EAST)
        val bot = InteractiveBot(InputBuffer(), StallPolicy.WAIT_FOR_INPUT)

        assertEquals(Decision.Pending, bot.chooseMove(turnOn(board)))
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
