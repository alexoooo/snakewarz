package ao.snakewarz.core.snake

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.EliminationReason

/**
 * A frozen [SnakeView], taken by [Board.snapshot].
 *
 * Copying a body is O(length), which is why snapshots are taken at most once per turn by the driver
 * and never inside a search. Search runs on the mutable arena with an undo journal instead.
 */
public class SnakeState internal constructor(
    override val id: SnakeId,
    override val alive: Boolean,
    override val eliminationReason: EliminationReason?,
    override val movesMade: Int,
    override val lastDirection: Direction?,
    override val growsOnNextMove: Boolean,
    /** Tail first, head last. */
    private val cells: IntArray,
) : SnakeView {
    override val length: Int get() = cells.size

    override val head: Cell get() = Cell(cells[cells.size - 1])

    override val tail: Cell get() = Cell(cells[0])

    override fun cellAt(i: Int): Cell = Cell(cells[i])

    override fun toString(): String =
        "SnakeState($id, length=$length, ${if (alive) "alive" else eliminationReason.toString()})"
}
