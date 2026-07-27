package ao.snakewarz.core.grid

import ao.snakewarz.core.random.GOLDEN_GAMMA
import ao.snakewarz.core.random.mix64
import ao.snakewarz.core.snake.SnakeId

/**
 * Who owns every square of a [Grid], as one `ByteArray` of owner codes, updated incrementally.
 *
 * This is the single hottest data structure in the program, and it is the direct answer to the worst
 * performance bug in the legacy engine: `SimpleSnakesGame.commonBoard()` allocated a fresh
 * `BitSetMatrix` and OR-ed three matrices together on **every** `askForMove` — that is, inside every
 * rollout step of every MCTS iteration. Here nothing is rebuilt and nothing is allocated; a move
 * touches at most two squares.
 *
 * A byte of owner ids beats a bitset because one array read answers both "is this square free" and
 * "whose is it", with no shift and no mask. Rendering needs the second question and so does every
 * flood-fill bot.
 *
 * The border ring of the padded grid is permanently marked as wall, so walking off the board and
 * walking into a snake are the same array read and neither needs a bounds check.
 *
 * ### Zobrist hashing
 *
 * [hash] is an O(1)-updated fingerprint of the occupied squares, which turns MCTS transposition
 * lookups into a `Long` compare — replacing the legacy `BiState.equals`, which compared whole
 * `BitSet`s. Keys come from a **fixed compile-time mix**, never from the match seed: the hash is
 * never persisted, and tree reuse needs it stable within a process, not unpredictable. Deriving keys
 * from [mix64] rather than a table costs a few arithmetic operations and saves a per-instance table
 * that would otherwise be copied around and would miss cache on every lookup.
 *
 * The invariant that guards this whole optimization — *incremental occupancy always equals occupancy
 * rebuilt from all bodies, cell for cell and hash for hash* — is property-tested.
 */
public class Occupancy(public val grid: Grid) {
    private val owner = ByteArray(grid.cellCount)

    /** Indexed by [Direction.ordinal], mirroring the bit layout of [DirectionSet]. */
    private val offsets = IntArray(DIRECTION_COUNT) { grid.offsetOf(Direction.entries[it]) }

    /** A fingerprint of the occupied squares. Zero for an empty board; walls do not contribute. */
    public var hash: Long = 0L
        private set

    init {
        // The wall ring is set once and never changes, so it is deliberately left out of the hash.
        val lastRow = grid.rows + 1
        for (col in 0 until grid.stride) {
            owner[col] = WALL
            owner[lastRow * grid.stride + col] = WALL
        }
        for (row in 1..grid.rows) {
            owner[row * grid.stride] = WALL
            owner[row * grid.stride + grid.cols + 1] = WALL
        }
    }

    public fun isFree(cell: Cell): Boolean = owner[cell.index] == EMPTY

    public fun isWall(cell: Cell): Boolean = owner[cell.index] == WALL

    /** The snake occupying [cell], or [SnakeId.NONE] if it is empty or part of the wall ring. */
    public fun ownerOf(cell: Cell): SnakeId {
        val code = owner[cell.index].toInt()
        return if (code > 0) SnakeId(code - 1) else SnakeId.NONE
    }

    public fun occupy(cell: Cell, by: SnakeId) {
        val code = (by.index + 1).toByte()
        owner[cell.index] = code
        hash = hash xor zobrist(cell.index, code.toInt())
    }

    public fun vacate(cell: Cell) {
        val code = owner[cell.index]
        owner[cell.index] = EMPTY
        hash = hash xor zobrist(cell.index, code.toInt())
    }

    /**
     * The directions in which [cell] has a free neighbour.
     *
     * Unrolled and branch-light on purpose: this runs once per legality check, which is once per
     * rollout step. Off-board neighbours land in the wall ring and are simply not free, so there is
     * no bounds check to pay for.
     */
    public fun freeNeighbors(cell: Cell): DirectionSet {
        val index = cell.index
        var bits = 0
        if (owner[index + offsets[0]] == EMPTY) bits = bits or 0b0001
        if (owner[index + offsets[1]] == EMPTY) bits = bits or 0b0010
        if (owner[index + offsets[2]] == EMPTY) bits = bits or 0b0100
        if (owner[index + offsets[3]] == EMPTY) bits = bits or 0b1000
        return DirectionSet(bits)
    }

    /** Overwrites this occupancy with [other]'s, reusing the existing array. */
    public fun copyFrom(other: Occupancy) {
        require(other.grid.rows == grid.rows && other.grid.cols == grid.cols) {
            "cannot copy a ${other.grid} occupancy into a $grid one"
        }

        other.owner.copyInto(owner)
        hash = other.hash
    }

    /** Clears every snake, leaving the wall ring in place. */
    public fun clear() {
        for (row in 1..grid.rows) {
            val rowStart = row * grid.stride
            owner.fill(EMPTY, rowStart + 1, rowStart + grid.cols + 1)
        }
        hash = 0L
    }

    private fun zobrist(cellIndex: Int, ownerCode: Int): Long =
        mix64(ZOBRIST_SALT + (cellIndex.toLong() shl 8) + (ownerCode.toLong() and 0xFF))

    public companion object {
        /** The largest number of snakes an owner byte can distinguish. */
        public const val MAX_SNAKES: Int = 126

        private const val DIRECTION_COUNT = 4
        private const val EMPTY: Byte = 0
        private const val WALL: Byte = -1

        /** Arbitrary but fixed. Anything odd works; this is the golden-ratio constant again. */
        private const val ZOBRIST_SALT: Long = GOLDEN_GAMMA
    }
}
