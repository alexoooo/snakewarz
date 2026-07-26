package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.EliminationReason
import ao.snakewarz.core.SnakeId

/**
 * One slot's numbers, frozen at the moment [Match.stats] was called.
 *
 * Carries the [bot] as well as the [slot], because a slot index means nothing on its own: the whole
 * point of reading these is to compare who was playing, and the seating is shuffled per match.
 */
public class SlotStats internal constructor(
    public val slot: SnakeId,
    public val bot: BotId,
    /**
     * Squares this snake's body occupies.
     *
     * A dead snake keeps its length: the body stays on the board as an obstacle, which is most of
     * what makes a three-way match interesting, so it is still holding that much territory.
     */
    public val length: Int,
    /**
     * Moves survived.
     *
     * The fatal one is not among them — a move into an occupied square never lands, so the engine
     * does not count it. This is therefore "how long it lasted" and never "how long plus one".
     */
    public val movesMade: Int,
    public val alive: Boolean,
    /** Why it left, or `null` while it is still in the match. */
    public val fate: EliminationReason?,
    public val winner: Boolean,
) {
    override fun toString(): String =
        "SlotStats($slot, $bot, length=$length, moves=$movesMade" +
            (if (alive) ")" else ", $fate)")
}
