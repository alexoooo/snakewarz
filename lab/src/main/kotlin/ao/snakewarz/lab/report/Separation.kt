package ao.snakewarz.lab.report

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId

/**
 * Whether the snakes can still reach each other, asked two ways — and how much room each has.
 *
 * A match is two games in sequence. While the snakes share ground, a move is about who gets where
 * first; once they cannot reach each other at all, the game is a solo space-filling race and the
 * winner is whoever was left the most room. `PhaseCommand` splits a log along that line, which is
 * the only place the split can come from: nothing in a logged match records it.
 *
 * ### The two predicates, and why the difference is the point
 *
 * - [naive] floods the **free** squares. This is what every evaluation in `:bots` means by separated:
 *   a body is a wall, so the regions do not connect.
 * - [permanent] floods the free squares **plus every square a living snake's body sits on**, keeping
 *   only dead bodies as wall. A living snake's tail retracts one square every second move, so a
 *   barrier made of it erodes and a naive separation can come apart again. If the regions do not
 *   connect even with the living bodies removed, nothing that happens later can join them.
 *
 * The conservative one is a strictly weaker claim, so `permanent` implies `naive`.
 *
 * **And at two snakes on a rectangle it can never be true**, which is worth knowing before anybody
 * builds a bot that dispatches on it. Its passable set is every playable square minus the *dead*
 * bodies; a rectangle minus its wall ring is connected; and a two-snake match ends at the first
 * death, so there are never any dead bodies while it is running. `PhasesCommand` measures it anyway
 * and reports the count, because a structural argument that agrees with a measurement is worth more
 * than either alone — and it becomes a real question the moment a third snake is seated.
 *
 * What that leaves is a *graded* reading rather than a predicate, and [PhasesCommand] takes it from
 * the match instead of from the position: a separation that held to the end of the game was
 * permanent, and one the next few moves undid was not.
 *
 * Buffers are fields and the visited marks are generation-stamped, so a walk of fifty thousand
 * replays costs one allocation per board geometry.
 */
internal class Separation(private val grid: Grid) {
    private val seen = IntArray(grid.cellCount)
    private val queue = IntArray(grid.cellCount)
    private val label = IntArray(grid.cellCount)
    private val step = IntArray(Direction.entries.size) { grid.offsetOf(Direction.entries[it]) }
    private var generation = 0

    /** Whether no two living snakes' regions connect over the free squares alone. */
    fun naive(board: BoardView): Boolean = separated(board, erodable = false)

    /** Whether they still fail to connect once every living body is treated as ground. */
    fun permanent(board: BoardView): Boolean = separated(board, erodable = true)

    /**
     * Free squares [slot] can reach, which is what a separated race is decided by.
     *
     * Counted over free squares only and from the head's own neighbours outward, so a snake with
     * nothing legal has none — the head square itself is occupied and is never room to spend.
     */
    fun roomOf(board: BoardView, slot: Int): Int {
        val snake = board.snake(SnakeId(slot))
        if (!snake.alive) {
            return 0
        }

        generation++
        var head = 0
        var tail = 0
        for (offset in step) {
            val next = snake.head.index + offset
            if (board.isFree(Cell(next)) && seen[next] != generation) {
                seen[next] = generation
                queue[tail++] = next
            }
        }

        var count = 0
        while (head < tail) {
            val at = queue[head++]
            count++
            for (offset in step) {
                val next = at + offset
                if (board.isFree(Cell(next)) && seen[next] != generation) {
                    seen[next] = generation
                    queue[tail++] = next
                }
            }
        }
        return count
    }

    // -- internals

    private fun separated(board: BoardView, erodable: Boolean): Boolean {
        generation++
        var component = 0

        for (slot in 0 until board.snakeCount) {
            val snake = board.snake(SnakeId(slot))
            if (!snake.alive) {
                continue
            }

            val at = snake.head.index
            if (seen[at] == generation) {
                // An earlier flood crossed this snake's own square, which only a passable body
                // allows — so the two are standing in the same region.
                return false
            }
            component++
            if (!fill(board, at, component, erodable)) {
                return false
            }
        }
        return true
    }

    /**
     * Floods [component] out from [from], answering whether it stayed clear of the others.
     *
     * The collision has to be tested on the *ground* rather than on the heads. A head is occupied,
     * so under the free-squares reading no flood ever enters one — two snakes facing each other
     * across a shared corridor would each label the corridor and neither would reach the other's
     * square. Meeting somebody else's label is the test; the head check above is the second case,
     * which only the erodable reading can produce.
     */
    private fun fill(board: BoardView, from: Int, component: Int, erodable: Boolean): Boolean {
        var head = 0
        var tail = 0
        seen[from] = generation
        label[from] = component
        queue[tail++] = from

        while (head < tail) {
            val at = queue[head++]
            for (offset in step) {
                val next = at + offset
                if (seen[next] == generation) {
                    if (label[next] != component) {
                        return false
                    }
                    continue
                }
                if (!passable(board, next, erodable)) {
                    continue
                }
                seen[next] = generation
                label[next] = component
                queue[tail++] = next
            }
        }
        return true
    }

    /**
     * Whether a walk may cross [cell] — free always, and a living snake's body when [erodable].
     *
     * A dead snake's body stays wall in both readings: nothing retracts it, so a region closed by a
     * corpse is closed for the rest of the match. The wall ring answers `SnakeId.NONE` to
     * [BoardView.ownerOf] and is not free, so it stops both floods without a bounds test.
     */
    private fun passable(board: BoardView, cell: Int, erodable: Boolean): Boolean {
        val at = Cell(cell)
        if (board.isFree(at)) {
            return true
        }
        if (!erodable) {
            return false
        }

        val owner = board.ownerOf(at)
        return !owner.isNone && board.snake(owner).alive
    }
}
