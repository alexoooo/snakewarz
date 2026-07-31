package ao.snakewarz.match.human

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId

/**
 * How long each held square stays held: the time-aware counterpart of the board's occupancy.
 *
 * The arithmetic comes straight off `Board`. `growsOnNextMove` is
 * `(movesMade + 1) % growEveryNthMove == 0` and `apply` pops the tail only when that is false, so
 * walking a snake's next moves `m = 1, 2, 3…`, move `m` retracts exactly when
 * `(movesMade + m) % g != 0`, and the `j`-th square from the tail is vacated on the `(j+1)`-th such
 * move. [refresh] walks each living snake tail-to-head accumulating those retractions.
 *
 * **Walls and corpses need no scan at all.** Neither is free and neither is stamped, and an
 * unstamped square already reads "never" — which is the right answer for both.
 *
 * Cost is `O(sum of body lengths)`: `moves` only ever increases across a body, so the inner loop is
 * amortised at about `2 × length` iterations under the default `growEveryNthMove` of 2. Every caller
 * refreshes before it reads, because a `Board` is a live arena whose turn index is not an identity —
 * `undo` walks it backwards and `reset` returns it to zero.
 *
 * SW-03: two [IntArray]s sized off the grid in the constructor, and a generation stamp rather than
 * an array cleared per call.
 */
internal class Clearance(grid: Grid) {
    private val stamp = IntArray(grid.cellCount)
    private val clearsAt = IntArray(grid.cellCount)
    private var generation = 0

    /** Restamps every living snake's body against [board]'s present position. */
    fun refresh(board: BoardView) {
        nextGeneration()

        val growth = board.rules.growEveryNthMove
        if (growth == 1) {
            // Classic Tron: a trail that never retracts gives nothing back, and the loop below would
            // never terminate looking for a retraction that cannot happen.
            return
        }

        for (slot in 0 until board.snakeCount) {
            val snake = board.snake(SnakeId(slot))
            if (!snake.alive) {
                continue
            }

            var moves = 0
            var retracted = 0
            for (j in 0 until snake.length) {
                while (retracted <= j) {
                    moves++
                    if ((snake.movesMade + moves) % growth != 0) {
                        retracted++
                    }
                }

                val index = snake.cellAt(j).index
                stamp[index] = generation
                clearsAt[index] = moves
            }
        }
    }

    /**
     * Whether a snake could stand on [cell] having made [arrival] moves to get there.
     *
     * The `arrival - 1` is one move short of what the mover has made, for two unrelated reasons that
     * happen to agree — [PathPlanner] keeps them apart, and neither may be collapsed into the other.
     */
    fun enterableAt(board: BoardView, cell: Cell, arrival: Int): Boolean =
        board.isFree(cell) || (stamp[cell.index] == generation && clearsAt[cell.index] <= arrival - 1)

    override fun toString(): String = "Clearance(generation=$generation)"

    /**
     * Bumps the stamp, wrapping by clearing rather than overflowing into a generation the array still
     * holds. Reached after two billion refreshes, which is to say never — but a marker scheme that is
     * only *almost* always right is not worth the doubt.
     */
    private fun nextGeneration() {
        if (generation == Int.MAX_VALUE) {
            stamp.fill(0)
            generation = 0
        }
        generation++
    }
}
