package ao.snakewarz.ui.chrome

import ao.snakewarz.ui.model.Mode
import ao.snakewarz.ui.model.Panel
import ao.snakewarz.ui.model.Screen
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Which screen is showing, what is layered over it, and where the focus is.
 *
 * The page is three static sections and four static panels; this shows one section, at most one
 * overlay, and hides the rest. Hidden rather than moved out of sight, because `[hidden]` is what
 * takes a control out of the tab order — an off-screen but focusable screen means Tab walks into
 * nothing. The same attribute is how [Mode] gates a panel: the button that opens it goes, so a
 * control that could never apply is not on the bar rather than sitting there greyed.
 *
 * ### One overlay, and everything behind it is inert
 *
 * A panel covers the board and the result dialog covers everything, so at most one of them is
 * reachable at a time. `inert` on everything that is *not* the overlay on top is the whole of the
 * focus trap: with those subtrees inert and the unopened panels hidden, the only focusable elements
 * left in the document are the ones inside the overlay, so Tab cycles within it without a key
 * handler counting elements.
 *
 * That set is [behind] rather than `#app` alone, and the difference is the case where a panel was
 * still open when the match ended. The panels are siblings of `#app` in the markup — which is what
 * lets one attribute put a whole screen out of reach — so the board going inert says nothing about
 * the panel beside the card.
 *
 * Opening an overlay remembers what had the focus and takes the focus to the overlay's default
 * action; closing it hands the focus back. Without the hand-back, closing a panel drops the focus on
 * `<body>` and the next Tab starts over at the top of the page. **Navigating moves the focus too**,
 * to the section that arrived — the control that navigated is on the screen that just left, so the
 * focus would otherwise be on something no longer in the document's tab order.
 *
 * ### Enter, and why nothing here is a custom widget
 *
 * Every control on the page is a native `<button>`, `<select>` or `<input>`, so Enter activating the
 * focused one is the platform's and costs no code. What this class owes that arrangement is that the
 * focus lands somewhere worth pressing: a panel opens on itself and leaves Tab pointed at its first
 * control, and the result card opens on its `[data-focus]` default action, which is what makes a
 * lost match cost one key.
 *
 * ### Escape, and what "back" means
 *
 * Escape closes the overlay on top; with nothing open it goes back a screen. **Back is one screen
 * out and not one screen home:** a rung of the gauntlet is left to the level select, so that walking
 * out of level 7 does not cost the ten tiles as well. The bar's button, the verdict card's way out
 * and Escape are the same call, which is what stops the three offering different destinations from
 * one board. `ClosePanel` is one intent rather than two for the parallel reason [UiIntent.TogglePlay]
 * is: *which* overlay is on top is the session's to know.
 */
internal class Shell(private val dispatch: (UiIntent) -> Unit) {
    private val app: HTMLElement = elementById("app")
    private val scrim: HTMLElement = elementById("panel-scrim")

    private val dialog: HTMLElement = elementById("dialog-result")
    private val dialogPortrait: HTMLImageElement = elementById("result-portrait")
    private val dialogTitle: HTMLElement = elementById("result-title")
    private val dialogDetail: HTMLElement = elementById("result-detail")
    private val dialogAgain: HTMLButtonElement = elementById("result-again")
    private val dialogNext: HTMLButtonElement = elementById("result-next")
    private val dialogReplay: HTMLButtonElement = elementById("result-replay")

    private val intro: HTMLElement = elementById("gauntlet-intro")
    private val introPortrait: HTMLImageElement = elementById("intro-portrait")
    private val introName: HTMLElement = elementById("intro-name")
    private val introTitle: HTMLElement = elementById("intro-title")

    private val replayActions: HTMLElement = elementById("replay-actions")
    private val replayAgain: HTMLButtonElement = elementById("replay-again")
    private val replayNext: HTMLButtonElement = elementById("replay-next")

    /** The way off the board, which is the level select while a rung of the gauntlet is on it. */
    private val backButton: HTMLButtonElement = elementById("game-back")

