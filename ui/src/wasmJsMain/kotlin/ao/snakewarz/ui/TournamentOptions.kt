package ao.snakewarz.ui

import ao.snakewarz.botapi.BotId

/**
 * The tournament form, read off the DOM once and handed on as a value.
 *
 * [contestants] comes from the same slot pickers the new-match form uses, which is why there is no
 * second list of bots on the page: the sidebar already says who is playing, and a tournament is that
 * question asked over a few hundred matches instead of one. A human seat and a bot picked twice are
 * both dropped on the way through — neither is a contestant.
 */
internal class TournamentOptions(
    val rows: Int,
    val cols: Int,
    val seed: Long,
    val contestants: List<BotId>,
    val rounds: Int,
) {
    val ready: Boolean get() = contestants.size >= MINIMUM_CONTESTANTS

    override fun toString(): String = "TournamentOptions(${rows}x$cols, $rounds rounds, $contestants)"

    companion object {
        /** A win-rate matrix is a statement about pairs, and one bot makes no pairs. */
        const val MINIMUM_CONTESTANTS: Int = 2
    }
}
