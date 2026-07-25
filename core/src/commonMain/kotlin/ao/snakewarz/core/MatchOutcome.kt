package ao.snakewarz.core

/**
 * The terminal result of a match: who won, if anyone, and how it ended.
 *
 * `BoardView.outcome` is `null` while a match is running, which is the condition every rollout loop
 * spins on. [Board] builds this object once, when the match reaches a terminal state, and hands out
 * the same instance afterwards — so a rollout of several hundred steps allocates one of these, not
 * one per step.
 */
public data class MatchOutcome(
    /** The winner, or [SnakeId.NONE] for a draw. */
    public val winner: SnakeId,
    public val end: MatchEnd,
) {
    public val isDraw: Boolean get() = winner.isNone
}
