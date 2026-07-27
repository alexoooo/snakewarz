package ao.snakewarz.ui.schedule

import ao.snakewarz.match.tournament.Tournament
import kotlinx.browser.window
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Runs a batch of matches without stopping the page, by only ever playing a few milliseconds of it
 * per frame.
 *
 * The same shape as [TurnScheduler] and for the same reasons — `requestAnimationFrame`, a wall-time
 * guard, and no clock anywhere below `:ui`. What differs is the unit and the goal. The scheduler
 * paces a match *down* to a speed a person can watch; this one runs a tournament *up* to whatever the
 * machine will give, and the only limit is that a frame has to end.
 *
 * [Tournament.step] plays at most one turn, which is what makes that possible: a tournament of search
 * bots is hundreds of thousands of turns, and stopping between any two of them is free. Stepping a
 * whole *match* at a time would be simpler and would stall the page for as long as a match takes,
 * which at the shipped allowance is most of a second.
 *
 * There is no accumulator and no turns-per-second here. A tournament is not something you watch at a
 * speed; it is something you wait for, and the honest interface to it is a progress figure.
 */
internal class TournamentRunner(private val onFrame: () -> Unit) {
    var tournament: Tournament? = null
        private set

    var running: Boolean = false
        private set

    private var handle: Int = 0

    fun start(batch: Tournament) {
        stop()
        tournament = batch
        running = true
        handle = window.requestAnimationFrame(::frame)
    }

    /** Stops where it is. The table keeps whatever was played, which is a partial result and says so. */
    fun stop() {
        if (!running) {
            return
        }
        running = false
        window.cancelAnimationFrame(handle)
        handle = 0
    }

    override fun toString(): String = "TournamentRunner(${tournament ?: "idle"})"

    /**
     * The timestamp is ignored, unlike in [TurnScheduler.frame] and `KeyRepeat.frame`.
     *
     * Deliberate rather than missed: those two pace something against wall time and need to know how
     * much of it went by, and this one has nothing to pace. A batch runs as fast as the machine
     * allows, so the only clock it needs is the one bounding this frame.
     */
    private fun frame(timestamp: Double) {
        if (!running) {
            return
        }

        val batch = tournament ?: return
        val frameStart = TimeSource.Monotonic.markNow()

        while (batch.step() != Tournament.Progress.FINISHED) {
            if (frameStart.elapsedNow() > FRAME_BUDGET) {
                break
            }
        }

        if (batch.finished) {
            running = false
            onFrame()
            return
        }

        onFrame()
        handle = window.requestAnimationFrame(::frame)
    }

    private companion object {
        /**
         * Roughly half a 60 Hz frame, as [TurnScheduler] uses — the other half is the browser's.
         *
         * A single turn can overrun it, because a turn cannot be interrupted. That is the same bound
         * `MatchSetup.DEFAULT_BUDGET_PER_TURN` is chosen against, and it is why choosing that number
         * from a timing rather than a guess mattered.
         */
        val FRAME_BUDGET: Duration = 8.milliseconds
    }
}
