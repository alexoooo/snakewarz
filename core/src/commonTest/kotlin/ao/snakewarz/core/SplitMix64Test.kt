package ao.snakewarz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitMix64Test {
    @Test
    fun `matches the reference known-answer vectors`() {
        // Vigna's reference splitmix64, the sequence used to seed the xoshiro family. These are the
        // whole point of writing our own PRNG: a recorded replay from 2026 must still decode in
        // 2036, on whatever target, which the standard library does not promise.
        assertVectors(
            seed = 0L,
            0xE220A8397B1DCDAFuL.toLong(),
            0x6E789E6AA1B965F4uL.toLong(),
            0x06C45D188009454FuL.toLong(),
            0xF88BB8A8724C81ECuL.toLong(),
            0x1B39896A51A8749BuL.toLong(),
        )
        assertVectors(
            seed = 1L,
            0x910A2DEC89025CC1uL.toLong(),
            0xBEEB8DA1658EEC67uL.toLong(),
            0xF893A2EEFB32555EuL.toLong(),
            0x71C18690EE42C90BuL.toLong(),
            0x71BB54D8D101B5B9uL.toLong(),
        )
        assertVectors(
            seed = -1L,
            0xE4D971771B652C20uL.toLong(),
            0xE99FF867DBF682C9uL.toLong(),
            0x382FF84CB27281E9uL.toLong(),
            0x6D1DB36CCBA982D2uL.toLong(),
            0xB4A0472E578069AEuL.toLong(),
        )
    }

    @Test
    fun `nextInt stays within its bound and covers it`() {
        val rng = SplitMix64(42L)
        val seen = BooleanArray(7)

        repeat(10_000) {
            val value = rng.nextInt(7)
            assertTrue(value in 0..6, "nextInt(7) returned $value")
            seen[value] = true
        }

        assertTrue(seen.all { it }, "every residue should appear")
    }

    @Test
    fun `nextInt is close to uniform`() {
        // Modulo without rejection would over-represent the low residues; this is the assertion that
        // would notice if the rejection loop were ever "simplified" away.
        val rng = SplitMix64(7L)
        val counts = IntArray(5)
        val draws = 200_000

        repeat(draws) { counts[rng.nextInt(5)]++ }

        val expected = draws / 5
        for (residue in counts.indices) {
            val drift = (counts[residue] - expected).toDouble() / expected
            assertTrue(drift in -0.02..0.02, "residue $residue drifted by $drift")
        }
    }

    @Test
    fun `nextInt of one is always zero and a non-positive bound is rejected`() {
        val rng = SplitMix64(3L)

        repeat(100) { assertEquals(0, rng.nextInt(1)) }

        assertFailsWith<IllegalArgumentException> { rng.nextInt(0) }
        assertFailsWith<IllegalArgumentException> { rng.nextInt(-4) }
    }

    @Test
    fun `nextDouble stays in the unit interval`() {
        val rng = SplitMix64(11L)
        var sum = 0.0

        repeat(50_000) {
            val value = rng.nextDouble()
            assertTrue(value >= 0.0 && value < 1.0, "nextDouble returned $value")
            sum += value
        }

        assertTrue(sum / 50_000 in 0.49..0.51, "mean should sit near 0.5, was ${sum / 50_000}")
    }

    @Test
    fun `forked streams are reproducible, distinct, and do not disturb the parent`() {
        val parent = SplitMix64(2024L)

        val first = parent.fork(0).take(8)
        val second = parent.fork(1).take(8)
        val firstAgain = parent.fork(0).take(8)

        assertEquals(first, firstAgain, "forking the same stream twice must reproduce it")
        assertNotEquals(first, second, "different slots must get different streams")

        // Forking is what hands each slot of a match its own RNG. If it consumed from the parent,
        // the streams a slot received would depend on how many slots were set up before it.
        assertEquals(SplitMix64(2024L).take(8), parent.take(8), "forking must not advance the parent")
    }

    @Test
    fun `pick chooses only from the set and covers all of it`() {
        val rng = SplitMix64(5L)
        val set = DirectionSet.of(Direction.NORTH, Direction.EAST, Direction.WEST)
        val seen = mutableSetOf<Direction>()

        repeat(1_000) {
            val picked = assertNotNull(rng.pick(set))
            assertTrue(picked in set, "picked $picked from $set")
            seen += picked
        }

        assertEquals(3, seen.size, "every member of $set should be reachable")
        assertNull(rng.pick(DirectionSet.EMPTY))
    }

    private fun assertVectors(seed: Long, vararg expected: Long) {
        val rng = SplitMix64(seed)
        for ((index, value) in expected.withIndex()) {
            assertEquals(value, rng.nextLong(), "output $index for seed $seed")
        }
    }

    private fun Rng.take(count: Int): List<Long> = List(count) { nextLong() }
}
