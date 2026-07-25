package ao.snakewarz.core

/**
 * The tunable rules of a match. Part of the replay header, so a recorded game replays under the
 * rules it was played under rather than under today's defaults.
 *
 * @param growEveryNthMove how often a move extends the body instead of dragging it. The default of
 *   `2` is the legacy engine's real, easily-missed behaviour: `SnakeImpl.advance` flips `willGrow`
 *   on every call starting from `false`, so lengths run `1, 1, 2, 2, 3, 3, 4…` and the tail only
 *   retracts on alternating turns. `1` gives classic Tron, where the trail is permanent.
 * @param maxTurns a hard ceiling on the number of individual moves in a match, after which it is a
 *   draw. New in the rewrite: the legacy engine had no cap. A browser cannot be allowed to hang, and
 *   bounded matches are what make bounded MCTS rollouts possible.
 */
public data class RulesConfig(
    public val growEveryNthMove: Int = 2,
    public val maxTurns: Int = 4096,
) {
    init {
        require(growEveryNthMove >= 1) { "growEveryNthMove must be at least 1, was $growEveryNthMove" }
        require(maxTurns >= 1) { "maxTurns must be at least 1, was $maxTurns" }
    }
}
