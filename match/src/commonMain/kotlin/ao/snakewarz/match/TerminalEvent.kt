package ao.snakewarz.match

import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.snake.SnakeId

/**
 * A turn on which a snake left the match without moving — the replay's side table.
 *
 * Only `RESIGNED` and `FORFEIT` appear here, and there are at most `slots - 1` of them in a whole
 * match. `TRAPPED` and `SUICIDE` are not events at all: they are recorded directions that turn out
 * to be fatal when replayed, so they cost nothing beyond the two bits every turn costs anyway.
 *
 * The turn a listed event names carries **no** entry in the move stream, which is what keeps that
 * stream dense.
 */
public class TerminalEvent(
    public val turnIndex: Int,
    public val slot: SnakeId,
    public val reason: EliminationReason,
) {
    init {
        require(turnIndex >= 0) { "turn index must not be negative, was $turnIndex" }
        require(!slot.isNone) { "a terminal event names a slot" }
        require(reason == EliminationReason.RESIGNED || reason == EliminationReason.FORFEIT) {
            "$reason is implied by the recorded move and needs no entry"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalEvent) return false
        return turnIndex == other.turnIndex && slot == other.slot && reason == other.reason
    }

    override fun hashCode(): Int = (31 * turnIndex + slot.index) * 31 + reason.ordinal

    override fun toString(): String = "TerminalEvent(turn $turnIndex, $slot, $reason)"
}
