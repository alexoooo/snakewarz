package ao.snakewarz.core.rules

/**
 * What [Board.apply] did with a move.
 *
 * A move into an occupied square is legal to *submit* and fatal to *make* — that is the whole game.
 * Distinguishing [TRAPPED] from [SUICIDE] costs one occupancy read and is the difference between "it
 * had nowhere to go" and "it had somewhere to go and chose otherwise", which matters to every stats
 * view and to anyone judging a bot.
 */
public enum class MoveOutcome {
    /** The head advanced; the snake is still alive. */
    MOVED,

    /** Fatal, and unavoidable: no free square was adjacent to the head. */
    TRAPPED,

    /** Fatal, and avoidable: a free square was adjacent to the head. */
    SUICIDE,
    ;

    public val eliminated: Boolean get() = this != MOVED

    public val eliminationReason: EliminationReason?
        get() = when (this) {
            MOVED -> null
            TRAPPED -> EliminationReason.TRAPPED
            SUICIDE -> EliminationReason.SUICIDE
        }
}