    /** The rung Next level starts, read at the press for the reason `HomeScreen.resume` is. */
    private var nextLevel: Int? = null

    /** The rung offered beside the replay transport, read when the button is pressed. */
    private var replayNextLevel: Int? = null

    /**
     * The rung on the board, or `null` for a match somebody configured.
     *
     * Kept for [nextLevel]'s reason — read at the press rather than captured at render — and it is
     * what [back] is: walking out of level 7 goes to the level select, not to the front page.
     */
    private var level: Int? = null

    private val screens: List<Pair<Screen, HTMLElement>> =
        Screen.entries.map { it to elementById<HTMLElement>(sectionIdOf(it)) }

    private val panels: List<Pair<Panel, HTMLElement>> =
        Panel.entries.map { it to elementById<HTMLElement>(panelIdOf(it)) }

    /**
     * The button on the top bar that opens each panel, kept rather than merely listened to.
     *
     * Which panels a mode offers is answered by hiding these — a gauntlet level *is* its
     * configuration, so Setup and Tournament are not controls that happen to do nothing there, they
     * are controls that should not be on the bar at all.
     */
    private val openers: List<Pair<Panel, HTMLButtonElement>> =
        Panel.entries.map { it to elementById<HTMLButtonElement>(openerIdOf(it)) }

    /**
     * Everything an overlay can be in front of, which is what [settle] marks `inert`.
     *
     * `#app` alone is not it. The panels are *siblings* of `#app` rather than children — which is
     * what lets one attribute put the whole board out of reach — and that is exactly why a panel
     * still open when the match ends is left beside the result card, visible and still a tab stop,
     * while the card claims `aria-modal`. Marking everything that is not the thing in front is the
     * rule, so it is written as one list rather than as one element that happens to be enough.
     */
    private val behind: List<HTMLElement> = listOf(app, dialog) + panels.map { it.second }

    /** The overlay on screen, so the focus moves when one opens rather than on every frame. */
    private var overlay: HTMLElement? = null

    /** What had the focus when the overlay opened, so closing it can give the focus back. */
    private var opener: HTMLElement? = null

    private var screen: Screen = Screen.HOME

    init {
        for ((panel, opener) in openers) {
            opener.addEventListener("click") { dispatch(UiIntent.OpenPanel(panel)) }
            elementById<HTMLButtonElement>(closerIdOf(panel))
                .addEventListener("click") { dispatch(UiIntent.ClosePanel) }
        }

        // Clicking the board a panel is covering means "put it away", which is what the dimmed
        // backdrop is there to invite.
        scrim.addEventListener("click") { dispatch(UiIntent.ClosePanel) }

        backButton.addEventListener("click") { back() }
        elementById<HTMLButtonElement>("gauntlet-back").addEventListener("click") { back() }

        dialogAgain.addEventListener("click") { dispatch(UiIntent.Restart) }
        dialogNext.addEventListener("click") { nextLevel?.let { dispatch(UiIntent.StartLevel(it)) } }
        dialogReplay.addEventListener("click") { dispatch(UiIntent.WatchReplay) }
        replayAgain.addEventListener("click") { dispatch(UiIntent.TryAgain) }
        replayNext.addEventListener("click") { replayNextLevel?.let { dispatch(UiIntent.StartLevel(it)) } }
        // The same call as the bar's own way out, so the card cannot offer a different destination
        // from the button behind it.
        elementById<HTMLButtonElement>("result-home").addEventListener("click") { back() }

        window.addEventListener("keydown") { event -> onKeyDown(event as KeyboardEvent) }
    }

    /**
     * Whether the arrow keys, the space bar and the step key belong to the board.
     *
     * They do not while an overlay is up: a panel is full of buttons, and the space bar activating
     * the focused one is what a person pressing it expects. Nor do they on a screen with no board on
     * it. `Chrome` asks this before anything else in its keydown, which is why its own two guards —
     * a focused text field, and a modifier held — are about what happens *on* the game screen.
     */
    val boardHasKeys: Boolean get() = screen == Screen.GAME && overlay == null

