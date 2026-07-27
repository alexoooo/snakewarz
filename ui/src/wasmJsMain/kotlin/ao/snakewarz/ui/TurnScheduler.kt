package ao.snakewarz.ui

import kotlinx.browser.window
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Turns wall-clock time into turns, and is the only thing in the project that paces a *match*.
 *
 * The one other clock is [KeyRepeat], which paces a held key. It is not a second scheduler: it
 * produces keypresses, exactly as a person leaning on an arrow key would, and what those turn into
 * is still `GameSession`'s decision.
 *
 * **`requestAnimationFrame`, not a coroutine `delay`.** rAF is vsync-aligned, so stepping never
 * tears against painting, and the browser stops it entirely in a hidden tab. `delay()` in a
 * background tab is throttled to roughly a second and then releases the backlog in one burst, which
 * on return looks like the game skipping.
 *
 * Pacing cannot affect a result. [step] makes at most one bot call and its outcome does not depend
 * on how many steps preceded it in the same frame, so every guard below changes how *fast* a match
 * plays and never how it ends. That is what makes it safe to bail out of a frame halfway through.
 */
internal class TurnScheduler(
    private val step: () -> Progress,
    private val onFrame: () -> Unit,
) {
    /** What [step] managed to do, and therefore whether the frame should keep asking. */
    enum class Progress {
        /** A turn was played. */
        CONTINUED,

        /** An interactive slot has nothing to play yet, and no turn was consumed. */
        AWAITING_INPUT,

        /**
         * There is nothing further to play.
         *
         * The match ended, or a recording ran out — a partial replay parks here rather than under
         * [AWAITING_INPUT], because no key exists that could resume a scripted slot.
         */
        FINISHED,
    }

    var turnsPerSecond: Double = DEFAULT_TURNS_PER_SECOND
        set(value) {
            require(value > 0.0) { "turnsPerSecond must be positive, was $value" }
            field = value
        }

    var running: Boolean = false
        private set

    private var handle: Int = 0
    private var previousFrame: Double = 0.0
    private var hasPreviousFrame: Boolean = false
    private var accumulator: Double = 0.0

    fun start() {
        if (running) {
            return
        }
        running = true
        hasPreviousFrame = false
        accumulator = 0.0
        handle = window.requestAnimationFrame(::frame)
    }

    fun stop() {
        if (!running) {
            return
        }
        running = false
        window.cancelAnimationFrame(handle)
        handle = 0
    }

    override fun toString(): String = "TurnScheduler($turnsPerSecond/s, running=$running)"

    /**
     * `internal` rather than `private` so `TurnSchedulerTest` can drive the clock.
     *
     * The accumulator arithmetic below is the only thing here that can fail silently, and the only
     * way to exercise it is to hand it timestamps — which is exactly what the browser does.
     */
    internal fun frame(timestamp: Double) {
        if (!running) {
            return
        }

        // The first frame after start() has no predecessor, and the first after a hidden tab has a
        // useless one. Both are clamped rather than special-cased.
        //
        // The lower bound is not decoration. Frame timestamps are meant to be monotonic, and if one
        // ever is not, an unclamped negative interval drives the accumulator below zero and the
        // match silently stops for however long it takes to climb back — a freeze with no error and
        // no obvious cause. Refusing to run time backwards costs nothing and removes the failure.
        val elapsedMillis = if (hasPreviousFrame) timestamp - previousFrame else 0.0
        previousFrame = timestamp
        hasPreviousFrame = true
        accumulator += elapsedMillis.coerceIn(0.0, MAX_FRAME_MILLIS) / 1000.0 * turnsPerSecond

        val frameStart = TimeSource.Monotonic.markNow()
        var played = 0

        while (accumulator >= 1.0) {
            when (step()) {
                Progress.CONTINUED -> {
                    accumulator -= 1.0
                    played++
                }

                Progress.AWAITING_INPUT -> {
                    // Do not consume, and do not let the debt grow: a player who thinks for five
                    // seconds must not have five seconds of turns fired at them on the next keypress.
                    accumulator = minOf(accumulator, 1.0)
                    break
                }

                Progress.FINISHED -> {
                    accumulator = 0.0
                    stop()
                    onFrame()
                    return
                }
            }

            if (played >= MAX_TURNS_PER_FRAME) {
                break
            }
            if (frameStart.elapsedNow() > FRAME_BUDGET) {
                // Degrade the turn rate rather than freeze the page. A search bot having a hard
                // think makes the match visibly slower, which is honest, and never unresponsive.
                break
            }
        }

        onFrame()
        handle = window.requestAnimationFrame(::frame)
    }

    companion object {
        /** Slow enough for a person to steer at, on a board where each snake acts every other turn. */
        const val DEFAULT_TURNS_PER_SECOND: Double = 12.0

        /** Roughly half a 60 Hz frame, leaving the other half for painting and the browser. */
        private val FRAME_BUDGET: Duration = 8.milliseconds

        /** A long stall — an alt-tab, a breakpoint — is one frame of turns, not a backlog of them. */
        private const val MAX_FRAME_MILLIS: Double = 250.0

        /** A ceiling for the pathological case of a very high speed and a very cheap bot. */
        private const val MAX_TURNS_PER_FRAME: Int = 256
    }
}
