package ao.snakewarz.ui

/**
 * Everything the chrome renders, computed once per frame rather than per turn.
 *
 * At the top speeds this thing runs at a frame is worth dozens of turns, and a DOM write per turn
 * would cost more than the whole match does. The board is painted per turn because painting two
 * rectangles is nearly free; the text around it is written once a frame because writing text is not.
 *
 * Deliberately a plain snapshot with no engine types in it: the chrome cannot reach back into the
 * match even by accident, so there is exactly one direction data can travel.
 */
internal class UiModel(
    /** Watching a recording rather than playing a match. Reveals the scrub bar. */
    val replay: Boolean,
    val running: Boolean,
    val turnIndex: Int,
    /** The length of the recording, or [turnIndex] when there is no recording to be ahead of. */
    val turnCount: Int,
    /** One sentence about where the match is, already worded for a person. */
    val status: String,
    val slots: List<SlotStatus>,
    /** Non-null once the player has asked for a link. */
    val shareUrl: String?,
)
