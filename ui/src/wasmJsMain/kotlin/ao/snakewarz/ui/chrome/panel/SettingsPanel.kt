package ao.snakewarz.ui.chrome.panel

import ao.snakewarz.ui.chrome.elementById
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import ao.snakewarz.ui.render.Theme
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * `#panel-settings`: how the game is played and how it looks, rather than what is being played.
 *
 * Speed is here and not in the bottom bar because it is a preference — set once and left — while the
 * bar is the transport, which is pressed every few seconds. It also buys the bar a row on a phone,
 * and a row of the bar is a row of the board.
 *
 * The two controls hand their answer over differently, and the difference is who owns the value.
 * The **speed** is form state, like which bot a picker is showing: this writes the label beside the
 * thumb itself, because routing it through a once-a-frame model would leave the number trailing the
 * thumb that produced it. The **theme** is app state — it is stored, it is resolved against the
 * system's light or dark, and it colours a board this panel cannot see — so the picker only says
 * what was chosen and the session says what is in force.
 *
 * The theme options are static markup, like the map shapes and unlike the bot pickers: [Theme.ALL]
 * is not a registry, there is no "fork, add a file, register it" workflow behind a colour scheme,
 * and three of them is not a third exception to *"Kotlin never constructs structure"*.
 */
internal class SettingsPanel(dispatch: (UiIntent) -> Unit) {
    private val themeSelect: HTMLSelectElement = elementById("theme")
    private val speedSlider: HTMLInputElement = elementById("speed")
    private val speedValue: HTMLElement = elementById("speed-value")

    init {
        // Looked up by id so a theme the markup forgot fails at boot with its own name, rather than
        // becoming a picker that quietly offers two of three.
        for (id in Theme.ALL) {
            themeSelect.querySelector("option[value='$id']")
                ?: error("the page skeleton is missing a theme option for $id")
        }
        themeSelect.addEventListener("change") { dispatch(UiIntent.SetTheme(themeSelect.value)) }

        speedValue.textContent = speedLabel()
        speedSlider.addEventListener("input") {
            speedValue.textContent = speedLabel()
            dispatch(UiIntent.SetSpeed(turnsPerSecond()))
        }
    }

    /**
     * Points the picker at the theme actually in force.
     *
     * Which is not always the one it was left on: a stored choice arrives before this panel is ever
     * opened, and an id nothing offers has already fallen back to the default by the time it gets
     * here.
     */
    fun render(model: UiModel) {
        themeSelect.value = model.theme.id
    }

    /** Where the speed slider is now, so the scheduler and the label agree from the first frame. */
    fun turnsPerSecond(): Double {
        val index = speedSlider.value.toIntOrNull() ?: DEFAULT_SPEED_INDEX
        return SPEEDS[index.coerceIn(0, SPEEDS.size - 1)]
    }

    override fun toString(): String = "SettingsPanel(${themeSelect.value}, ${speedLabel()})"

    // -- internals

    private fun speedLabel(): String {
        val speed = turnsPerSecond()
        return if (speed < 10) "${speed.toInt()} turn/s" else "${speed.toInt()} turns/s"
    }

    private companion object {
        /** Must line up with the `max` on `#speed` in index.html. */
        val SPEEDS = doubleArrayOf(1.0, 2.0, 4.0, 8.0, 12.0, 20.0, 40.0, 80.0)

        /** Twelve turns a second: on a two-snake board that is six moves a second each. */
        const val DEFAULT_SPEED_INDEX = 4
    }
}
