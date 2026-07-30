package ao.snakewarz.ui.chrome

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.human.PlayableRegistry
import ao.snakewarz.match.stats.SlotStats
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentFormat
import ao.snakewarz.ui.model.MatchOptions
import ao.snakewarz.ui.model.TournamentOptions
import ao.snakewarz.ui.model.TournamentStatus
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import ao.snakewarz.ui.render.Palette
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * The DOM half of the interface: reads it, writes it, and turns everything the player does into a
 * [UiIntent].
 *
 * The page skeleton is static in `index.html`, so the first paint happens while the wasm module is
 * still compiling and this class never builds structure — it looks elements up by id once and then
 * only ever writes text, values and hidden flags. The one exception is the `<option>` list of each
 * bot picker, and it is a deliberate one: hard-coding the bots in HTML would make "fork, add a file,
 * register it, open a PR" into "and also edit the markup", which is the workflow this whole project
 * exists to keep cheap. Options are content; the structure around them is not.
 */
internal class Chrome(
    private val registry: BotRegistry,
    private val dispatch: (UiIntent) -> Unit,
) {
    val canvas: HTMLCanvasElement = elementById("board")
    val overlay: HTMLCanvasElement = elementById("board-overlay")

    private val boardWrap: HTMLElement = elementById("board-wrap")
    private val tip: HTMLElement = elementById("board-tip")
    private val tipWho: HTMLElement = tip.child(".tip-who")
    private val tipDetail: HTMLElement = tip.child(".tip-detail")

    private val playButton: HTMLButtonElement = elementById("play")
    private val stepButton: HTMLButtonElement = elementById("step")
    private val restartButton: HTMLButtonElement = elementById("restart")
    private val speedSlider: HTMLInputElement = elementById("speed")
    private val speedValue: HTMLElement = elementById("speed-value")

    private val scrub: HTMLElement = elementById("scrub")
    private val seekSlider: HTMLInputElement = elementById("seek")
    private val seekValue: HTMLElement = elementById("seek-value")

    private val status: HTMLElement = elementById("status")
    private val rows: List<SlotRow> = List(SCOREBOARD_ROWS) { SlotRow(elementById("slot-$it")) }

    private val sizeSelect: HTMLSelectElement = elementById("size")
    private val seedInput: HTMLInputElement = elementById("seed")
    private val reseedButton: HTMLButtonElement = elementById("reseed")
    private val botSelects: List<HTMLSelectElement> = List(SCOREBOARD_ROWS) { elementById("bot-$it") }
    private val seats: List<SlotForm> = List(SCOREBOARD_ROWS) { SlotForm(it, registry, botSelects[it]) }
    private val startButton: HTMLButtonElement = elementById("new-match")

    private val tournament: HTMLElement = elementById("tournament")
    private val formatSelect: HTMLSelectElement = elementById("format")
    private val roundsSelect: HTMLSelectElement = elementById("rounds")
    private val tournamentButton: HTMLButtonElement = elementById("run-tournament")
    private val tournamentProgress: HTMLElement = elementById("tournament-progress")
    private val tournamentTable: HTMLElement = elementById("tournament-table")

    private val watchReplayButton: HTMLButtonElement = elementById("watch-replay")
    private val shareButton: HTMLButtonElement = elementById("share")
    private val shareUrlInput: HTMLInputElement = elementById("share-url")

    private val repeat = KeyRepeat { direction -> dispatch(UiIntent.Steer(direction)) }

    /** Where the pointer last was, so [placeTip] can run again once the label has a width. */
    private var tipX: Double = 0.0
    private var tipY: Double = 0.0

    init {
        fillPickers(registry)
        // After the pickers are filled and seated, so each panel opens on the bot actually selected.
        seats.forEach(SlotForm::refresh)
        seedInput.value = freshSeed().toString()
        speedValue.textContent = speedLabel()

        playButton.addEventListener("click") { dispatch(UiIntent.TogglePlay) }
        stepButton.addEventListener("click") { dispatch(UiIntent.StepOnce) }
        restartButton.addEventListener("click") { dispatch(UiIntent.Restart) }
        startButton.addEventListener("click") { dispatch(UiIntent.StartMatch(readOptions())) }
        tournamentButton.addEventListener("click") { dispatch(UiIntent.ToggleTournament) }
        watchReplayButton.addEventListener("click") { dispatch(UiIntent.WatchReplay) }
        shareButton.addEventListener("click") { dispatch(UiIntent.Share) }
        reseedButton.addEventListener("click") { seedInput.value = freshSeed().toString() }

        // Opening the disclosure takes its room out of the board's track rather than off the bottom
        // of a page that no longer scrolls, so the board has a new size and does not know it.
        tournament.addEventListener("toggle") { dispatch(UiIntent.Relayout) }

        speedSlider.addEventListener("input") {
            speedValue.textContent = speedLabel()
            dispatch(UiIntent.SetSpeed(turnsPerSecond()))
        }
        seekSlider.addEventListener("input") {
            dispatch(UiIntent.SeekTo(seekSlider.value.toIntOrNull() ?: 0))
        }

        // On the canvas rather than on the window, because the board is the thing being asked
        // about. The overlay above it declines the pointer in CSS, so the question still lands here.
        canvas.addEventListener("pointermove") { event ->
            val pointer = event as MouseEvent
            tipX = pointer.clientX.toDouble()
            tipY = pointer.clientY.toDouble()
            placeTip()
            dispatch(UiIntent.Hover(tipX, tipY))
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
    fun turnsPerSecond(): Double {
        val index = speedSlider.value.toIntOrNull() ?: DEFAULT_SPEED_INDEX
        return SPEEDS[index.coerceIn(0, SPEEDS.size - 1)]
    }

    fun readOptions(): MatchOptions {
        val size = sizeSelect.value.toIntOrNull() ?: DEFAULT_SIZE
        val seed = seedInput.value.trim().toLongOrNull()
            ?: freshSeed().also { seedInput.value = it.toString() }

        return MatchOptions(
            rows = size,
            cols = size,
            seed = seed,
            // Each seat answers with its bot *and* its settings or with nothing at all, so an empty
            // picker drops the whole seat and there is no index left to keep aligned downstream.
            slots = seats.mapNotNull(SlotForm::read),
        )
    }

    /**
     * The tournament form: the bots seated in the pickers, on the board and from the seed beside
     * them, over however many rounds a pairing.
     *
     * Deliberately no second list of contestants. The sidebar already says who is playing, and a
     * tournament is that question asked a few hundred times — so a seat filled by a person, or by a
     * bot already picked, drops out and the rest are the field.
     */
    fun readTournamentOptions(): TournamentOptions {
        val match = readOptions()
        return TournamentOptions(
            rows = match.rows,
            cols = match.cols,
            seed = match.seed,
            // An allowance left at the default is left unsaid, so a stock seat enters as plain
            // `uct` rather than as `uct@40k` and the matrix reads the way it always has.
            contestants = match.slots
                .filter { it.bot != PlayableRegistry.HUMAN_ID }
                .map { seat ->
                    Contestant(
                        bot = seat.bot,
                        budgetPerTurn = seat.budgetPerTurn.takeIf { it != MatchSetup.DEFAULT_BUDGET_PER_TURN },
                        params = seat.params,
                    )
                }
                .distinct(),
            rounds = roundsSelect.value.toIntOrNull() ?: DEFAULT_ROUNDS,
            format = if (formatSelect.value == FREE_FOR_ALL_VALUE) {
                TournamentFormat.FREE_FOR_ALL
            } else {
                TournamentFormat.HEAD_TO_HEAD
            },
        )
    }

    /**
     * Points the new-match form at [setup], so that loading somebody's replay leaves you one click
     * away from a rematch under the same conditions.
     *
     * Which now includes what those conditions *were*: a replay of UCT at a tenth of its allowance
     * that rematched at the full one would be the feature half-built.
     */
    fun applySetup(setup: MatchSetup) {
        if (setup.rows == setup.cols) {
            selectIfOffered(sizeSelect, setup.rows.toString())
        }
        seedInput.value = setup.seed.toString()
        for (slot in seats.indices) {
            val bot = setup.slots.getOrNull(slot)
            seats[slot].apply(
                bot = bot,
                budgetPerTurn = if (bot == null) setup.budgetPerTurn else setup.budgetFor(slot),
                params = if (bot == null) BotParams.EMPTY else setup.paramsFor(slot),
            )
        }
    }

    fun render(model: UiModel) {
        playButton.textContent = if (model.running) "Pause" else "Play"

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
        startButton.disabled = model.batchRunning
        shareButton.disabled = model.batchRunning
        watchReplayButton.disabled = !model.canWatchReplay

        scrub.hidden = !model.replay
        if (model.replay) {
            seekSlider.max = model.turnCount.toString()
            seekSlider.value = model.turnIndex.toString()
            seekValue.textContent = "${model.turnIndex} / ${model.turnCount}"
        }

        for (slot in rows.indices) {
            rows[slot].render(model.stats.slots.getOrNull(slot), model.labels[slot])
        }

        val hover = model.hover
        tip.hidden = hover == null
        if (hover != null) {
            tipWho.textContent = hover.who
            tipDetail.textContent = hover.detail
            // Now that it is visible and says what it says, it has a width to be kept inside by.
            placeTip()
        }

        renderTournament(model.tournament)

        val url = model.shareUrl
        shareUrlInput.hidden = url == null
        if (url != null) {
            shareUrlInput.value = url
        }
    }

    /**
     * Selects the link and offers it to the clipboard.
     *
     * Called straight out of the click that asked for it, because the clipboard is only writable
     * from a user gesture — and selecting the text is the fallback for when it is not writable at
     * all, which is why it happens first and unconditionally.
     */
    fun copyShareUrl() {
        shareUrlInput.hidden = false
        shareUrlInput.select()
        copyToClipboard(shareUrlInput.value)
    }

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

    private fun renderTournament(status: TournamentStatus?) {
        tournamentButton.textContent = if (status?.running == true) "Stop" else "Run tournament"
        tournamentProgress.textContent = status?.progress ?: ""

        tournamentTable.hidden = status == null || status.table.isEmpty()
        if (status != null) {
            tournamentTable.textContent = status.table
        }
    }

    private fun fillPickers(registry: BotRegistry) {
        val everyone = registry.entries
        val bots = everyone.filter { it.id != PlayableRegistry.HUMAN_ID }

        for (slot in botSelects.indices) {
            val select = botSelects[slot]
            // Only the first seat is offered to a person: every interactive slot reads the same
            // keyboard, so a second one would steer by stealing the first one's moves.
            if (slot == 0) {
                everyone.forEach { select.appendChild(optionFor(it.id.slug, it.displayName)) }
            } else {
                select.appendChild(optionFor("", "— empty —"))
                bots.forEach { select.appendChild(optionFor(it.id.slug, it.displayName)) }
            }
        }

        // You against a strong bot, with the rest of the board empty: the page opens on the game
        // somebody came here to play, not on the weakest rung of the ladder.
        //
        // It used to say "the best bot there is", and that stopped being true when `puct` and
        // `alphabeta` graduated above `DEFAULT_OPPONENT` into the ladder. Which of the three a page
        // nobody has configured should open on is a design decision and not a consequence of a
        // ranking, so the slug below is unchanged and this comment no longer claims a superlative.
        selectIfOffered(botSelects[0], PlayableRegistry.HUMAN_ID.slug)
        val opponent = bots.firstOrNull { it.id.slug == DEFAULT_OPPONENT } ?: bots.firstOrNull()
        opponent?.let { selectIfOffered(botSelects[1], it.id.slug) }
    }

    /**
     * Every key the game answers to, cancelled first and acted on second.
     *
     * Auto-repeat is the keyboard talking, not the player: its rate is a text-editing rate, and a
     * different one on every machine, so a held key is repeated by [KeyRepeat] instead. But dropping
     * those events is a statement about how fast the *snake* moves and says nothing about what the
     * browser may do with them — so `preventDefault` comes first and the repeat guard second. The
     * other way round, holding an arrow key steered at our rate and scrolled the page at the
     * keyboard's.
     */
    private fun onKeyDown(event: KeyboardEvent) {
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

    private fun speedLabel(): String {
        val speed = turnsPerSecond()
        return if (speed < 10) "${speed.toInt()} turn/s" else "${speed.toInt()} turns/s"
    }

    private fun optionFor(value: String, label: String): HTMLOptionElement {
        val option = document.createElement("option") as HTMLOptionElement
        option.value = value
        option.textContent = label
        return option
    }

    /** Sets [select] to [value], or leaves it on its first option if nothing offers that value. */
    private fun selectIfOffered(select: HTMLSelectElement, value: String) {
        select.value = value
        if (select.value != value) {
            select.selectedIndex = 0
        }
    }

    private class SlotRow(private val root: HTMLElement) {
        private val swatch: HTMLElement = root.child(".swatch")
        private val who: HTMLElement = root.child(".who")
        private val length: HTMLElement = root.child(".length")
        private val fate: HTMLElement = root.child(".fate")

        fun render(state: SlotStats?, name: String) {
            if (state == null) {
                root.hidden = true
                return
            }

            root.hidden = false
            root.className = if (state.alive) "slot" else "slot out"
            swatch.style.backgroundColor = Palette.bodyColour(state.slot.index)
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
        /** Four seats: enough for the free-for-all matches that make this game interesting. */
        const val SCOREBOARD_ROWS = 4

        /** Must be the `selected` option on `#size` in index.html. */
        const val DEFAULT_SIZE = 8

        /**
         * Who slot 2 opens on: a **slug**, and a preference rather than a requirement.
         *
         * `:ui` cannot depend on `:bots` and must not start to — the renderer painting a board it
         * cannot tell a wall hugger from a human on is the point of that edge. A string names one
         * without reaching for it, and a registry that does not offer it seats whatever is first
         * instead, so an injected registry of one bot still opens on a playable match.
         */
        const val DEFAULT_OPPONENT = "uct"

        /** Must be one of the options on `#rounds` in index.html, and even. */
        const val DEFAULT_ROUNDS = 20

        /** Must be the value of the free-for-all option on `#format` in index.html. */
        const val FREE_FOR_ALL_VALUE = "free-for-all"

        /** How a snake left, in plain words. The engine's vocabulary stops here. */
        fun fateText(reason: EliminationReason?): String = when (reason) {
            EliminationReason.TRAPPED -> "trapped"
            EliminationReason.SUICIDE -> "crashed"
            EliminationReason.RESIGNED -> "resigned"
            EliminationReason.FORFEIT -> "forfeited"
            null -> ""
        }

        /** Must line up with the `max` on `#speed` in index.html. */
        val SPEEDS = doubleArrayOf(1.0, 2.0, 4.0, 8.0, 12.0, 20.0, 40.0, 80.0)

        /** Twelve turns a second: on a two-snake board that is six moves a second each. */
        const val DEFAULT_SPEED_INDEX = 4

        val EDITABLE_TAGS = setOf("INPUT", "SELECT", "TEXTAREA")

        /** How far the hover label sits from the pointer, so the pointer never covers it. */
        const val TIP_GAP = 14.0
    }
}

/** A part of a static block the chrome writes into. Absent means the skeleton lost a line. */
private fun HTMLElement.child(selector: String): HTMLElement =
    querySelector(selector) as? HTMLElement ?: error("the page skeleton is missing $selector")

/**
 * Offers [text] to the clipboard, and shrugs if the browser declines.
 *
 * Hand-written interop because `navigator.clipboard` is not in the typed DOM bindings, and a
 * rejected promise here is a permissions decision rather than a fault — the link is already selected
 * in a visible field either way.
 */
private fun copyToClipboard(text: String): Unit =
    js("{ if (navigator.clipboard) { navigator.clipboard.writeText(text).catch(function () {}); } }")
