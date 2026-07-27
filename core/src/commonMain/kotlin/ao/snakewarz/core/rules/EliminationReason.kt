package ao.snakewarz.core.rules

/**
 * Why a snake left the match.
 *
 * This replaces the legacy `GameResult.isSuicide` — a single boolean for the whole game, which could
 * not say *who* it was about and was computed from the loser's options after the fact. A reason per
 * snake costs nothing and is what the stats panel and the replay side-table both want.
 */
public enum class EliminationReason {
    /** Moved into an occupied square while no free square was adjacent — there was no choice. */
    TRAPPED,

    /** Moved into an occupied square while a free one was available. */
    SUICIDE,

    /** The bot returned `Decision.Resign`. */
    RESIGNED,

    /** The bot threw, or an interactive-only decision arrived from a non-interactive slot. */
    FORFEIT,
}
