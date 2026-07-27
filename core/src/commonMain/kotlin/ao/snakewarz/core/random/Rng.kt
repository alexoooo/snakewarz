package ao.snakewarz.core.random

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet

/**
 * The engine's source of randomness. Deliberately **not** `kotlin.random.Random`.
 *
 * A match must reproduce exactly, and its replay travels in a URL that has to keep decoding years
 * from now. That rules out the standard library, whose algorithm is not contractually stable across
 * Kotlin versions or between the JVM and wasm targets. [SplitMix64] is the one implementation, it is
 * twenty lines, and it is locked with known-answer vectors.
 *
 * There is no global RNG anywhere in the project. Every consumer is handed its own stream, forked
 * from the match seed, so one bot's consumption can never shift another's.
 */
public interface Rng {
    /** A uniformly distributed 64-bit value. Every other method is defined in terms of this one. */
    public fun nextLong(): Long

    /**
     * A uniformly distributed value in `0 until bound`, using modulo-with-rejection so the
     * distribution is exactly uniform rather than merely close.
     */
    public fun nextInt(bound: Int): Int

    /** A uniformly distributed value in `[0, 1)`, with 53 bits of mantissa. */
    public fun nextDouble(): Double

    /**
     * An independent stream derived from this one and [stream].
     *
     * Forking does **not** consume from, or otherwise disturb, the parent, so the streams handed to
     * the slots of a match do not depend on the order in which they were created.
     */
    public fun fork(stream: Int): Rng

    /** A uniformly chosen member of [set], or `null` if it is empty. */
    public fun pick(set: DirectionSet): Direction? =
        if (set.isEmpty) null else set.nth(nextInt(set.size))
}
