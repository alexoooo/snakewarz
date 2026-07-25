package ao.snakewarz.bots

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Direction

/**
 * Goes straight while it can, then turns left, then right — a semantic port of the legacy
 * `WallHugAi`, which tried `FOREWARD, LEFT, RIGHT` in exactly that order.
 *
 * The emergent behaviour is a snake that runs to a wall and then follows it around, filling the
 * board in a spiral. It carries no state and consumes no randomness, so two of them on a symmetric
 * board play a symmetric game — which makes it a good fixture as well as a good sparring partner.
 *
 * Two places where the legacy version was under-specified and this one is not. Its *first* move came
 * from `MoveTracker`, which seeded an unset direction with the first available one — so the bot
 * played a move nobody chose; here the first move is the lowest-ordinal legal direction, stated
 * outright. And when nothing was available it set no direction at all; here it reverses, which on a
 * board with no way out is fatal either way, and at length one is a genuine escape.
 */
public class WallHugBot : Bot {
    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        val facing = turn.me.lastDirection
            ?: return Decision.Move(if (legal.isEmpty) Direction.NORTH else legal.nth(0))

        val choice = when {
            facing in legal -> facing
            facing.turnedLeft in legal -> facing.turnedLeft
            facing.turnedRight in legal -> facing.turnedRight
            else -> facing.opposite
        }
        return Decision.Move(choice)
    }

    override fun toString(): String = "WallHugBot"
}
