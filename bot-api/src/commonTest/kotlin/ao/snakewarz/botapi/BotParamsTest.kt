package ao.snakewarz.botapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BotParamsTest {
    @Test
    fun `a missing name falls back to the default`() {
        val params = BotParams.EMPTY

        assertEquals(1000, params.int("iterations", 1000))
        assertEquals(0.5, params.double("exploration", 0.5))
        assertEquals(true, params.boolean("reuseTree", true))
        assertEquals("uct", params.string("policy", "uct"))
        assertTrue(params.isEmpty)
    }

    @Test
    fun `a present name is parsed`() {
        val params = BotParams(mapOf("iterations" to "25000", "exploration" to "1.5", "reuseTree" to "false"))

        assertEquals(25000, params.int("iterations", 1000))
        assertEquals(1.5, params.double("exploration", 0.5))
        assertEquals(false, params.boolean("reuseTree", true))
    }

    @Test
    fun `an unparseable value throws rather than silently using the default`() {
        // A typo in a search constant that quietly does nothing is a lost afternoon.
        val params = BotParams(mapOf("iterations" to "lots", "exploration" to "wide", "reuseTree" to "yes"))

        assertFailsWith<IllegalArgumentException> { params.int("iterations", 1) }
        assertFailsWith<IllegalArgumentException> { params.double("exploration", 1.0) }
        assertFailsWith<IllegalArgumentException> { params.boolean("reuseTree", false) }
    }

    @Test
    fun `names iterate in insertion order, never in hash order`() {
        val params = BotParams(linkedMapOf("zeta" to "1", "alpha" to "2", "mu" to "3"))

        assertEquals(listOf("zeta", "alpha", "mu"), params.names.toList())
    }
}
