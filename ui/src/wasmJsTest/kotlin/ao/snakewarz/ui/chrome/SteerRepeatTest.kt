package ao.snakewarz.ui.chrome

import ao.snakewarz.core.grid.Direction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The held-direction clock, driven from outside — which its own KDoc says is the point of taking a
 * timestamp rather than reading one.
 *
 * Both behaviours checked here are stated in that KDoc and neither was checked anywhere: taking over
 * on a second key is how a person turns a corner, and owing the next repeat from *now* is what stops
 * a slow frame paying itself back as two moves at once.
 */
class SteerRepeatTest {
    @Test
    fun `a tap is one move`() {
        val moves = mutableListOf<Direction>()
        val repeat = SteerRepeat { moves += it }

        repeat.press(Direction.NORTH)
        repeat.release(Direction.NORTH)
        repeat.frame(1_000.0)

        assertEquals(listOf(Direction.NORTH), moves)
    }

    @Test
    fun `holding repeats once a period, and not a millisecond sooner`() {
        val moves = mutableListOf<Direction>()
        val repeat = SteerRepeat { moves += it }

        repeat.press(Direction.EAST)
        assertEquals(1, moves.size, "the press itself plays immediately")

        // The first frame only learns when the repeat is owed; it is not owed yet.
        repeat.frame(1_000.0)
        assertEquals(1, moves.size)

        repeat.frame(1_249.0)
        assertEquals(1, moves.size, "a millisecond short is short")

        repeat.frame(1_250.0)
        assertEquals(2, moves.size)

        repeat.cancel()
    }

    @Test
    fun `a repeat is owed from now, not from when it fell due`() {
        // A frame that arrives late must not leave a debt that spends itself as two moves in a row
        // the moment the page catches up. Measured from the late frame, the next one is not yet due.
        val moves = mutableListOf<Direction>()
        val repeat = SteerRepeat { moves += it }

        repeat.press(Direction.SOUTH)
        repeat.frame(0.0)

        repeat.frame(900.0)
        assertEquals(2, moves.size, "the repeat was long overdue and is paid once")

        repeat.frame(1_000.0)
        assertEquals(2, moves.size, "and the next one is owed from 900, not from 250")

        repeat.frame(1_150.0)
        assertEquals(3, moves.size)

        repeat.cancel()
    }

    @Test
    fun `a second key takes the repeat over`() {
        val moves = mutableListOf<Direction>()
        val repeat = SteerRepeat { moves += it }

        repeat.press(Direction.NORTH)
        repeat.frame(0.0)
        repeat.press(Direction.EAST)

        repeat.frame(100.0)
        repeat.frame(350.0)

        assertEquals(
            listOf(Direction.NORTH, Direction.EAST, Direction.EAST),
            moves,
            "the new direction is the one being asked for, and its clock starts fresh",
        )
        repeat.cancel()
    }

    @Test
    fun `releasing a key that is not held is not news`() {
        val moves = mutableListOf<Direction>()
        val repeat = SteerRepeat { moves += it }

        repeat.press(Direction.WEST)
        repeat.release(Direction.NORTH)

        repeat.frame(0.0)
        repeat.frame(250.0)

        assertEquals(2, moves.size, "the west key is still down")
        repeat.cancel()
    }

    @Test
    fun `cancel forgets the key, which is what a lost keyup needs`() {
        // Alt-tab mid-move sends no keyup at all, so the snake would otherwise keep going with
        // nothing on screen to explain why.
        val moves = mutableListOf<Direction>()
        val repeat = SteerRepeat { moves += it }

        repeat.press(Direction.NORTH)
        repeat.cancel()
        repeat.frame(0.0)
        repeat.frame(1_000.0)

        assertEquals(1, moves.size)
    }
}
