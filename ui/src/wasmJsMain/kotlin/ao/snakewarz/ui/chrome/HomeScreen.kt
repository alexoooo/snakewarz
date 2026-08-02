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
    private val gauntletButton: HTMLButtonElement = elementById("home-gauntlet")
    private val replayButton: HTMLButtonElement = elementById("home-replay")

    /** Which level Continue resumes, read at the press rather than captured when it was rendered. */
    private var resume: Int = FIRST_LEVEL

    init {
        continueButton.addEventListener("click") { dispatch(UiIntent.StartLevel(resume)) }
        // Custom means "start one", so it replaces the match rather than merely showing the board
        // that is already there. Watch replay below means the opposite — "show me the recording I
        // have" — which is why the two buttons beside each other dispatch different things.
        customButton.addEventListener("click") { dispatch(UiIntent.StartCustom) }
        gauntletButton.addEventListener("click") { dispatch(UiIntent.Navigate(Screen.GAUNTLET)) }
        replayButton.addEventListener("click") { dispatch(UiIntent.Navigate(Screen.GAME)) }
    }

    fun render(model: UiModel) {
        val started = model.gauntlet.started
        resume = model.gauntlet.highest

        continueButton.hidden = !started
        continueButton.textContent = continueLabel(resume)
        gauntletButton.textContent = campaignLabel(started)
        gauntletButton.className = if (started) GAUNTLET_CLASS else "$GAUNTLET_CLASS primary"

        replayButton.hidden = !model.replay
    }

    override fun toString(): String = "HomeScreen"

    private companion object {
        const val FIRST_LEVEL = 1

        /** What `#home-gauntlet` is without the primary fill, which Continue takes off it. */
        const val GAUNTLET_CLASS = "wide"
    }
}

internal fun campaignLabel(started: Boolean): String = if (started) "Gauntlet" else "New Game"

internal fun continueLabel(level: Int): String = "Continue — Level $level"
