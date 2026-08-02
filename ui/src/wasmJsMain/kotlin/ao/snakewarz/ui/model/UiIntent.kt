package ao.snakewarz.ui.model

import ao.snakewarz.core.grid.Direction

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
 *
 * **Every intent is a [Shell] or a [Match], and which it is decides where `GameSession.dispatch`
 * answers it.** That split is the one thing here that is load-bearing rather than descriptive: a
 * [Match] intent passes two guards about whose board is on screen and a [Shell] intent passes
 * neither, so an intent filed under the wrong one is a running tournament that ends when somebody
 * folds a panel away. Making it a type rather than a position in a `when` is what stops the next
 * intent being added to the wrong tier by accident.
 */
internal sealed interface UiIntent {
    /**
     * An intent that changes nothing about the match, and is therefore answered above every guard.
     *
     * Asking what is under the pointer, re-measuring the board, opening a panel over it, showing
     * another screen: none of them plays a turn, so none has to be dropped while a batch owns the
     * board, and none is grounds for taking the board back off one. Answer one *below* the guards
     * and moving the mouse across a finished tournament's last position — or folding a panel away —
     * silently swaps it for the player's own game.
     *
     * [Navigate] is the one that still costs something, and it pays for it itself.
     */
    sealed interface Shell : UiIntent

    /**
     * An intent that acts on the match on the board, and so passes both guards first.
     *
     * A running batch owns the arena, so these are dropped outright while one is going — the space
     * bar does not read the DOM's disabled flags. A *finished* batch has left its last position on
     * screen, so the first of these afterwards takes the arena back with a full repaint.
     */
    sealed interface Match : UiIntent

    /**
     * Show a different screen.
     *
     * The one navigation that costs something: only the game screen has a board, so leaving it
     * stops both clocks. A match nobody can see must not keep playing itself.
     */
    class Navigate(val screen: Screen) : Shell

    /** Slide a panel over the board. The match underneath is untouched, and so is its box. */
    class OpenPanel(val panel: Panel) : Shell

    /**
     * Draw the board [options] describe, so the form is not something you submit blind.
     *
     * A [Shell] intent for [Hover]'s reason: it puts a picture on the arena and changes nothing
     * about the match, so it must neither be dropped while a batch owns the board nor be grounds for
     * taking the board off one. Closing the panel and starting a match both put the player's own
     * board back, so nothing here outlives the form it came from.
     */
    class PreviewSetup(val options: MatchOptions) : Shell

    /**
     * Colour the board and the page some other way.
     *
     * A [Shell] intent for [Hover]'s reason: recolouring changes nothing about the position, so it
     * must neither be dropped while a batch owns the board nor be grounds for taking the board off
     * one. Picking a theme mid-tournament repaints the tournament.
     *
     * An **id** rather than a theme, because what the picker knows is the string in its `<option>`
     * and what is stored is the same string — resolving it against the scheme in force is the
     * session's job and happens in one place.
     */
    class SetTheme(val id: String) : Shell

    /**
     * Put away whatever is on top — the result dialog if it is showing, otherwise the open panel.
     *
     * One intent rather than one per overlay, like [TogglePlay]: Escape, the close button and the
     * dimmed backdrop all mean "the thing in front of me", and which that is belongs to the session
     * that knows what is open.
     */
    data object ClosePanel : Shell

    data object TogglePlay : Match

    /** Play exactly one turn, pausing first. The only way to watch a match a move at a time. */
    data object StepOnce : Match

    /** Replay the current setup from the opening position. Same seed, same bots, same board. */
    data object Restart : Match

    /** Publish the match so far as a link. */
    data object Share : Match

    /** Switch to watching the recording of the match just played, right here on this board. */
    data object WatchReplay : Match

    /** Leave a recording and play its human seat again. A gauntlet attempt draws a fresh seed. */
    data object TryAgain : Match

    class StartMatch(val options: MatchOptions) : Match

