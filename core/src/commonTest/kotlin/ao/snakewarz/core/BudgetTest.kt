package ao.snakewarz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BudgetTest {
    @Test
    fun `spends down to the limit and then refuses`() {
        val budget = Budget(3)

        assertFalse(budget.exhausted)
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertTrue(budget.exhausted)
        assertFalse(budget.tryConsume())
        assertEquals(3, budget.consumed, "a refused request must charge nothing")
    }

    @Test
    fun `a request larger than the remainder is refused whole`() {
        // Partial fulfilment would let a bot overrun by up to one request, and would make the
        // iteration count — and so the recorded move — depend on the request size.
        val budget = Budget(10)

        assertTrue(budget.tryConsume(7))
        assertFalse(budget.tryConsume(4))
        assertEquals(3, budget.remaining)
        assertTrue(budget.tryConsume(3))
        assertTrue(budget.exhausted)
    }

    @Test
    fun `reset restores the full allowance`() {
        val budget = Budget(5)
        budget.tryConsume(5)

        budget.reset()

        assertEquals(0, budget.consumed)
        assertEquals(5, budget.remaining)
        assertFalse(budget.exhausted)
    }

    @Test
    fun `a zero budget is exhausted from the start`() {
        val budget = Budget(0)

        assertTrue(budget.exhausted)
        assertFalse(budget.tryConsume())
        assertTrue(budget.tryConsume(0), "charging nothing always succeeds")
    }

    @Test
    fun `negative limits and charges are rejected`() {
        assertFailsWith<IllegalArgumentException> { Budget(-1) }
        assertFailsWith<IllegalArgumentException> { Budget(4).tryConsume(-1) }
    }
}
