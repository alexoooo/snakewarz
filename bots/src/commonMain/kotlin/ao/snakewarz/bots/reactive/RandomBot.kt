package ao.snakewarz.bots.reactive

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.grid.Direction

/**
 * Picks uniformly among the moves that do not kill it. A semantic port of the legacy `RandomAi`.
 *
 * Weak on purpose, and useful out of all proportion to that: it is the default MCTS rollout policy,
 * the opponent every other bot is measured against, and the thing the golden move-stream tests hash.
 *
 * When there is nothing legal left it plays a random direction and dies, rather than resigning. The
 * difference is not cosmetic — it is the difference between a match that ends in `TRAPPED`, which is
 * what actually happened, and one that ends in `RESIGNED`, which is not.
 */
public class RandomBot(setup: BotSetup) : Bot {
    private val rng = setup.rng

    override fun chooseMove(turn: Turn): Decision {
        val move = rng.pick(turn.legalMoves)
            ?: Direction.entries[rng.nextInt(Direction.entries.size)]
        return Decision.Move(move)
    }

    override fun toString(): String = "RandomBot"
}
