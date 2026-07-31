package ao.snakewarz.ui.chrome.panel

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.human.PlayableRegistry
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentFormat
import ao.snakewarz.ui.chrome.elementById
import ao.snakewarz.ui.model.MatchOptions
import ao.snakewarz.ui.model.TournamentOptions
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * `#panel-setup`: the board, the map, four seats with their settings, the seed, and Start match.
 *
 * A form and nothing more. Nothing here dispatches until Start match is pressed, so which bot is
 * seated and what its knobs say is form state right up to that click — the same kind of thing the
 * reseed button writes straight into `#seed`, and no part of the running match can see any of it.
 *
 * The two registry-driven exceptions to *"Kotlin never constructs structure"* both live behind this
 * panel: the `<option>` list of each picker, built here, and the knob rows inside each seat, built by
 * [SlotForm]. Both exist to keep *"fork, add a file, register it, open a PR"* from also meaning *"and
 * edit the markup"*. The containers around them are static, and adding a third exception needs a
 * better reason than either of these had.
 */
internal class SetupPanel(
    private val registry: BotRegistry,
    dispatch: (UiIntent) -> Unit,
) {
    private val sizeSelect: HTMLSelectElement = elementById("size")
    private val mapSelect: HTMLSelectElement = elementById("map")
    private val fromReplayOption: HTMLOptionElement = elementById("map-from-replay")

    /**
     * Each shape's option, paired with the shape it draws.
     *
     * Looked up by slug so that a shape the markup forgot fails at boot with its own name, rather
     * than becoming a picker that quietly offers seven of eight maps.
     */
    private val mapOptions: List<Pair<MapShape, HTMLOptionElement>> = MapShape.entries.map { shape ->
        shape to (
            mapSelect.querySelector("option[value='${shape.slug}']") as? HTMLOptionElement
                ?: error("the page skeleton is missing a map option for ${shape.slug}")
            )
    }

    /**
     * The map a loaded replay arrived with, when no shape draws it — see [applyMap].
     *
     * Empty whenever the picker is showing a shape, because a shape is *redrawn* at whatever size and
     * seed the form says: moving either has to move the map with it.
     */
    private var replayWalls: IntArray = IntArray(0)

    private val seedInput: HTMLInputElement = elementById("seed")
    private val reseedButton: HTMLButtonElement = elementById("reseed")
    private val botSelects: List<HTMLSelectElement> = List(SEATS) { elementById("bot-$it") }
    private val seats: List<SlotForm> = List(SEATS) { SlotForm(it, registry, botSelects[it]) }
    private val startButton: HTMLButtonElement = elementById("new-match")

    init {
        fillPickers(registry)
        // After the pickers are filled and seated, so each panel opens on the bot actually selected.
        seats.forEach(SlotForm::refresh)
        seedInput.value = freshSeed().toString()
        refreshMapOptions()

        startButton.addEventListener("click") { dispatch(UiIntent.StartMatch(readOptions())) }
        reseedButton.addEventListener("click") { seedInput.value = freshSeed().toString() }

        // A smaller board offers fewer shapes, so the gate moves with the size rather than being
        // discovered at Start match by a `generateMap` that refuses the shape still selected.
        sizeSelect.addEventListener("change") {
            discardReplayMap()
            refreshMapOptions()
        }
    }

    fun readOptions(): MatchOptions {
        val size = boardSize()
        val seed = seedInput.value.trim().toLongOrNull()
            ?: freshSeed().also { seedInput.value = it.toString() }

        return MatchOptions(
            rows = size,
            cols = size,
            seed = seed,
            walls = mapWalls(size, seed),
            // Each seat answers with its bot *and* its settings or with nothing at all, so an empty
            // picker drops the whole seat and there is no index left to keep aligned downstream.
            slots = seats.mapNotNull(SlotForm::read),
        )
    }

    /**
     * The tournament form: the bots seated in these pickers, on this board and from the seed beside
     * them, over however many [rounds] a pairing in [format].
     *
     * Deliberately no second list of contestants. The seats already say who is playing and a
     * tournament is that question asked a few hundred times — so a seat filled by a person, or by a
     * bot already entered, drops out and the rest are the field. The schedule is the other panel's to
     * state, which is why it arrives as two arguments rather than being read here.
     */
    fun readTournamentOptions(rounds: Int, format: TournamentFormat): TournamentOptions {
        val match = readOptions()
        return TournamentOptions(
            rows = match.rows,
            cols = match.cols,
            seed = match.seed,
            walls = match.walls,
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
            rounds = rounds,
            format = format,
        )
    }

    /**
     * Points the form at [setup], so that loading somebody's replay leaves you one click away from a
     * rematch under the same conditions.
     *
     * Which includes what those conditions *were*: a replay of UCT at a tenth of its allowance that
     * rematched at the full one would be the feature half-built.
     */
    fun applySetup(setup: MatchSetup) {
        if (setup.rows == setup.cols) {
            selectIfOffered(sizeSelect, setup.rows.toString())
        }
        seedInput.value = setup.seed.toString()
        // After the size and the seed, which are the two things a shape is drawn from.
        applyMap(setup)
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
        // A running batch owns the arena, so there is nothing for a new match to start onto.
        startButton.disabled = model.batchRunning
    }

    override fun toString(): String = "SetupPanel(${seats.size} seats)"

    // -- internals

    private fun boardSize(): Int = sizeSelect.value.toIntOrNull() ?: DEFAULT_SIZE

    /**
     * The map the picker is showing, drawn at [size] and from [seed].
     *
     * Regenerated on every read rather than kept, which is what makes the board size and the seed
     * boxes above it mean what they say: change either and the map changes with it. An unrecognised
     * value is [FROM_REPLAY_VALUE] — see [applyMap] — and `empty` draws no walls at all, so a match
     * on a bare rectangle encodes to the bytes it always did.
     */
    private fun mapWalls(size: Int, seed: Long): IntArray {
        val shape = MapShape.ofSlug(mapSelect.value) ?: return replayWalls.copyOf()
        return generateMap(size, size, shape, seed = seed).walls()
    }

    /**
     * Greys out every shape the chosen board is too small to draw, and falls back off one that was
     * already picked.
     *
     * `MapShape.minimumSide` is the single source of that number: a copy in the markup would sit a
     * file away from the shape it belongs to, and `generateMap` refuses a smaller board outright — so
     * an option left pickable is a Start match that throws.
     */
    private fun refreshMapOptions() {
        val size = boardSize()
        var lost = false
        for ((shape, option) in mapOptions) {
            option.disabled = size < shape.minimumSide
            lost = lost || (option.disabled && option.selected)
        }
        if (lost) {
            mapSelect.value = MapShape.EMPTY.slug
        }
    }

    /**
     * Points the map picker at the board [setup] was played on.
     *
     * A replay carries the wall squares themselves and never a shape name, so a shape is recognised
     * by redrawing each one at the setup's own size and seed and comparing. Where none matches, the
     * bitmap itself becomes the picker's answer: a rematch that silently regenerated a *different*
     * board would be the same half-built feature the allowances above exist to prevent.
     *
     * The form offers square boards from a fixed list, so a replay on anything else is one it cannot
     * express. Its size already falls back to whatever the picker was showing, and the map falls back
     * with it — a map drawn for another board is not a map.
     */
    private fun applyMap(setup: MatchSetup) {
        discardReplayMap()
        refreshMapOptions()

        val size = boardSize()
        val shown = setup.rows == size && setup.cols == size
        val drawn = if (shown) shapeDrawing(setup) else MapShape.EMPTY
        if (drawn != null) {
            mapSelect.value = drawn.slug
            return
        }

        replayWalls = setup.walls()
        fromReplayOption.hidden = false
        mapSelect.value = FROM_REPLAY_VALUE
    }

    /**
     * Gives up the map a replay arrived with.
     *
     * A bitmap belongs to the board it was drawn for and nothing can redraw it at another size, so
     * anything that moves the board has to let it go — where a *shape* survives, because a shape is a
     * recipe and [mapWalls] runs it again at whatever the form now says.
     */
    private fun discardReplayMap() {
        if (mapSelect.value == FROM_REPLAY_VALUE) {
            mapSelect.value = MapShape.EMPTY.slug
        }
        replayWalls = IntArray(0)
        fromReplayOption.hidden = true
    }

    /**
     * The shape that draws [setup]'s map, or `null` when none of them does.
     *
     * An unmapped setup is `empty`, which draws nothing and so matches first — the same answer a
     * board with no walls would get from the picker.
     */
    private fun shapeDrawing(setup: MatchSetup): MapShape? {
        val walls = setup.walls()
        val side = minOf(setup.rows, setup.cols)
        return mapOptions.firstOrNull { (shape, _) ->
            shape.minimumSide <= side &&
                generateMap(setup.rows, setup.cols, shape, seed = setup.seed).walls().contentEquals(walls)
        }?.first
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

    companion object {
        /**
         * Four seats: enough for the free-for-all matches that make this game interesting.
         *
         * One `#bot-N` picker and one `#knobs-N` block each in index.html, and one `#slot-N` card
         * each in the bottom bar — the scoreboard reports on the seats this form offers, so the two
         * are one number rather than two that have to be kept equal.
         */
        const val SEATS: Int = 4

        /** Must be the `selected` option on `#size` in index.html. */
        private const val DEFAULT_SIZE = 8

        /**
         * Who slot 2 opens on: a **slug**, and a preference rather than a requirement.
         *
         * `:ui` cannot depend on `:bots` and must not start to — the renderer painting a board it
         * cannot tell a wall hugger from a human on is the point of that edge. A string names one
         * without reaching for it, and a registry that does not offer it seats whatever is first
         * instead, so an injected registry of one bot still opens on a playable match.
         */
        private const val DEFAULT_OPPONENT = "uct"

        /**
         * Must be the value of the last option on `#map` in index.html, and no shape's slug.
         *
         * It stands for a bitmap rather than for a recipe, which is why it is not a `MapShape` and
         * cannot become one: the thing it selects is whatever the loaded replay was played on.
         */
        private const val FROM_REPLAY_VALUE = "from-replay"
    }
}
