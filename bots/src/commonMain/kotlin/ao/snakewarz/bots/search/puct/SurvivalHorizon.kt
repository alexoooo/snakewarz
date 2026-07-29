package ao.snakewarz.bots.search.puct

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.RulesConfig

/**
 * How many moves a region leaves the snake standing in it — which is not how many of its squares that
 * snake can take.
 *
 * [FillableSpace] answers the second question, and answers it for a walk that never revisits a
 * square. That walk is the one classic Tron plays. At the shipped [RulesConfig.growEveryNthMove] of
 * two the tail retracts on alternating turns, so a square comes back into play about `length` moves
 * after the head leaves it, and the two readings come apart by a factor that is **not** uniform: an
 * open room of `n` squares is worth about `2n` moves rather than `n`, while a room behind a neck is
 * worth nothing at all to a self-avoiding walk and worth two crossings to a retracting one. A leaf
 * built on the square count therefore declines necks it could cross both ways, and declines them
 * hardest in the middle game, where whether it can is exactly the question territory turns on.
 *
 * ### The same decomposition, a different price per block
 *
 * Everything structural is [FillableSpace]'s: one iterative Hopcroft-Tarjan pass, blocks popped in
 * post-order at their articulation points, the answer parked at the cut vertex each block hangs from,
 * generation-stamped buffers sized once from [Grid.cellCount]. What changes is what a block is worth
 * and how a chain of them combines, and both change because a cut vertex is no longer a door that
 * shuts behind you.
 *
 * - **The block the head is standing in is worth `2 * area` moves.** Its own tail is dragging through
 *   that block rather than sealing it off, so the walk can loop and every square is worth a second
 *   visit. That is [SurvivalEval]'s own "a snake in a closed room of `n` squares survives about `2n`
 *   moves", applied where it is true instead of being waved at and cancelled.
 * - **A bridge — one square, no cycle — is worth one move, or two if the walk comes back out of what
 *   is behind it.** A dead end is entered and never left: reversing means stepping into the square
 *   the body is standing on, and legality is tested before any tail retracts.
 * - **Every other block is worth `2 * (area - arrival + 1)`, and the parity cap when that is less.**
 *   Crossing into a block puts the whole body across the square behind, so the walk is self-avoiding
 *   in there until its own tail catches up — `arrival` squares' worth of waiting. What survives the
 *   wait loops and is worth two moves apiece; what does not is [FillableSpace]'s chessboard count,
 *   unchanged, because a walk that never gets a square back really is the walk that class measures.
 *   Graded rather than a step, for the reason [TerritoryEval] records about its own separated branch.
 * - **A block is charged both ways when it outlasts the seal and once when it does not.** A far side
 *   the walk dies in is a place it can go only once, so at most one of them counts — [FillableSpace]'s
 *   `max` over children, kept for exactly the case that argument still holds. A far side it can leave
 *   again is a detour, and detours add up.
 *
 * ### The comparison is against a running length, because filling is what makes a snake long
 *
 * The seal a walk has to outlast is `2 * length - 1` moves, and the length that matters is the one it
 * **arrives** with rather than the one it has now: it is at least [TempoOwnership.distanceTo] moves
 * from its head before it can stand at a cut vertex at all, and every two of those moves added a
 * square. So the far end of a corridor is measured against a longer snake than the near end, which is
 * the shape of the answer on a comb — the first tooth is a round trip and the fourth is a dead end.
 * Reading it off the distance rather than off the order blocks happened to close in is what makes the
 * answer independent of which way the sweep walked.
 *
 * ### The ceiling, which is exact
 *
 * A retracting move takes a square with the head and gives one back with the tail; a growing move
 * takes one and gives none. So the free count falls by exactly one every second move, and a walk
 * needs a free square in front of it to make each move at all — `f` free squares are `2 * f` moves,
 * one fewer when the next move is the growing one. That bound is tight and it is the last thing
 * applied, so no combination of the rules above can read a region as worth more than it holds.
 *
 * ### It is an upper bound, deliberately, and `SurvivalHorizonTest` is where that is checked
 *
 * Moves-until-trapped is exactly computable on a region small enough to search, so the claim is an
 * oracle test rather than an argument: over four hundred generated regions and every shape drawn by
 * hand there, the estimate is never under the truth, and it is within two moves of it on an open
 * room. Three things are deliberately not modelled and all three read a region as too *open*: that
 * the walk must reach the neck it leaves by, that a block satisfying the parity count need not admit
 * a Hamiltonian path through it, and that the growth phase on arrival is unknowable from a post-order
 * pass — so the seal is taken at the earliest move it can possibly break. Erring the other way would
 * be worse than erring at all: an evaluation is read as a comparison, and a bound that is sometimes
 * under the truth cannot be checked against anything.
 *
 * **What the sweep hands over is free squares, so a snake's own body is not the region.** The squares
 * under it come back as the tail passes and can open ground no reading taken now can see; on such a
 * position the board really does outlast this estimate. That is a limit of measuring a region rather
 * than searching it, [FillableSpace] is under the truth there by at least as much, and the test names
 * the case rather than leaving it to be rediscovered.
 *
 * One instance per bot per match, and the same eight buffers as [FillableSpace] plus one more, so a
 * region costs the squares it holds rather than the size of the board.
 */
