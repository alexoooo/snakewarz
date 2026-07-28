package ao.snakewarz.bots.search.puct

import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid

/**
 * How much of a region its owner can actually *use* — which is not how big it is.
 *
 * A snake is a walk that never revisits a square, so a room reached through a one-square neck is a
 * room you enter and never leave. Count the squares and a dumbbell reads as two bells; count what a
 * walk can take and it reads as one bell plus the neck, which is what the snake will actually get.
 * The same gap opens at every scale: a comb of side pockets is mostly unreachable, a corridor with
 * rooms hanging off it is worth the corridor plus the best room, and a search told otherwise will
 * happily seal itself into the small half of its own territory believing it kept everything.
 *
 * This is the one question [SpaceOwnership] and [FloodFill] cannot answer between them, and it is
 * the whole difference between [SurvivalEval] and [TerritoryEval].
 *
 * ### Blocks, and why the answer falls out of the pop
 *
 * Split the region into biconnected components — **blocks** — at its articulation points. Inside a
 * block every square is on a cycle, so a walk may wander freely; between blocks it must pass through
 * a cut vertex, and it can do that once. So the best a walk can do, entering block `B` at vertex `v`:
 *
 * ```
 * fill(B, v) = cap(B, v) + max over cut vertices c in B, c != v, of fill(child block at c, c)
 * ```
 *
 * Hopcroft-Tarjan already hands over exactly this. Its component stack pops a block precisely when
 * the parent `u` is found to be an articulation point, in post-order, so every child block below has
 * already been closed and its answer parked in [best] at the cut vertex it hangs from. The DP is
 * therefore not a second pass over a block-cut tree that never gets built: it is four lines inside
 * [closeBlock], and the region's answer is [best] at the root when the walk unwinds.
 *
 * The entry vertex is charged to the *parent* block and skipped in the child, so every square is
 * counted once along the chosen chain. The root is nobody's child, which is why a snake's own head —
 * the square it is standing on and the one square of its region it can never spend — is naturally
 * excluded rather than subtracted.
 *
 * ### Parity, and why counting squares is still too generous
 *
 * A grid is bipartite: colour it like a chessboard and every move flips colour. So a walk entering a
 * block on a black square goes white, black, white — and if the block holds six black squares and
 * one white, the walk gets three of the seven, not seven. The cap is
 * `2 * min(a, b) + (1 if a > b)`, where `a` counts the squares of the *opposite* colour to the entry
 * and `b` the same. Without it a comb reads as though every tooth were fillable, and the endgame this
 * evaluation exists for is nothing but combs.
 *
 * ### It is an upper bound, deliberately
 *
 * Two things are not modelled: that the walk must *reach* the cut vertex it leaves by, and that a
 * block satisfying the parity count need not admit a Hamiltonian path through it — the centre of a
 * 3x3 is the small counterexample. Both would over-count in the same direction for everybody, and an
 * evaluation is read as a comparison. Tightening either means searching the region rather than
 * measuring it, which is the tree's job and not a leaf's.
 *
 * One instance per bot per match. The visited set is a generation stamp, so a region costs the
 * squares it holds rather than the size of the board, and the four stacks are sized once from
 * [Grid.cellCount] because no region can hold more squares than the board has.
 */
internal class FillableSpace(private val grid: Grid) {
    /**
     * The chessboard colouring, built once.
     *
     * Off the padded index rather than [Grid.rowOf] and [Grid.colOf], which is the same colouring
     * shifted by a constant — and only differences matter here.
     */
    private val colour = ByteArray(grid.cellCount) { (((it / grid.stride) + (it % grid.stride)) and 1).toByte() }

    private val mark = IntArray(grid.cellCount)
    private val discovered = IntArray(grid.cellCount)
    private val low = IntArray(grid.cellCount)

    /** Per cut vertex, the best any block hanging below it can offer. The DP's whole state. */
    private val best = IntArray(grid.cellCount)

    private val stackCell = IntArray(grid.cellCount)
    private val stackParent = IntArray(grid.cellCount)
    private val stackEdge = IntArray(grid.cellCount)
    private val blockStack = IntArray(grid.cellCount)

    private val directions = Direction.entries
    private var generation = 0

    /**
     * The most squares a walk out of [root] can take from the ground [space] gave [slot].
     *
     * [root] is the snake's head: in the region, and not counted, because it is standing there.
     * Returns zero for a snake with nothing next to it, which is the honest reading of a snake with
     * nowhere to go.
     */
    fun measure(space: TempoOwnership, slot: Int, root: Cell): Int {
        nextGeneration()

        val start = root.index
        var timer = 1

        mark[start] = generation
        discovered[start] = timer
        low[start] = timer
        best[start] = 0

        var sp = 0
        stackCell[0] = start
        stackParent[0] = NO_CELL
        stackEdge[0] = 0

        var blockTop = 0

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
                    continue
                }

                if (mark[next] == generation) {
                    if (discovered[next] < low[here]) {
                        low[here] = discovered[next]
                    }
                    continue
                }

                timer++
                mark[next] = generation
                discovered[next] = timer
                low[next] = timer
                best[next] = 0
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
            }
        }

        return best[start]
    }

    /**
     * Settles one block: everything above [last] on the stack, entered at [entry].
     *
     * Reads the child answers already parked in [best] as it pops, so the DP costs one pass over
     * squares that were going to be popped anyway.
     */
    private fun closeBlock(entry: Int, last: Int, top: Int): Int {
        var blockTop = top
        val entryColour = colour[entry].toInt()

        var sameColour = 0
        var otherColour = 0
        var branch = 0
        var cell: Int

        do {
            cell = blockStack[--blockTop]
            if (colour[cell].toInt() == entryColour) {
                sameColour++
            } else {
                otherColour++
            }
            if (best[cell] > branch) {
                branch = best[cell]
            }
        } while (cell != last)

        // A walk leaving `entry` steps to the other colour first and alternates, so it can pair off
        // min(a, b) of each and take one more of the opposite colour if there is one spare.
        val paired = if (sameColour < otherColour) sameColour else otherColour
        val cap = 2 * paired + if (otherColour > sameColour) 1 else 0

        val fill = cap + branch
        if (fill > best[entry]) {
            best[entry] = fill
        }
        return blockTop
    }

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
