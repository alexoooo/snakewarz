package ao.snakewarz.ui.model

/**
 * Which game is being played, which outlives the screen showing it.
 *
 * A gauntlet level and a custom match both run on [Screen.GAME] and differ only in what the player is
 * allowed to change, so this is not derivable from the screen: it is decided by the way in and kept
 * for as long as the board is up.
 */
internal enum class Mode {
    /** Everything the page can do: any board, any map, any seats, tournaments. */
    CUSTOM,

    /** One level of the gauntlet, which *is* its configuration. */
    GAUNTLET,
    ;

    /**
     * Whether [panel] is reachable in this mode.
     *
     * A level's board, map and opponent are what make it that level, so re-seating it would be
     * playing something else under its name — and a tournament is a research instrument rather than
     * a way through the gauntlet. The button that opens each is hidden rather than disabled: a control
     * that can never apply here should not be present at all.
     *
     * Exhaustive with no `else`, so a fifth panel has to declare which modes offer it.
     */
    fun offers(panel: Panel): Boolean = when (panel) {
        Panel.SETUP, Panel.TOURNAMENT -> this == CUSTOM
        Panel.SHARE, Panel.SETTINGS -> true
    }
}
