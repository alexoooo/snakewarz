package ao.snakewarz.ui

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.human.PlayableRegistry
import ao.snakewarz.ui.model.Portraits
import ao.snakewarz.ui.model.SlotPortraits
import ao.snakewarz.ui.render.GauntletVisual
import ao.snakewarz.ui.render.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResultPortraitTest {
    @Test
    fun `Gauntlet results show the defeated rival on a win and regular rival on a loss`() {
        val faces = faces(level = 1)

        assertEquals("portrait/gauntlet-hunter-defeated.webp", resultPortrait(SnakeId(0), 1, faces, ART))
        assertEquals("portrait/gauntlet-hunter.webp", resultPortrait(SnakeId(1), 1, faces, ART))
    }

    @Test
    fun `draws and custom human wins stay portrait free`() {
        val faces = faces(level = null)

        assertNull(resultPortrait(SnakeId.NONE, null, faces, ART))
        assertNull(resultPortrait(SnakeId(0), null, faces, ART))
    }

    private fun faces(level: Int?): SlotPortraits = SlotPortraits(SETUP, ART, THEME, level)

    private companion object {
        val SETUP = MatchSetup.create(
            rows = 8,
            cols = 8,
            slots = listOf(PlayableRegistry.HUMAN_ID, BotId("wallhug")),
            seed = 1,
        )
        val THEME = Theme.of(Theme.DEFAULT_ID, dark = false)
        val ART = Portraits { key ->
            when (key) {
                "wallhug",
                GauntletVisual.at(1)?.portraitKey,
                GauntletVisual.at(1)?.defeatedPortraitKey,
                -> "portrait/$key.webp"

                else -> null
            }
        }
    }
}
