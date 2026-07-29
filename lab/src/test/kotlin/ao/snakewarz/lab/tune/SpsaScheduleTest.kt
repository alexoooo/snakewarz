package ao.snakewarz.lab.tune

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpsaScheduleTest {
    @Test
    fun `a stride is the most the point moves on the first iteration, and scales with the gap`() {
        // The one sentence the whole parameterisation exists to make true, because the raw SPSA gain
        // has no units anybody can picture. Asserted through the optimiser rather than the formula,
        // so it is the movement that is pinned and not the arithmetic that happens to produce it.
        val whole = search(stride = 4.0).apply(search(stride = 4.0).probe(0), SpsaSchedule.WHOLE_GAP)
        val tenth = search(stride = 4.0).apply(search(stride = 4.0).probe(0), SpsaSchedule.WHOLE_GAP / 10.0)

        assertEquals(4.0, abs(whole[0]), 1e-9)
        assertEquals(0.4, abs(tenth[0]), 1e-9)
    }

    @Test
    fun `both sequences decay, and the gain decays faster`() {
        // The convergence argument: the perturbation shrinks so the chord becomes a gradient, and
        // the gain shrinks faster so the accumulated noise is summable.
        val schedule = SpsaSchedule(iterations = 200, spread = 8.0, stride = 2.0)

        assertTrue(schedule.spreadOn(100) < schedule.spreadOn(0))
        assertTrue(schedule.gainOn(100) < schedule.gainOn(0))
        assertTrue(
            schedule.gainOn(100) / schedule.gainOn(0) < schedule.spreadOn(100) / schedule.spreadOn(0),
            "the gain has to fade faster than the perturbation",
        )
    }

    @Test
    fun `the perturbation never falls below a declared step`() {
        // Below one step the two arms snap to the same entrant spec, and a batch of a bot against a
        // bit-identical copy of itself is refused by TournamentConfig rather than measuring zero.
        val schedule = SpsaSchedule(iterations = 100_000, spread = SpsaSchedule.MINIMUM_SPREAD, stride = 1.0)

        assertEquals(SpsaSchedule.MINIMUM_SPREAD, schedule.spreadOn(99_999))
    }

    @Test
    fun `a schedule that cannot move or cannot look is refused`() {
        assertFailsWith<IllegalArgumentException> { SpsaSchedule(iterations = 0, spread = 8.0, stride = 2.0) }
        assertFailsWith<IllegalArgumentException> { SpsaSchedule(iterations = 10, spread = 0.5, stride = 2.0) }
        assertFailsWith<IllegalArgumentException> { SpsaSchedule(iterations = 10, spread = 8.0, stride = 0.0) }
    }

    private fun search(stride: Double): Spsa = Spsa(
        start = doubleArrayOf(50.0),
        lower = doubleArrayOf(0.0),
        upper = doubleArrayOf(100.0),
        schedule = SpsaSchedule(iterations = 50, spread = 8.0, stride = stride),
        seed = 1L,
    )
}
