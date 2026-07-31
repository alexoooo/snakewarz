package ao.snakewarz.match.map

/**
 * A materialised wall set: which squares of a [rows] x [cols] board are permanently impassable.
 *
 * **A map is a wall set. A [MapShape] is how one is made, and nothing outside this package ever sees
 * a shape.** That split is what the replay format is built on: `MatchSetup` takes an `IntArray`, the
 * codec carries the bitmap itself, and a shape stays free to be redesigned or deleted without
 * breaking a link anybody has shared.
 *
 * Walls are **playable** indices, `row * cols + col`, strictly ascending — the same form and the same
 * canonical order `MatchSetup.walls` is validated in, so `walls()` feeds it without a conversion.
 *
 * [init] demands ascending, in range, and nothing more. **Symmetry and connectivity are guarantees of
 * [generateMap], not of this type**: a hand-drawn fixture is legitimately neither, and a map decoded
 * from a stranger's replay is whatever they played on.
 */
public class BoardMap(public val rows: Int, public val cols: Int, walls: IntArray) {
    private val cells: IntArray = walls.copyOf()

    public val wallCount: Int get() = cells.size

    init {
        require(rows > 0 && cols > 0) { "a board must have positive sides, was ${rows}x$cols" }

        val playableCount = rows * cols
        var previous = -1
        for (i in cells.indices) {
            require(cells[i] in 0 until playableCount) {
                "wall $i is at ${cells[i]}, which is off a ${rows}x$cols board"
            }
            require(cells[i] > previous) { "walls must ascend and not repeat; ${cells[i]} follows $previous" }
            previous = cells[i]
        }
    }

    /** The wall squares as a fresh array, matching `MatchSetup.spawns` in returning a copy. */
    public fun walls(): IntArray = cells.copyOf()

    public fun isWall(row: Int, col: Int): Boolean {
        require(row in 0 until rows && col in 0 until cols) {
            "($row, $col) is off a ${rows}x$cols board"
        }
        return holdsSorted(cells, row * cols + col)
    }

    override fun toString(): String = "BoardMap(${rows}x$cols, $wallCount walls)"

    public companion object {
        public fun empty(rows: Int, cols: Int): BoardMap = BoardMap(rows, cols, IntArray(0))

        /**
         * A hand-drawn map: `#` a wall, `.` open — the alphabet the region fixtures draw shapes in.
         *
         * Strict about the alphabet on purpose. A stray character in a picture would otherwise read
         * as an open square, and a fixture that is quietly not the shape it looks like is the worst
         * kind of test.
         */
        public fun of(picture: List<String>): BoardMap {
            require(picture.isNotEmpty()) { "a picture needs at least one row" }

            val rows = picture.size
            val cols = picture[0].length
            val cells = mutableListOf<Int>()
            for (row in 0 until rows) {
                require(picture[row].length == cols) {
                    "row $row is ${picture[row].length} squares, not $cols"
                }
                for (col in 0 until cols) {
                    when (picture[row][col]) {
                        '#' -> cells += row * cols + col
                        '.' -> Unit
                        else -> throw IllegalArgumentException(
                            "a picture is '#' and '.'; row $row column $col is '${picture[row][col]}'",
                        )
                    }
                }
            }
            return BoardMap(rows, cols, cells.toIntArray())
        }
    }
}

/**
 * Whether the ascending [sorted] holds [value].
 *
 * A binary search rather than a scan because a decoded map is as long as the board is large;
 * `java.util.Arrays` is not available to common code.
 */
private fun holdsSorted(sorted: IntArray, value: Int): Boolean {
    var low = 0
    var high = sorted.size - 1
    while (low <= high) {
        val middle = (low + high) ushr 1
        when {
            sorted[middle] < value -> low = middle + 1
            sorted[middle] > value -> high = middle - 1
            else -> return true
        }
    }
    return false
}
