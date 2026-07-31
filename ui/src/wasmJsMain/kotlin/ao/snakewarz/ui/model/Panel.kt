package ao.snakewarz.ui.model

/**
 * A slide-over that covers the board without moving it.
 *
 * An overlay rather than a column, and that is what makes opening one free: the board's box is
 * unchanged, so nothing has to be re-measured and a batch running underneath keeps its arena. A
 * panel that ever *pushed* the board instead would have to come up as [UiIntent.Relayout].
 *
 * At most one is open, and while one is the board is inert — so Tab stays inside the panel and the
 * arrow keys belong to whatever is focused in it rather than to a snake nobody is looking at.
 */
internal enum class Panel {
    /** The new-match form: board, map, seats and their settings, seed. */
    SETUP,

    /** Format, rounds, and the win-rate matrix. */
    TOURNAMENT,

    /** The replay link, and watching the match just played. */
    SHARE,

    /** How the game is played rather than what is being played: the speed, and the keys. */
    SETTINGS,
}
