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
 * ### How to read it
 *
 * [measure] once per position, then [into] per slot. Both are allocation-free after the constructor,
 * and every reading holds until the next [measure] — the same contract [TempoOwnership] has. A dead
 * snake's row is all zeros; a caller judging a corpse should not be asking.
 */
public class PositionFeatures(private val grid: Grid, private val slotCount: Int) {
    private val space = TempoOwnership(grid, slotCount)

    /** The cap on and the discount off, so the chain is a square count and the contested share is free. */
    private val chambers = ChamberTree(grid, parityWeight = 1.0, frontierPenalty = 0.0)

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

        for (other in 0 until slotCount) {
            if (other == slot || !alive[other]) {
                continue
            }
            if (usable[other] > rivalUsable) rivalUsable = usable[other]
            if (region[other] > rivalRegion) rivalRegion = region[other]
            if (sealedShare[other] > rivalSealed) rivalSealed = sealedShare[other]
            if (liberties[other] < rivalLiberties) rivalLiberties = liberties[other]
            if (length[other] > rivalLength) rivalLength = length[other]

            val reach = abs(headRow[other] - headRow[slot]) + abs(headCol[other] - headCol[slot])
            if (reach < rivalReach) rivalReach = reach
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

        /** How many readings a row holds. Adding one changes every baked weight, so it is a decision. */
        public const val LENGTH: Int = 25

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
        )

        /** Sides a square has, which is both the ways out of it and the walls it can sit against. */
        private const val LIBERTIES_MAX = 4.0
    }
}
