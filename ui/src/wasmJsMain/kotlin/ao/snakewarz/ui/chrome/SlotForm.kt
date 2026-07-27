package ao.snakewarz.ui.chrome

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.ui.model.SlotOptions
import ao.snakewarz.ui.model.UiIntent
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLLabelElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * One seat of the new-match form: the picker, and the settings panel that follows it around.
 *
 * The rows are built here rather than pre-written in `index.html`, and that is the second exception
 * to "Kotlin never constructs structure" — the same one the `<option>` lists get, for the same
 * reason. A fixed pool of rows in the markup would mean a bot that declares one knob too many
 * silently loses it, which puts the markup back in the way of "fork, add a file, register it, open a
 * PR". The container is still static; only what goes in it comes off the registry.
 *
 * Nothing here dispatches a [UiIntent]. Which bot is selected and what its knobs are set to is form
 * state — the same kind of thing the reseed button writes straight into `#seed` — and it becomes app
 * state only when somebody presses Start match and [read] is called. It reads like a hole in the
 * one-way data flow and is not one: no part of the running match can see any of it.
 */
internal class SlotForm(
    seat: Int,
    private val registry: BotRegistry,
    private val select: HTMLSelectElement,
) {
    private val root: HTMLElement = elementById("knobs-$seat")
    private val grid: HTMLElement = root.child(".knob-grid")
    private val resetButton: HTMLButtonElement =
        root.querySelector("button.reset") as? HTMLButtonElement ?: error("seat $seat has no reset button")

    /** The entry the panel is currently showing, so [refresh] knows whose values to put away. */
    private var shown: BotEntry? = null

    private var rows: List<Row> = emptyList()

    /**
     * What was typed, per bot slug.
     *
     * Flicking a picker from `uct` to `space` and back is how you compare two bots, and losing the
     * allowance you just typed every time you did it would make the panel actively annoying.
     */
    private val remembered = LinkedHashMap<String, MutableMap<String, String>>()

    init {
        select.addEventListener("change") { refresh() }
        resetButton.addEventListener("click") {
            remembered.remove(select.value)
            rows = emptyList()
            shown = null
            refresh()
        }
    }

    /** Rebuilds the panel for whichever bot is selected now, keeping what was typed for it before. */
    fun refresh() {
        remember()

        val entry = selected()
        shown = entry
        rows = emptyList()
        grid.clear()

        // `offered` rather than `knobs`: a bot's hyperparameters stay tunable from `:lab` and from a
        // replay, and stay off this form, where a number nobody can judge is worse than no number.
        // A bot whose every knob is one of those gets no panel at all.
        val knobs = entry?.offered.orEmpty()
        if (entry == null || knobs.isEmpty()) {
            root.hidden = true
            return
        }

        val saved = remembered[entry.id.slug].orEmpty()
        rows = knobs.map { knob -> rowFor(knob, saved[knob.name] ?: defaultTextOf(knob)) }
        root.hidden = false
    }

    /**
     * This seat as a value, or `null` if nobody is sitting in it.
     *
     * Read over the bot's **whole declaration** rather than over the rows on screen, because the two
     * stopped being the same list the moment the form began offering only the tradeoffs. A knob this
     * seat does not show still has a value — a replay carried one in through [apply], or somebody
     * measured one in `:lab` and pasted the link — and it is sitting in [remembered] where [apply]
     * put it. Reading only the rows would drop it and rematch at the default, which is exactly the
     * promise [Chrome.applySetup] makes about replaying under the conditions the match was played at.
     */
    fun read(): SlotOptions? {
        val slug = select.value
        if (slug.isEmpty()) {
            return null
        }

        val id = BotId(slug)
        val entry = registry[id]
        var budget = MatchSetup.DEFAULT_BUDGET_PER_TURN

        for (row in rows) {
            val knob = row.knob
            if (knob is BotKnob.Search) {
                budget = allowanceOf(row, knob)
            }
        }

        val saved = remembered[slug].orEmpty()
        val params = LinkedHashMap<String, String>()
        for (knob in entry?.params.orEmpty()) {
            val row = rows.firstOrNull { it.knob.name == knob.name }
            val value = if (row != null) valueOf(row, knob) else carriedValue(knob, saved[knob.name])
            value?.let { params[knob.name] = it }
        }

        return SlotOptions(id, budget, BotParams(params))
    }

    /** Points this seat at [bot] under [budgetPerTurn] and [params] — how a loaded replay comes back. */
    fun apply(bot: BotId?, budgetPerTurn: Int, params: BotParams) {
        select.value = bot?.slug ?: ""
        if (select.value != (bot?.slug ?: "")) {
            select.selectedIndex = 0
        }

        // Straight into the memory rather than into the fields, because refresh() reads from there
        // and would overwrite anything written the other way round.
        val slug = select.value
        if (slug.isNotEmpty()) {
            val values = remembered.getOrPut(slug) { LinkedHashMap() }
            values[BotKnob.Search.NAME] = budgetPerTurn.toString()
            for (name in params.names) {
                values[name] = params.string(name, "")
            }
        }

        rows = emptyList()
        shown = null
        refresh()
    }

    override fun toString(): String = "SlotForm(${select.id}, ${rows.size} knobs)"

    // -- internals

    private fun selected(): BotEntry? = select.value.takeIf { it.isNotEmpty() }?.let { registry[BotId(it)] }

    /** Puts the current values away under the bot they belong to, before the panel is torn down. */
    private fun remember() {
        val entry = shown ?: return
        val values = remembered.getOrPut(entry.id.slug) { LinkedHashMap() }
        for (row in rows) {
            values[row.knob.name] = row.text()
        }
    }

    private fun defaultTextOf(knob: BotKnob): String = when (knob) {
        // A bot does not get to name the engine's default allowance; the match does.
        is BotKnob.Search -> MatchSetup.DEFAULT_BUDGET_PER_TURN.coerceIn(knob.min, knob.max).toString()
        is BotKnob.Param<*> -> knob.defaultText
    }

    /**
     * The allowance, coerced rather than trusted.
     *
     * Every reading here has to be total. `Match` builds its bots in a field initializer, outside
     * the `try` that guards `chooseMove`, so a value that threw on the way in would escape the click
     * handler with nothing above it to catch it and take the page down.
     */
    private fun allowanceOf(row: Row, knob: BotKnob.Search): Int {
        val typed = row.text().trim().toIntOrNull()
        val value = typed?.coerceIn(knob.min, knob.max)
            ?: MatchSetup.DEFAULT_BUDGET_PER_TURN.coerceIn(knob.min, knob.max)

        row.settle(value.toString(), rejected = typed == null)
        return value
    }

    /**
     * The same, for a knob with no row: whatever was carried in for it, or `null`.
     *
     * No `settle` and no rejection mark, because there is no control to correct or to colour. An
     * unparseable leftover is simply dropped, which lands the bot on the very default
     * [BotKnob.Param.read] would have coerced it to — so the two paths agree and neither can throw.
     */
    private fun carriedValue(knob: BotKnob.Param<*>, text: String?): String? {
        val carried = text ?: return null
        val usable = knob.reject(carried) == null && !knob.isDefault(carried)
        return if (usable) carried else null
    }

    /** The value if it departs from the declared default, or `null` if there is nothing to say. */
    private fun valueOf(row: Row, knob: BotKnob.Param<*>): String? {
        val typed = row.text()
        val complaint = knob.reject(typed)
        if (complaint != null) {
            row.settle(knob.defaultText, rejected = true)
            return null
        }

        row.settle(typed, rejected = false)
        // Omitting the defaults is what keeps an untouched seat's replay URL byte-identical to the
        // one it would have produced before any of this existed.
        return if (knob.isDefault(typed)) null else typed
    }

    /**
     * One row: a caption, and whatever control the knob's own type calls for.
     *
     * A [BotKnob.Choice] is the one that is not an `<input>`, and the `when` is a statement over a
     * sealed hierarchy with no `else` — so a knob type added to `:bot-api` and forgotten here is a
     * compile error rather than a control that silently never appears.
     */
    private fun rowFor(knob: BotKnob, value: String): Row {
        val label = document.createElement("label") as HTMLLabelElement
        label.className = if (knob is BotKnob.Search) "knob granted" else "knob"

        val caption = document.createElement("span") as HTMLElement
        caption.textContent = knob.label
        label.appendChild(caption)

        val field: HTMLElement = when (knob) {
            is BotKnob.Choice -> chooser(knob, value)
            is BotKnob.Flag -> checkbox(knob, value)
            is BotKnob.Integer -> number(knob.min.toString(), knob.max.toString(), knob.step.toString(), value)
            is BotKnob.Decimal -> number(knob.min.toString(), knob.max.toString(), knob.step.toString(), value)
            is BotKnob.Search -> number(knob.min.toString(), knob.max.toString(), knob.step.toString(), value)
        }
        field.title = knob.help
        label.appendChild(field)

        grid.appendChild(label)
        return Row(knob, field)
    }

    /**
     * A knob's control, whichever element it turned out to be.
     *
     * Widened from `HTMLInputElement` when [BotKnob.Choice] arrived. Reading and writing branch on
     * the element rather than on the knob, because those are the two things that actually differ —
     * a `<select>` has no `checked` and a checkbox has no meaningful `value`.
     */
    private class Row(val knob: BotKnob, private val field: HTMLElement) {
        fun text(): String = when (val element = field) {
            is HTMLSelectElement -> element.value
            is HTMLInputElement -> if (element.type == "checkbox") element.checked.toString() else element.value
            else -> ""
        }

        /**
         * Writes [value] back and says whether it had to be corrected.
         *
         * Correcting the field rather than merely the value is the whole point: a match that
         * silently played at a number nobody typed would be worse than one that refused to start.
         * The same shape the seed field already uses for an unreadable seed.
         */
        fun settle(value: String, rejected: Boolean) {
            when (val element = field) {
                is HTMLSelectElement -> if (element.value != value) {
                    element.value = value
                }

                is HTMLInputElement -> if (element.type == "checkbox") {
                    element.checked = value.trim().toBooleanStrictOrNull() ?: element.checked
                } else if (element.value != value) {
                    element.value = value
                }

                else -> Unit
            }

            if (rejected) {
                field.classList.add(REJECTED)
            } else {
                field.classList.remove(REJECTED)
            }
        }
    }

    private companion object {
        const val REJECTED = "rejected"

        /**
         * The one place `:ui` writes an `<option>` that is not a bot picker, and the same exception
         * covers it: what goes in the container comes off the registry, and the container is static.
         */
        fun chooser(knob: BotKnob.Choice, value: String): HTMLSelectElement {
            val select = document.createElement("select") as HTMLSelectElement
            select.autocomplete = "off"
            for (offered in knob.values) {
                val option = document.createElement("option") as HTMLOptionElement
                option.value = offered
                option.textContent = offered
                select.appendChild(option)
            }
            select.value = value.trim().takeIf { it in knob.values } ?: knob.default
            return select
        }

        fun checkbox(knob: BotKnob.Flag, value: String): HTMLInputElement {
            val input = field()
            input.type = "checkbox"
            input.checked = value.trim().toBooleanStrictOrNull() ?: knob.default
            return input
        }

        fun number(min: String, max: String, step: String, value: String): HTMLInputElement {
            val input = field()
            input.type = "number"
            input.min = min
            input.max = max
            input.step = step
            input.value = value
            return input
        }

        fun field(): HTMLInputElement =
            (document.createElement("input") as HTMLInputElement).apply { autocomplete = "off" }

        /** Emptied a node at a time rather than through `innerHTML`, which is writing structure. */
        fun HTMLElement.clear() {
            while (firstChild != null) {
                removeChild(firstChild!!)
            }
        }

        fun HTMLElement.child(selector: String): HTMLElement =
            querySelector(selector) as? HTMLElement ?: error("a settings panel is missing $selector")
    }
}
