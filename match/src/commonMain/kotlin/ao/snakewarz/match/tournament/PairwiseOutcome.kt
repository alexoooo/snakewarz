package ao.snakewarz.match.tournament

import ao.snakewarz.core.snake.SnakeId

/**
 * How two slots of one match compared: which of them did better, or that neither did.
 *
 * In **slot** space rather than contestant space, because a match knows where a snake sat and
 * nothing about who entered it. The caller maps [one] and [other] through its own seating, which is
 * what lets a single scoring rule serve [Tournament], which keeps a seating array as it plays, and a
 * batch runner outside this module, which asks [TournamentSchedule] for one.
 */
public class PairwiseOutcome(
    public val one: SnakeId,
    public val other: SnakeId,
    /** [one], [other], or [SnakeId.NONE] for a draw. */
    public val winner: SnakeId,
) {
    init {
        require(!one.isNone && !other.isNone) { "a comparison is between two slots, was $one and $other" }
        require(one != other) { "$one cannot be compared with itself" }
        require(winner.isNone || winner == one || winner == other) {
            "$winner was not one of the two slots compared, $one and $other"
        }
    }

    /** The slot that did worse, or [SnakeId.NONE] when neither did. */
    public val loser: SnakeId
        get() = when {
            winner.isNone -> SnakeId.NONE
            winner == one -> other
            else -> one
        }

    public val isDraw: Boolean get() = winner.isNone

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairwiseOutcome) return false
        return one == other.one && this.other == other.other && winner == other.winner
    }

    override fun hashCode(): Int = (31 * one.index + other.index) * 31 + winner.index

    override fun toString(): String =
        if (isDraw) "PairwiseOutcome($one drew $other)" else "PairwiseOutcome($winner beat $loser)"
}
