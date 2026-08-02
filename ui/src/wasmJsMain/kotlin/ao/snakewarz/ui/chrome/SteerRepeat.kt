package ao.snakewarz.ui.chrome

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.ui.schedule.TurnScheduler
import kotlinx.browser.window

/**
 * A direction held down, turned into moves at a rate a person can watch.
 *
 * Two things hold one — an arrow key and a button on [SteerPad] — and they share one instance rather
 * than pacing themselves separately, because a thumb and a keyboard both holding would otherwise be
 * two clocks driving one snake.
 *
 * The operating system repeats a held key already, and its rate is exactly wrong here: half a second
 * of nothing and then thirty a second, tuned for cursors in text and different on every machine.
 * A snake crossing the board at that rate is a snake nobody can stop where they meant to. So
 * [Chrome] drops `KeyboardEvent.repeat` and this times the repeat instead — a tap is one move, and
 * holding is a move every [PERIOD_MILLIS], the same on every keyboard and under every thumb.
 *
 * On `requestAnimationFrame` for the same reasons [TurnScheduler] is: it is vsync-aligned, it stops
 * dead in a hidden tab rather than banking up moves to fire on return, and a timestamp handed in
 * from outside is a clock a test can drive.
 */
internal class SteerRepeat(private val onMove: (Direction) -> Unit) {
    private var held: Direction? = null
    private var handle: Int = 0

    /** When the next repeat is owed, or `NaN` while nothing has been scheduled yet. */
    private var dueAt: Double = Double.NaN

    /** Plays [direction] now, and again every [PERIOD_MILLIS] until it is let go of. */
    fun press(direction: Direction) {
        // A second direction taken while the first is still down takes the repeat over, which is how
        // anybody actually turns a corner: the new direction is the one being asked for.
        held = direction
        dueAt = Double.NaN
        // Establish the hold before playing the immediate move. That move can end the match and
        // synchronously cancel steering; writing the hold afterwards would resurrect it and prime
        // the next match with a direction nobody pressed there.
        onMove(direction)
        if (held == direction && handle == 0) {
            handle = window.requestAnimationFrame(::frame)
        }
    }

    /** Stops the repeat, if [direction] is the one running. One that was not held is not news. */
    fun release(direction: Direction) {
        if (held == direction) {
            cancel()
        }
    }

    /**
     * Forgets the held direction.
     *
     * Needed because a key released while the page is not looking sends no `keyup` at all: alt-tab
     * mid-move and the snake would keep going, with nothing on screen to explain why.
     */
    fun cancel() {
        held = null
        if (handle != 0) {
            window.cancelAnimationFrame(handle)
            handle = 0
        }
    }

    override fun toString(): String = "SteerRepeat(${held ?: "up"})"

    /** `internal` rather than `private` so `SteerRepeatTest` can be the clock this KDoc promises. */
    internal fun frame(timestamp: Double) {
        val direction = held
        if (direction == null) {
            handle = 0
            return
        }

        if (dueAt.isNaN()) {
            dueAt = timestamp + PERIOD_MILLIS
        } else if (timestamp >= dueAt) {
            onMove(direction)
            // Owed from now rather than from when it fell due: a slow frame must not leave a debt
            // that spends itself as two moves in a row the moment the page catches up.
            dueAt = timestamp + PERIOD_MILLIS
        }

        handle = window.requestAnimationFrame(::frame)
    }

    private companion object {
        /**
         * Four moves a second.
         *
         * Fast enough to cross a board without pressing a key thirty times, slow enough that you
         * stop on the square you meant to — which on a board where one square decides the game is
         * the only rate that matters.
         */
        const val PERIOD_MILLIS: Double = 250.0
    }
}
