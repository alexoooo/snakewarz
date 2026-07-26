package ao.snakewarz.bots

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Cell
import ao.snakewarz.core.Direction

/**
 * Goes wherever there is the most room, measured by flood fill. A semantic port of legacy `ForkAi`.
 *
 * It is the first bot here that looks more than one square ahead, and the jump in strength is out of
 * all proportion to its size: a wall hugger walks into pockets it could have seen, and this one
 * never does. It plays no attention to its opponents at all — it is trying not to trap *itself*,
 * which on a board this size is most of the game.
 *
 * Costs no budget. Every candidate is one breadth-first sweep over the free squares, the arrays are
 * allocated once per match, and nothing is simulated — so this is also the sensible fallback for a
 * search bot handed an allowance it cannot search with. [UctBot] uses it for exactly that.
 *
 * Two things legacy left vague and this does not. Its flood fill ran on the board *after* the move,
 * which differs from the board before it by one square — the tail, and only on the turns the tail
 * actually retracts; here that is stated outright as [FloodFill.reachable]'s `alsoFree`. And ties
 * are broken uniformly from the slot's own stream rather than from a global `Rand`, so two of these
 * on a symmetric board still play a reproducible game.
 */
public class SpaceBot(setup: BotSetup) : Bot {
    private val rng = setup.rng
    private val fill = FloodFill(setup.grid)

    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        if (legal.isEmpty) {
            // Doomed: every direction is the same death, and the engine will record it as TRAPPED.
            return Decision.Move(Direction.NORTH)
        }

        val board = turn.board
        val me = turn.me
        val head = me.head

        // The square the tail is about to leave is free by the time the fill would reach it -- but
        // only on the turns it actually leaves, because snakes here grow at half speed.
        val vacating = if (me.growsOnNextMove) Cell.NONE else me.tail

        var chosen = legal.nth(0)
        var most = -1
        var tied = 0

        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val room = fill.reachable(board, board.grid.step(head, direction), vacating)

            if (room > most) {
                most = room
                tied = 1
                chosen = direction
            } else if (room == most) {
                // Reservoir sampling, so the choice is uniform across the tied directions without
                // building a list to pick from. `Rand.fromList` allocated one per turn.
                tied++
                if (rng.nextInt(tied) == 0) {
                    chosen = direction
                }
            }
        }

        return Decision.Move(chosen)
    }

    override fun toString(): String = "SpaceBot"
}
