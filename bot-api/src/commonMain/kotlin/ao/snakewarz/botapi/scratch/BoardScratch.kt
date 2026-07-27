package ao.snakewarz.botapi.scratch

import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.rules.MoveOutcome
import ao.snakewarz.core.snake.SnakeId

/**
 * The [Scratch] the driver hands to a bot: one arena board, copied from the live match on reset.
 *
 * The arena is allocated once per slot per match and then reused forever. A rollout costs one
 * `copyFrom` at the start and nothing at all per move, because [Board] mutates in place and unwinds
 * through its undo journal. That is the whole reason this engine exists in the shape it does: the
 * legacy design allocated a fresh persistent board per search node.
 */
public class BoardScratch(
    private val source: Board,
    private val budget: Budget,
) : Scratch {
    private val instance = ArenaPlayout()

    override fun playout(): Playout {
        instance.reset()
        return instance
    }

    private inner class ArenaPlayout : Playout {
        private val arena: Board = source.copy()

        override val board: BoardView get() = arena

        override val toAct: SnakeId get() = arena.toAct

        override val outcome: MatchOutcome?
            get() = arena.outcome ?: if (budget.exhausted) EXHAUSTED else null

        override fun advance(direction: Direction): MoveOutcome = apply(arena.toAct, direction)

        override fun apply(id: SnakeId, direction: Direction): MoveOutcome {
            val over = outcome
            check(over == null) { "the playout is over: $over" }

            budget.tryConsume()
            return arena.apply(id, direction)
        }

        override fun undo() {
            arena.undo()
        }

        override val undoDepth: Int get() = arena.undoDepth

        override fun reset() {
            arena.copyFrom(source)
        }

        override fun toString(): String = "ArenaPlayout($budget)"
    }

    public companion object {
        /**
         * What an out-of-budget rollout reports.
         *
         * Deliberately the ordinary turn-limit draw rather than a new [MatchEnd] case: it is
         * literally "the move allowance ran out with nobody having won", it needs no encoding in the
         * replay format, and a search evaluating it as a draw is evaluating it correctly.
         */
        public val EXHAUSTED: MatchOutcome = MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT)
    }
}
