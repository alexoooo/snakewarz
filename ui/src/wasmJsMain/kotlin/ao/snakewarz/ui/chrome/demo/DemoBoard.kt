package ao.snakewarz.ui.chrome.demo

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.match.Match
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.demo.DemoReplay
import ao.snakewarz.match.human.PathPlanner
import ao.snakewarz.match.replay.MatchRecord
import ao.snakewarz.match.replay.ReplayCodec
import ao.snakewarz.ui.chrome.elementById
import ao.snakewarz.ui.model.Screen
import ao.snakewarz.ui.model.UiModel
import ao.snakewarz.ui.render.BoardRenderer
import ao.snakewarz.ui.render.Theme
import ao.snakewarz.ui.render.prefersReducedMotion
import ao.snakewarz.ui.schedule.Ticker
import ao.snakewarz.ui.schedule.TurnScheduler
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

/**
 * A small board on the menu, playing one recorded match over and over.
 *
 * It is here because the objective is the thing people got wrong, and prose had already failed at
 * it: the rules were on this screen in a sentence nobody read, and playtesters still arrived
 * expecting to collect something or to run away. The release this screen was designed in settled
 * that the game explains itself by *making the interaction self-evident rather than by instructing*,
 * and this is that decision applied to what to want instead of to what to press.
 *
 * It owns a second [BoardRenderer], and that is cheap rather than clever: a renderer is per
 * canvas-pair and keeps no static state, so two of them coexist without knowing about each other.
 * The only thing they share is a `Theme`, handed down each render for the same reason the arena is —
 * the board must not be lit one way while the panel around it is lit the other.
 *
 * **`GameSession.begin` refuses to run a match clock anywhere but the game screen**, on the grounds
 * that it would be a game playing itself out where nobody can see it. The grounds are visibility and
 * not the screen, and this board is the thing being looked at — so the clocks below start on the
 * menu and stop the moment it is left. That is not merely tidiness: a `requestAnimationFrame` chain
 * left running behind a hidden section is a phone getting warm in somebody's hand.
 *
 * Under `prefers-reduced-motion` it does not play at all. The finished position — two long bodies,
 * one snake wedged in a corner it cannot leave — carries the same three rules as a single picture,
 * which is a better answer for that reader than a slower loop would be.
 */
internal class DemoBoard {
    private val canvas: HTMLCanvasElement = elementById("demo-board")
    private val overlay: HTMLCanvasElement = elementById("demo-overlay")
    private val caption: HTMLElement = elementById("demo-caption")

    /**
     * Decoded once. Playback is a fresh [Match] per loop and costs no search at all, so restarting is
     * a few microseconds of replaying moves onto a clean board.
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

    private val still: Boolean = prefersReducedMotion()

    private var play: Match = Match.playback(record)

    /** Whether the menu is the screen showing, which is the whole of when this may measure or move. */
    private var showing: Boolean = false

    /** The theme last applied, so a render that changed nothing does not repaint the ground. */
    private var themed: Theme? = null

    private var loopTimer: Int? = null

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

    fun render(model: UiModel) {
        if (model.screen != Screen.HOME) {
            leave()
            return
        }

        val restyled = themed != model.theme
        if (restyled) {
            themed = model.theme
            renderer.applyTheme(model.theme)
        }

        if (!showing) {
            showing = true
            // From the top rather than from wherever it was paused: somebody arriving back at the
            // menu should meet the setup, not the last frame of a kill they did not see coming.
            replay()
        } else if (restyled) {
            refit()
        }
    }

    override fun toString(): String = "DemoBoard(turn ${play.turnIndex}, showing=$showing)"

    // -- internals

    private fun leave() {
        if (!showing) {
            return
        }
        showing = false
        scheduler.stop()
        ticker.stop()
        cancelLoop()
    }

    private fun replay() {
        cancelLoop()
        play = Match.playback(record)
        refit()

        if (!still) {
            scheduler.start()
            return
        }
        runOut()
        paint()
        say()
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
        say()

        // AwaitingInput cannot happen against a complete recording, and stepping past it throws
        // rather than parking twice — so it ends the loop here exactly as the ending does.
        if (result is StepResult.Finished || result == StepResult.AwaitingInput) {
            holdThenReplay()
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
        say()
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

    private fun say() {
        caption.textContent = DemoCaptions.at(play.turnIndex)
    }

    /** Holds the finished board long enough for the closing line to be read, then starts over. */
    private fun holdThenReplay() {
        cancelLoop()
        loopTimer = window.setTimeout(
            {
                if (showing) {
                    replay()
                }
                null
            },
            HOLD_MILLIS,
        )
    }

    private fun cancelLoop() {
        loopTimer?.let { window.clearTimeout(it) }
        loopTimer = null
    }

    private companion object {
        const val TURNS_PER_SECOND: Double = 6.0

        /** Long enough to read the closing line, short enough that a glance catches a whole match. */
        const val HOLD_MILLIS: Int = 2_000
    }
}
