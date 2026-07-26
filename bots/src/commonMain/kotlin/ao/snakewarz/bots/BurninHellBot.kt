package ao.snakewarz.bots

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Direction

/**
 * Takes the first direction that is open, always in the same order. A semantic port of `Burninhell`,
 * contributed to the original 2005 project (`legacy/java/ao/ai/da/Burninhell.java`).
 *
 * "First of four" undersells what that produces. The one direction a moving snake can never take is
 * the one its own neck is in, so the fixed order plus that exclusion turns into a **serpentine sweep**
 * of the board: it runs north until the wall, where north is gone and south is its neck, so it steps
 * east; on the top row north is a wall, so it dives south; running south its neck is north, so it
 * keeps diving to the bottom wall and steps east again. Full-height columns, one column east per
 * pass — a systematic fill rather than a walk, and nothing else on the roster plays like it.
 *
 * What it does not have is any notion of a pocket, so it dies where the sweep meets its own earlier
 * column or a corpse: it will step into a dead end as readily as anywhere else, because the dead end
 * was north. That is the bot, and the whole of it.
 *
 * Costs no budget and consumes no randomness at all, which makes it the second bot after
 * [WallHugBot] whose move stream is pinned by the rules alone.
 *
 * Two things about the port. It extended `PvpAi`, so every turn it paid for a path search per
 * opponent to reduce them to a single `opp` — and then never read `opp`. The reduction is dropped
 * rather than ported, which is also why `PvpAi`'s walled-off-opponent bug never bit this one. And
 * legacy had no branch for being trapped, because `MoveTracker.directionOfMoveBy`
 * (`sw/engine/MoveTracker.java:37-40`) checked for an empty direction list and never called the bot
 * at all. This engine does call it — `Turn.legalMoves` is genuinely empty on a 1x1 board, which is
 * the first thing the contract suite tries — so the fall-through is spelled out here.
 */
public class BurninHellBot : Bot {
    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        for (direction in PRIORITY) {
            if (direction in legal) {
                return Decision.Move(direction)
            }
        }

        // Doomed: every direction is the same death, and the engine will record it as TRAPPED.
        return Decision.Move(Direction.NORTH)
    }

    override fun toString(): String = "BurninHellBot"

    private companion object {
        /**
         * Legacy's `if / else if` chain, in its order.
         *
         * `Direction.entries` happens to agree with it today, so `legal.nth(0)` would pick the same
         * move — and would make this bot's whole identity a silent consequence of an enum's
         * declaration order. One shared array costs nothing and says what it means.
         */
        val PRIORITY = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
    }
}
