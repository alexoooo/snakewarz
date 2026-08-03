package ao.snakewarz.ui.chrome.demo

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.match.Match
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.demo.DemoReplay
import ao.snakewarz.match.human.PathPlanner
import ao.snakewarz.match.replay.MatchRecord
import ao.snakewarz.match.replay.ReplayCodec
import ao.snakewarz.ui.chrome.elementById
import ao.snakewarz.ui.render.BoardRenderer
import ao.snakewarz.ui.render.Theme
import ao.snakewarz.ui.render.prefersReducedMotion
import ao.snakewarz.ui.schedule.Ticker
import ao.snakewarz.ui.schedule.TurnScheduler
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement

/**
 * One recorded match, played over and over on a pair of canvases.
 *
 * This is the *board* half of a demo and nothing else: decode the recording once, play it at a
 * reading speed, hold the finished position for a beat, start again. Two of them run in the game —
 * the menu's `How to play` card and the once-per-browser objective card — and both are here for the
 * same reason. The objective is the thing people got wrong, prose had already failed at it, and this
 * game explains itself by *making the interaction self-evident rather than by instructing*. A card
 * that says what winning is should show it in the same breath.
 *
 * A [BoardRenderer] per instance is cheap rather than clever: a renderer is per canvas-pair and keeps
 * no static state, so several coexist without knowing about each other. The one thing they share is a
 * `Theme`, handed to [show] for the reason the arena is handed one — no board may be lit one way while
 * the page around it is lit the other.
 *
 * **It moves only while it is being looked at.** `GameSession.begin` refuses to run a match clock
 * anywhere but the game screen, on the grounds that a match playing itself out where nobody can see it
 * is a phone getting warm; the grounds are visibility rather than the screen, and one of these *is*
 * the thing being looked at while it is up. So [show] starts both clocks and [hide] stops them, and
 * the caller's whole job is to ask on every frame. Ask while the box is still `hidden` and the board
 * measures no room and sizes every cell to the minimum, so ask after whatever reveals it.
 *
 * **What is written around it is not its business.** The menu's board carries a line per lap and a row
 * of dots to steer them; the objective card carries neither. That difference is the three lambdas —
 * [onEnter] when a board goes up, [onLap] when a lap ends, [onFit] once the picture is down and
 * anything written around it should be written again — and none of them touches the recording. **The
 * board is never restarted, reseeked or interrupted to change a sentence**, which is the fault the
 * lap-long captions exist to fix and would be the easiest one to reintroduce here.
 *
 * Under `prefers-reduced-motion` it does not play at all: [still] is true, the recording is run out to
 * the finished position — two long bodies, one snake wedged in a corner it cannot leave — and that
 * single picture carries the same rules a lap would.
 */
