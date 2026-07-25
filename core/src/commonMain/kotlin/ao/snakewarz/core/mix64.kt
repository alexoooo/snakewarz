package ao.snakewarz.core

/** The golden-ratio odd constant used to stride SplitMix64's state, `floor(2^64 / phi) | 1`. */
internal const val GOLDEN_GAMMA: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

/**
 * SplitMix64's finalizing mix — an invertible bijection on `Long` with full avalanche.
 *
 * Used for two unrelated jobs, both of which need "an arbitrary but fixed bit-scramble" and neither
 * of which may drift between Kotlin versions or between the JVM and wasm targets: driving
 * [SplitMix64], and deriving Zobrist keys in [Occupancy].
 *
 * Only `+`, `*`, `xor` and `ushr` are used, all of which are exactly specified on both targets.
 */
internal fun mix64(value: Long): Long {
    var z = value
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
    return z xor (z ushr 31)
}
