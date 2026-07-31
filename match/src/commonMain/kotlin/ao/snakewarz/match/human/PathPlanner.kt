package ao.snakewarz.match.human

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView

/**
 * The route a player draws: breadth-first from where the path currently ends to the square they are
 * pointing at, over squares that are free *now* and are not already on the path.
 *
 * **A plan, never a promise.** Tails retract and opponents move, so a route that was clear when it
 * was drawn can kill you by the time it is walked — which is the game, and is why this does not try
 * to predict the board it will actually meet. [InputBuffer.take] is the other half of that bargain:
 * a queued direction that has become illegal is discarded rather than played.
 *
 * **Breadth-first rather than "append the square if it is adjacent."** A finger jumps several squares
 * between pointer events and a mouse dragged quickly does the same, so requiring adjacency would make
 * the path stutter and would make touch nearly unusable. Routing means the player sketches and the
 * planner draws — around a body, and around a wall, which is a shape the straight line between two
 * squares cannot be assumed to have.
 *
 * The path is kept in both of the representations it is read in — padded [Cell] indices for painting
 * it, [Direction] ordinals for [InputBuffer.replace] — so no caller ever holds two arrays it has to
 * reconcile about one route.
 *
 * SW-03 applies although this runs a handful of times a second rather than millions a turn: every
 * buffer is allocated in the constructor off [Grid.cellCount] and a call touches no heap, the visited
 * set being a generation stamp rather than an array cleared per call. One planner belongs to one
 * board, since the board's geometry is what sizes it.
 */
public class PathPlanner(private val grid: Grid) {
    /**
     * Squares a path may hold.
     *
     * Bounded by what the queue can take, since a route longer than that is one [InputBuffer.replace]
     * would refuse, and by the board, since a path that never revisits a square cannot outrun it.
     */
    private val maxCells = minOf(grid.cellCount, InputBuffer.PATH_CAPACITY + 1)

    private val path = IntArray(maxCells)
    private val moves = IntArray(maxCells - 1)
    private val stamp = IntArray(grid.cellCount)
    private val cameFrom = IntArray(grid.cellCount)
    private val frontier = IntArray(grid.cellCount)
    private var generation = 0

    /** Squares on the path, the anchor included — so a path with nothing left to play still counts one. */
    public var cellCount: Int = 0
        private set

    /** Moves the path spells out: one fewer than [cellCount], and none at all without an anchor. */
    public val moveCount: Int get() = if (cellCount == 0) 0 else cellCount - 1

    /** Whether there is nothing left to walk. An anchor on its own is empty in the only sense that matters. */
    public val isEmpty: Boolean get() = moveCount == 0

    /**
     * The queued directions as [Direction] ordinals, valid to [moveCount] — the array
     * [InputBuffer.replace] takes.
     *
     * A live buffer rather than a copy, so feeding the queue and painting the route cost nothing. The
     * next [extend] rewrites it, and nobody else may.
     */
    public val directions: IntArray get() = moves

    /** The [i]-th square of the path, `cellAt(0)` being the anchor the snake is standing on. */
    public fun cellAt(i: Int): Cell {
        require(i in 0 until cellCount) { "a path of $cellCount squares has no square $i" }
        return Cell(path[i])
    }

    /** Anchors a fresh path on [head], which is the square the snake being steered stands on. */
    public fun begin(head: Cell) {
        require(grid.isPlayable(head)) { "a path is anchored on a playable square, was ${head.index}" }
        path[0] = head.index
        cellCount = 1
    }

