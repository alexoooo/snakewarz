package ao.snakewarz.bots.search.puct

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid

/**
 * [FillableSpace]'s decomposition with the chambers kept rather than summed away.
 *
 * That class splits a region at its articulation points, prices every block by the chessboard parity
 * a walk cannot escape, chains the blocks and returns **one integer**. The decomposition knows more
 * than that integer carries: which chambers the chain leaves behind, how much of a chamber's edge is
 * ground somebody else got to first, and how much of the region the chain never reaches at all. That
 * is the evaluation the 2010 Google AI Challenge was won on, and the whole of what this adds.
 *
 * ### Three questions per chamber, and what each is for
 *
 * A leaf is read as a **comparison**, never as a quantity, and `HorizonEval` is the standing evidence
 * for that: a provably truer count of moves lost to the square count it corrected, because it was
 * generous in exactly the shapes where a walk cannot really loop. So each term here is a *ratio*
 * against the shape being compared rather than an absolute correction, and each is weighted by a knob
 * a sweep can settle — see `PuctBot.PARITY_WEIGHT`, `PuctBot.FRONTIER_PENALTY` and
 * `PuctBot.SEAL_PENALTY`.
 *
 * - **What is its parity worth here?** [FillableSpace] applies the chessboard cap outright, and under
 *   the shipped `RulesConfig.growEveryNthMove` of two that cap binds in a corridor and does not bind
 *   in a room a retracting walk can loop in. [parityWeight] grades between the cap and the raw square
 *   count instead of picking one. At `1.0` this is [FillableSpace] exactly, which is where
 *   [SurvivalEval] was measured — and where a sweep of all three weights **left it**, the relaxation
 *   turning out to be worth nothing once the other two were doing their work. `PuctBot.PARITY_WEIGHT`
 *   has the table.
 * - **Is it contested?** A square with a walkable neighbour somebody else reaches first is ground the
 *   sweep awarded on half a step of tempo, and the next few moves can take it back. Territory and
 *   fillable space both count such a square exactly as they count one in the back of a sealed pocket.
 *   [frontierPenalty] discounts a chamber by the *share* of it that sits on that boundary, which is
 *   naturally near zero in an open midgame — the boundary is a line and a chamber is an area — and
 *   large in the knife-fight endgame, where it is the reading that decides the game.
 * - **Does taking it seal me in?** The chain is a max over children, so a region that shatters into
 *   four pockets is worth its best pocket and the other three are gone. [chainWorth] alone cannot say
 *   that: twenty spendable squares out of twenty-two and twenty out of forty read identically. The
 *   pair [chainArea] and [regionArea] is what says it, and [sealed] is the fraction it comes to.
 *
 * What this deliberately does **not** ask is *who arrives first*. [TempoOwnership] already answers
 * that per square and at half-step resolution, which is finer than any chamber can put it; the region
 * this walks is what that answer handed over. Asking it again per chamber would be a coarser copy of
 * a reading already in hand.
 *
 * ### How often there is any structure to read, which is the ceiling on all of it
 *
 * Over four `chase` games on a 12x12, taking the region of whoever is to move at every position:
 * **311 of 597 positions came apart into more than one chamber**, and **81 of 597** held ground the
 * best chain could not reach. So the decomposition finds something about half the time and the seal
 * term fires on one position in seven. Where a region is a single chamber the chain *is* the region,
 * [sealed] is zero and the reading falls back on [SurvivalEval]'s with a frontier discount on it —
 * which is the honest bound on what any of this can be worth. `ChamberTreeTest` re-runs the count.
 *
 * ### Priced as it pops, because there is no second pass to spend
 *
 * Hopcroft–Tarjan closes a block in post-order exactly when its entry vertex is found to be an
 * articulation point, so every child's answer is already parked at the cut vertex it hangs from. Both
 * numbers a chain carries — what it is worth and how many squares it covers — are settled in the same
 * pop, and no chamber record outlives it. A block-cut tree built as objects would be the readable
 * version and would allocate per evaluation, which a leaf a search calls a thousand times a turn
 * cannot afford.
 *
 * Among chains worth the same, the one covering more squares wins, so a chamber the frontier discount
 * happens to zero out still reports its area. Without that a heavily contested region would read as
 * one the walk cannot enter, which is a different claim entirely.
 *
 * One instance per bot per match. The visited set is a generation stamp, so a region costs the squares
 * it holds rather than the size of the board, and the stacks are sized once from [Grid.cellCount]
 * because no region can hold more squares than the board has.
 */
