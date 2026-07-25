package ao.snakewarz.core

/**
 * A read-only projection of a match in progress — everything a bot or a renderer may ask, and
 * nothing that would let either of them change the game.
 *
 * Deliberately not a snapshot: [Board] implements this over its live mutable arena, so a search bot
 * can apply and undo millions of moves behind the same view without allocating. Anything that needs
 * to *keep* a position past the next move must take a [Board.snapshot] instead.
 *
 * There is no per-frame immutable scene type above this, either. `BoardView` is already a read-only
 * projection containing no drawing concepts, so producing one more object per frame would allocate
 * for nothing.
 */
public interface BoardView {
    public val grid: Grid

    public val rules: RulesConfig

    /** Snake ids are dense: they are exactly `SnakeId(0) until SnakeId(snakeCount)`. */
    public val snakeCount: Int

    /** Whose move it is. Always a living snake while the match is running. */
    public val toAct: SnakeId

    /** Individual moves made so far, counting every snake — not rounds. */
    public val turnIndex: Int

    public val aliveCount: Int

    /** `null` while the match is running. This is the condition a rollout loop spins on. */
    public val outcome: MatchOutcome?

    /**
     * A Zobrist fingerprint covering occupancy, heads, growth phases, liveness and whose turn it is.
     *
     * Equal hashes mean equal positions with overwhelming probability, which is what makes MCTS
     * transposition and tree reuse a `Long` compare — the legacy `BiState.equals` compared whole
     * `BitSet`s on every node visit.
     */
    public val hash: Long

    public fun isFree(cell: Cell): Boolean

    /** The snake occupying [cell], or [SnakeId.NONE] if it is empty or part of the wall ring. */
    public fun ownerOf(cell: Cell): SnakeId

    /**
     * The directions from [id]'s head that do not end in a wall or a body — the snake's own included.
     *
     * Empty means the snake is doomed: whatever it plays next is fatal, and will be recorded as
     * [EliminationReason.TRAPPED] rather than [EliminationReason.SUICIDE].
     *
     * Note that this is evaluated **before** any tail retracts, so a snake may not move into the
     * square its own tail is about to leave. That is the legacy rule, and it is the conservative one.
     */
    public fun legalMoves(id: SnakeId): DirectionSet

    public fun snake(id: SnakeId): SnakeView
}
