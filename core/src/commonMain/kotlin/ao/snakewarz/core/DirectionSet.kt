package ao.snakewarz.core

import kotlin.jvm.JvmInline

/**
 * A set of up to four [Direction]s packed into the low four bits of an `Int`.
 *
 * This replaces the legacy `Direction.availableFrom`, which allocated a fresh `ArrayList` on every
 * call — from every bot, on every rollout step, inside every MCTS iteration. Being a value class
 * over `Int`, this allocates nothing.
 *
 * [nth] exists so an `Rng` can choose uniformly from the set without materialising a list.
 */
@JvmInline
public value class DirectionSet(public val bits: Int) {
    public val size: Int get() = bits.countOneBits()
    public val isEmpty: Boolean get() = bits == 0
    public val isNotEmpty: Boolean get() = bits != 0

    public operator fun contains(direction: Direction): Boolean =
        (bits shr direction.ordinal) and 1 != 0

    public operator fun plus(direction: Direction): DirectionSet =
        DirectionSet(bits or (1 shl direction.ordinal))

    public operator fun minus(direction: Direction): DirectionSet =
        DirectionSet(bits and (1 shl direction.ordinal).inv())

    public infix fun intersect(other: DirectionSet): DirectionSet =
        DirectionSet(bits and other.bits)

    /**
     * The [i]-th direction present in this set, ordered by [Direction.ordinal].
     *
     * @param i in `0 until size`
     */
    public fun nth(i: Int): Direction {
        require(i >= 0 && i < size) { "index $i out of bounds for a set of size $size" }

        var remaining = bits
        repeat(i) { remaining = remaining and (remaining - 1) }
        return Direction.entries[remaining.countTrailingZeroBits()]
    }

    /** The single direction in this set, or `null` if it holds zero or several. */
    public fun singleOrNull(): Direction? =
        if (size == 1) Direction.entries[bits.countTrailingZeroBits()] else null

    override fun toString(): String =
        Direction.entries.filter { it in this }.joinToString(prefix = "[", postfix = "]")

    public companion object {
        public val EMPTY: DirectionSet = DirectionSet(0)
        public val ALL: DirectionSet = DirectionSet(0b1111)

        public fun of(vararg directions: Direction): DirectionSet {
            var bits = 0
            for (direction in directions) {
                bits = bits or (1 shl direction.ordinal)
            }
            return DirectionSet(bits)
        }
    }
}
