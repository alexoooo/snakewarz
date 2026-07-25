package ao.snakewarz.match

import ao.snakewarz.core.Direction
import ao.snakewarz.core.EliminationReason
import ao.snakewarz.core.MatchOutcome
import ao.snakewarz.core.MoveOutcome
import ao.snakewarz.core.SnakeId

/**
 * What one call to [Match.step] did. Exactly one thing, always — which is the point.
 *
 * This replaces the legacy `SnakesGame2` loop wholesale: a side-effecting `for` condition,
 * `players.set(i, null)` tombstones and a `nextPlayer = -1` sentinel, all of which had to be read
 * together to work out what had just happened.
 *
 * The two ways a snake dies stay apart here exactly as they do in the engine. A fatal *move* is an
 * [Advanced] carrying `MoveOutcome.SUICIDE` or `TRAPPED` — the direction really was played, and the
 * replay needs it. [Eliminated] is only for leaving without moving.
 */
public sealed interface StepResult {
    /**
     * A snake played [direction]. [outcome] says whether it survived doing so.
     */
    public class Advanced(
        public val id: SnakeId,
        public val direction: Direction,
        public val outcome: MoveOutcome,
    ) : StepResult {
        public val fatal: Boolean get() = outcome != MoveOutcome.MOVED

        override fun toString(): String = "Advanced($id, $direction, $outcome)"
    }

    /**
     * A snake left without moving: it resigned, it threw, or it stalled from a non-interactive slot.
     * Its body stays on the board as an obstacle.
     */
    public class Eliminated(
        public val id: SnakeId,
        public val reason: EliminationReason,
    ) : StepResult {
        override fun toString(): String = "Eliminated($id, $reason)"
    }

    /**
     * An interactive slot has nothing to play yet. Nothing moved and no turn was consumed, so the
     * next [Match.step] asks the same bot again.
     */
    public data object AwaitingInput : StepResult

    /**
     * The match was already over, so nothing happened and no bot was called.
     *
     * A step that *ends* the match reports what it did — an [Advanced] or an [Eliminated] — and the
     * step after it returns this. One wasted call per match buys a result type where every case
     * describes one event.
     */
    public class Finished(public val outcome: MatchOutcome) : StepResult {
        override fun toString(): String = "Finished($outcome)"
    }
}
