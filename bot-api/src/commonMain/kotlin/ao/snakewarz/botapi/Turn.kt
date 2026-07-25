package ao.snakewarz.botapi

import ao.snakewarz.core.BoardView
import ao.snakewarz.core.Budget
import ao.snakewarz.core.DirectionSet
import ao.snakewarz.core.SnakeId
import ao.snakewarz.core.SnakeView

/**
 * Everything a bot is given for one decision.
 *
 * [board] is a live projection of the driver's arena, not a snapshot — reading it is free, and it
 * changes under you as the match proceeds. Anything a bot wants to keep past this turn it must copy.
 * To *explore* moves, use [scratch] rather than trying to mutate anything here; there is no mutating
 * method in reach, by design.
 */
public class Turn(
    public val board: BoardView,
    public val self: SnakeId,
    /**
     * The directions from this snake's head that do not end in a wall or a body.
     *
     * Empty means doomed: whatever it plays is fatal, and the engine will record it as `TRAPPED`
     * rather than blame the bot with `SUICIDE`.
     */
    public val legalMoves: DirectionSet,
    /** This turn's search allowance, already reset. Counted in iterations, never in milliseconds. */
    public val budget: Budget,
    public val scratch: Scratch,
) {
    /** This bot's own snake — head, tail, length and whether the next move grows it. */
    public val me: SnakeView get() = board.snake(self)

    override fun toString(): String = "Turn(${board.turnIndex}, $self, $legalMoves, $budget)"
}
