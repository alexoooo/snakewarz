package ao.snakewarz.core.rules

/** How a match finished. See [MatchOutcome]. */
public enum class MatchEnd {
    /**
     * One snake outlived the rest and still has somewhere to move. A trapped sole survivor takes its
     * forced fatal turn instead, leaving nobody moving and producing [ALL_ELIMINATED].
     */
    LAST_SNAKE_STANDING,

    /** Nobody is left: a solo snake died, or the last survivor was trapped too. */
    ALL_ELIMINATED,

    /** [RulesConfig.maxTurns] was reached with more than one snake alive. */
    TURN_LIMIT,
}
