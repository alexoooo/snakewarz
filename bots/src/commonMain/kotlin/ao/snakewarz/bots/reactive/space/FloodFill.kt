package ao.snakewarz.bots.reactive.space

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView

/**
 * How much room is left, measured by breadth-first search over the free squares.
 *
 * This is the single heuristic that turns a snake from a random walk into something that survives: a
 * move that fits into a pocket of nine squares is losing however open the rest of the board looks.
 * Legacy called it `AiUtil.availableArea` and it is the core of `ForkAi` and `ForkPathAi`.
 *
 * One instance is allocated per bot per match and reused for every fill of every turn. Two things
 * make that possible:
 *
 * - **The visited set is a generation stamp, not a bitset that gets cleared.** `generation++` is the
 *   whole reset, so a fill costs the squares it actually visits rather than the size of the board.
 *   Legacy allocated a fresh `BitSetMatrix` per fill — per direction, per turn, inside every rollout.
 * - **The frontier is an `IntArray` ring**, not a `HashSet` of location objects. It cannot overflow:
 *   every square is enqueued at most once and the array holds the whole padded grid.
 *
 * Stepping needs no bounds check. The padded border ring is permanently wall, so walking off the
 * board and walking into a body are the same array read — which is exactly what `:core`'s grid
 * layout exists to buy.
 */
internal class FloodFill(private val grid: Grid) {
    private val stamp = IntArray(grid.cellCount)
    private val frontier = IntArray(grid.cellCount)
    private val directions = Direction.entries
    private var generation = 0

    /**
     * The number of squares reachable from [from], counting [from] itself, and never more than
     * [limit].
     *
     * [from] is counted whether or not it is free — callers ask "what would I have if I moved
     * *there*", and there is where the head would be. Everything beyond it must be free.
     *
     * [alsoFree] is treated as empty however it is occupied, and exists for one specific reason:
     * **snakes here grow at half speed**, so the tail only retracts on alternating turns. A bot
     * measuring its room on the un-advanced board is right about its own head, which becomes the
     * neck and stays put, and wrong by exactly one square at the tail. Passing `me.tail` when
     * `!me.growsOnNextMove` makes the count exact for one comparison per square.
     *
     * [limit] is checked per square rather than between search layers. Legacy checked it between
     * layers, so its cap overshot by up to a whole frontier and `ForkAi(6)` never meant six squares.
     */
    fun reachable(
        board: BoardView,
        from: Cell,
        alsoFree: Cell = Cell.NONE,
        limit: Int = Int.MAX_VALUE,
    ): Int {
        require(grid.isPlayable(from)) { "a fill must start on a playable square, was $from" }
        require(limit >= 0) { "limit must not be negative, was $limit" }

        if (limit == 0) {
            return 0
        }

        nextGeneration()
        stamp[from.index] = generation
        frontier[0] = from.index

        var head = 0
        var tail = 1
        var count = 1

        while (head < tail && count < limit) {
            val cell = Cell(frontier[head++])

            for (i in directions.indices) {
                val next = grid.step(cell, directions[i])
                if (stamp[next.index] == generation) {
                    continue
                }
                if (!board.isFree(next) && next != alsoFree) {
                    continue
                }

                stamp[next.index] = generation
                frontier[tail++] = next.index
                count++

                if (count == limit) {
                    return count
                }
            }
        }

        return count
    }

    /**
     * Bumps the stamp, wrapping by clearing rather than overflowing into a generation the array
     * still holds. Reached after two billion fills, which is to say never — but a marker scheme that
     * is only *almost* always right is not worth the doubt.
     */
    private fun nextGeneration() {
        if (generation == Int.MAX_VALUE) {
            stamp.fill(0)
            generation = 0
        }
        generation++
    }
}
