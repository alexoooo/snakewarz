package ao.snakewarz.bots.reactive.space

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId
import kotlin.math.sqrt

/**
 * Keeps its room first and crowds the opponent second. A semantic port of legacy `ForkPathAi`.
 *
 * The ranking is lexicographic and the order is the whole idea: among the moves that leave the most
 * room — and only among those — take the one that ends up nearest an opponent. So it never trades
 * its own survival for aggression, and it spends the freedom it has left leaning on somebody. On an
 * open board that is worth surprisingly little; in the endgame, when both snakes are threading a
 * corridor, it is worth the match.
 *
 * The odd-looking clamp is legacy's and it is doing real work: a proximity below
 * [ADJACENCY_FLOOR] of the board's diagonal is scored as [ADJACENCY_PENALTY] instead, which is worse
 * than genuinely being that close and better than being far. Without it, "get nearer" means "trade
 * heads", and two of these would kill each other on contact every time.
 *
 * Costs no budget. Four legacy defects are not reproduced:
 *
 * - The appraisals were the keys of a `TreeMap`, so two equally-rated directions **collapsed into
 *   one entry** and one of them silently stopped being a candidate.
 * - `Math.random() < 0.5` is not a uniform tie-break — with three tied moves it gives the last one
 *   half the probability — and it drew from a global generator, which no bot here may do.
 * - With no opponents left, the mean distance was `0 / 0`, and `NaN` poisons every comparison it
 *   touches. A solo board is the first thing the contract suite tries.
 * - Both sides of every space comparison were divided by `rows * cols`. The counts compare the same
 *   way as integers, so the division — and a floating-point comparison — simply goes away.
 */
public class PressureBot(setup: BotSetup) : Bot {
    private val rng = setup.rng
    private val fill = FloodFill(setup.grid)

    /** Proximity is measured as a fraction of the board's diagonal, so the clamp scales with it. */
    private val diagonal = sqrt(
        (setup.grid.rows.toDouble() * setup.grid.rows + setup.grid.cols.toDouble() * setup.grid.cols),
    )

    private val adjacencyFloor = ADJACENCY_FLOOR.read(setup.params)
    private val adjacencyPenalty = ADJACENCY_PENALTY.read(setup.params)

    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        if (legal.isEmpty) {
            return Decision.Move(Direction.NORTH)
        }

        val board = turn.board
        val me = turn.me
        val head = me.head
        val vacating = if (me.growsOnNextMove) Cell.NONE else me.tail

        var chosen = legal.nth(0)
        var mostRoom = -1
        var leastDistance = Double.MAX_VALUE
        var tied = 0

        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val destination = board.grid.step(head, direction)
            val room = fill.reachable(board, destination, vacating)
            val distance = proximityFrom(board, turn.self, destination)

            val verdict = when {
                room != mostRoom -> if (room > mostRoom) BETTER else WORSE
                distance != leastDistance -> if (distance < leastDistance) BETTER else WORSE
                else -> TIED
            }

            when (verdict) {
                BETTER -> {
                    mostRoom = room
                    leastDistance = distance
                    chosen = direction
                    tied = 1
                }

                TIED -> {
                    // Reservoir sampling: uniform over however many directions tie, one draw each,
                    // and no list to allocate.
                    tied++
                    if (rng.nextInt(tied) == 0) {
                        chosen = direction
                    }
                }

                else -> Unit
            }
        }

        return Decision.Move(chosen)
    }

    /**
     * Mean distance from [cell] to the living opponents' heads, as a fraction of the diagonal, with
     * the adjacency clamp applied.
     *
     * `0.0` when nobody is left — a constant, so on a solo board every move ties on this term and
     * the ranking falls back to room alone, which is the sensible reading of "crowd nobody".
     */
    private fun proximityFrom(board: BoardView, self: SnakeId, cell: Cell): Double {
        val grid = board.grid
        val row = grid.rowOf(cell)
        val col = grid.colOf(cell)

        var total = 0.0
        var living = 0

        for (slot in 0 until board.snakeCount) {
            if (slot == self.index) {
                continue
            }

            val other = board.snake(SnakeId(slot))
            if (!other.alive) {
                continue
            }

            val dRow = (row - grid.rowOf(other.head)).toDouble()
            val dCol = (col - grid.colOf(other.head)).toDouble()
            total += sqrt(dRow * dRow + dCol * dCol) / diagonal
            living++
        }

        if (living == 0) {
            return 0.0
        }

        val mean = total / living
        return if (mean < adjacencyFloor) adjacencyPenalty else mean
    }

    override fun toString(): String = "PressureBot"

    internal companion object {
        /** Below this fraction of the diagonal, closing further is head-trading rather than pressure. */
        val ADJACENCY_FLOOR = BotKnob.Decimal(
            name = "adjacencyFloor",
            label = "Adjacency floor",
            help = "Closer than this fraction of the board's diagonal counts as head-trading.",
            default = 0.05,
            min = 0.0,
            max = 1.0,
            step = 0.01,
        )

        /** What being that close scores instead: worse than really being there, better than far. */
        val ADJACENCY_PENALTY = BotKnob.Decimal(
            name = "adjacencyPenalty",
            label = "Adjacency penalty",
            help = "What a head-trading distance scores instead of its real value.",
            default = 0.1,
            min = 0.0,
            max = 1.0,
            step = 0.01,
        )

        val KNOBS: List<BotKnob> = listOf(ADJACENCY_FLOOR, ADJACENCY_PENALTY)

        const val BETTER = 1
        const val TIED = 0
        const val WORSE = -1
    }
}
