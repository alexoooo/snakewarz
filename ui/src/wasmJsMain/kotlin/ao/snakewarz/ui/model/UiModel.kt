package ao.snakewarz.ui.model

import ao.snakewarz.match.gauntlet.Gauntlet
import ao.snakewarz.match.stats.MatchStats
import ao.snakewarz.ui.model.gauntlet.GauntletProgress
import ao.snakewarz.ui.render.Theme

/**
 * Everything the chrome renders, computed once per frame rather than per turn.
 *
 * At the top speeds this thing runs at a frame is worth dozens of turns, and a DOM write per turn
 * would cost more than the whole match does. The board is painted per turn because painting two
 * rectangles is nearly free; the text around it is written once a frame because writing text is not.
 *
 * Data travels one way only. [stats] is the driver's own snapshot rather than a copy of it — it is
 * frozen at the moment it was taken and holds no handle back to the match, so the chrome still cannot
 * reach the game even by accident, and the scoreboard and the stats panel cannot disagree because
 * there is one set of numbers rather than two.
 */
internal class UiModel(
    /** Which of the three sections is showing. Exactly one is, and the other two are `hidden`. */
    val screen: Screen,
    /**
     * The rung of the gauntlet on the board, or `null` for a match somebody configured.
     *
     * Deliberately not derivable from [screen]: a level and a custom match are both played on
     * [Screen.GAME], so what makes one a level is the way in and is kept while the board is up. It is
     * the level *number* rather than a flag because everything downstream wants it — the bar names
     * the level, the verdict offers the one above it, and progress is keyed on it.
     */
    val level: Int?,
    /**
     * How far up the gauntlet this browser has got, which the level select and the menu both read.
     *
     * Carried on the model rather than looked up where it is wanted, so that the tiles, the Continue
     * button and the verdict cannot disagree about what has been beaten within one frame.
     */
    val gauntlet: GauntletProgress,
    /**
     * The player has just beaten [level].
     *
     * What turns the verdict from "try again" into "move on": the card offers [nextLevel] instead of
     * a retry, and on the last rung it offers neither. Always false in a custom match, which has no
     * level to clear.
     */
    val levelCleared: Boolean,
    /** The Gauntlet opponent presented beside the board, or `null` in every other mode. */
    val rival: RivalCard? = null,
    /** The first-entry splash currently blocking the game, or `null` once play is available. */
    val intro: RivalCard? = null,
    /** The panel slid over the board, or `null` when the board has the screen to itself. */
    val openPanel: Panel?,
    /**
     * The colours in force — the player's theme, resolved against their system's light or dark.
     *
     * Here rather than read statically wherever a colour is wanted, because a theme can move a
     * trail hue: a scoreboard swatch painted from a global would keep the old theme's colour until
     * something else happened to redraw it, which is intermittent and looks like nothing.
     */
    val theme: Theme,
    /**
     * The verdict on the match the player just finished — *"You win"* — or `null` when there is
     * none to give, which is most of the time.
     *
     * Non-null is what opens the result dialog, so dismissing it is a matter of this going back to
     * `null` rather than of the chrome remembering anything. Only ever about a match somebody
     * played by hand: a batch's matches are the tournament's and a recording has been watched
     * before.
     */
    val result: String?,
    /**
     * The face of whoever won the match [result] is about, shown beside the verdict.
     *
     * `null` for a draw, where there is nobody to show, and `null` whenever [result] is — the dialog
     * says who beat you or who you beat, so a match still being played has no face to put on it.
     */
    val resultPortrait: String?,
    /** Watching a recording rather than playing a match. Reveals the scrub bar. */
    val replay: Boolean,
    /** The recording has a human seat that can be played live. */
    val canTryAgain: Boolean,
    /** The rung after a winning level recording, or `null` when moving on is not available. */
    val replayNextLevel: Int?,
    /**
     * A person is still in this match, so the keyboard is its clock rather than the scheduler.
     * Disables the transport: there is no clock to start, stop or step.
     */
    val interactive: Boolean,
    /**
     * The player has a snake on the board and a move to make with it, so steering it is a thing that
     * can happen right now.
     *
     * Stronger than [interactive], which only asks whether a person is still alive in the match on
     * screen: this is the very predicate a press on the board is answered by, so the on-screen pad
     * offers exactly what the board would accept. Off while a tournament or a setup preview owns the
     * arena, and off the instant the match has an outcome.
     */
    val steering: Boolean,
    /** A human seat belongs to this board, even when playback or a verdict temporarily disables input. */
    val steeringPad: Boolean,
    val running: Boolean,
    /** The length of the recording, or the current turn when there is no recording to be ahead of. */
    val turnCount: Int,
    /** The fixed-width round badge, kept separate from the changing status sentence. */
    val round: String = "Round 0",
    /** One sentence about where the match is, already worded for a person. */
    val status: String,
    val stats: MatchStats,
    /** What to call each seat of the match [stats] describes. Rebuilt only when that match changes. */
    val labels: SlotLabels,
    /**
     * The face of each of those seats. Decoration beside the name, never instead of it.
     *
     * Rebuilt when the match changes *or* the theme does, because a bot with no shipped art is drawn
     * in its seat's trail colour.
     */
    val portraits: SlotPortraits,
    /** The snake under the pointer, or `null` when the pointer is not over one. */
    val hover: HoverInfo?,
    /**
     * The player's own match is finished and can be watched again. About *their* match, never the
     * one a batch has on the board — that one is the tournament's to drive, not theirs to rewind.
     */
    val canWatchReplay: Boolean,
    /** Non-null once the player has asked for a link. */
    val shareUrl: String?,
    /** Non-null once a batch has been run, whether or not it is still running. */
    val tournament: TournamentStatus?,
) {
    val turnIndex: Int get() = stats.turnsPlayed

    /** A running batch owns the board, so the match transport has nothing to drive. */
    val batchRunning: Boolean get() = tournament?.running == true

    /**
     * What may be changed about the match on the board.
     *
     * Derived from [level] rather than stored beside it, because "this is a level" and "this is level
     * seven" would otherwise be two answers to one question — and the pair could disagree.
     */
    val mode: Mode get() = if (level == null) Mode.CUSTOM else Mode.GAUNTLET

    /**
     * The rung the verdict offers to move on to, or `null` where there is none.
     *
     * `null` on a loss, on a draw, in a custom match, and on the last rung — which is where the card
     * offers Home and the verdict says the gauntlet is finished.
     */
    val nextLevel: Int? get() = level?.takeIf { levelCleared && it < Gauntlet.size }?.plus(1)
}

/** The stable presentation shared by a Gauntlet rival card and its first-entry splash. */
internal class RivalCard(
    val name: String,
    val title: String,
    val portrait: String?,
    val length: Int,
    val status: String,
)
