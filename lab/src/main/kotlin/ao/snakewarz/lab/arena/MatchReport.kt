package ao.snakewarz.lab.arena

import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.match.replay.MatchRecord
import ao.snakewarz.match.stats.MatchStats
import ao.snakewarz.match.tournament.PairwiseOutcome

/**
 * One finished match, as everything anybody downstream asks of it.
 *
 * Kept rather than folded straight into a matrix, because a win count is the *least* of what a match
 * knows and everything else — who died how, how long it took, which opening it was, whether it was
 * the same game as the last one — is discarded the moment the matrix is all that survives. Ratings,
 * diagnostics and sequential tests all read these.
 */
internal class MatchReport(
    /** Where in the schedule this was, which is what makes a batch's output independent of threading. */
    val index: Int,
    /** Which matches shared this board — see `TournamentSchedule.pairKeyFor`. */
    val pairKey: Int,
    /** Which contestant sat in which seat. */
    val seating: IntArray,
    val stats: MatchStats,
    val comparisons: List<PairwiseOutcome>,
    /**
     * A fold over the moves played, so two matches can be told apart without keeping either.
     *
     * The whole point of counting these: a batch of a hundred matches that holds four distinct hashes
     * measured four games, and nothing else in a result would have said so.
     */
    val moveStreamHash: Long,
    /** Wall clock, and contended when threads share a machine — a within-run ratio, never a figure. */
    val elapsedMicros: Long,
    /** Present only when the run was asked to keep replays. */
    val record: MatchRecord?,
) {
    val seed: Long get() = stats.setup.seed

    /** A bot that threw. Always a defect, never a result — see [Arena]. */
    val forfeits: Int get() = stats.slots.count { it.fate == EliminationReason.FORFEIT }

    override fun toString(): String = "MatchReport(#$index, pair $pairKey, ${stats.outcome})"
}
