package ao.snakewarz.botapi

/**
 * The contract every bot implements. One instance is created per slot per match.
 *
 * Because the instance lives for the whole match, per-turn state is just an instance field — which
 * is how an MCTS bot keeps its tree across turns with no extra API at all. `BoardView.hash` makes
 * finding last turn's subtree a `Long` compare rather than a full board equality test.
 *
 * ### [chooseMove] is synchronous, and must never become `suspend`
 *
 * Bots run the engine *inside their own turn*: a search bot builds whole games per rollout using
 * another bot as its policy. A suspending bot cannot serve as a rollout policy without
 * `runBlocking`, which does not exist in wasm, and it would allocate a continuation per call across
 * millions of rollout steps. A human player returns [Decision.Pending] and the driver polls instead.
 *
 * The useful side effect is that bot code physically cannot reach a clock or a `delay`, so
 * determinism holds by construction rather than by discipline.
 */
public interface Bot {
    /**
     * Decide this turn's move. Called at most once per turn, and only while this bot's snake is
     * alive and to act.
     *
     * Get randomness from `BotSetup.rng`, never from a global. Poll `turn.budget` in any search loop.
     */
    public fun chooseMove(turn: Turn): Decision

    /**
     * Whether this bot may answer [Decision.Pending].
     *
     * Read by the driver only when a bot actually returns `Pending`: `true` parks the match on
     * `StepResult.AwaitingInput` without consuming a turn, `false` forfeits. Human input is the only
     * intended use — a search bot has no reason to stall.
     */
    public val interactive: Boolean get() = false

    /**
     * Called once, after this bot's snake has left the match for any reason.
     *
     * A hook for releasing a search tree, not for changing anything: the board is already decided by
     * the time it runs, and an exception thrown here is swallowed.
     */
    public fun onEliminated() {}
}