internal class SurvivalHorizon(private val grid: Grid) {
    /** The chessboard colouring, built once — [FillableSpace]'s, off the padded index for its reason. */
    private val colour = ByteArray(grid.cellCount) { (((it / grid.stride) + (it % grid.stride)) and 1).toByte() }

    private val mark = IntArray(grid.cellCount)
    private val discovered = IntArray(grid.cellCount)
    private val low = IntArray(grid.cellCount)

    /** Per cut vertex, the moves of everything below it the walk can enter *and leave again*. */
    private val returned = IntArray(grid.cellCount)

    /** Per cut vertex, the best of what is below it that the walk can only enter. It ends there. */
    private val stranded = IntArray(grid.cellCount)

    private val stackCell = IntArray(grid.cellCount)
    private val stackParent = IntArray(grid.cellCount)
    private val stackEdge = IntArray(grid.cellCount)
    private val blockStack = IntArray(grid.cellCount)

    private val directions = Direction.entries
    private var generation = 0

    /**
     * The most moves a snake of [length] standing on [head] can take out of the ground [space] gave
     * [slot], with [growsNext] saying whether its next move is the one that does not free a square.
     *
     * [head] is in the region and is charged only for the moves that land back on it, because a walk
     * that leaves and returns has spent it again. Returns zero for a snake with nothing next to it.
     */
    fun measure(space: TempoOwnership, slot: Int, head: Cell, length: Int, growsNext: Boolean): Int {
        nextGeneration()

        val start = head.index
        var timer = 1

        mark[start] = generation
        discovered[start] = timer
        low[start] = timer
        returned[start] = 0
        stranded[start] = 0

        var sp = 0
        stackCell[0] = start
        stackParent[0] = NO_CELL
        stackEdge[0] = 0

        var blockTop = 0
        var free = 0

        while (sp >= 0) {
            val here = stackCell[sp]

            if (stackEdge[sp] < directions.size) {
                val next = grid.step(Cell(here), directions[stackEdge[sp]++]).index

                // The one edge back to the parent is the tree edge just taken, not a cycle. A grid is
                // simple, so skipping it once is exactly right.
                if (next == stackParent[sp]) {
                    continue
                }
                // The head is in the region by fiat: it is where the walk starts, and a step back to
                // it is a genuine cycle rather than a way of counting the head twice.
                if (next != start && space.ownerOf(Cell(next)) != slot) {
                    continue
                }

                if (mark[next] == generation) {
                    if (discovered[next] < low[here]) {
                        low[here] = discovered[next]
                    }
                    continue
                }

                timer++
                free++
                mark[next] = generation
                discovered[next] = timer
                low[next] = timer
                returned[next] = 0
                stranded[next] = 0
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
                // head, for which this holds trivially and gives the same answer.
                val seal = sealClears(space, length, parent)
                blockTop = closeBlock(parent, here, blockTop, parent == start, seal)
            }
        }

        val ceiling = if (growsNext) 2 * free - 1 else 2 * free
        val moves = returned[start] + stranded[start]
        return if (ceiling <= 0) {
            0
        } else if (moves < ceiling) {
            moves
        } else {
            ceiling
        }
    }

