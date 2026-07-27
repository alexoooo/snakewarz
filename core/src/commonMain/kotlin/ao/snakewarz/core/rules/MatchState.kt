package ao.snakewarz.core.rules

import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.core.snake.SnakeState

/**
 * An immutable snapshot of a match, taken by [Board.snapshot].
 *
 * The engine keeps **both** representations on purpose. A fully persistent state is right for the
 * driver, for replay and for rendering, and catastrophic inside a search doing millions of steps —
 * which is precisely why the legacy `UctAi`, built on persistent state everywhere, is slow. So the
 * canonical representation is [Board]'s mutable arena, the rules exist once on top of it, and this
 * is derived from it at most once per turn. That is O(snakes), not O(cells).
 */
public class MatchState internal constructor(
    public val grid: Grid,
    public val rules: RulesConfig,
    public val turnIndex: Int,
    public val toAct: SnakeId,
    public val outcome: MatchOutcome?,
    private val snakes: Array<SnakeState>,
) {
    public val snakeCount: Int get() = snakes.size

    public val aliveCount: Int get() = snakes.count { it.alive }

    public fun snake(id: SnakeId): SnakeState = snakes[id.index]

    /**
     * An ASCII rendering of the board: `.` is empty, a snake's body is its slot digit, and its head
     * is the corresponding letter. Purely a debugging aid — nothing renders through this.
     */
    override fun toString(): String {
        val picture = CharArray(grid.rows * grid.cols) { '.' }
        for (snake in snakes) {
            for (i in 0 until snake.length) {
                val cell = snake.cellAt(i)
                val symbol = if (i == snake.length - 1) 'A' + snake.id.index else '0' + snake.id.index
                picture[grid.rowOf(cell) * grid.cols + grid.colOf(cell)] = symbol
            }
        }

        return buildString {
            append("turn ").append(turnIndex)
            append(", to act ").append(toAct.index)
            if (outcome != null) append(", ").append(outcome)
            appendLine()
            for (row in 0 until grid.rows) {
                appendRange(picture, row * grid.cols, (row + 1) * grid.cols)
                appendLine()
            }
        }
    }
}
