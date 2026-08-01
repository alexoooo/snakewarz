package ao.snakewarz.ui.chrome.panel

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.ui.model.SlotOptions
import ao.snakewarz.ui.model.UiIntent
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The four rules the new-match form keeps, and the only tests [SlotForm]'s read and apply logic has.
 *
 * All four are invisible until a replay comes back wrong or a URL grows bytes nobody typed, which is
 * why they are pinned here rather than left to the browser: an untouched seat says nothing, a
 * rejected value is corrected in the field, a configured seat survives the round trip, and a knob
 * with no row on the form still has a value.
 *
 * Driven against a cut-down skeleton, as [ao.snakewarz.ui.chrome.ShellTest] is, and against a
 * registry of one invented bot rather than the shipped ones — `:ui` may not see `:bots`, and what is
 * under test is what the form does with a declaration, not what any particular bot declares.
 */
class SetupPanelTest {
    private val skeleton: HTMLElement = (document.createElement("div") as HTMLElement).also {
        it.innerHTML = SKELETON
        document.body?.appendChild(it)
    }

    private val intents = mutableListOf<UiIntent>()
    private val panel = SetupPanel(ONE_BOT) { intents += it }

    @AfterTest
    fun detach() {
        skeleton.remove()
    }

    @Test
    fun `a seat nobody touched carries no settings at all`() {
        val seat = firstSeat()

        // Empty and not "every declared default written out": MatchSetup.configured stays false, so
        // a stock match's replay URL is byte-identical to the one the codec produced before knobs
        // existed at all.
        assertEquals(BotParams.EMPTY, seat.params)
        assertEquals(MatchSetup.DEFAULT_BUDGET_PER_TURN, seat.budgetPerTurn, "the match's grant, not the bot's")
    }

    @Test
    fun `a value out of range is corrected in the field, not merely in the read`() {
        val depth = knobField(seat = 0, knob = DEPTH.name)
        depth.value = "999"

        val seat = firstSeat()

        assertEquals(DEPTH.defaultText, depth.value, "the box now says what the match will play at")
        assertTrue(depth.classList.contains("rejected"), "and says it had to be corrected")
        assertEquals(BotParams.EMPTY, seat.params, "a value equal to the default is left unsaid")
    }

    @Test
    fun `an allowance out of range is corrected too`() {
        val budget = knobField(seat = 0, knob = BotKnob.Search.NAME)
        budget.value = "not a number"

        val seat = firstSeat()

        assertEquals(MatchSetup.DEFAULT_BUDGET_PER_TURN.toString(), budget.value)
        assertEquals(MatchSetup.DEFAULT_BUDGET_PER_TURN, seat.budgetPerTurn)
    }

    @Test
    fun `a configured seat survives applySetup and comes back out of read`() {
        // What loading somebody's replay has to leave behind: one click from a rematch under the
        // conditions the match was actually played at, allowance and all.
        panel.applySetup(
            MatchSetup.create(
                rows = 12,
                cols = 12,
                slots = listOf(SEARCHER),
                seed = 4242,
                budgets = intArrayOf(777),
                slotParams = listOf(BotParams(mapOf(DEPTH.name to "25"))),
            ),
        )

        val options = panel.readOptions()
        val seat = options.slots.first()

        assertEquals(12, options.rows)
        assertEquals(4242, options.seed)
        assertEquals(SEARCHER, seat.bot)
        assertEquals(777, seat.budgetPerTurn)
        assertEquals("25", seat.params.string(DEPTH.name, ""))
    }

    @Test
    fun `a knob the form does not offer still survives applySetup and read`() {
        // The form shows BotEntry.offered and reads BotEntry.params, and those are different lists.
        // A knob with no row still has a value — a replay carried one in, or somebody measured one
        // in :lab and shared the link — and reading the rows instead would rematch at the default.
        panel.applySetup(
            MatchSetup.create(
                rows = 8,
                cols = 8,
                slots = listOf(SEARCHER),
                seed = 7,
                slotParams = listOf(BotParams(mapOf(WEIGHT.name to "1.5"))),
            ),
        )

        assertEquals(null, knobFieldOrNull(seat = 0, knob = WEIGHT.name), "no row for a hyperparameter")
        assertEquals("1.5", panel.readOptions().slots.first().params.string(WEIGHT.name, ""))
    }

    @Test
    fun `the map picker offers only the shapes the chosen board can draw, and says what they want`() {
        val size = document.getElementById("size") as HTMLSelectElement
        size.value = "8"
        size.dispatchEvent(Event("change"))

        // Rooms wants a band wider than its own door, so on an 8x8 leaving it pickable is a Start
        // that throws. The number is read off the shape rather than written here, because a redrawn
        // shape moves it and this test is about the label rather than about the figure in it.
        val wants = MapShape.ROOMS.minimumSide
        assertTrue(option(MapShape.ROOMS).disabled, "rooms needs $wants")
        assertEquals("Rooms — needs $wants × $wants", option(MapShape.ROOMS).textContent)
        assertTrue(!option(MapShape.PILLARS).disabled, "pillars fits")
        assertEquals("Pillars", option(MapShape.PILLARS).textContent, "so it says nothing extra")

        size.value = "20"
        size.dispatchEvent(Event("change"))

        // The base label is the markup's and is captured once, so the suffix comes back off rather
        // than accumulating on a picker that has been resized twice.
        assertEquals("Rooms", option(MapShape.ROOMS).textContent)
    }

