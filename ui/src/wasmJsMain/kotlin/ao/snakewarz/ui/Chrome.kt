package ao.snakewarz.ui

import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotRegistry
import ao.snakewarz.core.Direction
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.PlayableRegistry
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
    registry: BotRegistry,
    private val dispatch: (UiIntent) -> Unit,
) {
    val canvas: HTMLCanvasElement = elementById("board")

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
    private val startButton: HTMLButtonElement = elementById("new-match")

    private val shareButton: HTMLButtonElement = elementById("share")
    private val shareUrlInput: HTMLInputElement = elementById("share-url")

    init {
        fillPickers(registry)
        seedInput.value = freshSeed().toString()
        speedValue.textContent = speedLabel()

        playButton.addEventListener("click") { dispatch(UiIntent.TogglePlay) }
        stepButton.addEventListener("click") { dispatch(UiIntent.StepOnce) }
        restartButton.addEventListener("click") { dispatch(UiIntent.Restart) }
        startButton.addEventListener("click") { dispatch(UiIntent.StartMatch(readOptions())) }
        shareButton.addEventListener("click") { dispatch(UiIntent.Share) }
        reseedButton.addEventListener("click") { seedInput.value = freshSeed().toString() }

        speedSlider.addEventListener("input") {
            speedValue.textContent = speedLabel()
            dispatch(UiIntent.SetSpeed(turnsPerSecond()))
        }
        seekSlider.addEventListener("input") {
            dispatch(UiIntent.SeekTo(seekSlider.value.toIntOrNull() ?: 0))
        }

        window.addEventListener("keydown") { event -> onKeyDown(event as KeyboardEvent) }
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
            slots = botSelects.mapNotNull { select -> select.value.takeIf { it.isNotEmpty() } }.map(::BotId),
        )
    }

    /**
     * Points the new-match form at [setup], so that loading somebody's replay leaves you one click
     * away from a rematch under the same conditions.
     */
    fun applySetup(setup: MatchSetup) {
        if (setup.rows == setup.cols) {
            selectIfOffered(sizeSelect, setup.rows.toString())
        }
        seedInput.value = setup.seed.toString()
        for (slot in botSelects.indices) {
            selectIfOffered(botSelects[slot], setup.slots.getOrNull(slot)?.slug ?: "")
        }
    }

    fun render(model: UiModel) {
        playButton.textContent = if (model.running) "Pause" else "Play"
        status.textContent = "Turn ${model.turnIndex} · ${model.status}"

        scrub.hidden = !model.replay
        if (model.replay) {
            seekSlider.max = model.turnCount.toString()
            seekSlider.value = model.turnIndex.toString()
            seekValue.textContent = "${model.turnIndex} / ${model.turnCount}"
        }

        for (slot in rows.indices) {
            rows[slot].render(model.slots.getOrNull(slot))
        }

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

    // -- internals ------------------------------------------------------------------------------

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

        // You against the first bot on the list, with the rest of the board empty.
        selectIfOffered(botSelects[0], PlayableRegistry.HUMAN_ID.slug)
        bots.firstOrNull()?.let { selectIfOffered(botSelects[1], it.id.slug) }
    }

    private fun onKeyDown(event: KeyboardEvent) {
        // Auto-repeat is the keyboard talking, not the player. InputBuffer collapses repeats too;
        // this is the cheaper half of the pair, and the half that also leaves the queue alone.
        if (event.repeat) {
            return
        }
        // Arrows belong to a focused select or slider while it has the focus, not to the snake.
        if (document.activeElement?.tagName in EDITABLE_TAGS) {
            return
        }

        val steer = when (event.key) {
            "ArrowUp", "w", "W" -> Direction.NORTH
            "ArrowDown", "s", "S" -> Direction.SOUTH
            "ArrowLeft", "a", "A" -> Direction.WEST
            "ArrowRight", "d", "D" -> Direction.EAST
            else -> null
        }

        if (steer != null) {
            event.preventDefault()
            dispatch(UiIntent.Steer(steer))
            return
        }

        when (event.key) {
            " ", "Spacebar" -> {
                event.preventDefault()
                dispatch(UiIntent.TogglePlay)
            }

            "." -> dispatch(UiIntent.StepOnce)
        }
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

        fun render(state: SlotStatus?) {
            if (state == null) {
                root.hidden = true
                return
            }

            root.hidden = false
            root.className = if (state.alive) "slot" else "slot out"
            swatch.style.backgroundColor = Palette.bodyColour(state.slot)
            who.textContent = state.name
            length.textContent = state.length.toString()
            fate.textContent = when {
                state.winner -> "winner"
                !state.alive -> state.fate
                else -> ""
            }
        }

        private fun HTMLElement.child(selector: String): HTMLElement =
            querySelector(selector) as? HTMLElement ?: error("a scoreboard row is missing $selector")
    }

    private companion object {
        /** Four seats: enough for the free-for-all matches that make this game interesting. */
        const val SCOREBOARD_ROWS = 4

        const val DEFAULT_SIZE = 20

        /** Must line up with the `max` on `#speed` in index.html. */
        val SPEEDS = doubleArrayOf(1.0, 2.0, 4.0, 8.0, 12.0, 20.0, 40.0, 80.0)

        /** Twelve turns a second: on a two-snake board that is six moves a second each. */
        const val DEFAULT_SPEED_INDEX = 4

        val EDITABLE_TAGS = setOf("INPUT", "SELECT", "TEXTAREA")
    }
}

private inline fun <reified T : Element> elementById(id: String): T =
    document.getElementById(id) as? T ?: error("index.html is missing #$id")

/**
 * Offers [text] to the clipboard, and shrugs if the browser declines.
 *
 * Hand-written interop because `navigator.clipboard` is not in the typed DOM bindings, and a
 * rejected promise here is a permissions decision rather than a fault — the link is already selected
 * in a visible field either way.
 */
private fun copyToClipboard(text: String): Unit =
    js("{ if (navigator.clipboard) { navigator.clipboard.writeText(text).catch(function () {}); } }")