    fun render(model: UiModel) {
        val navigated = model.screen != screen
        screen = model.screen
        level = model.level
        // The label follows the destination rather than being written once, because they are one
        // decision: a rung is backed out of to the level select and a custom match to the menu.
        backButton.textContent = if (model.level == null) "$BACK_ARROW Home" else "$BACK_ARROW Gauntlet"
        for ((which, section) in screens) {
            section.hidden = which != model.screen
        }
        for ((which, panel) in panels) {
            panel.hidden = which != model.openPanel
        }
        for ((which, opener) in openers) {
            opener.hidden = !model.mode.offers(which)
        }
        renderReplayActions(model)

        val result = model.result
        dialog.hidden = result == null
        if (result != null) {
            // The face of whoever won, which a draw does not have — so the card is laid out to read
            // with or without one rather than keeping a space for it.
            dialogPortrait.showPortrait(model.resultPortrait)
            dialogTitle.textContent = result
            dialogDetail.textContent = model.status
            renderResultActions(model)
        }

        val introduction = model.intro
        intro.hidden = introduction == null
        if (introduction != null) {
            introPortrait.showPortrait(introduction.portrait)
            introName.textContent = introduction.name
            introTitle.textContent = introduction.title
        }

        // A first-entry presentation is above every other layer and blocks every form of input.
        val wanted = when {
            introduction != null -> intro
            result != null -> dialog
            else -> model.openPanel?.let { panel -> panels.first { it.first == panel }.second }
        }
        scrim.hidden = wanted == null || wanted === dialog
        settle(wanted)

        // Last, so it wins over the hand-back in [settle]: navigating away from a screen with an
        // overlay on it would otherwise hand the focus to a control that has just been hidden. The
        // control that navigated is on the screen that just left either way, so without this the
        // focus falls to <body> and the next Tab starts in the browser's own chrome rather than in
        // the page.
        if (navigated) {
            focusInto(screens.first { it.first == model.screen }.second)
        }
    }

    override fun toString(): String = "Shell($screen${overlay?.let { ", ${it.id}" } ?: ""})"

    // -- internals

    /**
     * What the verdict offers: one of three ways on, and the way back over what just happened.
     *
     * A **lost** level is retried — one key, unlimited lives, and `Restart` on a level draws a fresh
     * seed rather than the same board. A **beaten** one is moved on from, because replaying a level
     * you have just cleared is not what anybody wants next. The **last** rung has neither: there is
     * nothing above it, and the verdict itself says the gauntlet is finished. A custom match is the
     * first of those and always has been: Play again, and Home.
     *
     * Watch replay sits under all of them rather than among them, and it is offered on every verdict
     * this card is ever up for: the card is only shown on a finished match of the player's own, which
     * is exactly the match a recording can be taken of.
     *
     * Run *before* [settle] takes the focus, because which button is showing is what decides where
     * the focus lands — every action carries `[data-focus]` and [focusInto] takes the first one a
     * person could actually press.
     */
    private fun renderResultActions(model: UiModel) {
        nextLevel = model.nextLevel

        dialogNext.hidden = model.nextLevel == null
        dialogAgain.hidden = model.levelCleared
        dialogAgain.textContent = if (model.level == null) "Play again" else "Retry"
        // A second way to the recording of the match just finished, off the same flag `#panel-share`
        // reads: winning a level and then hunting through a panel for the run is what this is for.
        dialogReplay.hidden = !model.canWatchReplay
    }

    /** Keeps the ways out of playback ahead of the controls that only move through it. */
    private fun renderReplayActions(model: UiModel) {
        replayNextLevel = model.replayNextLevel
        replayAgain.hidden = !model.canTryAgain
        replayNext.hidden = model.replayNextLevel == null
        replayActions.hidden = !model.replay || (!model.canTryAgain && model.replayNextLevel == null)
    }

