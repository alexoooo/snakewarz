package ao.snakewarz.bots.search.learned

import ao.snakewarz.bots.search.puct.ChamberEval
import ao.snakewarz.bots.search.puct.ChamberTree
import ao.snakewarz.bots.search.puct.FillableSpace
import ao.snakewarz.bots.search.puct.TempoOwnership
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId
import kotlin.math.abs

/**
 * A position, read from one slot's point of view as a fixed-length vector of bounded numbers.
 *
 * This is the input a learned evaluation gets in place of the board, and it is **the only part of
 * `:bots` that is public besides the registry**. That is deliberate and it is the whole design: a
 * trainer lives in `:lab`, Kotlin's `internal` is module-scoped, and `:lab`'s main compilation is not
 * an associated compilation of `:bots` — so a trainer that could not import this would have to
 * reimplement it, and a reimplementation that drifts by one term produces a bot that is merely
 * mediocre rather than visibly broken. One definition, read by the trainer and by [LearnedEval].
 *
 * ### Why a hand-built vector rather than the board
 *
 * `BotContractTest` runs 1x1 through 20x20 and `MatchSetup.MAX_SIDE` is 256, so there is no input
 * plane a model could be shaped for: a network taking the squares would have to be retrained per
 * geometry and could not answer at all on a board it had never seen. Every reading below is therefore
 * a **ratio, a share or a flag**, so that the same number means the same thing on a 3x7 and on a
 * 200x200 — which is also what makes a linear model over them a reasonable thing to fit.
 *
 * ### What it costs, and why it is exactly [ChamberEval]'s bill
 *
 * One [TempoOwnership] sweep and one [ChamberTree] decomposition per live snake, which is what that
 * leaf already pays. Everything after those is a division. So a batch between `eval=chamber` and
 * `eval=learned` is very nearly a comparison at an equal clock as well as at an equal allowance,
 * which is the only honest way to ask whether the *readings* are better rather than whether more of
 * them fit.
 *
 * The decomposition runs at `parityWeight = 1.0, frontierPenalty = 0.0`, so [ChamberTree.chainWorth]
 * is [FillableSpace]'s integer exactly and the contested share is handed over as [CONTESTED] for the
 * model to price. `ChamberEval` folds that discount into the chain with a swept weight; here the
 * weight is the thing being learned, so folding it in first would be fitting on top of an answer.
 *
 * ### The readings, and why each is here
 *
 * Six phases of measurement say where the signal in this game is, and the vector is built from that
 * rather than from a list of everything a board can be asked:
 *
 * - **[SEALED] is the one reading no other leaf could make**, and it is the whole of what
 *   `ChamberEval` was worth (`PuctBot.SEAL_PENALTY` carries the ablation). [RIVAL_SEALED] is the same
 *   question asked of the opponent, which nothing in the box asks at all.
 * - **The parity relaxation lost the same bet twice** — `HorizonEval` by argument and SPSA by search.
 *   So the chessboard cap stays on inside [USABLE_SHARE] and the raw square count is carried
 *   *separately* as [OWNED_SHARE] and [REGION_SHARE], leaving the model free to relax it or not
 *   rather than being handed a number that already has.
 * - **Territory share and the separated margin are what every shipped leaf reads**, so [USABLE_SHARE]
 *   and [USABLE_MARGIN] are here to reproduce them, and [ISOLATED_MARGIN] and [CONTESTED_SHARE] are
 *   the same two gated by [ISOLATED] — a linear model has no product term, and which of the two
 *   branches applies is exactly a product.
 * - **[LENGTH_VS_ROOM] is the question the retraction argument was really asking.** Whether a walk can
 *   loop in a room is `2 * area` against `length`, and no shipped leaf carries the length at all.
 * - **[TAIL_DISTANCE] is `MovePrior`'s strongest single reading** promoted from a fact about a move to
 *   a fact about a position.
 *
 * ### The last four came from the fit's residual, and they are worth almost nothing
 *
 * [FALLBACK_CHAIN], [CHOKEPOINTS], [COLOUR_IMBALANCE] and [TEMPO_MARGIN] were added because the fit's
 * train and holdout losses agreed to five places, which says the model is short of *readings* rather
 * than of capacity or data. They are: what the second best chain is worth, how many cut vertices the
 * region has, the colour imbalance before the parity cap spends it, and whose ground is nearer to its
 * owner. Each is free — the [TempoOwnership] sweep and the [ChamberTree] decomposition were running
 * already, and three of the four are counters in a pop.
 *
 * **Measured, over 965,878 rows off 39,600 logged matches at three board sizes, three fits of each
 * shape on the same corpus and the same seeds: they buy `0.0039 ± 0.0017` of held-out log-loss and
 * 0.26 points of accuracy.** Paired per seed, twenty-five readings against twenty-nine: 0.57819 →
 * 0.57370, 0.57526 → 0.57211, 0.57514 → 0.57102, against 0.69315 for a model that answers even. Per
 * board the four are worth 0.0054 at 8x8, 0.0059 at 12x12 and **0.0024** at 20x20 — least where the
 * leaf was worst.
 *
 * Set that beside the two things already measured on the same scale: **the hidden layer is worth
 * 0.023**, and **refitting the identical twenty-five readings on the board being played is worth
 * 0.048 at 20x20**. So the residual did name four real readings, and the diagnosis it was read as —
 * *"bounded by its features"* — was wrong by an order of magnitude. That equality was measured on a
 * holdout drawn from a **one-board** corpus, where the only thing it can be is a statement about
 * capacity; it says nothing at all about whether the fit transfers. `LearnedWeights` carries both
 * tables.
 *
 * They are kept, at four columns of a 497-weight literal, because the sign is negative on every board
 * at every seed and the cost is work the leaf was already doing. They are **not** the reason the
 * shipped fit moved.
 *
 * ### A fifth was measured and declined: how big the board is
 *
 * Step 0 showed one model over three boards losing 0.0015 / 0.0096 / 0.0092 to three models fitted one
 * per board, which is a **mixture tax** and exactly what a reading a hidden unit could gate on would
 * recover. Tried, as `playable / (playable + 144)`: worth `0.0017` pooled over the same three seeds,
 * 95% CI **[−0.0037, +0.0003]** — it does recover the 12x12 tax outright and it does not clear zero.
 *
 * Declined, for two reasons that outweigh a marginal number. It is the **only** reading here that is
 * not a ratio, a share or a flag, so it retires the property this whole vector is built on and the
 * test that pins it — *"the same opening reads the same on a small board and a large one"*. And a
 * fitted corpus spans `0.31..0.74` of that reading where `MatchSetup.MAX_SIDE` of 256 reaches `0.999`,
 * so any board outside the corpus hands the softsign units an input past everything anybody measured.
 * A reading that is safe only on the boards it was fitted on is the failure this phase was sent to fix.
 *
 * ### How to read it
 *
 * [measure] once per position, then [into] per slot. Both are allocation-free after the constructor,
 * and every reading holds until the next [measure] — the same contract [TempoOwnership] has. A dead
 * snake's row is all zeros; a caller judging a corpse should not be asking.
 */
