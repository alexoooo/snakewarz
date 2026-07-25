package ao.snakewarz.core

/**
 * Vigna's SplitMix64: a 64-bit state, a golden-ratio stride and a finalizing mix.
 *
 * Chosen because it is short enough to be *specified* rather than merely referenced, which is what a
 * replay format needs — see [Rng]. It is not cryptographic and makes no attempt to be; it passes
 * BigCrush, which is far beyond what a game needs, and it costs one add and a handful of shifts.
 *
 * The [nextInt] rejection loop is part of the specification, not an implementation detail: a plain
 * `nextLong() % bound` would bias the low residues, and the bias would be baked into every recorded
 * match.
 */
public class SplitMix64(seed: Long) : Rng {
    private var state: Long = seed

    override fun nextLong(): Long {
        state += GOLDEN_GAMMA
        return mix64(state)
    }

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, was $bound" }

        val limit = bound.toLong()
        while (true) {
            // Dropping the sign bit leaves 63 uniformly distributed bits, so `bits` is always
            // non-negative and the modulus below is well defined.
            val bits = nextLong() ushr 1
            val value = bits % limit

            // The last, short block of `[0, 2^63)` would over-represent small residues, so reject
            // it. The addition overflows into the sign bit exactly when `bits` falls in that block.
            if (bits - value + (limit - 1) >= 0) {
                return value.toInt()
            }
        }
    }

    override fun nextDouble(): Double =
        (nextLong() ushr 11) * DOUBLE_UNIT

    override fun fork(stream: Int): Rng =
        SplitMix64(mix64(state + (stream + 1).toLong() * GOLDEN_GAMMA))

    override fun toString(): String = "SplitMix64"

    private companion object {
        /** Exactly `2^-53`, so `(53 random bits) * DOUBLE_UNIT` is an exact scaling into `[0, 1)`. */
        val DOUBLE_UNIT: Double = 1.0 / (1L shl 53)
    }
}
