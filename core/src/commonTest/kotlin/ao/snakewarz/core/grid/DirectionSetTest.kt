package ao.snakewarz.core.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectionSetTest {
    @Test
    fun `empty and full sets report the expected size`() {
        assertEquals(0, DirectionSet.EMPTY.size)
        assertTrue(DirectionSet.EMPTY.isEmpty)

        assertEquals(Direction.entries.size, DirectionSet.ALL.size)
        assertTrue(DirectionSet.ALL.isNotEmpty)
    }

    @Test
    fun `membership matches what was added`() {
        val set = DirectionSet.of(Direction.NORTH, Direction.EAST)

        assertTrue(Direction.NORTH in set)
        assertTrue(Direction.EAST in set)
        assertFalse(Direction.SOUTH in set)
        assertFalse(Direction.WEST in set)
        assertEquals(2, set.size)
    }

    @Test
    fun `plus and minus are idempotent`() {
        val once = DirectionSet.EMPTY + Direction.SOUTH
        val twice = once + Direction.SOUTH
        assertEquals(once, twice, "adding a member twice changes nothing")

        val removed = twice - Direction.SOUTH
        assertEquals(DirectionSet.EMPTY, removed)
        assertEquals(removed, removed - Direction.SOUTH, "removing a non-member changes nothing")
    }

    @Test
    fun `nth enumerates every member exactly once for all sixteen sets`() {
        // Exhaustive: there are only 16 possible sets, so test all of them rather than sampling.
        for (bits in 0..0b1111) {
            val set = DirectionSet(bits)
            val enumerated = (0 until set.size).map { set.nth(it) }

            assertEquals(set.size, enumerated.size, "size of $set")
            assertEquals(enumerated.size, enumerated.toSet().size, "$set enumerated a duplicate")
            assertTrue(enumerated.all { it in set }, "$set enumerated a non-member")
            assertEquals(
                Direction.entries.filter { it in set },
                enumerated,
                "$set should enumerate in ordinal order",
            )
        }
    }

    @Test
    fun `singleOrNull only answers for a set of exactly one`() {
        assertEquals(Direction.WEST, DirectionSet.of(Direction.WEST).singleOrNull())
        assertNull(DirectionSet.EMPTY.singleOrNull())
        assertNull(DirectionSet.of(Direction.WEST, Direction.NORTH).singleOrNull())
    }

    @Test
    fun `intersect keeps only common members`() {
        val northEast = DirectionSet.of(Direction.NORTH, Direction.EAST)
        val eastSouth = DirectionSet.of(Direction.EAST, Direction.SOUTH)

        assertEquals(DirectionSet.of(Direction.EAST), northEast intersect eastSouth)
        assertEquals(DirectionSet.EMPTY, northEast intersect DirectionSet.EMPTY)
        assertEquals(northEast, northEast intersect DirectionSet.ALL)
    }
}
