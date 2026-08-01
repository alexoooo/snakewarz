package ao.snakewarz.match.map

import kotlin.math.abs

/**
 * The sheet a shape draws on: **every placement is made twice**, once where it was asked for and once
 * at its image under the half turn `ρ(row, col) = (rows - 1 - row, cols - 1 - col)`.
 *
 * Symmetry is therefore a property of the instrument rather than of each shape, and cannot be got
 * wrong one shape at a time. That is what makes the two-seat opening fair *by construction*: a
 * row-major index `i` maps under ρ to `rows * cols - 1 - i`, so the lowest and the highest open
 * squares — which is where `mostDistantSpawns` seats slot 0 and slot 1 — are exact images of each
 * other on any ρ-invariant map.
 *
 * The recipe is usually described as "draw the top half and reflect", and [halfRows] is that half.
 * Mirroring per *placement* produces the same set while letting each drawing operation carry the
 * guarantee itself; restricting the caller would buy nothing the mirror does not already enforce.
 */
internal class HalfBoard(val rows: Int, val cols: Int) {
    private val wall = BooleanArray(rows * cols)

    /**
     * Rows `0 until halfRows` cover the board once under ρ — the top half, plus the middle row of an
     * odd board, which ρ maps onto itself.
     */
    val halfRows: Int = (rows + 1) / 2

    /** Marks `(row, col)` and its image under ρ. */
    fun set(row: Int, col: Int) {
        require(row in 0 until rows && col in 0 until cols) { "($row, $col) is off a ${rows}x$cols board" }
        wall[row * cols + col] = true
        wall[(rows - 1 - row) * cols + (cols - 1 - col)] = true
    }

    /** Marks every square of the inclusive run `(row, fromCol)..(row, toCol)`, and its image. */
    fun setRow(row: Int, fromCol: Int, toCol: Int) {
        for (col in minOf(fromCol, toCol)..maxOf(fromCol, toCol)) {
            set(row, col)
        }
    }

    /** Marks every square of the inclusive run `(fromRow, col)..(toRow, col)`, and its image. */
    fun setColumn(col: Int, fromRow: Int, toRow: Int) {
        for (row in minOf(fromRow, toRow)..maxOf(fromRow, toRow)) {
            set(row, col)
        }
    }

    /**
     * Marks `(row, col)` and its image, unless either would touch a wall already placed — and reports
     * whether it did.
     *
     * "Touch" is the eight-neighbourhood, so every square this places stays a lone square with a gap
     * on every side. **A rectangle cannot be disconnected by isolated single squares**, which is what
     * makes connectivity a property of the construction here rather than of a retry loop: a walk
     * blocked by one of these always has a way round it, including in a corner.
     *
     * Only one neighbourhood has to be examined even though two squares are placed. The wall set is
     * ρ-invariant at every moment, and ρ is an isometry, so `(row, col)` touches a wall exactly when
     * its image does. What ρ cannot rule out is the pair touching *each other*, which is the second
     * test and only ever fires within one square of the board's centre — and the exact centre of an
     * odd board is exempt, being one square rather than a pair.
     */
    fun placeIsolated(row: Int, col: Int): Boolean {
        val mirrorRow = rows - 1 - row
        val mirrorCol = cols - 1 - col
        val ownImage = row == mirrorRow && col == mirrorCol
        if (touches(row, col)) {
            return false
        }
        if (!ownImage && abs(row - mirrorRow) <= 1 && abs(col - mirrorCol) <= 1) {
            return false
        }
        set(row, col)
        return true
    }

    /**
     * Marks the [height] x [width] block with its top-left corner at `(row, col)`, and the block's
     * image, unless one of three conditions fails — and reports whether it did.
     *
     * The first two generalise [placeIsolated]: the block **and its eight-neighbourhood** are clear,
     * and the block does not touch its own image, which only comes up within a square of the board's
     * centre. A block that *is* its own image is exempt from the second, the way the exact centre
     * square is there.
     *
     * **The third has no counterpart there, and it is what makes connectivity an argument rather
     * than a hope: the block keeps a free square between itself and every edge of the board.** A
     * rectangle cannot be disconnected by isolated *squares*, but it can by two blocks between them,
     * so the argument has to be made differently here. A free border ring plus rectangles that never
     * touch gives it: the ring of squares immediately around a block is entirely open — no other
     * block reaches it — and is 4-connected, so a walk blocked going up follows that ring to the
     * square diagonally off the block's top corner, which is a strictly lower row, and repeats until
     * it reaches row 0. Row 0 is open the whole way across, so every open square reaches every other.
     *
     * Only the block's own neighbourhood is examined even though two blocks are placed, for the
     * reason [placeIsolated] gives: the wall set is ρ-invariant at every moment and ρ is an isometry.
     */
    fun placeIsolatedBlock(row: Int, col: Int, height: Int, width: Int): Boolean {
        if (row < 1 || col < 1 || row + height > rows - 1 || col + width > cols - 1) {
            return false
        }
        for (r in row - 1..row + height) {
            for (c in col - 1..col + width) {
                if (wall[r * cols + c]) return false
            }
        }

        val mirrorRow = rows - height - row
        val mirrorCol = cols - width - col
        val ownImage = mirrorRow == row && mirrorCol == col
        if (!ownImage && touchingRuns(row, mirrorRow, height) && touchingRuns(col, mirrorCol, width)) {
            return false
        }

        for (r in row until row + height) {
            for (c in col until col + width) {
                set(r, c)
            }
        }
        return true
    }

    /** The marked squares as ascending playable indices, which is the form `MatchSetup` takes. */
    fun walls(): IntArray {
        var count = 0
        for (index in wall.indices) {
            if (wall[index]) count++
        }

        val cells = IntArray(count)
        var next = 0
        for (index in wall.indices) {
            if (wall[index]) cells[next++] = index
        }
        return cells
    }

    /** Whether two runs of [extent] squares, one from [from] and one from [other], come within one. */
    private fun touchingRuns(from: Int, other: Int, extent: Int): Boolean =
        from <= other + extent && other <= from + extent

    /** Whether `(row, col)` or any of its eight neighbours is already a wall. */
    private fun touches(row: Int, col: Int): Boolean {
        for (r in maxOf(0, row - 1)..minOf(rows - 1, row + 1)) {
            for (c in maxOf(0, col - 1)..minOf(cols - 1, col + 1)) {
                if (wall[r * cols + c]) return true
            }
        }
        return false
    }
}