public class PositionFeatures(private val grid: Grid, private val slotCount: Int) {
    private val space = TempoOwnership(grid, slotCount)

    /** The cap on and the discount off, so the chain is a square count and the contested share is free. */
    private val chambers = ChamberTree(grid, parityWeight = 1.0, frontierPenalty = 0.0, allReadings = true)

    private val alive = BooleanArray(slotCount)
    private val usable = DoubleArray(slotCount)
    private val owned = IntArray(slotCount)
    private val region = IntArray(slotCount)
    private val sealedShare = DoubleArray(slotCount)
    private val contestedShare = DoubleArray(slotCount)
    private val chamberSplit = DoubleArray(slotCount)
    private val liberties = IntArray(slotCount)
    private val length = IntArray(slotCount)
    private val growing = BooleanArray(slotCount)
    private val headRow = IntArray(slotCount)
    private val headCol = IntArray(slotCount)
    private val tailReach = IntArray(slotCount)
    private val fallback = DoubleArray(slotCount)
    private val chokepoints = DoubleArray(slotCount)
    private val imbalance = DoubleArray(slotCount)
    private val reach = DoubleArray(slotCount)

    private var live = 0
    private var totalUsable = 0.0
    private var totalOwned = 0
    private var fill = 0.0
    private var progress = 0.0