    @Test
    fun `shrinking the board previews the map it fell back to, not the one it cannot draw`() {
        val size = document.getElementById("size") as HTMLSelectElement
        val map = document.getElementById("map") as HTMLSelectElement

        size.value = "20"
        size.dispatchEvent(Event("change"))
        map.value = MapShape.ROOMS.slug
        map.dispatchEvent(Event("change"))

        size.value = "8"
        size.dispatchEvent(Event("change"))

        // `generateMap` refuses rooms on an 8x8, so a preview raised before the picker was regated
        // would throw rather than draw — which is the whole of why that dispatch goes last.
        val preview = intents.filterIsInstance<UiIntent.PreviewSetup>().last()
        assertEquals(8, preview.options.rows)
        assertEquals(0, preview.options.walls.size, "an 8x8 cannot draw rooms, so it drew nothing")
    }

    // -- internals

    private fun firstSeat(): SlotOptions = panel.readOptions().slots.first()

    private fun option(shape: MapShape): HTMLOptionElement =
        document.querySelector("#map option[value='${shape.slug}']") as HTMLOptionElement

    private fun knobField(seat: Int, knob: String): HTMLInputElement =
        assertNotNull(knobFieldOrNull(seat, knob), "seat $seat has no row for '$knob'")

    /**
     * The control for one knob, found by position rather than by a name in the DOM.
     *
     * The rows come off `BotEntry.offered` in declaration order and carry no id — nothing in the
     * page needs one — so the index of the knob in that list is what identifies its field.
     */
    private fun knobFieldOrNull(seat: Int, knob: String): HTMLInputElement? {
        val at = ENTRY.offered.indexOfFirst { it.name == knob }
        if (at < 0) {
            return null
        }
        val grid = document.querySelector("#knobs-$seat .knob-grid") as HTMLElement
        return grid.querySelectorAll("input").item(at) as? HTMLInputElement
    }

    private companion object {
        val SEARCHER = BotId("searcher")

        /** Offered, so it gets a row: an allowance is the type case of a tradeoff. */
        val BUDGET = BotKnob.Search(min = 1, max = 1_000_000, step = 1)

        /** Offered too, and the one a test types an out-of-range number into. */
        val DEPTH = BotKnob.Integer(
            name = "depth",
            label = "Depth",
            help = "How far ahead.",
            default = 10,
            min = 1,
            max = 50,
            tradeoff = true,
        )

        /**
         * Declared and **not** offered: a hyperparameter, which `:lab` sweeps and a replay carries
         * but no form shows. The gap between `knobs` and `offered` is the whole point of it.
         */
        val WEIGHT = BotKnob.Decimal(
            name = "weight",
            label = "Weight",
            help = "A fitted constant.",
            default = 1.0,
            min = 0.0,
            max = 4.0,
            step = 0.1,
        )

        val ENTRY = BotEntry(SEARCHER, "Searcher", BotFactory { Quitter }, listOf(BUDGET, DEPTH, WEIGHT))

        val ONE_BOT = object : BotRegistry {
            override val entries: List<BotEntry> = listOf(ENTRY)

            override fun get(id: BotId): BotEntry? = ENTRY.takeIf { it.id == id }
        }

        object Quitter : Bot {
            override fun chooseMove(turn: Turn): Decision = Decision.Resign
        }

        /**
         * The shape of `#panel-setup`, cut down to what [SetupPanel] looks up.
         *
         * Every map option is here because the panel fails at boot on a shape the markup forgot,
         * which is the behaviour that keeps a picker from quietly omitting a map.
         */
        val SKELETON = """
            <select id="size">
              <option value="8" selected>8</option>
              <option value="12">12</option>
              <option value="20">20</option>
            </select>
            <select id="map">
              <option value="empty" selected>Empty</option>
              <option value="arena">Arena</option>
              <option value="pillars">Pillars</option>
              <option value="pinwheel">Pinwheel</option>
              <option value="rooms">Rooms</option>
              <option value="scatter">Scatter</option>
              <option value="islands">Islands</option>
              <option id="map-from-replay" value="from-replay" hidden>from replay</option>
            </select>
            <select id="bot-0"></select>
            <details class="knobs" id="knobs-0" hidden>
              <div class="knob-grid"></div>
              <button type="button" class="reset"></button>
            </details>
            <select id="bot-1"></select>
            <details class="knobs" id="knobs-1" hidden>
              <div class="knob-grid"></div>
              <button type="button" class="reset"></button>
            </details>
            <select id="bot-2"></select>
            <details class="knobs" id="knobs-2" hidden>
              <div class="knob-grid"></div>
              <button type="button" class="reset"></button>
            </details>
            <select id="bot-3"></select>
            <details class="knobs" id="knobs-3" hidden>
              <div class="knob-grid"></div>
              <button type="button" class="reset"></button>
            </details>
            <input id="seed" type="text">
            <button id="reseed" type="button"></button>
            <button id="new-match" type="button"></button>
        """.trimIndent()
    }
}
