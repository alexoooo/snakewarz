package ao.snakewarz.match

import ao.snakewarz.core.Direction

/**
 * The moves of a match, **two bits each**.
 *
 * Two bits and not three. The obvious alternative reserves extra codes for resign and forfeit, which
 * costs 50% more across the entire stream to describe events that happen at most `slots - 1` times
 * in a whole match; those go in a side table instead. A suicide needs no symbol at all — it is a
 * recorded direction that turns out to be illegal when replayed, so it describes itself.
 *
 * An 800-turn match is 200 bytes here, about 264 base64url characters, which is what makes a replay
 * fit in a URL.
 */
public class DirectionStream {
    private var packed: ByteArray = ByteArray(INITIAL_CAPACITY)

    public var size: Int = 0
        private set

    public fun add(direction: Direction) {
        val byteIndex = size shr 2
        if (byteIndex == packed.size) {
            packed = packed.copyOf(packed.size * 2)
        }

        val shift = (size and 0b11) shl 1
        packed[byteIndex] = (packed[byteIndex].toInt() or (direction.ordinal shl shift)).toByte()
        size++
    }

    public operator fun get(index: Int): Direction {
        require(index >= 0 && index < size) { "index $index out of bounds for a stream of $size moves" }

        val shift = (index and 0b11) shl 1
        return Direction.entries[(packed[index shr 2].toInt() shr shift) and 0b11]
    }

    public fun isEmpty(): Boolean = size == 0

    public fun toList(): List<Direction> = List(size) { get(it) }

    public fun copy(): DirectionStream {
        val clone = DirectionStream()
        clone.packed = packed.copyOf()
        clone.size = size
        return clone
    }

    /** Number of bytes actually carrying moves. Any bits above [size] are zero and stay zero. */
    internal val byteLength: Int get() = (size + 3) shr 2

    internal fun bytes(): ByteArray = packed.copyOf(byteLength)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DirectionStream || other.size != size) return false

        for (i in 0 until byteLength) {
            if (packed[i] != other.packed[i]) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = size
        for (i in 0 until byteLength) {
            result = 31 * result + packed[i]
        }
        return result
    }

    override fun toString(): String = "DirectionStream($size moves)"

    internal companion object {
        private const val INITIAL_CAPACITY = 64

        fun of(bytes: ByteArray, size: Int): DirectionStream {
            require(size >= 0) { "a stream cannot hold $size moves" }
            require(bytes.size == (size + 3) shr 2) {
                "${bytes.size} bytes do not hold exactly $size two-bit moves"
            }

            val stream = DirectionStream()
            stream.packed = if (bytes.isEmpty()) ByteArray(INITIAL_CAPACITY) else bytes.copyOf()
            stream.size = size
            return stream
        }
    }
}
