package ao.snakewarz.ui.chrome

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.gauntlet.Gauntlet
import ao.snakewarz.match.stats.SlotStats
import ao.snakewarz.ui.chrome.panel.SettingsPanel
import ao.snakewarz.ui.chrome.panel.SetupPanel
import ao.snakewarz.ui.chrome.panel.SharePanel
import ao.snakewarz.ui.chrome.panel.TournamentPanel
import ao.snakewarz.ui.model.MatchOptions
import ao.snakewarz.ui.model.Portraits
import ao.snakewarz.ui.model.TournamentOptions
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import ao.snakewarz.ui.render.Theme
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * The DOM half of the interface: reads it, writes it, and turns everything the player does into a
 * [UiIntent].
 *
 * The page skeleton is static in `index.html`, so the first paint happens while the wasm module is
 * still compiling and nothing in `:ui` builds structure — elements are looked up by id once and then
 * only ever written to. The two exceptions both come off the registry and both live behind
 * `#panel-setup`; see [SetupPanel].
 *
 * What is *here* is the game screen: the board, the transport, the scrub row, the status line, the
 * seat cards and the keyboard. Which screen is showing and what is layered over it belongs to
 * [Shell], the modes on offer to [HomeScreen], the level select to [GauntletScreen], and everything
 * dense to the four panels — all of them rendered from the same model on the way through [render].
 */
