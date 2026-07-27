package ao.snakewarz.botapi.scratch

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.rules.MoveOutcome
import ao.snakewarz.core.snake.SnakeId

/**
 * A private copy of the match a bot may play forward and take back, at zero allocation per move.
 *
 * The canonical rollout is:
 *
 * ```kotlin
 * val p = turn.scratch.playout()
 * while (p.outcome == null) p.advance(policy.pick(p))
 * ```
 *
 * and the thing to notice is that **the loop condition is also the budget check**. [advance] charges
 * the turn's [ao.snakewarz.core.Budget] internally, and once that is spent [outcome] reports a draw
 * whether the game is over or not. So a search terminates *structurally*: for the great majority of
 * bots, whose cost is dominated by simulation, budget enforcement is automatic rather than trusted.
 *
 * The honest limit: single-threaded wasm cannot preempt a bot spinning in a loop that simulates
 * nothing. Nothing here pretends otherwise. The mitigations are the contract suite in CI and the
 * renderer's frame-time guard.
 */
public interface Playout {
    /** The position, live — it changes with every [advance] and [undo]. */
    public val board: BoardView

    public val toAct: SnakeId

    /**
     * `null` while there is more to simulate.
     *
     * Non-null either because the game genuinely ended or because the budget ran out, in which case
     * it is a draw. A search that treats an exhausted rollout as a draw is doing the right thing:
     * it has no information about who would have won, and saying so is better than guessing.
     */
    public val outcome: MatchOutcome?

    /** [toAct] moves, and the turn passes to the next living snake. Charges one unit of budget. */
    public fun advance(direction: Direction): MoveOutcome

    /** As [advance], but naming the mover explicitly. It must be [toAct]. */
    public fun apply(id: SnakeId, direction: Direction): MoveOutcome

    /** Takes back the last move, restoring the position bit for bit — hash included. */
    public fun undo()

    /** How many moves can still be taken back. */
    public val undoDepth: Int

    /** Returns to the live match position, discarding the whole line. Does not refund budget. */
    public fun reset()
}
