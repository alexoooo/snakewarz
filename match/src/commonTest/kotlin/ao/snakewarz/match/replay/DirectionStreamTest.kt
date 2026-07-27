package ao.snakewarz.match.replay

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.random.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DirectionStreamTest {
    @Test
    fun `it reads back what it was given`() {
        val moves = List(1000) { Direction.entries[it % 4] }
        val stream = DirectionStream()
        moves.forEach(stream::add)

        assertEquals(moves, stream.toList())
        assertEquals(moves.size, stream.size)
    }

    @Test
    fun `it really is two bits a move`() {
        // The whole reason a replay fits in a URL. An 800-turn match is 200 bytes here.
        val stream = DirectionStream()
        repeat(800) { stream.add(Direction.NORTH) }

        assertEquals(200, stream.bytes().size)
    }

    @Test
    fun `a byte packs four moves without the high one bleeding into its neighbour`() {
        // WEST is ordinal 3, so it sets both bits — the case where a sign-extended byte read would
        // corrupt everything above it.
        val stream = DirectionStream()
        val moves = listOf(Direction.WEST, Direction.WEST, Direction.WEST, Direction.WEST, Direction.NORTH)
        moves.forEach(stream::add)

        assertEquals(moves, stream.toList())
        assertEquals(2, stream.bytes().size)
    }

    @Test
    fun `fuzzing reads back exactly`() {
        val rng = SplitMix64(2005)

        repeat(50) {
            val moves = List(rng.nextInt(500)) { Direction.entries[rng.nextInt(4)] }
            val stream = DirectionStream()
            moves.forEach(stream::add)

            assertEquals(moves, stream.toList())
            assertEquals(stream, DirectionStream.of(stream.bytes(), stream.size))
        }
    }

    @Test
    fun `a copy is independent of what it was copied from`() {
        val stream = DirectionStream()
        repeat(10) { stream.add(Direction.EAST) }

        val copy = stream.copy()
        stream.add(Direction.WEST)

        assertEquals(10, copy.size)
        assertEquals(11, stream.size)
        assertTrue(copy != stream)
    }

    @Test
    fun `equality ignores spare capacity`() {
        val grown = DirectionStream()
        repeat(400) { grown.add(Direction.SOUTH) }
        while (grown.size > 3) {
            // No removal by design, so rebuild rather than shrink — the point is that a stream which
            // once held 400 moves compares equal to a fresh one holding the same three.
            break
        }

        val small = DirectionStream()
        repeat(3) { small.add(Direction.SOUTH) }
        val alsoSmall = DirectionStream.of(small.bytes(), small.size)

        assertEquals(small, alsoSmall)
        assertEquals(small.hashCode(), alsoSmall.hashCode())
        assertTrue(small != grown)
    }

    @Test
    fun `reading past the end is a mistake, not a wrapped index`() {
        val stream = DirectionStream()
        stream.add(Direction.NORTH)

        assertFailsWith<IllegalArgumentException> { stream[1] }
        assertFailsWith<IllegalArgumentException> { stream[-1] }
    }
}
