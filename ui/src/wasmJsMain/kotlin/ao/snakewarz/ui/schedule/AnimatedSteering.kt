package ao.snakewarz.ui.schedule

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.match.human.InputBuffer
import kotlinx.browser.window

/** Holds direct steering until the move already on screen has finished gliding. */
internal class AnimatedSteering(
    private val moveAnimating: () -> Boolean,
    private val play: (Direction) -> Unit,
) {
    private val pending = InputBuffer()
    private var handle: Int = 0

    var running: Boolean = false
        private set

    /** Queues [direction], playing it now only when it cannot replace an unfinished transition. */
    fun offer(direction: Direction) {
        pending.push(direction)
        if (!running && moveAnimating()) {
            running = true
            handle = window.requestAnimationFrame(::frame)
        } else if (!running) {
            playNext()
        }
    }

    /** Cancels every anticipated direction when another control or match takes ownership. */
    fun clear() {
        pending.clear()
        if (running) {
            running = false
            window.cancelAnimationFrame(handle)
            handle = 0
        }
    }

    /** `internal` so the browser test can drive the same frame boundary the browser supplies. */
    internal fun frame(timestamp: Double) {
        if (!running) {
            return
        }
        if (!moveAnimating()) {
            playNext()
        }
        if (pending.isEmpty) {
            running = false
            handle = 0
        } else {
            handle = window.requestAnimationFrame(::frame)
        }
    }

    private fun playNext() {
        pending.take(DirectionSet.ALL)?.let(play)
    }
}
