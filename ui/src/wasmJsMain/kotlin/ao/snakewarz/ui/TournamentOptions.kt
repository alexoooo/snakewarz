package ao.snakewarz.ui

import ao.snakewarz.match.Contestant

/**
 * The tournament form, read off the DOM once and handed on as a value.
 *
 * [contestants] comes from the same slot pickers the new-match form uses, which is why there is no
 * second list of bots on the page: the sidebar already says who is playing, and a tournament is that
 * question asked over a few hundred matches instead of one. A human seat and a seat identical to one
 * already entered are both dropped on the way through — neither is a contestant.
 *
 * *Identical* now means identically configured rather than merely the same bot, so seating `uct` at
 * two allowances enters two contestants and the batch runs the comparison the testbed exists for.
 */
internal class TournamentOptions(
    val rows: Int,
    val cols: Int,
    val seed: Long,
    val contestants: List<Contestant>,
    val rounds: Int,
) {
    val ready: Boolean get() = contestants.size >= MINIMUM_CONTESTANTS

    override fun toString(): String = "TournamentOptions(${rows}x$cols, $rounds rounds, $contestants)"

    companion object {
        /** A win-rate matrix is a statement about pairs, and one bot makes no pairs. */
        const val MINIMUM_CONTESTANTS: Int = 2
    }
}
