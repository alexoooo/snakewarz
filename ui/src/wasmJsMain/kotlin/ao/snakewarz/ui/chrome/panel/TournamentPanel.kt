package ao.snakewarz.ui.chrome.panel

import ao.snakewarz.match.tournament.TournamentFormat
import ao.snakewarz.ui.chrome.elementById
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement

/**
 * `#panel-tournament`: the schedule a batch runs to, and the matrix it produces.
 *
 * Who is playing is not asked here — the seats under `#panel-setup` are the field, and
 * [SetupPanel.readTournamentOptions] turns them into contestants. This panel states only how they are
 * paired and how many times.
 *
 * **The matrix is text.** `TournamentTable.toString()` lays it out in `:match` and this writes the
 * whole thing into one `<pre>`, which is the case that most invites building DOM in Kotlin and does
 * not: a table element here would be a second account of a layout that already exists, and the one in
 * `:match` is the one `:lab` prints.
 *
 * While a batch runs it owns the arena. `GameSession` paints its current match and builds the whole
 * model from it, the transport is greyed, and `dispatch` drops transport intents outright — the space
 * bar does not read the DOM's disabled flags.
 */
internal class TournamentPanel(dispatch: (UiIntent) -> Unit) {
    private val formatSelect: HTMLSelectElement = elementById("format")
    private val roundsSelect: HTMLSelectElement = elementById("rounds")
    private val runButton: HTMLButtonElement = elementById("run-tournament")
    private val progress: HTMLElement = elementById("tournament-progress")
    private val table: HTMLElement = elementById("tournament-table")

    init {
        runButton.addEventListener("click") { dispatch(UiIntent.ToggleTournament) }
    }

    /** How many times each pairing is played. */
    val rounds: Int get() = roundsSelect.value.toIntOrNull() ?: DEFAULT_ROUNDS

    val format: TournamentFormat
        get() = if (formatSelect.value == FREE_FOR_ALL_VALUE) {
            TournamentFormat.FREE_FOR_ALL
        } else {
            TournamentFormat.HEAD_TO_HEAD
        }

    fun render(model: UiModel) {
        val status = model.tournament

        runButton.textContent = if (status?.running == true) "Stop" else "Run tournament"
        progress.textContent = status?.progress ?: ""

        table.hidden = status == null || status.table.isEmpty()
        if (status != null) {
            table.textContent = status.table
        }
    }

    override fun toString(): String = "TournamentPanel($rounds rounds, $format)"

    private companion object {
        /** Must be one of the options on `#rounds` in index.html, and even. */
        const val DEFAULT_ROUNDS = 20

        /** Must be the value of the free-for-all option on `#format` in index.html. */
        const val FREE_FOR_ALL_VALUE = "free-for-all"
    }
}
