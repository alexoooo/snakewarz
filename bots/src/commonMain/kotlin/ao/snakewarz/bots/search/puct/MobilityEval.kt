package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.core.snake.SnakeId

/**
 * The cheap end: how many ways out each snake has, as a share of all of them.
 *
 * A handful of array reads against [ExpertEval]'s whole-board sweep, so at one allowance this buys
 * something like a hundred times the tree. Whether that beats a better guess at a leaf is the
 * question a hand-written evaluation has to answer, and it cannot be answered by weights — setting
 * [ExpertEval]'s territory weight to zero does not make it skip the sweep. So this is a separate
 * evaluation rather than a configuration of that one: it is a claim about *cost*.
 *
 * A share rather than a raw count, so the values land on [LeafEval]'s scale with no special case: a
 * sole survivor holds every liberty there is and reads [LeafEval.WIN], and a snake with nothing legal
 * left reads [LeafEval.LOSS] whatever its opponents have.
 */
internal class MobilityEval(private val slotCount: Int) : LeafEval {
    /** One, matching one simulated move: a few array reads against one `Board.apply`. */
    override val cost: Int get() = 1

    override fun valuesInto(playout: Playout, into: DoubleArray): Boolean {
        val board = playout.board

        var total = 0
        for (slot in 0 until slotCount) {
            val id = SnakeId(slot)
            val liberties = if (board.snake(id).alive) board.legalMoves(id).size else 0
            into[slot] = liberties.toDouble()
            total += liberties
        }

        for (slot in 0 until slotCount) {
            into[slot] = when {
                !board.snake(SnakeId(slot)).alive -> LeafEval.LOSS
                // Everybody hemmed in at once. Saying so beats inventing a leader out of nothing.
                total == 0 -> LeafEval.EVEN
                else -> into[slot] / total
            }
        }

        return true
    }

    override fun toString(): String = "MobilityEval"
}
