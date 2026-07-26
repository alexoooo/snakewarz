package ao.snakewarz.match

import ao.snakewarz.core.Direction
import ao.snakewarz.core.DirectionSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputBufferTest {
    @Test
    fun `directions come back out in the order they went in`() {
        val buffer = InputBuffer()

        assertTrue(buffer.push(Direction.NORTH))
        assertTrue(buffer.push(Direction.EAST))

        assertEquals(Direction.NORTH, buffer.take(DirectionSet.ALL))
        assertEquals(Direction.EAST, buffer.take(DirectionSet.ALL))
        assertNull(buffer.take(DirectionSet.ALL))
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `an illegal input is dropped rather than played`() {
        // The UX decision this class exists for: a human who taps left half a square too late
        // should die to being trapped, which is the game, and not to input lag, which is not.
        val buffer = InputBuffer()
        buffer.push(Direction.WEST)
        buffer.push(Direction.NORTH)

        assertEquals(Direction.NORTH, buffer.take(DirectionSet.of(Direction.NORTH, Direction.SOUTH)))
        assertTrue(buffer.isEmpty, "and the input it skipped past is gone, not still waiting")
    }

    @Test
    fun `nothing legal queued reads as nothing queued`() {
        val buffer = InputBuffer()
        buffer.push(Direction.WEST)

        assertNull(buffer.take(DirectionSet.of(Direction.EAST)))
    }

    @Test
    fun `a repeat of the direction queued last is auto-repeat, not intent`() {
        // Holding an arrow key fires keydown at the operating system's repeat rate. Without this a
        // single held key would fill the queue and eat the next several turns the player meant.
        val buffer = InputBuffer()

        assertTrue(buffer.push(Direction.NORTH))
        assertFalse(buffer.push(Direction.NORTH))
        assertEquals(1, buffer.size)

        assertTrue(buffer.push(Direction.EAST), "a different direction is always intent")
        assertTrue(buffer.push(Direction.NORTH), "and so is going back to one played earlier")
        assertEquals(3, buffer.size)
    }

    @Test
    fun `a full buffer drops the newest keypress`() {
        // Dropping the newest keeps what the player asked for first; dropping the oldest would make
        // a mashed key produce a move nobody could trace back to a keypress.
        val buffer = InputBuffer(capacity = 2)

        assertTrue(buffer.push(Direction.NORTH))
        assertTrue(buffer.push(Direction.EAST))
        assertFalse(buffer.push(Direction.SOUTH))

        assertEquals(Direction.NORTH, buffer.take(DirectionSet.ALL))
        assertEquals(Direction.EAST, buffer.take(DirectionSet.ALL))
    }

    @Test
    fun `the queue wraps without losing its order`() {
        val buffer = InputBuffer(capacity = 2)

        buffer.push(Direction.NORTH)
        buffer.push(Direction.EAST)
        assertEquals(Direction.NORTH, buffer.take(DirectionSet.ALL))

        assertTrue(buffer.push(Direction.SOUTH), "the freed slot is at the far end of the array")
        assertEquals(Direction.EAST, buffer.take(DirectionSet.ALL))
        assertEquals(Direction.SOUTH, buffer.take(DirectionSet.ALL))
    }

    @Test
    fun `clearing stops old keypresses leaking into the next match`() {
        val buffer = InputBuffer()
        buffer.push(Direction.NORTH)
        buffer.push(Direction.WEST)

        buffer.clear()

        assertTrue(buffer.isEmpty)
        assertNull(buffer.take(DirectionSet.ALL))
        assertTrue(buffer.push(Direction.NORTH), "and the collapse rule forgot what was queued too")
    }
}