    /**
     * Settles one block: everything above [last] on the stack, entered at [entry].
     *
     * Reads the answers already parked at the cut vertices it pops, so the whole DP costs one pass
     * over squares that were going to be popped anyway.
     */
    private fun closeBlock(entry: Int, last: Int, top: Int, isHead: Boolean, seal: Int): Int {
        var blockTop = top
        val entryColour = colour[entry].toInt()

        var sameColour = 0
        var otherColour = 0
        var detours = 0
        var terminal = 0
        var cell: Int

        do {
            cell = blockStack[--blockTop]
            if (colour[cell].toInt() == entryColour) {
                sameColour++
            } else {
                otherColour++
            }

            // Whatever hangs below this square was already split at its own cut vertex into what the
            // walk comes back from and what it does not, so the block's share of each is a sum and a
            // max: it can take every detour, and it can end in exactly one place.
            detours += returned[cell]
            if (stranded[cell] > terminal) {
                terminal = stranded[cell]
            }
        } while (cell != last)

        val area = sameColour + otherColour
        val paired = if (sameColour < otherColour) sameColour else otherColour

        // A walk leaving `entry` steps to the other colour first and alternates, so it can pair off
        // min(a, b) of each and take one more of the opposite colour if there is one spare.
        val cap = 2 * paired + if (otherColour > sameColour) 1 else 0

        val within = when {
            // One square and no cycle. The only way to stand on it twice is to come back out of what
            // is behind it: reversing off a dead end means stepping into the body, and legality is
            // tested before the tail retracts.
            area == 1 -> if (detours > 0) 2 else 1

            // The walk is already standing in this block and its own tail is dragging through it
            // rather than sealing it off, so every square here is worth a second visit.
            isHead -> 2 * area

            // Everywhere else the body arrives with the head and seals the way in behind it, so the
            // walk is self-avoiding until its own tail catches up. `area - arrival` squares survive
            // that wait and are worth two moves each; the parity cap is what is left when none do.
            // Graded rather than a step for TerritoryEval's reason — a cliff here would leave the
            // search nothing to prefer between two moves either side of it.
            else -> {
                val looped = 2 * (area - (seal + 1) / 2 + 1)
                if (looped > cap) looped else cap
            }
        }

        // Two readings of the same block, and it is the square above that picks: the walk either
        // turns round in here and carries on somewhere else, or it stops in here for good.
        val roundTrip = within + detours
        if (roundTrip >= seal) {
            // Coming back lands on the entry square. For every cut vertex but the head that square
            // belongs to the block above and is already paid for there; the head's belongs to nobody,
            // so a round trip out of it is charged here or nowhere.
            returned[entry] += roundTrip + if (isHead) 1 else 0
            if (terminal > stranded[entry]) {
                stranded[entry] = terminal
            }
        } else if (roundTrip + terminal > stranded[entry]) {
            stranded[entry] = roundTrip + terminal
        }
        return blockTop
    }

    /**
     * Moves a walk has to last inside what it enters at [entry] before [entry] itself clears again.
     *
     * Crossing in puts the whole body across the square behind, and that square is free only once the
     * tail has been dragged past it — [length] retractions, one every second move, the earliest of
     * which lands on the first move. So the seal is `2 * length - 1`, and [length] is the length **on
     * arrival**, which is not the length now: the walk is at least [TempoOwnership.distanceTo] moves
     * from its head before it can stand here at all, and every two of those moves added a square.
     * The caller adds what has already been spent on other detours from the same square for the same
     * reason, which is what makes a comb read as a first tooth it can leave and a fourth it cannot.
     *
     * Taken at the earliest the seal can break rather than the likeliest — the growth phase on
     * arrival is not knowable from a post-order pass, and reading a region as too open is the
     * direction that keeps this an upper bound.
     */
    private fun sealClears(space: TempoOwnership, length: Int, entry: Int): Int =
        2 * length + space.distanceTo(Cell(entry)) - 1

    private fun nextGeneration() {
        if (generation == Int.MAX_VALUE) {
            mark.fill(0)
            generation = 0
        }
        generation++
    }

    private companion object {
        const val NO_CELL = -1
    }
}
