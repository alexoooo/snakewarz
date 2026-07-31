package ao.snakewarz.ui.chrome

import ao.snakewarz.ui.model.Screen
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import org.w3c.dom.HTMLButtonElement

/**
 * The screen the page opens on: the modes, and which of them are worth offering.
 *
 * Two of the four buttons are conditional, and neither can be true on a first visit. **Continue**
 * appears once a level has been beaten and starts the highest unlocked one outright, because that is
 * the button somebody came back for — the grid is one press further on for the times they want to
 * replay something. **Watch replay** is offered exactly when a recording is loaded, which is what a
 * shared `#r=` link leaves behind once you have walked back out of it; without one there is nothing
 * on the game screen to return to and the button would open somebody's stale board.
 *
 * The primary styling follows Continue rather than being fixed in the markup: exactly one button on a
 * menu should look like the way on, and which one that is depends on whether there is a game in
 * progress.
 */
internal class HomeScreen(dispatch: (UiIntent) -> Unit) {
    private val continueButton: HTMLButtonElement = elementById("home-continue")
    private val customButton: HTMLButtonElement = elementById("home-custom")
    private val ladderButton: HTMLButtonElement = elementById("home-ladder")
    private val replayButton: HTMLButtonElement = elementById("home-replay")

    /** Which level Continue resumes, read at the press rather than captured when it was rendered. */
    private var resume: Int = FIRST_LEVEL

    init {
        continueButton.addEventListener("click") { dispatch(UiIntent.StartLevel(resume)) }
        customButton.addEventListener("click") { dispatch(UiIntent.Navigate(Screen.GAME)) }
        ladderButton.addEventListener("click") { dispatch(UiIntent.Navigate(Screen.LADDER)) }
        replayButton.addEventListener("click") { dispatch(UiIntent.Navigate(Screen.GAME)) }
    }

    fun render(model: UiModel) {
        val started = model.ladder.started
        resume = model.ladder.highest

        continueButton.hidden = !started
        continueButton.textContent = "Continue — Level $resume"
        ladderButton.className = if (started) LADDER_CLASS else "$LADDER_CLASS primary"

        replayButton.hidden = !model.replay
    }

    override fun toString(): String = "HomeScreen"

    private companion object {
        const val FIRST_LEVEL = 1

        /** What `#home-ladder` is without the primary fill, which Continue takes off it. */
        const val LADDER_CLASS = "wide"
    }
}