    /**
     * Routes the path on to [target], reporting whether a route was found.
     *
     * `false` leaves the path exactly as it was, and is an ordinary answer rather than a fault. A
     * pointer dragged past the edge of the board, on to a wall or a body, into a pocket the path has
     * sealed off behind itself, or further than the queue can hold all read the same way: the player
     * keeps dragging and the plan keeps the last route that worked.
     */
    public fun extend(board: BoardView, target: Cell): Boolean {
        check(cellCount > 0) { "a path is anchored by begin() before it is extended" }

        val end = path[cellCount - 1]
        if (target.index == end) {
            // The pointer has not left the square the route already ends on, which is most of what a
            // drag reports. Answering yes without searching keeps a held-still finger free.
            return true
        }
        if (!grid.isPlayable(target) || !board.isFree(target)) {
            return false
        }

        val steps = search(board, end, target.index)
        if (steps == 0 || cellCount + steps > maxCells) {
            return false
        }

        append(end, target.index, steps)
        return true
    }

    /**
     * Drops the square the snake has just left, keeping the anchor under its head.
     *
     * A plan is anchored where the snake is standing, so something has to consume it as the snake
     * walks it; without this the painted route trails one square further behind on every turn. A step
     * the plan did not spell out leaves the anchor behind the snake, and an anchor that is not the
     * head is not a plan, so that discards it.
     */
    public fun advance() {
        if (moveCount == 0) {
            clear()
            return
        }

        path.copyInto(path, 0, 1, cellCount)
        moves.copyInto(moves, 0, 1, cellCount - 1)
        cellCount--
    }

    /** Forgets the route. What letting go of a drag does, and so what makes release mean stop. */
    public fun clear() {
        cellCount = 0
    }

    override fun toString(): String = "PathPlanner($moveCount moves)"

    /**
     * The number of moves from [from] to [to] over free squares the path does not already hold, or
     * `0` when there is no way through, leaving the route itself in [cameFrom].
     */
    private fun search(board: BoardView, from: Int, to: Int): Int {
        nextGeneration()
        // The path blocks itself, so a route can neither cross what is already drawn nor turn back
        // along it -- a snake walking its own route would be walking into its own body.
        for (i in 0 until cellCount) {
            stamp[path[i]] = generation
        }

        frontier[0] = from
        var head = 0
        var tail = 1

        while (head < tail) {
            val cell = Cell(frontier[head++])

            for (i in DIRECTIONS.indices) {
                val next = grid.step(cell, DIRECTIONS[i])
                if (stamp[next.index] == generation || !board.isFree(next)) {
                    continue
                }

                stamp[next.index] = generation
                cameFrom[next.index] = cell.index
                if (next.index == to) {
                    return stepsBack(to, from)
                }
                frontier[tail++] = next.index
            }
        }

        return 0
    }

    private fun stepsBack(to: Int, from: Int): Int {
        var steps = 0
        var walk = to
        while (walk != from) {
            walk = cameFrom[walk]
            steps++
        }
        return steps
    }

    /** Writes the [steps] squares between [from] and [to] on to the end of the path, cells and moves together. */
    private fun append(from: Int, to: Int, steps: Int) {
        var index = cellCount + steps - 1
        var walk = to
        while (walk != from) {
            path[index--] = walk
            walk = cameFrom[walk]
        }

        for (i in cellCount - 1 until cellCount + steps - 1) {
            moves[i] = directionOrdinal(path[i], path[i + 1])
        }
        cellCount += steps
    }

    private fun directionOrdinal(from: Int, to: Int): Int {
        val delta = to - from
        for (i in DIRECTIONS.indices) {
            if (grid.offsetOf(DIRECTIONS[i]) == delta) {
                return DIRECTIONS[i].ordinal
            }
        }
        error("$from and $to are not neighbouring squares of $grid")
    }

    /**
     * Bumps the stamp, wrapping by clearing rather than overflowing into a generation the array still
     * holds. Reached after two billion routes, which is to say never — but a marker scheme that is
     * only *almost* always right is not worth the doubt.
     */
    private fun nextGeneration() {
        if (generation == Int.MAX_VALUE) {
            stamp.fill(0)
            generation = 0
        }
        generation++
    }

    private companion object {
        val DIRECTIONS = Direction.entries
    }
}
