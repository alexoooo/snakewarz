package ao.snakewarz.ui.chrome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplayKeysTest {
    @Test
    fun `left and A step backward while right and D step forward`() {
        for (key in listOf("ArrowLeft", "a", "A")) {
            assertEquals(-1, replayStepFor(key), key)
        }
        for (key in listOf("ArrowRight", "d", "D")) {
            assertEquals(1, replayStepFor(key), key)
        }
    }

    @Test
    fun `vertical movement keys remain outside replay stepping`() {
        for (key in listOf("ArrowUp", "ArrowDown", "w", "W", "s", "S")) {
            assertNull(replayStepFor(key), key)
        }
    }
}
