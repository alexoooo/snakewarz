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
 * and the thing to notice is that **the loop condition is also the budget check** — but at the top
 * rather than throughout. [Scratch.playout] charges for the evaluation before handing this over, and
 * refuses once there is not enough left, in which case [outcome] is non-null before a single move is
 * made and the loop never runs. So a search terminates *structurally*: an iteration it cannot afford
 * is an iteration it cannot start.
 *
 * Once the evaluation is paid for, [advance] charges nothing and [outcome] is the game's own —
 * a rollout that has begun always finishes, bounded by the rules' turn limit rather than by an
 * allowance expiring half way through a line nobody can credit.
 *
 * The honest limit: single-threaded wasm cannot preempt a bot spinning in a loop that asks for no
 * playout at all. Nothing here pretends otherwise. The mitigations are the contract suite in CI and
 * the renderer's frame-time guard.
 */
public interface Playout {
    /** The position, live — it changes with every [advance] and [undo]. */
    public val board: BoardView

    public val toAct: SnakeId

    /**
     * `null` while there is more to simulate.
     *
     * Non-null either because the game genuinely ended or because the allowance would not stretch to
     * this evaluation at all, in which case it is a draw carrying no information — see
     * [BoardScratch.EXHAUSTED]. It cannot become the second of those part-way through: an evaluation
     * is paid for before it starts.
     */
    public val outcome: MatchOutcome?

    /** [toAct] moves, and the turn passes to the next living snake. */
    public fun advance(direction: Direction): MoveOutcome

    /** As [advance], but naming the mover explicitly. It must be [toAct]. */
    public fun apply(id: SnakeId, direction: Direction): MoveOutcome

    /** Takes back the last move, restoring the position bit for bit — hash included. */
    public fun undo()

    /** How many moves can still be taken back. */
    public val undoDepth: Int
}
