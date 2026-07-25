package ao.snakewarz.match

import ao.snakewarz.core.Cell

/**
 * The squares the last [Match.step] changed. Valid until the next one.
 *
 * A normal turn dirties one or two squares — the new head, and the vacated tail on the turns when
 * the snake does not grow. That is what lets the renderer paint two rectangles per turn instead of
 * a whole board, and it is why a full repaint is needed only on resize, on seek and at match start.
 *
 * A **death dirties nothing**: the losing snake does not move, it changes colour, and recolouring a
 * whole body is a repaint rather than a cell update. `StepResult.Advanced.fatal` is the signal for
 * that, not this.
 */
public class TurnEvents internal constructor() {
    private val cells = IntArray(MAX_DIRTY)

    public var size: Int = 0
        private set

    public fun cellAt(index: Int): Cell {
        require(index >= 0 && index < size) { "index $index out of bounds for $size dirty cells" }
        return Cell(cells[index])
    }

    public val isEmpty: Boolean get() = size == 0

    internal fun clear() {
        size = 0
    }

    internal fun add(cell: Cell) {
        check(size < MAX_DIRTY) { "a turn cannot dirty more than $MAX_DIRTY squares" }
        cells[size++] = cell.index
    }

    override fun toString(): String = "TurnEvents(${List(size) { cells[it] }})"

    private companion object {
        /** A head and, on a non-growing turn, a tail. There is no third thing a move can touch. */
        const val MAX_DIRTY = 2
    }
}
