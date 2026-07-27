package ao.snakewarz.ui

import ao.snakewarz.match.MatchStats

/**
 * Everything the chrome renders, computed once per frame rather than per turn.
 *
 * At the top speeds this thing runs at a frame is worth dozens of turns, and a DOM write per turn
 * would cost more than the whole match does. The board is painted per turn because painting two
 * rectangles is nearly free; the text around it is written once a frame because writing text is not.
 *
 * Data travels one way only. [stats] is the driver's own snapshot rather than a copy of it — it is
 * frozen at the moment it was taken and holds no handle back to the match, so the chrome still cannot
 * reach the game even by accident, and the scoreboard and the stats panel cannot disagree because
 * there is one set of numbers rather than two.
 */
internal class UiModel(
    /** Watching a recording rather than playing a match. Reveals the scrub bar. */
    val replay: Boolean,
    /**
     * A person is still in this match, so the keyboard is its clock rather than the scheduler.
     * Disables the transport: there is no clock to start, stop or step.
     */
    val interactive: Boolean,
    val running: Boolean,
    /** The length of the recording, or the current turn when there is no recording to be ahead of. */
    val turnCount: Int,
    /** One sentence about where the match is, already worded for a person. */
    val status: String,
    val stats: MatchStats,
    /** What to call each seat of the match [stats] describes. Rebuilt only when that match changes. */
    val labels: SlotLabels,
    /** The snake under the pointer, or `null` when the pointer is not over one. */
    val hover: HoverInfo?,
    /**
     * The player's own match is finished and can be watched again. About *their* match, never the
     * one a batch has on the board — that one is the tournament's to drive, not theirs to rewind.
     */
    val canWatchReplay: Boolean,
    /** Non-null once the player has asked for a link. */
    val shareUrl: String?,
    /** Non-null once a batch has been run, whether or not it is still running. */
    val tournament: TournamentStatus?,
) {
    val turnIndex: Int get() = stats.turnsPlayed

    /** A running batch owns the board, so the match transport has nothing to drive. */
    val batchRunning: Boolean get() = tournament?.running == true
}
