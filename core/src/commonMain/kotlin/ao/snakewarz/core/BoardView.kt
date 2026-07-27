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
     * Equal hashes mean equal positions with overwhelming probability, and it is maintained
     * incrementally: applying a move xors a handful of keys rather than rescanning the board, and
     * taking one back xors exactly those keys again.
     *
     * **What it is for today is the undo journal.** A search descends and backs up millions of times
     * a turn and is correct only if the board it returns to is bit-for-bit the board it left;
     * comparing one `Long` at every depth is the only affordable way to assert that, and it is what
     * `BoardScratchTest` and `BoardUndoTest` do. The growth-phase and to-act keys are in here for
     * that reason — without them an unwind could restore the squares and still leave the wrong snake
     * to act, which no occupancy-only fingerprint would notice.
     *
     * It is *also* what a bot would use for a transposition table or for keeping a subtree across
     * turns — a `Long` compare, where the legacy `BiState.equals` compared whole `BitSet`s on every
     * node visit. No shipped bot does either, and that is a measured decision rather than a gap:
     * tree reuse was built and benchmarked during the rewrite and about eight of a turn's 137 nodes
     * survived into the next one. `UctBot` and `PuctBot` reset instead. `docs/Bots.md` carries the
     * numbers and the soundness wrinkle that goes with them — this fingerprint deliberately omits
     * `turnIndex`, which is what `maxTurns` terminates on.
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
