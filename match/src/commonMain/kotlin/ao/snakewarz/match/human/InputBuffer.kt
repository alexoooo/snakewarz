package ao.snakewarz.match.human

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet

/**
 * The queue between a keyboard and a snake.
 *
 * A human presses keys on the browser's schedule and the match consumes them on the engine's, so
 * something has to sit between the two. This is that thing, and it is deliberately platform-free:
 * `:ui` calls [push] from a `keydown` listener, [InteractiveBot] calls [take] from inside its turn,
 * and neither the buffer nor the bot has any idea a DOM exists.
 *
 * Two behaviours here are UX decisions rather than plumbing, and both are load-bearing:
 *
 * - **[take] drops illegal inputs rather than playing them.** A human who taps left half a square
 *   too late would otherwise drive into their own neck and die to a mistimed keypress. Filtering
 *   means humans die by being trapped, which is the game, instead of by input lag, which is not.
 * - **[push] collapses a repeat of the direction already queued last.** Holding an arrow key fires
 *   `keydown` at the operating system's auto-repeat rate, and without this a single held key would
 *   fill the queue and eat the next several turns the player actually meant. `:ui` also ignores
 *   `KeyboardEvent.repeat`; this is the belt to that pair of braces.
 *
 * The buffer is small on purpose. It exists to absorb a turn or two of anticipation, not to let a
 * player type a route in advance and walk away.
 */
public class InputBuffer(capacity: Int = DEFAULT_CAPACITY) {
    private val queued = IntArray(capacity)
    private var head = 0

    /** How many directions are waiting to be played. */
    public var size: Int = 0
        private set

    init {
        require(capacity >= 1) { "an input buffer holds at least one direction, was $capacity" }
    }

    public val capacity: Int get() = queued.size

    public val isEmpty: Boolean get() = size == 0

    /**
     * Queues [direction], and reports whether it was actually taken.
     *
     * Returns `false` when the buffer is full — the *newest* keypress is the one dropped, so a
     * player mashing keys still gets the moves they asked for first rather than a scrambled tail —
     * and when [direction] repeats the one queued most recently, which is auto-repeat rather than
     * intent.
     */
    public fun push(direction: Direction): Boolean {
        if (size == queued.size) {
            return false
        }
        if (size > 0 && queued[slotAt(size - 1)] == direction.ordinal) {
            return false
        }

        queued[slotAt(size)] = direction.ordinal
        size++
        return true
    }

    /**
     * The oldest queued direction that is a member of [legal], discarding everything ahead of it.
     *
     * `null` means the player has nothing playable waiting — either the queue is empty or every
     * direction in it would have been fatal. What to do about that is [StallPolicy]'s decision, not
     * this class's.
     */
    public fun take(legal: DirectionSet): Direction? {
        while (size > 0) {
            val next = Direction.entries[queued[head]]
            head = if (head + 1 == queued.size) 0 else head + 1
            size--

            if (next in legal) {
                return next
            }
        }
        return null
    }

    /** Forgets everything queued. Called when a match restarts, so old keypresses do not leak into it. */
    public fun clear() {
        head = 0
        size = 0
    }

    override fun toString(): String = "InputBuffer($size/${queued.size})"

    private fun slotAt(offset: Int): Int {
        val raw = head + offset
        return if (raw >= queued.size) raw - queued.size else raw
    }

    public companion object {
        /**
         * Room for the turn you are making and a couple you are planning.
         *
         * Deep queues feel like input lag: every key you press has to wait behind the ones before
         * it, so the snake stops responding to the key you just hit.
         */
        public const val DEFAULT_CAPACITY: Int = 3
    }
}
