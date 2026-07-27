package ao.snakewarz.ui

import ao.snakewarz.core.Direction

/**
 * Something the player asked for.
 *
 * The chrome is a one-way data flow with no virtual DOM: state goes down through `Chrome.render`,
 * and everything the player does comes back up as one of these into a single `dispatch`. That is a
 * minimal MVI, it is about two hundred lines all in, and it scales — which a pile of listeners each
 * reaching into the match would not.
 *
 * Notice there is no intent for "a turn happened". Events flow up only from *people*; the match
 * moves because the scheduler stepped it, and the renderer hears about that directly.
 */
internal sealed interface UiIntent {
    data object TogglePlay : UiIntent

    /** Play exactly one turn, pausing first. The only way to watch a match a move at a time. */
    data object StepOnce : UiIntent

    /** Replay the current setup from the opening position. Same seed, same bots, same board. */
    data object Restart : UiIntent

    /** Publish the match so far as a link. */
    data object Share : UiIntent

    class StartMatch(val options: MatchOptions) : UiIntent

    class SetSpeed(val turnsPerSecond: Double) : UiIntent

    /**
     * Run a batch of matches, or stop the one running.
     *
     * One intent rather than two, like [TogglePlay]: the button has one place on the page and one
     * meaning — "the batch", start or stop — and which of those it is belongs to the session that
     * knows whether anything is running.
     */
    data object ToggleTournament : UiIntent

    /** Wind a recorded match to a turn. Replay only; a live match has no future to seek into. */
    class SeekTo(val turnIndex: Int) : UiIntent

    class Steer(val direction: Direction) : UiIntent

    /**
     * The pointer moved over the board.
     *
     * Client coordinates rather than a square, because the chrome does not know the geometry — the
     * renderer owns the cell size and the grid, and giving the chrome a copy of either would be two
     * accounts of where a square is.
     */
    class Hover(val clientX: Double, val clientY: Double) : UiIntent

    /** The pointer left the board. */
    data object HoverEnded : UiIntent
}
