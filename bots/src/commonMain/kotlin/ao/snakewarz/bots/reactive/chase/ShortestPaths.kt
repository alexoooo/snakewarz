package ao.snakewarz.bots.reactive.chase

import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView

/**
 * How far away everything is, and which way to start walking — one breadth-first sweep per turn.
 *
 * This is the semantic port of legacy `AStar`/`Path`, and it is breadth-first on purpose. The legacy
 * class was **not** A\*: `Path.compareTo` ordered by cost-so-far and used the Manhattan heuristic
 * only to break ties, so the frontier came off the queue in `g` order and the heuristic pruned
 * nothing. On a four-neighbour grid where every step costs one, that is Dijkstra, and Dijkstra with
 * unit costs is breadth-first search. The distances are identical.
 *
 * What A\* would buy is fewer expansions toward **one** goal. Every caller here wants distances to
 * *several* goals in the same turn — one per opponent head — so a single full sweep beats a
 * goal-directed search per opponent outright. It also deletes the priority queue, and with it the
 * question of how heap ties order, which is a determinism surface nobody should have to think about.
 *
 * [firstStepTo] needs no path reconstruction and allocates nothing: each square carries the
 * direction the walk left the origin by, propagated as the search spreads. Legacy rebuilt a
 * `LinkedList<BoardLocation>` per query, and a `List<Cell>` here would box every element.
 *
 * One instance per bot per match. As in [FloodFill], the visited set is a generation stamp, so a
 * sweep costs the squares it reaches rather than the size of the board.
 */
internal class ShortestPaths(private val grid: Grid) {
    private val stamp = IntArray(grid.cellCount)
    private val steps = IntArray(grid.cellCount)

    /** [Direction.ordinal] + 1 of the move that left the origin, or 0 at the origin itself. */
    private val opening = ByteArray(grid.cellCount)

    private val frontier = IntArray(grid.cellCount)
    private val directions = Direction.entries
    private var generation = 0

    /**
     * Sweeps outward from [from] over the free squares, which every later query then reads.
     *
     * [alsoFree] is treated as empty however it is occupied — the caller's own retracting tail, for
     * the same half-speed growth reason as [FloodFill.reachable].
     */
    fun scanFrom(board: BoardView, from: Cell, alsoFree: Cell = Cell.NONE) {
        require(grid.isPlayable(from)) { "a sweep must start on a playable square, was $from" }

        nextGeneration()
        stamp[from.index] = generation
        steps[from.index] = 0
        opening[from.index] = 0
        frontier[0] = from.index

        var head = 0
        var tail = 1

        while (head < tail) {
            val cell = Cell(frontier[head++])
            val distance = steps[cell.index] + 1
            val cameBy = opening[cell.index]

            for (i in directions.indices) {
                val next = grid.step(cell, directions[i])
                if (stamp[next.index] == generation) {
                    continue
                }
                if (!board.isFree(next) && next != alsoFree) {
                    continue
                }

                stamp[next.index] = generation
                steps[next.index] = distance
                // Squares one step out name themselves; everything further inherits.
                opening[next.index] = if (cameBy.toInt() == 0) {
                    (directions[i].ordinal + 1).toByte()
                } else {
                    cameBy
                }
                frontier[tail++] = next.index
            }
        }
    }

    /**
     * Steps from the origin to [cell], or [UNREACHABLE].
     *
     * No production caller: a bot wants [distanceBeside], because a snake cannot stand *on* its
     * target. This is how `ShortestPathsTest` asserts the scan itself — every square's distance, the
     * sealed-off region, the stale-generation guard — which needs the plain answer rather than the
     * one shifted by a step. [distanceBeside] repeats the array read instead of calling this, so
     * that [requireScanned] stays out of a four-way loop.
     */
    fun distanceTo(cell: Cell): Int {
        requireScanned()
        return if (stamp[cell.index] == generation) steps[cell.index] else UNREACHABLE
    }

    /** The first move of a shortest walk to [cell], or `null` at the origin or when unreachable. */
    fun firstStepTo(cell: Cell): Direction? {
        requireScanned()
        if (stamp[cell.index] != generation) {
            return null
        }
        val code = opening[cell.index].toInt()
        return if (code == 0) null else directions[code - 1]
    }

    /**
     * Steps to the nearest square *adjoining* [cell], or [UNREACHABLE].
     *
     * The goal a bot chases is an opponent's head, which is occupied and therefore never walked on.
     * Legacy expressed this as a loop condition — stop once the frontier is within one of the target
     * — which meant one search per target. Asked as a question instead, a single sweep answers it
     * for every opponent on the board.
     */
    fun distanceBeside(cell: Cell): Int {
        requireScanned()

        var best = UNREACHABLE
        for (i in directions.indices) {
            val beside = grid.step(cell, directions[i])
            val distance = if (stamp[beside.index] == generation) steps[beside.index] else UNREACHABLE
            if (distance < best) {
                best = distance
            }
        }
        return if (best == UNREACHABLE) UNREACHABLE else best + 1
    }

    /**
     * The first move toward the nearest square adjoining [cell].
     *
     * `null` when nothing beside [cell] can be reached, and also when the origin is *already*
     * beside it — there is no step to name, and a caller that close should be doing something other
     * than walking closer.
     */
    fun firstStepBeside(cell: Cell): Direction? {
        requireScanned()

        var best = UNREACHABLE
        var chosen = Cell.NONE
        for (i in directions.indices) {
            val beside = grid.step(cell, directions[i])
            val distance = if (stamp[beside.index] == generation) steps[beside.index] else UNREACHABLE
            if (distance < best) {
                best = distance
                chosen = beside
            }
        }
        return if (chosen.isNone) null else firstStepTo(chosen)
    }

    private fun requireScanned() {
        check(generation > 0) { "scanFrom must run before anything is asked of the sweep" }
    }

    private fun nextGeneration() {
        if (generation == Int.MAX_VALUE) {
            stamp.fill(0)
            generation = 0
        }
        generation++
    }

    companion object {
        /** Farther than any real distance, so it loses every `<` comparison rather than winning it. */
        const val UNREACHABLE: Int = Int.MAX_VALUE
    }
}
