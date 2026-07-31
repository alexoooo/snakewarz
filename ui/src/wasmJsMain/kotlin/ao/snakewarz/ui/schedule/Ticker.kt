package ao.snakewarz.ui.schedule

import ao.snakewarz.ui.chrome.KeyRepeat
import kotlinx.browser.window

/**
 * Turns wall-clock time into **paints**, and never into turns.
 *
 * It is not a second [TurnScheduler], and the framing is [KeyRepeat]'s: that one produces keypresses
 * and this one produces repaints, and what either turns into is somebody else's decision. **Nothing
 * this computes may reach `Match.step`.** A frame here changes how a position *looks* — a body
 * settling after a death, dashes marching along a held route, a head breathing — and a match played
 * on a machine that dropped every one of these frames ends exactly the same way. It reads the wall
 * clock, which `:ui` may and nothing below it may, and that permission is only safe while this
 * sentence stays true.
 *
 * **It exists because the turn clock stops at the wrong moment.** [TurnScheduler] runs only while a
 * match is being played and parks on `Progress.FINISHED` — which is the exact instant the last snake
 * dies and the death effect needs frames. It is also idle through every pause, every finished match
 * and every hover, all of which have something to draw.
 *
 * **It runs while something is moving and stops itself the moment nothing is.** [paint] answers with
 * whether any effect still has frames to run, so the loop ends by itself rather than by anybody
 * remembering to stop it — the alternative is a `requestAnimationFrame` chain that never ends on a
 * page somebody left open.
 */
internal class Ticker(
    /**
     * Paints one frame at the motion clock's current reading, and answers whether anything is still
     * moving.
     *
     * `false` is what stops the loop, so an effect that forgets to say it has finished is a page
     * that repaints forever, and one that says so early is an effect that ends a frame short.
     */
    private val paint: (Double) -> Boolean,
) {
    var running: Boolean = false
        private set

    private var handle: Int = 0
    private var previousFrame: Double = 0.0
    private var hasPreviousFrame: Boolean = false

    /**
     * How much time the effects have seen, which is **not** how much time has passed.
     *
     * It advances only while this is running, and it is never reset — so an effect stamped with it
     * while the loop is stopped is stamped at "now" in the only clock the effects have, and starting
     * the loop carries on from there. A clock that restarted at zero on every [start] would put every
     * stamp taken before it in the future.
     */
    private var motionMillis: Double = 0.0

    fun start() {
        if (running) {
            return
        }
        running = true
        // The gap across a stop is not motion anybody saw, so the first frame back measures nothing.
        hasPreviousFrame = false
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

    override fun toString(): String = "Ticker(${motionMillis.toInt()}ms, running=$running)"

    /**
     * `internal` rather than `private` so `TickerTest` can drive the clock, which is
     * [TurnScheduler.frame]'s reason and the same one: the only way to exercise an accumulator is to
     * hand it timestamps, and that is all the browser does.
     */
    internal fun frame(timestamp: Double) {
        if (!running) {
            return
        }

        // Both bounds are [TurnScheduler]'s, for the same two failure modes. A frame that runs
        // backwards would wind an effect back into its own past, and the gap across an alt-tab or a
        // breakpoint would otherwise finish every effect on the board in a single frame.
        val elapsed = if (hasPreviousFrame) timestamp - previousFrame else 0.0
        previousFrame = timestamp
        hasPreviousFrame = true
        motionMillis += elapsed.coerceIn(0.0, MAX_FRAME_MILLIS)

        if (!paint(motionMillis)) {
            stop()
            return
        }
        handle = window.requestAnimationFrame(::frame)
    }

    private companion object {
        /** A long stall is one frame of motion, not every effect on the board finishing at once. */
        const val MAX_FRAME_MILLIS: Double = 250.0
    }
}