    /**
     * Reads [board] once, for every slot at once.
     *
     * The sweep and the decompositions are shared between the slots because they are one walk of the
     * free area between them — the regions are disjoint, which is what keeps this a constant factor
     * over the sweep rather than a factor of the number of snakes.
     */
    public fun measure(board: BoardView) {
        space.measure(board)

        live = 0
        totalUsable = 0.0
        totalOwned = 0

        for (slot in 0 until slotCount) {
            val snake = board.snake(SnakeId(slot))
            alive[slot] = snake.alive
            if (!snake.alive) {
                usable[slot] = 0.0
                owned[slot] = 0
                region[slot] = 0
                sealedShare[slot] = 0.0
                contestedShare[slot] = 0.0
                chamberSplit[slot] = 0.0
                liberties[slot] = 0
                length[slot] = 0
                growing[slot] = false
                fallback[slot] = 0.0
                chokepoints[slot] = 0.0
                imbalance[slot] = 0.0
                reach[slot] = 0.0
                continue
            }
            live++

            val head = snake.head
            chambers.measure(space, slot, head)

            val area = chambers.regionArea
            usable[slot] = chambers.chainWorth
            owned[slot] = space.ownedBy(slot)
            region[slot] = area
            sealedShare[slot] = chambers.sealed
            contestedShare[slot] = if (area == 0) 0.0 else chambers.exposedArea.toDouble() / area
            chamberSplit[slot] = splitOf(chambers.chamberCount)
            fallback[slot] = if (chambers.chainWorth <= 0.0) 0.0 else (chambers.secondWorth / chambers.chainWorth)
            chokepoints[slot] = if (area == 0) 0.0 else chambers.articulations.toDouble() / area
            imbalance[slot] = if (area == 0) 0.0 else chambers.colourImbalance.toDouble() / area
            reach[slot] = if (area == 0) 0.0 else chambers.distanceSum.toDouble() / area
            liberties[slot] = board.legalMoves(SnakeId(slot)).size
            length[slot] = snake.length
            growing[slot] = snake.growsOnNextMove
            headRow[slot] = grid.rowOf(head)
            headCol[slot] = grid.colOf(head)
            tailReach[slot] = abs(grid.rowOf(snake.tail) - headRow[slot]) + abs(grid.colOf(snake.tail) - headCol[slot])

            totalUsable += usable[slot]
            totalOwned += owned[slot]
        }

        fill = 1.0 - space.walkableCount().toDouble() / grid.playableCount
        progress = (board.turnIndex.toDouble() / board.rules.maxTurns).coerceAtMost(1.0)
    }

    /**
     * Writes [slot]'s view of the position measured last into the first [LENGTH] entries of [into].
     *
     * The opponents are summarised rather than enumerated, because the vector's length may not depend
     * on how many snakes are seated: every `RIVAL_` reading is the *strongest challenge* — the most
     * usable space, the fewest liberties, the greatest length. At two snakes, which is what everything
     * here is measured on, that is simply the other one.
     */
    public fun into(slot: Int, into: DoubleArray) {
        require(into.size >= LENGTH) { "a feature row needs $LENGTH entries, was ${into.size}" }

        if (!alive[slot]) {
            into.fill(0.0, 0, LENGTH)
            return
        }

        var rivalUsable = 0.0
        var rivalRegion = 0
        var rivalSealed = 0.0
        var rivalLiberties = Direction.entries.size
        var rivalLength = 0
        var rivalReach = Int.MAX_VALUE
        var rivalTempo = Double.MAX_VALUE

        for (other in 0 until slotCount) {
            if (other == slot || !alive[other]) {
                continue
            }
            if (usable[other] > rivalUsable) rivalUsable = usable[other]
            if (region[other] > rivalRegion) rivalRegion = region[other]
            if (sealedShare[other] > rivalSealed) rivalSealed = sealedShare[other]
            if (liberties[other] < rivalLiberties) rivalLiberties = liberties[other]
            if (length[other] > rivalLength) rivalLength = length[other]

            // The nearest ground is the strongest challenge here, the way the most usable ground and
            // the fewest liberties are above: a rival who has to walk furthest is the least dangerous.
            if (reach[other] < rivalTempo) rivalTempo = reach[other]

            val apart = abs(headRow[other] - headRow[slot]) + abs(headCol[other] - headCol[slot])
            if (apart < rivalReach) rivalReach = apart
        }

        val fair = if (live == 0) 1.0 else 1.0 / live
        val isolated = if (space.isolated(slot)) 1.0 else 0.0
        val diameter = (grid.rows + grid.cols).toDouble()
        val playable = grid.playableCount.toDouble()

        val usableShare = share(usable[slot], totalUsable, fair)
        val usableMargin = margin(usable[slot], rivalUsable)

        into[USABLE_SHARE] = usableShare
        into[USABLE_MARGIN] = usableMargin
        into[OWNED_SHARE] = share(owned[slot].toDouble(), totalOwned.toDouble(), fair)
        into[CHAIN_EFFICIENCY] = if (region[slot] == 0) 0.0 else usable[slot] / region[slot]
        into[REGION_SHARE] = (region[slot] / playable).coerceAtMost(1.0)
        into[RIVAL_REGION_SHARE] = (rivalRegion / playable).coerceAtMost(1.0)
        into[SEALED] = sealedShare[slot]
        into[RIVAL_SEALED] = rivalSealed
        into[CHAMBERS] = chamberSplit[slot]
        into[CONTESTED] = contestedShare[slot]
        into[LIBERTIES] = liberties[slot] / LIBERTIES_MAX
        into[TRAPPED] = if (liberties[slot] == 0) 1.0 else 0.0
        into[RIVAL_LIBERTIES] = rivalLiberties / LIBERTIES_MAX
        into[RIVAL_TRAPPED] = if (live > 1 && rivalLiberties == 0) 1.0 else 0.0
        into[GROWS_NEXT] = if (growing[slot]) 1.0 else 0.0
        into[LENGTH_VS_ROOM] = length[slot] / (length[slot] + 2.0 * region[slot])
        into[LENGTH_MARGIN] = margin(length[slot].toDouble(), rivalLength.toDouble())
        into[ISOLATED] = isolated
        into[HEAD_WALLS] = wallsAt(headRow[slot], headCol[slot])
        into[TAIL_DISTANCE] = tailReach[slot] / diameter
        into[RIVAL_DISTANCE] = if (rivalReach == Int.MAX_VALUE) 1.0 else rivalReach / diameter
        into[BOARD_FILL] = fill
        into[TURN_PROGRESS] = progress
        into[ISOLATED_MARGIN] = isolated * usableMargin
        into[CONTESTED_SHARE] = (1.0 - isolated) * usableShare
        into[FALLBACK_CHAIN] = fallback[slot]
        into[CHOKEPOINTS] = chokepoints[slot].coerceAtMost(1.0)
        into[COLOUR_IMBALANCE] = imbalance[slot].coerceAtMost(1.0)
        into[TEMPO_MARGIN] = if (rivalTempo == Double.MAX_VALUE) 0.0 else margin(rivalTempo, reach[slot])
    }

