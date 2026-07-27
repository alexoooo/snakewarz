package ao.snakewarz.ui.model

/**
 * What the tournament panel shows. Already worded and already rendered; the chrome only writes it.
 *
 * [table] is text rather than a structure to build DOM from, because `TournamentTable` already knows
 * how to lay a win-rate matrix out and the result goes into one `<pre>`. That keeps the promise the
 * rest of `:ui` keeps — the chrome writes text, values and hidden flags, and never constructs
 * structure — for the one panel most likely to have broken it.
 */
internal class TournamentStatus(
    val running: Boolean,
    /** Where it has got to, worded for a person: "match 84 of 180 — uct vs chase". */
    val progress: String,
    /** The matrix so far, or empty before the first match finishes. */
    val table: String,
)
