package ao.snakewarz.match

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.grid.Occupancy
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.RulesConfig

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
 *
 * Four things are per slot — who is playing, when they act, where they start, and how they are
 * configured — and they are four parallel collections rather than a list of seat objects, because
 * most of the program only ever wants the first of them.
 */
public class MatchSetup(
    public val seed: Long,
    public val rows: Int,
    public val cols: Int,
    public val rules: RulesConfig,
    /**
     * The match default: the search allowance a slot gets unless it was handed its own.
     *
     * Recorded because `verify()` must re-run the bots under it.
     */
    public val budgetPerTurn: Int,
    public val slots: List<BotId>,
    turnOrder: IntArray,
    spawns: IntArray,
    /** Per-slot allowance. Empty gives every slot [budgetPerTurn], which is the usual match. */
    budgets: IntArray = IntArray(0),
    /** Per-slot knob values. Empty gives every slot [BotParams.EMPTY], which is the usual match. */
    slotParams: List<BotParams> = emptyList(),
) {
    private val order: IntArray = turnOrder.copyOf()
    private val starts: IntArray = spawns.copyOf()

    /**
     * Materialised rather than left null, so that a setup built from [budgetPerTurn] alone is
     * *equal* to one handed the same figure for every slot.
     *
     * That is not tidiness: `ReplayCodec` decodes an unconfigured payload into the broadcast form,
     * and every round-trip test asserts the result equals the record that produced it.
     */
    private val allowances: IntArray =
        if (budgets.isEmpty()) IntArray(slots.size) { budgetPerTurn } else budgets.copyOf()

    private val knobs: List<BotParams> =
        if (slotParams.isEmpty()) List(slots.size) { BotParams.EMPTY } else slotParams.toList()

    public val slotCount: Int get() = slots.size

    init {
        require(rows in 1..MAX_SIDE && cols in 1..MAX_SIDE) {
            "a board must be 1x1 to ${MAX_SIDE}x$MAX_SIDE, was ${rows}x$cols"
        }
        require(slots.isNotEmpty()) { "a match needs at least one slot" }
        require(slotCount <= Occupancy.MAX_SNAKES) {
            "a match takes at most ${Occupancy.MAX_SNAKES} slots, was $slotCount"
        }
        require(budgetPerTurn >= 0) { "budgetPerTurn must not be negative, was $budgetPerTurn" }
        require(order.size == slotCount) { "turn order has ${order.size} entries for $slotCount slots" }
        require(starts.size == slotCount) { "there are ${starts.size} spawns for $slotCount slots" }
        require(allowances.size == slotCount) { "there are ${allowances.size} allowances for $slotCount slots" }
        require(knobs.size == slotCount) { "there are ${knobs.size} parameter sets for $slotCount slots" }
        for (slot in 0 until slotCount) {
            require(allowances[slot] >= 0) { "slot $slot has an allowance of ${allowances[slot]}" }
        }

        val seenSlot = BooleanArray(slotCount)
        for (slot in order) {
            require(slot in 0 until slotCount) { "turn order names a slot $slot that does not exist" }
            require(!seenSlot[slot]) { "turn order names slot $slot twice" }
            seenSlot[slot] = true
        }

        // Cannot overflow: MAX_SIDE squared is four orders of magnitude short of Int.MAX_VALUE.
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

    /** The search allowance per slot, as a fresh array. */
    public fun budgets(): IntArray = allowances.copyOf()

    /** What [slot] may spend on one turn. */
    public fun budgetFor(slot: Int): Int = allowances[slot]

    /** How [slot]'s bot was tuned, which is `BotParams.EMPTY` for a bot nobody configured. */
    public fun paramsFor(slot: Int): BotParams = knobs[slot]

    /** Whether anything here departs from "every slot at the match default, nothing tuned". */
    public val configured: Boolean
        get() = allowances.any { it != budgetPerTurn } || knobs.any { !it.isEmpty }

    public fun grid(): Grid = Grid(rows, cols)

    /** The spawns translated into [grid]'s padded address space, which is what [ao.snakewarz.core.rules.Board] wants. */
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
            starts.contentEquals(other.starts) &&
            allowances.contentEquals(other.allowances) &&
            knobs == other.knobs
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
        result = 31 * result + allowances.contentHashCode()
        result = 31 * result + knobs.hashCode()
        return result
    }

    override fun toString(): String =
        "MatchSetup(${rows}x$cols, seed=$seed, slots=$slots, order=${order.toList()}, spawns=${starts.toList()})"

    public companion object {
        /**
         * The largest a board may be on either axis.
         *
         * Bounded for the reason [BotId.MAX_LENGTH] and `BotKnob.MAX_PER_BOT` are — *so a decoder
         * can reject a corrupt payload before allocating from it* — and this is the field where it
         * matters most. A board is what allocates: `Board` asks for a byte per padded square and an
         * `Int` per playable square **per slot**, so an unbounded `rows`/`cols` turns a sixty-byte
         * `#r=` link from a stranger into a request for half a gigabyte. The tab dies during boot,
         * and it dies with an OOM rather than the `IllegalArgumentException` that `:app` catches to
         * turn a bad link into a fresh match — so the reader is told their browser cannot run the
         * game, which is a confident and wrong diagnosis.
         *
         * 256 is far above anything the game offers: `:ui` stops at 40x40 and `:lab` defaults to
         * 12x12. It holds the worst case — eight slots on 256x256 — to a few megabytes, and it is
         * small enough that `rows * cols` cannot overflow anywhere downstream.
         */
        public const val MAX_SIDE: Int = 256

        /**
         * Evaluations a bot may spend on one turn — **measured, not guessed**.
         *
         * The criterion is the scheduler's frame budget. `:ui` gives a frame 8 ms of stepping and
         * then stops, but it can only stop *between* turns, so a turn that overruns the slice
         * overruns the frame. So `uct` was timed on a 20x20 in headless Chrome — the slower of the
         * two targets, and the one people play on — with `ThroughputTest`, and the other search bots
         * on the JVM with `:lab`'s `time`:
         *
         * | allowance | uct, Chrome | uct, JVM | flat-mc, JVM | puct territory, JVM |
         * |---|---|---|---|---|
         * | 250 | 1.1 ms | 0.41 ms | 0.42 ms | 1.8 ms |
         * | 1,000 | 5.0 ms | 2.0 ms | 1.5 ms | 5.4 ms |
         * | 2,000 | 9.8 ms | 4.3 ms | 3.4 ms | 12 ms |
         * | 10,000 | 60 ms | 25 ms | 17 ms | 69 ms |
         *
         * The `puct` column is that bot at its default appraisal, and it is the row of this table
         * that has aged: it was taken before the ownership sweep became a bitmap one, which took
         * `puct`'s turn 2.13x cheaper on a 20x20. `EvaluationCost` carries the current figures and
         * the control that makes them readable beside each other. The five other appraisals that
         * bot offers are absent because this table is what sets a *default*, and the default
         * evaluation is `territory`.
         *
         * 1,000 puts `uct` at 5 ms of the 8 ms slice in Chrome. That is inside it with less room
         * than the previous default had, and the trade was made deliberately: the number is now a
         * count of *iterations*, which is a thing a person can reason about — a thousand rollouts a
         * turn — where 40,000 simulated moves was a number whose meaning changed with the bot
         * reading it. The knob's ceiling of 10,000 is far past the slice, and that is what a ceiling
         * is for: somewhere to reach deliberately, not somewhere to sit.
         *
         * **`puct` at `eval=territory` is inside the slice too, at somewhere between 6 and 7 ms of
         * it.** That is a ratio rather than a measurement, and the band is the honest width of one:
         * the appraisal is 2.49 ms of JVM on a 20x20 at this allowance, and what carries a JVM
         * figure into the browser is `uct`'s tax between the two — 2.5x off this table's own row and
         * 2.75x against the control `EvaluationCost` took its own figures beside, so 6.2 ms and 6.8.
         * Nobody has timed an appraisal in Chrome. The one thing known about the direction of the
         * error runs the safe way: the bitmap sweep underneath this leaf gains *more* in the browser
         * than on the JVM, so the true figure sits at the low end of that band or under it.
         *
         * The other five appraisals do not land together. `eval=mobility` reads sixteen squares and
         * is nowhere near the slice; the four that take the board apart are four times `territory`
         * or more and overrun it outright. That spread is the honest reading of the bot being
         * registered experimental and of `EvaluationCost` being uncalibrated.
         *
         * **The same arithmetic says the slice now affords about 1,180 evaluations, up from about
         * 590** — at the dearer end of the band, so it is the conservative figure of the two.
         * Recorded here rather than taken. Raising this moves what every unconfigured match plays
         * at, so `BotLadderTest`'s thresholds — measured at 1,000 — and the two test constants that
         * spell this figure out would all have to move with it. That is a shipping decision somebody
         * makes, not a consequence of a sweep getting cheaper.
         *
         * The unit changed under this figure and the number changed with it. An allowance used to
         * count *simulated moves*, where 40,000 bought `uct` about 4 ms; counting evaluations makes
         * the same allowance mean the same amount of search whatever a bot does inside an iteration,
         * which is what a win-rate matrix needs it to mean. `uct` at this allowance beats `uct` at a
         * tenth of it, which is the assertion in `BotLadderTest` that says the extra iterations are
         * real playing strength rather than a bigger number. Every shipped bot still degrades
         * gracefully below it, down to and including zero.
         *
         * Raising it invalidates no replay. `budgetPerTurn` is in the header, so a record carries the
         * allowance it was played under and `verify` re-runs against that, never against whatever
         * this constant says today.
         */
        public const val DEFAULT_BUDGET_PER_TURN: Int = 1_000

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
            budgets: IntArray = IntArray(0),
            slotParams: List<BotParams> = emptyList(),
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
                budgets = budgets,
                slotParams = slotParams,
            )
        }
    }
}