    /**
     * Shows [wanted] and nothing else, moving the focus only when the overlay actually changed.
     *
     * Guarded on identity rather than run every frame, because taking the focus is not idempotent
     * from the player's point of view: doing it once a frame would fight anything they tabbed to.
     */
    private fun settle(wanted: HTMLElement?) {
        if (wanted === overlay) {
            return
        }

        if (overlay == null) {
            opener = document.activeElement as? HTMLElement
        }
        overlay = wanted

        if (wanted == null) {
            behind.forEach { it.removeAttribute(INERT) }
            opener?.focus()
            opener = null
            return
        }

        for (element in behind) {
            if (element === wanted) {
                element.removeAttribute(INERT)
            } else {
                element.setAttribute(INERT, "")
            }
        }
        focusInto(wanted)
    }

    /**
     * Takes the focus to [container]'s default action, or to the container itself where it names
     * none — which is why every screen and every panel carries `tabindex="-1"`.
     *
     * Landing on the container announces it and leaves Tab to reach its controls in order, which is
     * right for a form; a dialog names its default action instead, because there is one thing you
     * came to it to press.
     *
     * **The *first one a person could press*, and not simply the first.** A container is allowed to
     * mark more than one, because which of them is the default can depend on what just happened: the
     * verdict marks its three actions and shows one or two, and the level select marks the tile it
     * would open. Focusing a hidden or disabled element silently does nothing, which would leave the
     * focus on a screen that has just been hidden.
     */
    private fun focusInto(container: HTMLElement) {
        val offered = container.querySelectorAll("[$FOCUS]")
        for (index in 0 until offered.length) {
            val candidate = offered.item(index) as? HTMLElement ?: continue
            if (!candidate.hidden && (candidate as? HTMLButtonElement)?.disabled != true) {
                candidate.focus()
                return
            }
        }
        container.focus()
    }

    /**
     * One screen out: the level select from a rung of the gauntlet, and the menu from anything else.
     *
     * The bar's button, the verdict card's Home button and Escape are all this one call, which is
     * what stops the three offering different ways out of the same board. The level select itself
     * goes to the menu whatever is remembered about the board behind it — [level] outlives the
     * screen it was chosen on, so the screen has to be part of the question.
     */
    private fun back() {
        if (screen == Screen.HOME) {
            return
        }
        val target = if (screen == Screen.GAME && level != null) Screen.GAUNTLET else Screen.HOME
        dispatch(UiIntent.Navigate(target))
    }

    private fun onKeyDown(event: KeyboardEvent) {
        if (event.key != "Escape" || event.ctrlKey || event.altKey || event.metaKey) {
            return
        }
        if (overlay === intro) {
            event.preventDefault()
            return
        }
        if (overlay != null) {
            event.preventDefault()
            dispatch(UiIntent.ClosePanel)
            return
        }
        if (screen != Screen.HOME) {
            event.preventDefault()
            back()
        }
    }

    private companion object {
        /**
         * Set as an attribute because the typed DOM bindings do not carry it.
         *
         * It is what makes a whole subtree unreachable in one write — unfocusable, unclickable and
         * hidden from assistive technology — rather than a walk over every control inside it.
         */
        const val INERT = "inert"

        /** What a screen or an overlay marks the control it would rather the focus landed on. */
        const val FOCUS = "data-focus"

        /** The way-out button's arrow, which the destination beside it is written against. */
        const val BACK_ARROW = "←"

        /**
         * The `when`s below are exhaustive over their enums with no `else`, so a screen or a panel
         * added to the model without an id here is a compile error rather than an `elementById` that
         * fails at boot.
         */
        fun sectionIdOf(screen: Screen): String = when (screen) {
            Screen.HOME -> "screen-home"
            Screen.GAUNTLET -> "screen-gauntlet"
            Screen.GAME -> "screen-game"
        }

        fun panelIdOf(panel: Panel): String = when (panel) {
            Panel.SETUP -> "panel-setup"
            Panel.TOURNAMENT -> "panel-tournament"
            Panel.SHARE -> "panel-share"
            Panel.SETTINGS -> "panel-settings"
        }

        fun openerIdOf(panel: Panel): String = "open-" + panelIdOf(panel).removePrefix("panel-")

        fun closerIdOf(panel: Panel): String = "close-" + panelIdOf(panel).removePrefix("panel-")
    }
}
