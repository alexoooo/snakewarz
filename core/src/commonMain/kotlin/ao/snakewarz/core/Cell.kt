package ao.snakewarz.core

import kotlin.jvm.JvmInline

/**
 * A single square, stored as a linear index into a [Grid]'s padded backing array.
 *
 * Cells are opaque: the index encodes both row and column, and is only meaningful relative to the
 * [Grid] that produced it. Use [Grid.cellAt] to make one and [Grid.rowOf] / [Grid.colOf] to read it
 * back. Never do arithmetic on [index] outside `:core`.
 *
 * A `value class` over `Int` unboxes in most positions, but **boxes as a generic type argument or
 * when nullable** — so `List<Cell>` allocates per element. Bodies and paths therefore live in
 * `IntArray`, and no hot-path API returns a collection of cells. Use [NONE] instead of `Cell?`.
 */
@JvmInline
public value class Cell(public val index: Int) {
    public val isNone: Boolean get() = index < 0

    public companion object {
        /** The absent cell. Cheaper than `Cell?`, which would box. */
        public val NONE: Cell = Cell(-1)
    }
}
