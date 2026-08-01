package ao.snakewarz.lab.endgame

import ao.snakewarz.core.rules.BoardView

/**
 * The complete dynamic identity of an empty 8x8 position, packed without trusting [BoardView.hash].
 *
 * A solver owns one immutable context — geometry, rules, turn order and root player — so those
 * fields namespace the table rather than repeating in every entry. The words below retain every
 * dynamic field that can distinguish a replay state, including body order and the turn-limit clock.
 */
internal class ExactState(private val words: LongArray) {
    fun copyInto(target: LongArray, offset: Int = 0) {
        words.copyInto(target, offset)
    }

    fun sameAs(source: LongArray, offset: Int = 0): Boolean {
        for (word in words.indices) {
            if (words[word] != source[offset + word]) {
                return false
            }
        }
        return true
    }

    override fun equals(other: Any?): Boolean = other is ExactState && words.contentEquals(other.words)

    override fun hashCode(): Int = words.contentHashCode()

    override fun toString(): String = words.joinToString(prefix = "ExactState(", postfix = ")")
}

/** Packs exact structural states into a fixed-width record suitable for a flat transposition table. */
internal object ExactStateCodec {
    const val WORDS: Int = 10
    const val MAX_SNAKES: Int = 4

    fun snapshot(board: BoardView): ExactState {
        val words = LongArray(WORDS)
        encode(board, words, 0)
        return ExactState(words)
    }

    fun encode(board: BoardView, target: LongArray, offset: Int) {
        require(board.grid.rows == BOARD_SIDE && board.grid.cols == BOARD_SIDE) {
            "exact endgame state needs an ${BOARD_SIDE}x$BOARD_SIDE, was ${board.grid}"
        }
        require(board.snakeCount in 1..MAX_SNAKES) {
            "exact endgame state supports 1..$MAX_SNAKES snakes, was ${board.snakeCount}"
        }
        require(offset >= 0 && offset + WORDS <= target.size) {
            "exact state record at $offset does not fit ${target.size} words"
        }
        target.fill(0L, offset, offset + WORDS)

        var bit = 0
        bit = write(target, offset, bit, board.turnIndex.toLong(), Int.SIZE_BITS)
        bit = write(target, offset, bit, board.toAct.index.toLong(), Byte.SIZE_BITS)

        var bodyCells = 0
        for (slot in 0 until board.snakeCount) {
            val snake = board.snake(ao.snakewarz.core.snake.SnakeId(slot))
            require(snake.movesMade >= 0) { "slot $slot has ${snake.movesMade} moves" }
            require(snake.length in 1..BOARD_CELLS) { "slot $slot has an impossible length ${snake.length}" }

            bit = write(target, offset, bit, if (snake.alive) 1L else 0L, 1)
            bit = write(target, offset, bit, (snake.eliminationReason?.ordinal?.plus(1) ?: 0).toLong(), 3)
            bit = write(target, offset, bit, snake.movesMade.toLong(), Int.SIZE_BITS)
            bit = write(target, offset, bit, (snake.lastDirection?.ordinal?.plus(1) ?: 0).toLong(), 3)
            bit = write(target, offset, bit, if (snake.growsOnNextMove) 1L else 0L, 1)
            bit = write(target, offset, bit, snake.length.toLong(), 7)
            bodyCells += snake.length
        }
        require(bodyCells <= BOARD_CELLS) {
            "$bodyCells body cells do not fit an empty ${BOARD_SIDE}x$BOARD_SIDE"
        }

        for (slot in 0 until board.snakeCount) {
            val snake = board.snake(ao.snakewarz.core.snake.SnakeId(slot))
            for (i in 0 until snake.length) {
                val cell = snake.cellAt(i)
                val row = board.grid.rowOf(cell)
                val col = board.grid.colOf(cell)
                require(row in 0 until BOARD_SIDE && col in 0 until BOARD_SIDE) {
                    "slot $slot body cell $cell is outside an ${BOARD_SIDE}x$BOARD_SIDE"
                }
                bit = write(target, offset, bit, (row * BOARD_SIDE + col).toLong(), CELL_BITS)
            }
        }
        check(bit <= WORDS * Long.SIZE_BITS) { "exact state needs $bit bits, only ${WORDS * Long.SIZE_BITS} exist" }
    }

    fun hash(words: LongArray, offset: Int): Long {
        var hash = FNV_OFFSET
        for (word in 0 until WORDS) {
            hash = (hash xor words[offset + word]) * FNV_PRIME
        }
        return hash xor (hash ushr HASH_FOLD)
    }

    private fun write(target: LongArray, offset: Int, at: Int, value: Long, width: Int): Int {
        require(width in 1..Long.SIZE_BITS) { "bit width must be 1..${Long.SIZE_BITS}, was $width" }
        if (width < Long.SIZE_BITS) {
            require(value ushr width == 0L) { "$value does not fit $width bits" }
        }

        val word = at / Long.SIZE_BITS
        val shift = at % Long.SIZE_BITS
        target[offset + word] = target[offset + word] or (value shl shift)
        val first = minOf(width, Long.SIZE_BITS - shift)
        if (first < width) {
            target[offset + word + 1] = target[offset + word + 1] or (value ushr first)
        }
        return at + width
    }

    private const val BOARD_SIDE = 8
    private const val BOARD_CELLS = BOARD_SIDE * BOARD_SIDE
    private const val CELL_BITS = 6
    private const val HASH_FOLD = 29
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
}
