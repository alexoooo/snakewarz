package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.Grid
import ao.snakewarz.core.Occupancy
import ao.snakewarz.core.RulesConfig
import ao.snakewarz.core.SplitMix64

/**
 * Everything needed to set a match up, and the header of its replay.
 *
 * A recorded match replays under the rules and the layout it was *played* under, never under
 * today's defaults — which is why the geometry, the rules, the spawn squares and the turn order all
 * live here rather than being derived at playback time.
 *
 * The spawns are recorded rather than re-derived on purpose. Deriving them would save a dozen bytes
 * and stake every replay ever shared on the placement algorithm never changing again; recording them
 * costs a varint per slot and makes the record self-contained. [seed] is kept as provenance and as
 * the input CI needs to re-run the real bots, never as the source of truth for playback.
 *
 * [spawns] are **playable** indices, `row * cols + col`, so the format does not encode the engine's
 * padded-grid layout.
 */
public class MatchSetup(
    public val seed: Long,
    public val rows: Int,
    public val cols: Int,
    public val rules: RulesConfig,
    /** Search allowance per turn. Recorded because `verify()` must re-run the bots under it. */
    public val budgetPerTurn: Int,
    public val slots: List<BotId>,
    turnOrder: IntArray,
    spawns: IntArray,
) {
    private val order: IntArray = turnOrder.copyOf()
    private val starts: IntArray = spawns.copyOf()

    public val slotCount: Int get() = slots.size

    init {
        require(rows > 0 && cols > 0) { "a board must be at least 1x1, was ${rows}x$cols" }
        require(slots.isNotEmpty()) { "a match needs at least one slot" }
        require(slotCount <= Occupancy.MAX_SNAKES) {
            "a match takes at most ${Occupancy.MAX_SNAKES} slots, was $slotCount"
        }
        require(budgetPerTurn >= 0) { "budgetPerTurn must not be negative, was $budgetPerTurn" }
        require(order.size == slotCount) { "turn order has ${order.size} entries for $slotCount slots" }
        require(starts.size == slotCount) { "there are ${starts.size} spawns for $slotCount slots" }

        val seenSlot = BooleanArray(slotCount)
        for (slot in order) {
            require(slot in 0 until slotCount) { "turn order names a slot $slot that does not exist" }
            require(!seenSlot[slot]) { "turn order names slot $slot twice" }
            seenSlot[slot] = true
        }

        val playableCount = rows * cols
        for (slot in 0 until slotCount) {
            require(starts[slot] in 0 until playableCount) {
                "slot $slot spawns at ${starts[slot]}, which is off a ${rows}x$cols board"
            }
            for (other in 0 until slot) {
                require(starts[other] != starts[slot]) { "slots $other and $slot spawn on the same square" }
            }
        }
    }

    /** The slot indices in the order they act, as a fresh array. */
    public fun turnOrder(): IntArray = order.copyOf()

    /** The playable spawn index per slot, as a fresh array. */
    public fun spawns(): IntArray = starts.copyOf()

    public fun grid(): Grid = Grid(rows, cols)

    /** The spawns translated into [grid]'s padded address space, which is what [ao.snakewarz.core.Board] wants. */
    internal fun spawnCells(grid: Grid): IntArray =
        IntArray(slotCount) { grid.cellAt(starts[it] / cols, starts[it] % cols).index }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MatchSetup) return false
        return seed == other.seed &&
            rows == other.rows &&
            cols == other.cols &&
            rules == other.rules &&
            budgetPerTurn == other.budgetPerTurn &&
            slots == other.slots &&
            order.contentEquals(other.order) &&
            starts.contentEquals(other.starts)
    }

    override fun hashCode(): Int {
        var result = seed.hashCode()
        result = 31 * result + rows
        result = 31 * result + cols
        result = 31 * result + rules.hashCode()
        result = 31 * result + budgetPerTurn
        result = 31 * result + slots.hashCode()
        result = 31 * result + order.contentHashCode()
        result = 31 * result + starts.contentHashCode()
        return result
    }

    override fun toString(): String =
        "MatchSetup(${rows}x$cols, seed=$seed, slots=$slots, order=${order.toList()}, spawns=${starts.toList()})"

    public companion object {
        /**
         * Simulated moves a bot may spend on one turn — **measured, not guessed**.
         *
         * The criterion is the scheduler's frame budget. `:ui` gives a frame 8 ms of stepping and
         * then stops, but it can only stop *between* turns, so a turn that overruns the slice
         * overruns the frame. Phase 6 timed `UctBot` on a 20x20 in headless Chrome — the slower of
         * the two targets, and the one people play on:
         *
         * | allowance | Chrome | JVM |
         * |---|---|---|
         * | 10,000 | 1.2 ms/turn | 0.40 ms/turn |
         * | 40,000 | 4.1 ms/turn | 1.6 ms/turn |
         * | 60,000 | 5.2 ms/turn | 2.1 ms/turn |
         * | 100,000 | 16.6 ms/turn | 4.1 ms/turn |
         *
         * 40,000 is **half** the 8 ms slice rather than all of it, and that headroom is the whole
         * argument: the machine those numbers came off is a desktop, and one four times slower still
         * lands inside a single 60 Hz frame. Going to 60,000 would spend the headroom to buy a bot
         * that `BotLadderTest` cannot tell apart from this one.
         *
         * Four times the Phase 4 guess, and worth it: `uct` at this allowance beats `uct` at a tenth
         * of it, which is the assertion in `BotLadderTest` that says the extra iterations are real
         * playing strength rather than a bigger number. Every shipped bot still degrades gracefully
         * below it, down to and including zero.
         *
         * Raising it invalidates no replay. `budgetPerTurn` is in the header, so a record carries the
         * allowance it was played under and `verify` re-runs against that, never against whatever
         * this constant says today.
         */
        public const val DEFAULT_BUDGET_PER_TURN: Int = 40_000

        /**
         * The RNG stream setup draws from.
         *
         * Kept clear of the per-slot streams, which are `0 until slotCount`, so shuffling the turn
         * order can never shift a bot's randomness.
         */
        private const val SETUP_STREAM: Int = -1

        /**
         * The usual way to start a match: geometry, who is playing, and a seed.
         *
         * The turn order is shuffled from the seed rather than left as slot order, because acting
         * first is a real advantage and always handing it to slot 0 would bias every tournament.
         */
        public fun create(
            rows: Int,
            cols: Int,
            slots: List<BotId>,
            seed: Long,
            rules: RulesConfig = RulesConfig(),
            budgetPerTurn: Int = DEFAULT_BUDGET_PER_TURN,
        ): MatchSetup {
            val grid = Grid(rows, cols)
            val setupRng = SplitMix64(seed).fork(SETUP_STREAM)

            val order = IntArray(slots.size) { it }
            for (i in order.size - 1 downTo 1) {
                val j = setupRng.nextInt(i + 1)
                val swap = order[i]
                order[i] = order[j]
                order[j] = swap
            }

            return MatchSetup(
                seed = seed,
                rows = rows,
                cols = cols,
                rules = rules,
                budgetPerTurn = budgetPerTurn,
                slots = slots.toList(),
                turnOrder = order,
                spawns = mostDistantSpawns(grid, slots.size),
            )
        }
    }
}
