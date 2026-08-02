package ao.snakewarz.ui.chrome

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeScreenTest {
    @Test
    fun `the first campaign action is a new game`() {
        assertEquals("New Game", campaignLabel(started = false))
    }

    @Test
    fun `a returning player gets continue and the secondary gauntlet`() {
        assertEquals("Continue — Level 4", continueLabel(4))
        assertEquals("Gauntlet", campaignLabel(started = true))
    }
}
