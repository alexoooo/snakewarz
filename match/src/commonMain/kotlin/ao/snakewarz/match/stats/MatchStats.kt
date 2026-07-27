package ao.snakewarz.match.stats

import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup

/**
 * A match as numbers, computed once from the live position by [Match.stats].
 *
 * Everything here is *derived*: the driver counts nothing extra as it goes, because everything worth
 * reporting is already in the board — lengths, moves survived, who is left and why the rest are not.
 * A statistic the engine would have to be modified to collect is a statistic that has to stay correct
 * forever after, and none of these are.
 *
 * Taken at any point, not only at the end. A live match reports where it currently stands, which is
 * what lets one panel serve a game in progress, a finished game and a recording being scrubbed
 * through — the same way [Match] itself serves playing and replaying without a branch.
 */
public class MatchStats internal constructor(
    public val setup: MatchSetup,
    /** Individual moves played, counting every snake. Not rounds — see [rounds]. */
    public val turnsPlayed: Int,
    /** `null` while the match is still running. */
    public val outcome: MatchOutcome?,
    public val slots: List<SlotStats>,
) {
    /** Squares a snake could ever stand on. The wall ring the engine pads with is not one of them. */
    public val playableCells: Int = setup.rows * setup.cols

    /** Squares under a body, the dead included — a corpse is an obstacle and still holds ground. */
    public val occupiedCells: Int = slots.sumOf { it.length }

    /** How much of the board is no longer anybody's to move into, in `0.0..1.0`. */
    public val fillRate: Double = occupiedCells.toDouble() / playableCells

    /**
     * How many moves the busiest snake has made.
     *
     * The closest honest thing to a round count. Snakes do not act equally often once one of them is
     * out, so there is no single number of rounds — but the leader's move count is what a person
     * watching would call "how far in we are", and it is the one that stops climbing when they die.
     */
    public val rounds: Int = slots.maxOf { it.movesMade }

    public val survivors: Int = slots.count { it.alive }

    /** The slot that won, or `null` for a draw or a match still being played. */
    public val winner: SlotStats? = outcome?.winner?.takeIf { !it.isNone }?.let { slots[it.index] }

    public val finished: Boolean get() = outcome != null

    /** The longest snake, which is not always the winner and is more interesting when it is not. */
    public val longest: SlotStats = slots.maxBy { it.length }

    public fun of(slot: SnakeId): SlotStats = slots[slot.index]

    override fun toString(): String =
        "MatchStats(${setup.rows}x${setup.cols}, $turnsPlayed turns, ${(fillRate * 100).toInt()}% full, $outcome)"
}
