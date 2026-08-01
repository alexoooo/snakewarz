package ao.snakewarz.match.human

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import kotlin.math.abs
import kotlin.math.sign

/**
 * The route a player steers by. **A press [route]s, a drag [trace]s**, and the two draw differently
 * on purpose: a press names a destination and gets the way there, a drag names the way and gets no
 * more than it drew.
 *
 * **A plan, never a promise.** Opponents move, so a route that was clear when it was drawn can still
 * kill you by the time it is walked — which is the game. [InputBuffer.take] is the other half of that
 * bargain: a queued direction that has become illegal is discarded rather than played.
 *
 * ### What "blocked" means
 *
 * A square is passable at plan index `i` — the anchor under the head being `0` — if it is free now,
 * **or** its owner is alive and will have retracted past it within `i - 1` of that snake's own moves.
 * [Clearance] holds the arithmetic. Two unrelated reasons produce the same `i - 1`, and collapsing
 * them into one loses whichever is fixed:
 *
 * - **Your own body — an ordering rule.** `Board.apply` reads `isFree(target)` *before* the tail
 *   retracts, so a snake may not enter the square its own tail is about to leave. Your tail therefore
 *   has clearance 1: the route may not enter it at step 1 and may at step 2, reproduced here rather
 *   than special-cased.
 * - **Everyone else — a move count.** An opponent's retraction happens inside its own move and is
 *   already visible once that move is done. `Board.advanceToAct` cycles from the current position
 *   skipping the dead, so between two of your moves every living snake moves exactly once; an
 *   opponent has made `i - 1` or `i` moves depending where it sits in the cyclic to-act order **from
 *   the current `board.toAct`** — which is *not* its slot index, because a route can be begun
 *   mid-round. Assuming `i - 1` for everyone assumes fewer retractions, so it believes more squares
 *   occupied. Conservative, always.
 *
 * Conservative is the only safe direction, and the reason is sharper than a lost move: when
 * [InputBuffer.take] discards an illegal direction it returns *the next legal one from the same
 * route*, so an over-optimistic plan makes the snake skip to a later leg rather than stop.
 *
 * ### Three optimisms it keeps
 *
 * 1. An opponent's future head is never predicted — a square free now is assumed free forever.
 * 2. A snake that dies stops retracting, and its body freezes where it fell.
 * 3. Under `growEveryNthMove == 1`, classic Tron, nothing ever clears and only free squares are
 *    passable.
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
    private val depth = IntArray(grid.cellCount)
    private val frontier = IntArray(grid.cellCount)
    private val clearance = Clearance(grid)
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
     * next [route] or [trace] rewrites it, and nobody else may.
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
     * Replaces everything after the anchor with a shortest route to [target], choosing the route that
     * stays closest to the straight grid line between them.
     *
     * **Breadth-first rather than "append the square if it is adjacent."** A press names where to go
     * rather than how, and the answer has to go *round* a body and round a wall — a shape the straight
     * line between two squares cannot be assumed to have. Depth here is the arrival index, so the
     * clearance test is exactly `enterableAt(cell, depth)`; occupancy only ever decreases for bodies
     * already on the board, so that test is monotone in depth and plain breadth-first search stays
     * optimal with each square dequeued once. A square refused at one depth is deliberately left
     * unstamped, because a deeper frontier square may reach it after its owner has moved on.
     *
     * [target] being the anchor is a **zero-length route, and it exists.** That is what lets a press
     * on your own head take hold and a freehand drawing start from nothing.
     *
     * `false` leaves the path exactly as it was and is an ordinary answer rather than a fault: off the
     * board, on a wall, in a pocket, or further than the queue can hold all read the same way.
     *
     * Two honesty notes:
     *
     * - The route is reconstructed backwards from [target], preferring at each depth the predecessor
     *   nearest the ideal line. On an open board that produces an interleaved staircase; around
     *   obstacles it remains a heuristic among equally short routes, with the search depth still the
     *   authority over length.
     * - Because a snake **cannot wait in place**, this cannot express "loop around and come back once
     *   the tail clears" — a square whose neighbours are all dequeued before it opens is never
     *   reached. Soundness is unaffected, since a route that *is* found is walkable, and the failure
     *   mode is an ordinary "no route", which a press already treats as "do nothing".
     */
    public fun route(board: BoardView, target: Cell): Boolean {
        check(cellCount > 0) { "a path is anchored by begin() before it is routed" }
        if (!grid.isPlayable(target)) {
            return false
        }

        val anchor = path[0]
        if (target.index == anchor) {
            cellCount = 1
            return true
        }

        clearance.refresh(board)
        nextGeneration()
        stamp[anchor] = generation
        depth[anchor] = 0

        frontier[0] = anchor
        var head = 0
        var tail = 1

        while (head < tail) {
            val cell = frontier[head++]
            val arrival = depth[cell] + 1
            if (arrival > maxCells - 1) {
                // Further than the queue can hold, and a breadth-first frontier only gets deeper.
                break
            }

            for (i in DIRECTIONS.indices) {
                val next = grid.step(Cell(cell), DIRECTIONS[i])
                if (stamp[next.index] == generation || !clearance.enterableAt(board, next, arrival)) {
                    // A refused square is left unstamped on purpose: a body still holding it at this
                    // arrival may have retracted past it by the time a deeper square reaches it.
                    continue
                }

                stamp[next.index] = generation
                depth[next.index] = arrival
                if (next.index == target.index) {
                    reconstruct(next.index, arrival)
                    return true
                }
                frontier[tail++] = next.index
            }
        }

        return false
    }

    /**
     * Draws the line from the path's end towards [target], cut where it is blocked, and reports how
     * many moves that appended.
     *
     * A 4-connected staircase: step whichever axis has further to go, integer-only, re-derived from
     * the current square each step so it self-corrects and survives being cut. `(0,0)` to `(3,5)`
     * gives `E E S E S E S E`. The asymmetry with [route] is the whole point — an L would turn a
     * diagonal drag into a right angle, which is the opposite of following the pointer precisely.
     * There is no search here, so a drag can neither detour nor jump; once cut it simply stops growing
     * while the pointer stays past the obstruction, and resumes when the pointer comes back into line.
     *
     * `0` is an ordinary answer rather than a fault.
     *
     * **Dragging back along the route shortens it.** A [target] already on the path truncates to it,
     * which is what a player expects and what an unconditional self-block would refuse.
     *
     * Past that one case the drawn path blocks itself unconditionally, and the omission is
     * **deliberate**. The time-aware version is derivable — over `V = body ++ plan`, the squares this
     * snake holds at time `t` are exactly `V[r(t) … L0 - 1 + t]`, with `r(t)` the retractions made by
     * then — but it buys nothing a player can do today, so it is not paid for.
     */
    public fun trace(board: BoardView, target: Cell): Int {
        check(cellCount > 0) { "a path is anchored by begin() before it is traced" }
        if (!grid.isPlayable(target)) {
            return 0
        }

        clearance.refresh(board)
        markPath()
        if (stamp[target.index] == generation) {
            cellCount = depth[target.index] + 1
            return 0
        }

        var row = grid.rowOf(Cell(path[cellCount - 1]))
        var col = grid.colOf(Cell(path[cellCount - 1]))
        val targetRow = grid.rowOf(target)
        val targetCol = grid.colOf(target)
        var appended = 0

        while (row != targetRow || col != targetCol) {
            // Step whichever axis has further to go: the staircase that hugs the segment.
            if (abs(targetRow - row) >= abs(targetCol - col)) {
                row += (targetRow - row).sign
            } else {
                col += (targetCol - col).sign
            }

            val next = grid.cellAt(row, col)
            if (stamp[next.index] == generation || !clearance.enterableAt(board, next, cellCount)) {
                break
            }
            if (cellCount == maxCells) {
                break
            }

            moves[cellCount - 1] = directionOrdinal(path[cellCount - 1], next.index)
            path[cellCount] = next.index
            stamp[next.index] = generation
            depth[next.index] = cellCount
            cellCount++
            appended++
        }

        return appended
    }

    /**
     * Drops everything past the first square that will still be held when the snake could reach it,
     * reporting whether anything was dropped.
     *
     * `O(cellCount)` and run once per step, which is what keeps a held route honest as opponents move
     * across it. Truncating to a bare anchor is not a state of its own: [InteractiveBot] answers
     * `Pending`, the clock above it waits, and dragging refills the route.
     */
    public fun revalidate(board: BoardView): Boolean {
        clearance.refresh(board)
        for (i in 1 until cellCount) {
            if (!clearance.enterableAt(board, Cell(path[i]), i)) {
                cellCount = i
                return true
            }
        }
        return false
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

    // -- internals

    /** Stamps the drawn path with its own indices, so a square on it knows where on it it sits. */
    private fun markPath() {
        nextGeneration()
        for (i in 0 until cellCount) {
            stamp[path[i]] = generation
            depth[path[i]] = i
        }
    }

    /**
     * Writes the [steps] moves ending at [target] on to the path, cells and directions together,
     * walking backwards over the depths the search left behind.
     */
    private fun reconstruct(target: Int, steps: Int) {
        val anchor = path[0]
        val anchorRow = grid.rowOf(Cell(anchor))
        val anchorCol = grid.colOf(Cell(anchor))
        val targetRow = grid.rowOf(Cell(target))
        val targetCol = grid.colOf(Cell(target))
        path[steps] = target
        var walk = target

        for (index in steps downTo 1) {
            var chosen = -1
            var bestError = Long.MAX_VALUE
            for (i in DIRECTIONS.indices) {
                val predecessor = walk - grid.offsetOf(DIRECTIONS[i])
                if (precedes(predecessor, index - 1)) {
                    val error = lineError(
                        predecessor,
                        anchorRow,
                        anchorCol,
                        targetRow,
                        targetCol,
                        steps,
                        index - 1,
                    )
                    if (error < bestError) {
                        chosen = i
                        bestError = error
                    }
                }
            }
            if (chosen < 0) {
                error("no square at depth ${index - 1} neighbours square $walk of $grid")
            }

            moves[index - 1] = DIRECTIONS[chosen].ordinal
            walk -= grid.offsetOf(DIRECTIONS[chosen])
            path[index - 1] = walk
        }

        cellCount = steps + 1
    }

    /** Squared distance from [cell] to the ideal straight-line position at [atDepth], without floats. */
    private fun lineError(
        cell: Int,
        anchorRow: Int,
        anchorCol: Int,
        targetRow: Int,
        targetCol: Int,
        steps: Int,
        atDepth: Int,
    ): Long {
        val rowError =
            (grid.rowOf(Cell(cell)) - anchorRow).toLong() * steps - (targetRow - anchorRow).toLong() * atDepth
        val colError =
            (grid.colOf(Cell(cell)) - anchorCol).toLong() * steps - (targetCol - anchorCol).toLong() * atDepth
        return rowError * rowError + colError * colError
    }

    private fun precedes(cell: Int, atDepth: Int): Boolean =
        stamp[cell] == generation && depth[cell] == atDepth

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
