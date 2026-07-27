package ao.snakewarz.core.grid

/**
 * The board geometry: [rows] x [cols] of playable squares, addressed as [Cell]s.
 *
 * The backing array is **padded** to `(rows + 2) x (cols + 2)`. The border ring is permanently
 * marked as wall by [Occupancy], which means stepping to a neighbour is pure integer addition with
 * **no bounds check at all** — walking off the board is indistinguishable from walking into a
 * snake, so both fall out of a single array read.
 *
 * This is the hottest path in the program (every rollout step of every MCTS iteration), and it is
 * why the legacy `withinBounds` + `isAvailable` double dispatch is gone.
 *
 * [Grid] is immutable and safe to share between a match and any number of search arenas.
 */
public class Grid(public val rows: Int, public val cols: Int) {
    init {
        require(rows > 0) { "rows must be positive, was $rows" }
        require(cols > 0) { "cols must be positive, was $cols" }
        // In Int arithmetic a large enough board wraps to a negative cellCount, which every ceiling
        // downstream then passes and every allocation then fails on. Caught here in Long arithmetic
        // so that cellCount is always the number it says it is.
        require((rows.toLong() + 2) * (cols.toLong() + 2) <= Int.MAX_VALUE) {
            "a ${rows}x$cols board has more squares than an array can address"
        }
    }

    /** Width of one padded row, including the wall square at each end. */
    public val stride: Int = cols + 2

    /** Size of the padded backing array, including the wall ring. */
    public val cellCount: Int = stride * (rows + 2)

    /** Number of playable squares, excluding the wall ring. */
    public val playableCount: Int = rows * cols

    private val offsets: IntArray = IntArray(Direction.entries.size) { ordinal ->
        val direction = Direction.entries[ordinal]
        direction.dRow * stride + direction.dCol
    }

    public fun cellAt(row: Int, col: Int): Cell =
        Cell((row + 1) * stride + col + 1)

    public fun rowOf(cell: Cell): Int = cell.index / stride - 1

    public fun colOf(cell: Cell): Int = cell.index % stride - 1

    /** The signed index delta for one step in [direction]. */
    public fun offsetOf(direction: Direction): Int = offsets[direction.ordinal]

    /**
     * The neighbour of [from] in [direction]. Never bounds-checked: stepping off the board yields a
     * cell in the wall ring, which [Occupancy] reports as occupied.
     */
    public fun step(from: Cell, direction: Direction): Cell =
        Cell(from.index + offsets[direction.ordinal])

    /** True if [cell] is a playable square rather than part of the wall ring. */
    public fun isPlayable(cell: Cell): Boolean {
        if (cell.index < 0 || cell.index >= cellCount) return false
        val col = cell.index % stride
        return col in 1..cols && cell.index >= stride && cell.index < cellCount - stride
    }

    public fun contains(row: Int, col: Int): Boolean =
        row in 0 until rows && col in 0 until cols

    override fun toString(): String = "Grid(${rows}x$cols)"
}
