package ao.snakewarz.core.rules

/** How a match finished. See [MatchOutcome]. */
public enum class MatchEnd {
    /**
     * One snake outlived the rest, and wins **immediately** — even if it happens to be trapped.
     *
     * A deliberate change from the legacy engine, which asked the survivor for one more move and
     * returned a `null` winner if that move was also fatal (`SnakesGame2.java:96-101`). That
     * required a bot call after the game was already decided, and produced a drawn two-player match
     * in which one snake had visibly outlived the other.
     */
    LAST_SNAKE_STANDING,

    /** Nobody is left. Only reachable in a solo match, where there is no last survivor to crown. */
    ALL_ELIMINATED,

    /** [RulesConfig.maxTurns] was reached with more than one snake alive. */
    TURN_LIMIT,
}