    /**
     * Start a match somebody configured, on the game screen, from a seed nobody has played before.
     *
     * A [Match] intent and not a [Navigate], for [StartLevel]'s reason: it replaces the match on the
     * board, so it has to pass the guard that says whose board that is. Navigating alone would show
     * whatever was already there — a finished game, verdict card and all — because a board exists
     * from construction and nothing about arriving on the screen replaces it.
     */
    data object StartCustom : Match

    /**
     * Play a rung of the gauntlet, from a fresh seed.
     *
     * A [Match] intent and not a [Navigate], even though it ends on the game screen: what it does is
     * replace the match on the board, so it has to pass the guard that says whose board that is. The
     * screen and the mode follow from the match, rather than the other way round.
     *
     * The **level number**, because that is the frozen identifier — saved progress is keyed on it, and
     * `Gauntlet.levelAt` is what turns it back into a board.
     */
    class StartLevel(val index: Int) : Match

    /**
     * Watch the run that cleared a rung, kept from the day it was played.
     *
     * A [Match] intent for [StartLevel]'s reason and not for [WatchReplay]'s: what it does is put
     * another match on the board, so it passes the guard that says whose board that is. The
     * difference from [WatchReplay] is where the recording comes from — that one is the match still
     * on the board, this one is a payload out of storage, and a payload that will not decode is
     * treated as though there were none.
     */
    class WatchLevelReplay(val index: Int) : Match

    class SetSpeed(val turnsPerSecond: Double) : Match

    /**
     * Run a batch of matches, or stop the one running.
     *
     * One intent rather than two, like [TogglePlay]: the button has one place on the page and one
     * meaning — "the batch", start or stop — and which of those it is belongs to the session that
     * knows whether anything is running.
     */
    data object ToggleTournament : Match

    /**
     * The chrome around the board changed size, so the board has room it did not have.
     *
     * A resize of the window is not one of these — the renderer hears about that from the window
     * directly — but a person folding away something that shares the board's column is invisible to
     * that listener, and the board's track is `1fr` of a viewport-height column, so it really did
     * just change.
     *
     * **Nothing emits this today.** Panels are overlays: one slides over the board rather than
     * beside it, so opening one leaves the board's box exactly where it was. The seam is kept
     * because a panel that ever pushes the board instead needs it, and because being a [Shell] is
     * the part that is easy to get wrong — folding a panel away must not end somebody's tournament.
     */
    data object Relayout : Shell

    /** The timed first-entry Gauntlet presentation has finished. */
    data object IntroFinished : Shell

    /** Wind a recorded match to a turn. Replay only; a live match has no future to seek into. */
    class SeekTo(val turnIndex: Int) : Match

    class Steer(val direction: Direction) : Match

    /**
     * The pointer moved over the board.
     *
     * Client coordinates rather than a square, because the chrome does not know the geometry — the
     * renderer owns the cell size and the grid, and giving the chrome a copy of either would be two
     * accounts of where a square is.
     */
    class Hover(val clientX: Double, val clientY: Double) : Shell

    /** The pointer left the board. */
    data object HoverEnded : Shell

    /**
     * A pointer went down on the board.
     *
     * Client coordinates for [Hover]'s reason, and a [Shell] intent for it too: a press lands on
     * whatever board is on screen, and a press on a board a tournament owns has to change nothing
     * about that tournament — neither be dropped as though it were a move, nor take the arena back
     * off one. Whether it takes hold of a snake at all is the session's, which is the only thing
     * that knows where the player's head is.
     */
    class PathBegan(val clientX: Double, val clientY: Double) : Shell

    /**
     * The pointer moved with the button or the finger still down.
     *
     * The same event as [Hover] under a press, and it is deliberately a separate intent rather than
     * a flag on that one: what a drag does — route, requeue, start the clock — has nothing in
     * common with what a hover does beyond the coordinates it arrives with.
     */
    class PathDragged(val clientX: Double, val clientY: Double) : Shell

    /**
     * The pointer was let go of, cancelled, or had its capture taken away.
     *
     * One intent for all three, like [ClosePanel]: they are the same event to a player, who has
     * stopped holding the snake either way. **This is the stop** — the route left undrawn is
     * discarded and the snake halts on the square it is on.
     */
    data object PathReleased : Shell
}
