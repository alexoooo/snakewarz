package ao.snakewarz.core.snake

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.rules.Board

/**
 * One snake's occupied squares, tail first, as a ring buffer over an `IntArray`.
 *
 * A ring buffer is chosen over a growable list because the engine needs push **and** pop at *both*
 * ends: a move pushes a head and pops a tail, and [Board.undo] runs exactly that backwards. Both
 * directions are O(1) and none of them allocates, which is what lets a search node cost nothing.
 *
 * The capacity is a power of two so the wrap is a mask rather than a division, and it is sized above
 * the number of playable squares — a body can never be longer than the board.
 */
internal class SnakeBody(playableCount: Int) {
    // +2, not +1: one slot for the transient over-length state between pushHead and popTail, and one
    // so the ring is never completely full (a full ring cannot distinguish empty from full).
    private val cells = IntArray(powerOfTwoAtLeast(playableCount + 2))
    private val mask = cells.size - 1

    /** Index of the tail. The body occupies `start .. start + size - 1`, modulo [mask]. */
    private var start = 0

    var size: Int = 0
        private set

    val head: Cell get() = Cell(cells[(start + size - 1) and mask])

    val tail: Cell get() = Cell(cells[start])

    /** The [i]-th square from the tail, so `cellAt(0) == tail` and `cellAt(size - 1) == head`. */
    fun cellAt(i: Int): Cell {
        require(i >= 0 && i < size) { "index $i out of bounds for a body of length $size" }
        return Cell(cells[(start + i) and mask])
    }

    fun reset(at: Cell) {
        start = 0
        size = 1
        cells[0] = at.index
    }

    fun pushHead(cell: Cell) {
        cells[(start + size) and mask] = cell.index
        size++
    }

    fun popHead(): Cell {
        size--
        return Cell(cells[(start + size) and mask])
    }

    fun popTail(): Cell {
        val cell = Cell(cells[start])
        start = (start + 1) and mask
        size--
        return cell
    }

    fun pushTail(cell: Cell) {
        start = (start - 1) and mask
        cells[start] = cell.index
        size++
    }

    /** Overwrites this body with [other]'s, normalising the ring so the tail sits at index 0. */
    fun copyFrom(other: SnakeBody) {
        require(other.size <= cells.size) { "body of length ${other.size} does not fit a ${cells.size} ring" }

        for (i in 0 until other.size) {
            cells[i] = other.cells[(other.start + i) and other.mask]
        }
        start = 0
        size = other.size
    }
}

private fun powerOfTwoAtLeast(n: Int): Int {
    var size = 1
    while (size < n) {
        size = size shl 1
    }
    return size
}