internal class ChamberTree(
    private val grid: Grid,
    /** How much of the chessboard cap applies, against the raw square count. `1.0` is [FillableSpace]. */
    private val parityWeight: Double,
    /** How much of a chamber's worth the share of it on a contested boundary takes off. */
    private val frontierPenalty: Double,
    /**
     * Whether to keep the two readings that cost work **per square** — [secondWorth] and [distanceSum].
     *
     * Off by default, and that is not a micro-optimisation: [ChamberEval] reads neither, and turning
     * them on for it would put a load and a store on the per-square path of a leaf whose cost is
     * published in `EvaluationCost` and whose allowance is set from it. [articulations] and
     * [colourImbalance] need no flag — both are counters in the block pop, of which there are far
     * fewer than there are squares. With this off both gated readings report **zero** rather than
     * whatever the last caller left behind, so a misread is a reading of no information rather than
     * a reading of another position.
     */
    private val allReadings: Boolean = false,
) {
    /** The chessboard colouring, built once — [FillableSpace]'s, off the padded index for its reason. */
    private val colour = ByteArray(grid.cellCount) { (((it / grid.stride) + (it % grid.stride)) and 1).toByte() }

    private val mark = IntArray(grid.cellCount)
    private val discovered = IntArray(grid.cellCount)
    private val low = IntArray(grid.cellCount)

    /** Stamped on a square with a walkable neighbour somebody else reaches first. */
    private val contested = IntArray(grid.cellCount)

    /** Per cut vertex, the best chain hanging below it: what it is worth, and the squares it covers. */
    private val best = DoubleArray(grid.cellCount)
    private val bestArea = IntArray(grid.cellCount)

    /** The runner-up at each cut vertex — see [secondWorth], which is the only place it is read. */
    private val second = DoubleArray(grid.cellCount)

    /** Stamped on a cut vertex the first time a block closes under it, so each is counted once. */
    private val cutMark = IntArray(grid.cellCount)

    private val stackCell = IntArray(grid.cellCount)
    private val stackParent = IntArray(grid.cellCount)
    private val stackEdge = IntArray(grid.cellCount)
    private val blockStack = IntArray(grid.cellCount)

    private val directions = Direction.entries
    private var generation = 0

    /** What the best chain of chambers out of the head is worth, in effective squares. */
    var chainWorth: Double = 0.0
        private set

    /** Squares that same chain covers — the raw count, which is what [sealed] is a fraction of. */
    var chainArea: Int = 0
        private set

    /** Every square the sweep gave this slot, the head it is standing on excluded. */
    var regionArea: Int = 0
        private set

    /** How many chambers the region came apart into. One means the decomposition found no structure. */
    var chamberCount: Int = 0
        private set

    /**
     * Squares of the region sitting on a boundary somebody else reaches first, over every chamber.
     *
     * [frontierPenalty] folds this into [chainWorth] as a discount per chamber, which is the right
     * shape for a leaf reading a chain and the wrong one for a model being fitted: a weight already
     * applied is a weight that cannot be learned. Counted over the whole region rather than the
     * chain, because being cut off from contested ground and being cut off from safe ground are
     * different positions.
     */
    var exposedArea: Int = 0
        private set

    /**
     * What the *second* best chain out of the head is worth, in the same effective squares.
     *
     * [chainWorth] is a max over the branches hanging off the head, and a max says nothing about what
     * is behind it: a snake with two equally good ways out and one with a single way out and a dead
     * end read identically. This is the runner-up, so the pair is an **optionality** reading — how
     * much is left if the best branch turns out to be the wrong one. Zero when the region is one
     * chamber, which is the honest answer: there is no second branch to take.
     *
     * Read as a share of [chainWorth] rather than on its own, because only the ratio is board-free.
     */
    var secondWorth: Double = 0.0
        private set

    /**
     * Cut vertices in the region — the squares whose loss breaks it into pieces.
     *
     * [chamberCount] counts the *blocks*, which cannot tell a corridor of five chambers in a line
     * (four chokepoints) from five chambers around one square (one chokepoint), and those are
     * different positions: the first is a walk that keeps its options, the second a walk that spends
     * them all at once. Hopcroft-Tarjan settles this in the pop it is already doing, so the count is
     * free — the finding that *"a true articulation test is unaffordable"* was about `MovePrior`,
     * which would have to run three sweeps per iteration to get it.
     *
     * The head counts by the standard rule — a root is a cut vertex when it has two subtrees below
     * it — and that is the right reading here rather than an artefact of it: a head standing on a cut
     * vertex is one about to commit its region to whichever side it steps.
     */
    var articulations: Int = 0
        private set

    /**
     * Squares of the region the chessboard colouring cannot pair off, summed over every chamber.
     *
     * [FillableSpace]'s cap is `2 * min(a, b) + spare`, which folds this into an answer. Here it is
     * carried raw, for [ChamberTree]'s own reason for handing [exposedArea] over separately: a weight
     * already applied is a weight that cannot be learned. Against [regionArea] it is the share of a
     * region a retracting walk can never spend, which is the parity argument as a reading rather than
     * as a correction — and the parity relaxation has now lost twice as a correction.
     */
    var colourImbalance: Int = 0
        private set

    /**
     * Every square of the region weighted by how many moves the sweep says it takes to get there.
     *
     * Read against [regionArea] as a mean, and against a rival's as a margin: two snakes holding the
     * same number of squares are not in the same position if one of them has to walk twice as far to
     * spend them. Nothing else in the box reads the sweep's *arrival times* at all — every consumer
     * reads only who owns what — and this costs one array lookup per square already being walked.
     */
    var distanceSum: Long = 0
        private set

    /** The share of its own region the best chain never reaches, in `0.0..1.0`. */
    val sealed: Double
        get() = if (regionArea == 0) 0.0 else (regionArea - chainArea).toDouble() / regionArea

    /**
     * Takes the ground [space] gave [slot] apart, entered at [root].
     *
     * [root] is the snake's head: in the region, and not counted, because it is standing there. Every
     * reading above is refilled by this call and holds until the next one. A snake with nothing next
     * to it leaves all four at zero, which is the honest reading of a snake with nowhere to go.
     */
    fun measure(space: TempoOwnership, slot: Int, root: Cell) {
        nextGeneration()

        val start = root.index
        var timer = 1

        mark[start] = generation
        discovered[start] = timer
        low[start] = timer
        best[start] = 0.0
        bestArea[start] = 0
        second[start] = 0.0

        var sp = 0
        stackCell[0] = start
        stackParent[0] = NO_CELL
        stackEdge[0] = 0

        var blockTop = 0
        var squares = 0
        var chambers = 0
        var cuts = 0
        var rootBlocks = 0
        var distance = 0L
        exposedArea = 0
        colourImbalance = 0

        while (sp >= 0) {
            val here = stackCell[sp]

            if (stackEdge[sp] < directions.size) {
                val next = grid.step(Cell(here), directions[stackEdge[sp]++]).index

                // The one edge back to the parent is the tree edge just taken, not a cycle. A grid is
                // simple, so skipping it once is exactly right.
                if (next == stackParent[sp]) {
                    continue
                }
                // The root is in the region by fiat: it is where the walk starts, and a step back to
                // it is a genuine cycle rather than a way of counting the head twice.
                if (next != start && space.ownerOf(Cell(next)) != slot) {
                    // Ground the sweep could have walked on and gave to somebody else is a boundary;
                    // a wall is a back. Both read NOBODY from the owner alone, so the walkability
                    // test is what separates a chamber under pressure from one that is merely small.
                    if (space.walkable(Cell(next))) {
                        contested[here] = generation
                    }
                    continue
                }

                if (mark[next] == generation) {
                    if (discovered[next] < low[here]) {
                        low[here] = discovered[next]
                    }
                    continue
                }

                timer++
                squares++
                mark[next] = generation
                discovered[next] = timer
                low[next] = timer
                best[next] = 0.0
                bestArea[next] = 0
                if (allReadings) {
                    distance += space.distanceTo(Cell(next))
                    second[next] = 0.0
                }
                blockStack[blockTop++] = next

                sp++
                stackCell[sp] = next
                stackParent[sp] = here
                stackEdge[sp] = 0
                continue
            }

            sp--
            if (sp < 0) {
                break
            }

            val parent = stackCell[sp]
            if (low[here] < low[parent]) {
                low[parent] = low[here]
            }
            if (low[here] >= discovered[parent]) {
                // Nothing under `here` reaches past `parent`, so `parent` is a cut vertex — or the
                // root, for which this holds trivially and gives the same answer.
                blockTop = closeBlock(parent, here, blockTop)
                chambers++

                // The root's own test is different — it is a cut vertex only with two subtrees under
                // it — so it is counted after the walk rather than here. Every other parent that
                // closes a block is one, and is counted the first time it does.
                if (parent == start) {
                    rootBlocks++
                } else if (cutMark[parent] != generation) {
                    cutMark[parent] = generation
                    cuts++
                }
            }
        }

        chainWorth = best[start]
        chainArea = bestArea[start]
        secondWorth = if (allReadings) second[start] else 0.0
        regionArea = squares
        chamberCount = chambers
        articulations = cuts + if (rootBlocks > 1) 1 else 0
        distanceSum = distance
    }

    /**
     * Settles one chamber: everything above [last] on the stack, entered at [entry].
     *
     * Reads the child answers already parked in [best] as it pops, so the chain costs one pass over
     * squares that were going to be popped anyway. The entry vertex belongs to the chamber above and
     * is counted there, which is what keeps every square on a chain counted exactly once.
     */
    private fun closeBlock(entry: Int, last: Int, top: Int): Int {
        var blockTop = top
        val entryColour = colour[entry].toInt()

        var sameColour = 0
        var otherColour = 0
        var exposed = 0
        var branchWorth = 0.0
        var branchArea = 0
        var cell: Int

        do {
            cell = blockStack[--blockTop]
            if (colour[cell].toInt() == entryColour) {
                sameColour++
            } else {
                otherColour++
            }
            if (contested[cell] == generation) {
                exposed++
            }
            if (best[cell] > branchWorth) {
                branchWorth = best[cell]
                branchArea = bestArea[cell]
            }
        } while (cell != last)

        val area = sameColour + otherColour
        exposedArea += exposed
        colourImbalance += if (sameColour > otherColour) sameColour - otherColour else otherColour - sameColour

        // A walk leaving `entry` steps to the other colour first and alternates, so it can pair off
        // min(a, b) of each and take one more of the opposite colour if there is one spare.
        val paired = if (sameColour < otherColour) sameColour else otherColour
        val cap = 2 * paired + if (otherColour > sameColour) 1 else 0

        val capped = parityWeight * cap + (1.0 - parityWeight) * area
        val worth = capped * (1.0 - frontierPenalty * exposed.toDouble() / area)

        val fill = worth + branchWorth
        val covered = area + branchArea
        if (fill > best[entry] || (fill == best[entry] && covered > bestArea[entry])) {
            if (allReadings) {
                second[entry] = best[entry]
            }
            best[entry] = fill
            bestArea[entry] = covered
        } else if (allReadings && fill > second[entry]) {
            second[entry] = fill
        }
        return blockTop
    }

    private fun nextGeneration() {
        if (generation == Int.MAX_VALUE) {
            mark.fill(0)
            contested.fill(0)
            cutMark.fill(0)
            generation = 0
        }
        generation++
    }

    private companion object {
        const val NO_CELL = -1
    }
}
