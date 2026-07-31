package ao.snakewarz.match.human

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet

/**
 * The queue between what a player asked for and what a snake does next.
 *
 * A human works the keyboard or the pointer on the browser's schedule and the match consumes what
 * they meant on the engine's, so something has to sit between the two. This is that thing, and it is
 * deliberately platform-free: `:ui` calls [push] from a `keydown` listener and [replace] from a drag,
 * [InteractiveBot] calls [take] from inside its turn, and neither the buffer nor the bot has any idea
 * a DOM exists.
 *
 * Three behaviours here are UX decisions rather than plumbing, and all three are load-bearing:
 *
 * - **[take] drops illegal inputs rather than playing them.** A human who taps left half a square
 *   too late would otherwise drive into their own neck and die to a mistimed keypress. Filtering
 *   means humans die by being trapped, which is the game, instead of by input lag, which is not. It
 *   is also what lets a drawn route be a *plan* rather than a promise: a square that has been taken
 *   by the time the snake gets there costs the rest of the route, not the player's life.
 * - **[push] collapses a repeat of the direction already queued last.** Holding an arrow key fires
 *   `keydown` at the operating system's auto-repeat rate, and without this a single held key would
 *   fill the queue and eat the next several turns the player actually meant. `:ui` also ignores
 *   `KeyboardEvent.repeat`; this is the belt to that pair of braces.
 * - **[replace] swaps the whole queue, and neither collapses nor drops.** A drawn route is one
 *   intent rather than a run of key presses, and swapping it in whole is what makes letting go of a
 *   drag mean *stop*.
 *
 * The two capacities are those two intents. [DEFAULT_CAPACITY] holds a turn or two of anticipation,
 * because a deep keyboard queue reads as input lag — every key waits behind the ones before it, so
 * the snake stops answering the one just pressed. [PATH_CAPACITY] holds a route drawn in a single
 * gesture, which is deep without being a backlog: the player takes all of it back by lifting a
 * finger.
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
     * Replaces everything queued with [count] directions from [directions], as one swap.
     *
     * A drawn path is a single intent, not a run of key presses — so it neither collapses its repeats
     * (five squares east is five moves, not one) nor drops its newest end when it outgrows what was
     * queued before it. Replacing rather than appending is what makes redrawing mid-drag cheap and
     * what makes letting go mean *stop*.
     *
     * Directions arrive as [Direction] ordinals in a primitive array, which is the representation
     * [PathPlanner] plans in and the one that hands a whole route over without boxing a square of it.
     *
     * A route longer than [capacity] is **refused**, loudly: [PathPlanner] is bounded by
     * [PATH_CAPACITY] and a buffer a drag feeds is built with it, so an overlong one is a caller's
     * arithmetic rather than anything a player could do with a pointer.
     */
    public fun replace(directions: IntArray, count: Int) {
        require(count >= 0) { "a path cannot hold $count directions" }
        require(count <= directions.size) {
            "a path of $count directions cannot be read from ${directions.size} of them"
        }
        require(count <= queued.size) {
            "a path of $count directions does not fit a buffer of ${queued.size}"
        }

        directions.copyInto(queued, 0, 0, count)
        head = 0
        size = count
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

        /**
         * Room for a path drawn across a large board. A person does not draw a longer one.
         *
         * The buffer is built once, in `main()`, before any board exists, so it cannot be sized off
         * a grid. Two kilobytes, once. Keyboard behaviour does not change with it: the collapse rule
         * is on [push], so a held arrow still queues at most one pending move whatever the capacity.
         */
        public const val PATH_CAPACITY: Int = 512
    }
}