    override fun toString(): String = "PositionFeatures($LENGTH)"

    // -- internals

    /** A departure from an even split, scaled so that "twice a fair share" saturates at one. */
    private fun share(mine: Double, total: Double, fair: Double): Double =
        if (total <= 0.0) 0.0 else (((mine / total) - fair) * live).coerceIn(-1.0, 1.0)

    private fun margin(mine: Double, rival: Double): Double {
        val pool = mine + rival
        return if (pool <= 0.0) 0.0 else (mine - rival) / pool
    }

    /** Zero for a region the decomposition found no structure in, approaching one as it shatters. */
    private fun splitOf(chamberCount: Int): Double =
        if (chamberCount <= 1) 0.0 else 1.0 - 1.0 / chamberCount

    private fun wallsAt(row: Int, col: Int): Double {
        var edges = 0
        if (row == 0) edges++
        if (row == grid.rows - 1) edges++
        if (col == 0) edges++
        if (col == grid.cols - 1) edges++
        return edges / LIBERTIES_MAX
    }

    public companion object {
        /** A share of the ground the best chain of chambers can spend, against an even split. */
        public const val USABLE_SHARE: Int = 0

        /** The same quantity as a margin over the strongest rival, which is what a race turns on. */
        public const val USABLE_MARGIN: Int = 1

        /** A share of the raw squares the sweep awarded, before any walk is asked to spend them. */
        public const val OWNED_SHARE: Int = 2

        /** How much of its own region the best chain can spend, which is the region's shape. */
        public const val CHAIN_EFFICIENCY: Int = 3

        /** How much of the board this snake's region is, so a share reads against an absolute. */
        public const val REGION_SHARE: Int = 4

        public const val RIVAL_REGION_SHARE: Int = 5

        /** The share of its own region the best chain never reaches — `ChamberEval`'s whole gain. */
        public const val SEALED: Int = 6

        /** And the same of the rival, which no shipped leaf asks at all. */
        public const val RIVAL_SEALED: Int = 7

        /** How far the region came apart, so a shattered region reads differently from a corridor. */
        public const val CHAMBERS: Int = 8

        /** The share of the region on a boundary somebody else reaches first. */
        public const val CONTESTED: Int = 9

        public const val LIBERTIES: Int = 10
        public const val TRAPPED: Int = 11
        public const val RIVAL_LIBERTIES: Int = 12
        public const val RIVAL_TRAPPED: Int = 13

        /** Whether the next move extends the body rather than dragging it — the growth phase. */
        public const val GROWS_NEXT: Int = 14

        /** Length against twice the region, which is whether a walk can loop in its own room. */
        public const val LENGTH_VS_ROOM: Int = 15

        public const val LENGTH_MARGIN: Int = 16

        /** Whether this snake's ground still runs into anybody else's, in this sweep. */
        public const val ISOLATED: Int = 17

        /**
         * Board edges the head sits against, as a share of the four sides a square has.
         *
         * A fraction of four rather than of the two a corner can reach, because a board one square
         * wide puts all four against the wall and a reading that saturated at a corner would leave
         * that position indistinguishable from an ordinary one.
         */
        public const val HEAD_WALLS: Int = 18

        /** How far the head has wandered from the one square certain to come free. */
        public const val TAIL_DISTANCE: Int = 19

        public const val RIVAL_DISTANCE: Int = 20
        public const val BOARD_FILL: Int = 21
        public const val TURN_PROGRESS: Int = 22

        /** [USABLE_MARGIN] where nobody can reach this snake any more, and zero while they can. */
        public const val ISOLATED_MARGIN: Int = 23

        /** [USABLE_SHARE] while the board is still contested, and zero once it is not. */
        public const val CONTESTED_SHARE: Int = 24

        /**
         * What the runner-up chain is worth against the best one — how much is left if the best is wrong.
         *
         * [USABLE_SHARE] is a max over the branches out of the head and a max carries nothing about
         * what is behind it: one good way out and one good way out beside a dead end read identically.
         * Zero where the region is a single chamber, which is the honest answer rather than a missing
         * one — there is no second branch to take.
         */
        public const val FALLBACK_CHAIN: Int = 25

        /**
         * Cut vertices per square of the region — how choked the ground is, rather than how split.
         *
         * [CHAMBERS] counts the pieces; this counts the squares that make them pieces, and the two
         * come apart exactly where it matters: five chambers in a line is four chokepoints and five
         * chambers around one square is one. Free at the leaf — Hopcroft-Tarjan is already running
         * and the count falls out of the pop it is already doing.
         */
        public const val CHOKEPOINTS: Int = 26

        /**
         * The share of the region the chessboard colouring cannot pair off, **before** any cap.
         *
         * [USABLE_SHARE] is measured through `FillableSpace`'s cap, which is this reading already
         * spent. Carried raw for the same reason [CONTESTED] is: a weight already applied is a weight
         * that cannot be learned, and the parity relaxation has now lost twice as a hand-set weight
         * (`HorizonEval` by argument, SPSA by search) without ever being offered as a *reading*.
         */
        public const val COLOUR_IMBALANCE: Int = 27

        /**
         * Whether this snake's ground is nearer to it than the strongest rival's is to that one.
         *
         * Every other reading of space here is an amount; this is the *tempo* of it, off the arrival
         * times `TempoOwnership` already computed and which nothing else in the box reads at all. Two
         * snakes holding the same count of squares are not in the same position when one of them has
         * to walk twice as far to start spending them.
         */
        public const val TEMPO_MARGIN: Int = 28

        /** How many readings a row holds. Adding one changes every baked weight, so it is a decision. */
        public const val LENGTH: Int = 29

        /**
         * What each reading is called, for a trainer that wants to print a weight beside its feature.
         *
         * Diagnostics only — nothing on the hot path reads this, and the indices above are what a
         * forward pass uses.
         */
        public val NAMES: List<String> = listOf(
            "usableShare", "usableMargin", "ownedShare", "chainEfficiency", "regionShare",
            "rivalRegionShare", "sealed", "rivalSealed", "chambers", "contested",
            "liberties", "trapped", "rivalLiberties", "rivalTrapped", "growsNext",
            "lengthVsRoom", "lengthMargin", "isolated", "headWalls", "tailDistance",
            "rivalDistance", "boardFill", "turnProgress", "isolatedMargin", "contestedShare",
            "fallbackChain", "chokepoints", "colourImbalance", "tempoMargin",
        )

        /** Sides a square has, which is both the ways out of it and the walls it can sit against. */
        private const val LIBERTIES_MAX = 4.0
    }
}
