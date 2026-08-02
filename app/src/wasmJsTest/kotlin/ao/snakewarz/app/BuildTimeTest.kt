package ao.snakewarz.app

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildTimeTest {
    @Test
    fun `the build instant is local numeric time without a zone label`() {
        val instant = localInstant()
        assertEquals("Release · 2026-08-02 14:05:09", formatBuildTime(instant))
    }
}

private fun localInstant(): String = js("new Date(2026, 7, 2, 14, 5, 9).toISOString()")