internal class Chrome(
    registry: BotRegistry,
    portraits: Portraits,
    private val dispatch: (UiIntent) -> Unit,
) {
    private val shell = Shell(dispatch)
    private val home = HomeScreen(dispatch)
    private val gauntlet = GauntletScreen(registry, portraits, dispatch)
    private val setupPanel = SetupPanel(registry, dispatch)
    private val tournamentPanel = TournamentPanel(dispatch)
    private val sharePanel = SharePanel(dispatch)
    private val settingsPanel = SettingsPanel(dispatch)

    val canvas: HTMLCanvasElement = elementById("board")
    val overlay: HTMLCanvasElement = elementById("board-overlay")

    /** Owns the press and the release; the move between them is forked on its [PathInput.pressing]. */
    private val path = PathInput(canvas, dispatch)

    private val wordmark: HTMLElement = elementById("wordmark")
    private val boardWrap: HTMLElement = elementById("board-wrap")
    private val tip: HTMLElement = elementById("board-tip")
    private val tipWho: HTMLElement = tip.child(".tip-who")
    private val tipDetail: HTMLElement = tip.child(".tip-detail")

    private val playButton: HTMLButtonElement = elementById("play")
    private val stepButton: HTMLButtonElement = elementById("step")
    private val restartButton: HTMLButtonElement = elementById("restart")

    private val scrub: HTMLElement = elementById("scrub")
    private val seekSlider: HTMLInputElement = elementById("seek")
    private val seekValue: HTMLElement = elementById("seek-value")

    private val status: HTMLElement = elementById("status")
    private val rows: List<SlotRow> = List(SetupPanel.SEATS) { SlotRow(elementById("slot-$it")) }

    private val repeat = KeyRepeat { direction -> dispatch(UiIntent.Steer(direction)) }

    /** Where the pointer last was, so [placeTip] can run again once the label has a width. */
    private var tipX: Double = 0.0
    private var tipY: Double = 0.0

    init {
        playButton.addEventListener("click") { dispatch(UiIntent.TogglePlay) }
        stepButton.addEventListener("click") { dispatch(UiIntent.StepOnce) }
        restartButton.addEventListener("click") { dispatch(UiIntent.Restart) }

        seekSlider.addEventListener("input") {
            dispatch(UiIntent.SeekTo(seekSlider.value.toIntOrNull() ?: 0))
        }

        // On the canvas rather than on the window, because the board is the thing being asked
        // about. The overlay above it declines the pointer in CSS, so the question still lands here.
        //
        // One move, two meanings: a pointer held down on the board is drawing a route and one that
        // is not is asking what is under it. The label is placed either way -- it costs nothing, and
        // a press that took hold of nothing is answered as a hover, so it has to keep following.
        canvas.addEventListener("pointermove") { event ->
            val pointer = event as MouseEvent
            tipX = pointer.clientX.toDouble()
            tipY = pointer.clientY.toDouble()
            placeTip()
            dispatch(if (path.pressing) UiIntent.PathDragged(tipX, tipY) else UiIntent.Hover(tipX, tipY))
        }
        canvas.addEventListener("pointerleave") { dispatch(UiIntent.HoverEnded) }
        canvas.addEventListener("pointercancel") { dispatch(UiIntent.HoverEnded) }

        window.addEventListener("keydown") { event -> onKeyDown(event as KeyboardEvent) }
        window.addEventListener("keyup") { event -> onKeyUp(event as KeyboardEvent) }

        // A key let go of while the page is not looking never reports it, so a snake that kept
        // moving because somebody alt-tabbed would have nothing on screen to explain it.
        window.addEventListener("blur") { repeat.cancel() }
    }

    /** Where the speed slider is now, so the scheduler and the label agree from the first frame. */
    fun turnsPerSecond(): Double = settingsPanel.turnsPerSecond()

    fun readOptions(): MatchOptions = setupPanel.readOptions()

    /** Draws a fresh seed into the form, so that the next [readOptions] is a game nobody has played. */
    fun reseed(): Unit = setupPanel.reseed()

    /**
     * The two forms, read as one: the field comes off the seats and the schedule off the tournament
     * panel, which is why neither of them owns the whole answer.
     */
    fun readTournamentOptions(): TournamentOptions =
        setupPanel.readTournamentOptions(tournamentPanel.rounds, tournamentPanel.format)

    fun applySetup(setup: MatchSetup): Unit = setupPanel.applySetup(setup)

    fun copyShareUrl(): Unit = sharePanel.copyShareUrl()

    fun render(model: UiModel) {
        // Ahead of the shell, and that ordering is load-bearing: the level select marks the tile it
        // would open `[data-focus]`, and the shell takes the focus to it on the very frame the
        // screen arrives. Render them the other way round and arriving on the gauntlet by keyboard
        // lands on whichever tile was open last time.
        gauntlet.render(model)

        shell.render(model)
        home.render(model)
        setupPanel.render(model)
        tournamentPanel.render(model)
        sharePanel.render(model)
        settingsPanel.render(model)

        // A key held down when a panel opened has no keyup coming that this will hear about — the
        // same hole `blur` covers — so the snake would keep going behind the panel.
        if (!shell.boardHasKeys) {
            repeat.cancel()
        }

        playButton.textContent = if (model.running) "Pause" else "Play"

        // A level says which one it is, because the board and the seat cards cannot: every rung is
        // two snakes on a rectangle, and the title is the only thing that tells them apart. A custom
        // match is named by its seats already, so the bar goes back to naming the game. One line
        // either way — the bar's height is what the board's track is measured against.
        wordmark.textContent = model.level?.let { "Level $it — ${Gauntlet.levelAt(it).title}" } ?: GAME_NAME

        // Rounds rather than turns, and the difference is whose count it is. A turn is one snake
        // moving, so a four-way match counts four of them per move and the number means nothing to
        // anybody watching; a round is a move by everyone still going, which is what a player sees
        // happen. The seek readout below still says Turn, because a scrub position really is one.
        status.textContent = "Round ${model.stats.rounds} · ${model.status}"

        // A match with a person in it advances on their key and on nothing else, so there is no
        // clock here to start or step. A running batch owns the board for the same reason: there is
        // one match on screen and the tournament is the thing driving it. Greyed rather than hidden
        // in both cases -- the buttons come back, and a control that moves is worse than one that dims.
        val noTransport = model.interactive || model.batchRunning
        playButton.disabled = noTransport
        stepButton.disabled = noTransport
        restartButton.disabled = model.batchRunning

        scrub.hidden = !model.replay
        if (model.replay) {
            seekSlider.max = model.turnCount.toString()
            seekSlider.value = model.turnIndex.toString()
            seekValue.textContent = "${model.turnIndex} / ${model.turnCount}"
        }

        for (slot in rows.indices) {
            rows[slot].render(
                state = model.stats.slots.getOrNull(slot),
                name = model.labels[slot],
                face = model.portraits[slot],
                theme = model.theme,
            )
        }

        val hover = model.hover
        tip.hidden = hover == null
        if (hover != null) {
            tipWho.textContent = hover.who
            tipDetail.textContent = hover.detail
            // Now that it is visible and says what it says, it has a width to be kept inside by.
            placeTip()
        }
    }

    override fun toString(): String = "Chrome($shell)"

    // -- internals

    /**
     * Puts the hover label beside the pointer, inside the board panel on all four sides.
     *
     * Placed straight out of the pointer move rather than only through [render], and deliberately:
     * *where* a label sits is pointer state, like which option a picker is showing, and routing it
     * through a once-a-frame model would leave it trailing the thing it points at. What the label
     * *says* is match state and does come down through [render] — which then places it a second
     * time, because a label that is still `hidden` measures zero wide and would clamp against
     * nothing on the very move that reveals it.
     */
    private fun placeTip() {
        val box = boardWrap.getBoundingClientRect()
        val left = (tipX - box.left + TIP_GAP)
            .coerceAtMost(box.width - tip.offsetWidth - TIP_GAP)
            .coerceAtLeast(0.0)

        // Flipped above the pointer rather than squeezed against the bottom edge, so a label near
        // the foot of the board does not end up under the square it is describing.
        val below = tipY - box.top + TIP_GAP
        val top = if (below + tip.offsetHeight > box.height) below - tip.offsetHeight - 2 * TIP_GAP else below

        tip.style.left = "${left}px"
        tip.style.top = "${top.coerceAtLeast(0.0)}px"
    }

    /**
     * Every key the *board* answers to, cancelled first and acted on second.
     *
     * Enter and Escape are deliberately absent. Enter is what a native `<button>` already does with
     * the focus it is given, which is the whole reason nothing on this page is a custom widget; and
     * Escape belongs to [Shell], which is the only thing that knows what is in front of the board.
     *
     * Auto-repeat is the keyboard talking, not the player: its rate is a text-editing rate, and a
     * different one on every machine, so a held key is repeated by [KeyRepeat] instead. But dropping
     * those events is a statement about how fast the *snake* moves and says nothing about what the
     * browser may do with them — so `preventDefault` comes first and the repeat guard second. The
     * other way round, holding an arrow key steered at our rate and scrolled the page at the
     * keyboard's.
     */
    private fun onKeyDown(event: KeyboardEvent) {
        // A screen with no board on it, or a panel over the one there is, and these keys are not the
        // game's: a panel is full of buttons, and the space bar activating the focused one is what
        // pressing it there means.
        if (!shell.boardHasKeys) {
            return
        }
        // Arrows belong to a focused select or slider while it has the focus, not to the snake.
        if (document.activeElement?.tagName in EDITABLE_TAGS) {
            return
        }
        // Nor does a shortcut. `key` is still "a" under Ctrl and "ArrowLeft" under Alt, so without
        // this the page steers on select-all and swallows Back — and now that a steer key is
        // cancelled on every event rather than only the first, it would swallow them for good.
        if (event.ctrlKey || event.altKey || event.metaKey) {
            return
        }

        val steer = steerFor(event.key)
        if (steer != null) {
            event.preventDefault()
            if (!event.repeat) {
                repeat.press(steer)
            }
            return
        }

        when (event.key) {
            " ", "Spacebar" -> {
                event.preventDefault()
                if (!event.repeat) {
                    dispatch(UiIntent.TogglePlay)
                }
            }

            // Not cancelled: a full stop is not a scroll key, and nothing else on the page wants it.
            "." -> if (!event.repeat) dispatch(UiIntent.StepOnce)
        }
    }

    /**
     * Deliberately without the focus guard [onKeyDown] has: if the focus moves mid-hold, the key
     * still has to be able to stop, and a release that stops nothing costs nothing.
     */
    private fun onKeyUp(event: KeyboardEvent) {
        steerFor(event.key)?.let(repeat::release)
    }

    private fun steerFor(key: String): Direction? = when (key) {
        "ArrowUp", "w", "W" -> Direction.NORTH
        "ArrowDown", "s", "S" -> Direction.SOUTH
        "ArrowLeft", "a", "A" -> Direction.WEST
        "ArrowRight", "d", "D" -> Direction.EAST
        else -> null
    }

    private class SlotRow(private val root: HTMLElement) {
        private val portrait: HTMLImageElement = root.child(".portrait")
        private val swatch: HTMLElement = root.child(".swatch")
        private val who: HTMLElement = root.child(".who")
        private val length: HTMLElement = root.child(".length")
        private val fate: HTMLElement = root.child(".fate")

        /**
         * The swatch takes its colour from [theme] and not from a global, because a theme can move
         * a trail hue — and a swatch painted from a global would keep the old one until whatever
         * else happened to redraw the card, which is intermittent and reads as nothing at all.
         *
         * The [face] beside it stays decoration: it is `aria-hidden` in the markup and sits next to
         * the seat's name in text, so a reader hears "PUCT - 1k/territory" and not "image". The
         * swatch is what actually ties the card to a trail on the board, which is why a portrait does
         * not replace it — most of them carry no seat colour at all.
         */
        fun render(state: SlotStats?, name: String, face: String?, theme: Theme) {
            if (state == null) {
                root.hidden = true
                return
            }

            root.hidden = false
            root.className = if (state.alive) "slot" else "slot out"
            portrait.showPortrait(face)
            swatch.style.backgroundColor = theme.body(state.slot.index)
            who.textContent = name
            length.textContent = state.length.toString()
            fate.textContent = when {
                state.winner -> "winner"
                !state.alive -> fateText(state.fate)
                else -> ""
            }
        }
    }

    private companion object {
        /** How a snake left, in plain words. The engine's vocabulary stops here. */
        fun fateText(reason: EliminationReason?): String = when (reason) {
            EliminationReason.TRAPPED -> "trapped"
            EliminationReason.SUICIDE -> "crashed"
            EliminationReason.RESIGNED -> "resigned"
            EliminationReason.FORFEIT -> "forfeited"
            null -> ""
        }

        val EDITABLE_TAGS = setOf("INPUT", "SELECT", "TEXTAREA")

        /** What the top bar reads when the match on it is nobody's level. */
        const val GAME_NAME = "Snake Warz"

        /** How far the hover label sits from the pointer, so the pointer never covers it. */
        const val TIP_GAP = 14.0
    }
}
