package ao.snakewarz.botapi

import ao.snakewarz.core.Direction

/**
 * What a bot decided to do this turn.
 *
 * There is deliberately no "did not decide": [Decision] is non-nullable and the driver acts on every
 * case, so a bot cannot fail to move by accident. That is the whole point of replacing the legacy
 * three-implementation `MoveSpecifier` family, whose worst habit was
 * `MoveTracker.retrieveOrCreateSpecifier` filling an unset move in with *the first available
 * direction* — a bot that never chose anything played a move it never chose, and then repeated it
 * forever.
 *
 * Every case has a defined consequence and none of them is silent: [Resign] eliminates with
 * `RESIGNED`, [Pending] from a non-interactive slot eliminates with `FORFEIT`, a thrown exception
 * eliminates with `FORFEIT`, and a [Move] into an occupied square is an ordinary `SUICIDE` or
 * `TRAPPED` decided by the engine.
 */
public sealed interface Decision {
    /**
     * Move the head one square in [direction].
     *
     * The four instances are cached and the constructor is private, so `Move(NORTH)` allocates
     * nothing and identity equality is structural equality. That matters more than it looks: from
     * Phase 4 a search bot's rollout policy *is* another bot's [Bot.chooseMove], called millions of
     * times per turn, and an allocation there would be most of the cost of the rollout.
     */
    public class Move private constructor(public val direction: Direction) : Decision {
        override fun toString(): String = "Move($direction)"

        public companion object {
            private val CACHED: Array<Move> = Array(Direction.entries.size) { Move(Direction.entries[it]) }

            public operator fun invoke(direction: Direction): Move = CACHED[direction.ordinal]
        }
    }

    /**
     * Leave the match voluntarily. The snake is eliminated with `RESIGNED` and its body stays on the
     * board as an obstacle, exactly as a corpse does.
     */
    public data object Resign : Decision

    /**
     * No input yet — ask again next frame.
     *
     * Legal **only** from a bot that declares itself [Bot.interactive]. From any other slot it is a
     * `FORFEIT`, because a search bot that cannot come up with a move has malfunctioned, and letting
     * it stall would hang the match instead of saying so.
     */
    public data object Pending : Decision
}
