package ao.snakewarz.core

/**
 * A read-only look at one snake.
 *
 * Bots and renderers both read through this. It carries no colour, no name and no bot — those live
 * in `:ui` and `:bot-api` respectively, which is the split that dissolved the legacy `PlayerAvatar`.
 *
 * `Board` implements this as a *live* view over its mutable arena, so the values change under you as
 * moves are applied. [MatchState.snake] returns a frozen one instead.
 */
public interface SnakeView {
    public val id: SnakeId

    public val alive: Boolean

    /** Why this snake left the match, or `null` while it is still in it. */
    public val eliminationReason: EliminationReason?

    /** Number of squares occupied. Starts at 1 and grows per [RulesConfig.growEveryNthMove]. */
    public val length: Int

    public val head: Cell

    public val tail: Cell

    /** The direction of the most recent move, or `null` before this snake has moved. */
    public val lastDirection: Direction?

    public val movesMade: Int

    /**
     * Whether the next move extends the body instead of dragging it.
     *
     * Worth reading rather than deriving: a bot that assumes the tail always retracts will
     * mis-evaluate every other turn, because it does not.
     */
    public val growsOnNextMove: Boolean

    /** The [i]-th square from the tail, so `cellAt(0) == tail` and `cellAt(length - 1) == head`. */
    public fun cellAt(i: Int): Cell
}
