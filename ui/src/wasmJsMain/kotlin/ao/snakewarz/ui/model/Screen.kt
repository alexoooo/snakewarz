package ao.snakewarz.ui.model

/**
 * Which of the page's three sections is showing.
 *
 * Exactly one at a time, and the other two are `hidden` rather than merely moved out of sight: a
 * section that is only off-screen is still in the tab order, so Tab walks into controls nobody can
 * see and the page appears to lose the focus entirely.
 *
 * Only [GAME] has a board on it, which is why leaving it stops the clocks — a match nobody can watch
 * must not keep running.
 */
internal enum class Screen {
    /** The modes on offer, and the way back into a recording that is already loaded. */
    HOME,

    /** The gauntlet's level select. */
    GAUNTLET,

    /** The board, and the two bars around it. */
    GAME,
}
