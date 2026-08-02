package ao.snakewarz.ui.render

import ao.snakewarz.match.gauntlet.Gauntlet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GauntletVisualTest {
    @Test
    fun `every frozen level has exactly one visual`() {
        assertEquals((1..Gauntlet.size).toList(), GauntletVisual.ALL.map { it.index })
        assertEquals(Gauntlet.size, GauntletVisual.ALL.map { it.stageId }.toSet().size)
        assertEquals(Gauntlet.size, GauntletVisual.ALL.map { it.portraitKey }.toSet().size)
    }

    @Test
    fun `the boss is not alpha beta in disguise`() {
        val boss = GauntletVisual.at(Gauntlet.size) ?: error("the final level has no visual")
        assertEquals("gauntlet-final-boss", boss.portraitKey)
        assertNotEquals("alphabeta", boss.portraitKey)
    }
}
