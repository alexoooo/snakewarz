package ao.snakewarz.ui

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Grid
import kotlin.math.abs

/**
 * Whether a press on [pressed] means "take hold of the snake whose head is on [head]".
 *
 * One square of grace in every direction, the diagonals included, so the eight squares around the
 * head are as good as the head itself. **The grace is what makes this work with a fingertip.**
 * Exactly-on-the-head is a mouse-only interaction: a finger covers several squares of a large board
 * and the browser reports one point somewhere under it, so a press that misses by a square has to
 * mean what a press that does not mean. A mouse pays nothing for the same rule, since the squares
 * around a head are the player's own body and the board behind it.
 *
 * Off the board is never near anything, which is also the answer for `Cell.NONE`: a press outside
 * the canvas resolves to it, and the wall ring it sits in is not playable.
 */
internal fun nearHead(grid: Grid, pressed: Cell, head: Cell): Boolean {
    if (!grid.isPlayable(pressed)) {
        return false
    }

    val rows = abs(grid.rowOf(pressed) - grid.rowOf(head))
    val cols = abs(grid.colOf(pressed) - grid.colOf(head))
    return maxOf(rows, cols) <= GRACE_SQUARES
}

/** How far from the head a press may land and still take hold of the snake, in squares. */
private const val GRACE_SQUARES = 1
