package ao.snakewarz.lab.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyIntervalTest {
    @Test
    fun `bootstrap resamples whole experimental blocks deterministically`() {
        val points = listOf(
            PolicyMetricPoint("opening-a", true),
            PolicyMetricPoint("opening-a", true),
            PolicyMetricPoint("opening-b", false),
            PolicyMetricPoint("opening-b", false),
        )

        val first = policyRate(points, seed = 72_001L)
        val again = policyRate(points, seed = 72_001L)

        assertEquals(2, first.count)
        assertEquals(4, first.total)
        assertEquals(0.5, first.rate)
        assertEquals(0.0, first.low)
        assertEquals(1.0, first.high)
        assertEquals(first.low, again.low)
        assertEquals(first.high, again.high)
    }

    @Test
    fun `a saturated finite sample does not claim a zero width interval`() {
        val rate = policyRate(
            listOf(
                PolicyMetricPoint("opening-a", true),
                PolicyMetricPoint("opening-b", true),
            ),
            seed = 72_001L,
        )

        assertEquals(2, rate.count)
        assertEquals(2, rate.total)
        assertTrue(rate.low.isNaN())
        assertTrue(rate.high.isNaN())
    }
}
