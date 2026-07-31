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
 * The same reasoning puts the map here rather than a name for it: [walls] travels as the squares
 * themselves, so a map shape can be redesigned or deleted without breaking a link anybody has shared.
 *
 * [spawns] and [walls] are **playable** indices, `row * cols + col`, so the format does not encode
 * the engine's padded-grid layout.
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
    /** Permanently impassable squares, as **playable** indices `row * cols + col`, strictly ascending. */
    walls: IntArray = IntArray(0),
    /** Per-slot allowance. Empty gives every slot [budgetPerTurn], which is the usual match. */
    budgets: IntArray = IntArray(0),
    /** Per-slot knob values. Empty gives every slot [BotParams.EMPTY], which is the usual match. */
    slotParams: List<BotParams> = emptyList(),
) {
    private val order: IntArray = turnOrder.copyOf()
    private val starts: IntArray = spawns.copyOf()
    private val map: IntArray = walls.copyOf()

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

        // Ascending order is demanded here rather than by the engine because this is where a
        // stranger's payload lands, and it buys three things at once: duplicate detection in one
        // pass, a canonical form so `equals` compares maps honestly rather than orderings, and an
        // array the spawn test below can binary-search.
        var previous = -1
        for (i in map.indices) {
            require(map[i] in 0 until playableCount) {
                "wall $i is at ${map[i]}, which is off a ${rows}x$cols board"
            }
            require(map[i] > previous) { "walls must ascend and not repeat; ${map[i]} follows $previous" }
            previous = map[i]
        }
        for (slot in 0 until slotCount) {
            require(!holdsSorted(map, starts[slot])) { "slot $slot spawns on a wall of the map" }
        }
    }

    /** The slot indices in the order they act, as a fresh array. */
    public fun turnOrder(): IntArray = order.copyOf()

    /** The playable spawn index per slot, as a fresh array. */
    public fun spawns(): IntArray = starts.copyOf()

    /** The playable wall indices, as a fresh array. */
    public fun walls(): IntArray = map.copyOf()

    public val wallCount: Int get() = map.size

    /** Whether this match is played on a map at all — the codec's version selector. */
    public val mapped: Boolean get() = map.isNotEmpty()

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

    /** The walls translated into [grid]'s padded address space, which is what [ao.snakewarz.core.rules.Board] wants. */
    internal fun wallCells(grid: Grid): IntArray =
        IntArray(map.size) { grid.cellAt(map[it] / cols, map[it] % cols).index }

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
            map.contentEquals(other.map) &&
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
        result = 31 * result + map.contentHashCode()
        result = 31 * result + allowances.contentHashCode()
        result = 31 * result + knobs.hashCode()
        return result
    }

    // The wall count and not the wall indices: this string is embedded in the playback failure
    // message `Match.step()` raises, which a person has to be able to read.
    override fun toString(): String =
        "MatchSetup(${rows}x$cols, seed=$seed, slots=$slots, order=${order.toList()}, " +
            "spawns=${starts.toList()}, walls=${map.size})"

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
         * 256 is far above anything the game offers: `:ui` stops at 28x28 and `:lab` defaults to
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
         * | 250 | 1.1 ms | 0.41 ms | 0.42 ms | 0.78 ms |
         * | 1,000 | 5.0 ms | 2.0 ms | 1.5 ms | 2.6 ms |
         * | 2,000 | 9.8 ms | 4.3 ms | 3.4 ms | 5.5 ms |
         * | 10,000 | 60 ms | 25 ms | 17 ms | 29 ms |
         *
         * The `puct` column is that bot at its default appraisal. It reads **2.2x below what it read
         * before the ownership sweep became a bitmap one**, which is the 2.13x that change was
         * measured at, so what moved is the sweep and not the method. It was re-taken in a later
         * session on a different machine, so it is quoted with the control that makes the two
         * comparable: `uct` read 0.52 / 2.5 / 3.9 / 23 ms in that session against this table's own
         * `uct, JVM` row, which is the same figure to within the width of the instrument. The 1,000
         * row is six seeds (2.6 ms, sd 0.2); the other three are one seed each, and `time` plays a
         * different game per entrant, so read them as the shape of a column rather than as four
         * measurements. The five other appraisals that bot offers are absent because this table is
         * what sets a *default*, and the default evaluation is `territory`.
         *
         * 1,000 puts `uct` at 5 ms of the 8 ms slice in Chrome. That is inside it with less room
         * than the previous default had, and the trade was made deliberately: the number is now a
         * count of *iterations*, which is a thing a person can reason about — a thousand rollouts a
         * turn — where 40,000 simulated moves was a number whose meaning changed with the bot
         * reading it. The knob's ceiling of 10,000 is far past the slice, and that is what a ceiling
         * is for: somewhere to reach deliberately, not somewhere to sit.
         *
         * **`puct` at `eval=territory` is timed in Chrome, and on a 20x20 it is 6.0 ms of the slice
         * on the mean turn and 8.7 ms on the dearest.** `ThroughputTest`'s appraisal sweep is the
         * instrument and `AppraisalTape` is why it can be believed: every entrant is timed over one
         * fixed line of positions rather than over the game it happens to play. Both figures are
         * scaled by the control this table carries — `uct` on the whole-match path reads 3.4x to
         * 5.0x this table's `uct, Chrome` column on the machine the sweep was taken on, so the raw
         * readings are divided by 4.1 to land beside it.
         *
         * Read the *dearest* against the 8 ms, not the mean: this KDoc's own first paragraph says a
         * frame is overrun by a single turn. At this allowance on the largest board `puct` is at the
         * edge of the slice rather than comfortably inside it.
         *
         * **A JVM appraisal multiplied by `uct`'s browser tax is not a way to reach this figure**,
         * and the arithmetic that produced 6.2 to 6.8 ms for this row brackets the measurement by
         * luck rather than by validation. Two things are wrong with the step, and they run opposite
         * ways.
         *
         * *There is no single browser tax.* Timed over identical positions on a 20x20, the ratio
         * between the two targets runs from 2.4x for `alphabeta:eval=territory` to 3.3x for
         * `eval=chamber` — so carrying one bot's onto another's is worth up to 40%, and the spread
         * widens again on a smaller board where the JVM has more of the work in cache.
         *
         * *And the figure it multiplies is an opening.* `:lab`'s `time` seats the subject against a
         * `space` with no allowance, and on a 20x20 that match ends somewhere between turn 28 and
         * turn 225 depending only on the seed. It reads a `puct` turn at 2.6 ms where the same JVM
         * reads 10.0 ms over a line played out to a full board — and it is a *turn of a real game*
         * that has to fit inside a frame, not a turn of an opening.
         *
         * The other five appraisals do not land together. `eval=mobility` reads sixteen squares and
         * is nowhere near the slice; the four that take the board apart are four times `territory`
         * or more and overrun it outright — `eval=chamber` measures 4.6x on a 20x20 in Chrome, which
         * is 27 ms of an 8 ms slice, so it needs about a fifth of this allowance to fit. That spread
         * is the honest reading of `EvaluationCost` being uncalibrated, and it is a cost this
         * constant would have to answer for if a default evaluation ever moved.
         *
         * **One since has, and it answered in the good direction.** `alphabeta` defaulted to
         * `eval=chamber`, so the shipped bot's dearest turn on a 20x20 was ~44 ms — five and a half
         * times this slice — while nothing here said so, because this constant is one number for
         * every bot and the ladder was measured on a 12x12. Moving that default to `territory` took
         * it to ~8.5 ms, where `puct` already sat. The lesson is that a spread this wide means the
         * frame criterion is a property of the *bot* as much as of this constant, and the only way it
         * gets checked is somebody timing an appraisal in Chrome.
         *
         * **The slice affords `puct` between 920 and 1,350 evaluations on a 20x20** — 920 if the
         * dearest turn has to fit and 1,330 if the mean does, which is one measurement read against
         * the two criteria. This constant sits inside that band at either end.
         *
         * Raising it moves what every unconfigured match plays at, so `BotLadderTest`'s thresholds —
         * measured at 1,000 — and `:bots`' own copy of this figure have to move with it. That copy is
         * typed out rather than read, because `:bots` may not import `:match`, and what makes the
         * pair go red rather than quietly disagree is the
         * pin in `MatchSetupTest`. That is a shipping decision somebody makes, not a consequence of
         * a sweep getting cheaper.
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
            walls: IntArray = IntArray(0),
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
                spawns = mostDistantSpawns(grid, walls, slots.size),
                walls = walls,
                budgets = budgets,
                slotParams = slotParams,
            )
        }
    }
}

/**
 * Whether the ascending [sorted] holds [value].
 *
 * A binary search rather than a scan because the caller is the wall array, which a stranger's payload
 * can make as long as the board is large; `java.util.Arrays` is not available to common code.
 */
private fun holdsSorted(sorted: IntArray, value: Int): Boolean {
    var low = 0
    var high = sorted.size - 1
    while (low <= high) {
        val middle = (low + high) ushr 1
        when {
            sorted[middle] < value -> low = middle + 1
            sorted[middle] > value -> high = middle - 1
            else -> return true
        }
    }
    return false
}