internal class DemoLoop(
    boardId: String,
    overlayId: String,
    private val onEnter: (still: Boolean) -> Unit = {},
    private val onLap: () -> Unit = {},
    private val onFit: () -> Unit = {},
) {
    private val canvas: HTMLCanvasElement = elementById(boardId)
    private val overlay: HTMLCanvasElement = elementById(overlayId)

    /**
     * Decoded once. Playback is a fresh [Match] per lap and costs no search at all, so starting over
     * is a few microseconds of replaying moves onto a clean board.
     */
    private val record: MatchRecord = ReplayCodec.decode(DemoReplay.PAYLOAD)

    private val renderer = BoardRenderer(canvas, overlay)

    /** No `onFrame` work: nothing outside this board reports on it, so a frame owes the page nothing. */
    private val scheduler = TurnScheduler(::advance, {})
    private val ticker = Ticker(::paintMotion)

    /**
     * Empty for the life of the page.
     *
     * `paintOverlay` draws a held route and the route a press would commit to, and there is no
     * pointer on this board — but it takes them rather than assuming, so a pair of empty planners on
     * the demo's own grid is what says "nothing is being steered here".
     */
    private val plan = PathPlanner(record.setup.grid())
    private val preview = PathPlanner(record.setup.grid())

    /** Whether this reader asked for no motion, which decides what a board is instead of a loop. */
    val still: Boolean = prefersReducedMotion()

    private var play: Match = Match.playback(record)

    /** Whether the thing holding this board is on screen, which is when it may measure or move. */
    private var showing: Boolean = false

    /** The theme last applied, so a render that changed nothing does not repaint the ground. */
    private var themed: Theme? = null

    /** The finished board being held before the next lap, or nothing pending. */
    private var pauseTimer: Int? = null

    init {
        // Half the speed of a real match. This is a board being read by somebody who does not yet
        // know the rules, and at playing speed a move is over before it has been understood.
        scheduler.turnsPerSecond = TURNS_PER_SECOND
        window.addEventListener("resize") {
            if (showing) {
                refit()
            }
        }
    }

    /**
     * Puts the board up under [theme], playing from turn 0 if it was not already.
     *
     * Idempotent by design, because the caller asks on every frame: a board already up under the same
     * theme is left strictly alone, which is what keeps the animation unbroken across the hundreds of
     * renders a lap takes.
     */
    fun show(theme: Theme) {
        val restyled = themed != theme
        if (restyled) {
            themed = theme
            renderer.applyTheme(theme)
        }

        if (!showing) {
            showing = true
            // From the top rather than from wherever it was stopped: somebody meeting this board
            // should meet the setup, not the last frame of a kill they did not see coming.
            onEnter(still)
            replay()
        } else if (restyled) {
            refit()
        }
    }

    /** Takes the board down and stops both clocks. Idempotent, for [show]'s reason. */
    fun hide() {
        if (!showing) {
            return
        }
        showing = false
        scheduler.stop()
        ticker.stop()
        cancelPause()
    }

    override fun toString(): String = "DemoLoop(turn ${play.turnIndex}, showing=$showing)"

    // -- internals

    /** Plays the recording again from turn 0, under whatever is written around it. */
    private fun replay() {
        cancelPause()
        scheduler.stop()
        play = Match.playback(record)

        if (still) {
            runOut()
            refit()
            return
        }
        refit()
        scheduler.start()
    }

    /** Steps to the end without painting a frame of it, which is the reduced-motion reading. */
    private fun runOut() {
        while (true) {
            val result = play.step()
            if (result is StepResult.Finished || result == StepResult.AwaitingInput) {
                return
            }
        }
    }

    private fun advance(): TurnScheduler.Progress {
        val result = play.step()
        paint()

        // Nothing is said here: a caption lasts a whole lap, so writing it six times a second would
        // be six DOM writes a second to leave the same sentence on the page.

        // AwaitingInput cannot happen against a complete recording, and stepping past it throws
        // rather than parking twice — so it ends the loop here exactly as the ending does.
        if (result is StepResult.Finished || result == StepResult.AwaitingInput) {
            pauseThen(HOLD_MILLIS) {
                onLap()
                replay()
            }
            return TurnScheduler.Progress.FINISHED
        }
        return TurnScheduler.Progress.CONTINUED
    }

    /**
     * Sizes the board to the room it has and lays the ground down, then puts the snakes back.
     *
     * `fit` is the only thing that paints the board canvas, and `paintOverlay` is the only thing that
     * paints a snake anywhere — so neither is useful without the other.
     */
    private fun refit() {
        renderer.fit(play.view)
        paint()
        onFit()
    }

    private fun paint() {
        val moving = renderer.paintOverlay(play.view, Cell.NONE, plan, preview)
        if (moving && !still) {
            ticker.start()
        }
    }

    /**
     * [Ticker]'s half of [paint]: the same position at a later instant, so a body settling after the
     * kill finishes rather than snapping. It answers whether anything is still moving, which is what
     * stops the loop.
     */
    private fun paintMotion(motionMillis: Double): Boolean = renderer.animate(play.view, motionMillis)

    /** Holds the finished board for [millis] and then does [resume], unless it was taken down. */
    private fun pauseThen(millis: Int, resume: () -> Unit) {
        cancelPause()
        pauseTimer = window.setTimeout(
            {
                pauseTimer = null
                if (showing) {
                    resume()
                }
                null
            },
            millis,
        )
    }

    private fun cancelPause() {
        pauseTimer?.let { window.clearTimeout(it) }
        pauseTimer = null
    }

    private companion object {
        const val TURNS_PER_SECOND: Double = 6.0

        /**
         * Long enough to read the closing line, short enough that a glance catches a whole match.
         *
         * It is also what sets the reading time of every line on the menu rather than only the last,
         * because a lap is the thirty turns plus this: five seconds of match and two of held ending is
         * seven seconds a rule, against the longest of them being fourteen words.
         */
        const val HOLD_MILLIS: Int = 2_000
    }
}
