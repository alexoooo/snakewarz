package ao.snakewarz.bots.search.uct

/**
 * The natural logarithm, computed from arithmetic that is bit-identical on every target.
 *
 * Deliberately **not** `kotlin.math.ln`. `+ - * /` and `sqrt` are exactly specified by IEEE-754 and
 * so produce the same bits on the JVM and in wasm; `log` and `exp` are not, and are free to differ
 * in the last place between the two. UCB1 is `average + sqrt(ln(visits) / (5 * childVisits))`, and a
 * single last-place difference there flips a move choice, then the rest of the match. The golden
 * move-stream hashes run in a real browser as well as on the JVM, so a search bot built on
 * `kotlin.math.ln` would sooner or later fail in Chrome and pass on the JVM — a false alarm with no
 * bug behind it, arriving in the one test suite that exists to be trusted.
 *
 * The decomposition is the textbook one: `value = mantissa * 2^exponent` read straight out of the
 * IEEE bits, the mantissa folded into `[1/sqrt2, sqrt2)` so the series converges quickly, and then
 * `ln(m) = 2 * atanh((m - 1) / (m + 1))` summed as a polynomial in `s^2`. With `|s| <= 0.1716` the
 * term at `s^17` is below `10^-14`, which is the precision of a `Double` near 1 — so this agrees
 * with a correctly rounded `ln` to within an ulp or two, and, far more importantly, agrees with
 * *itself* everywhere.
 *
 * Accuracy is not really the point. Reproducibility is.
 */
internal fun portableLog(value: Double): Double {
    require(value > 0.0) { "the logarithm is defined for positive values, was $value" }

    var bits = value.toRawBits()
    var exponent = ((bits ushr MANTISSA_BITS) and EXPONENT_MASK).toInt() - EXPONENT_BIAS

    // Subnormals carry an exponent field of zero and no implicit leading one, so scale them into the
    // normal range first. A power of two is exact, so this costs nothing in precision.
    if (exponent == -EXPONENT_BIAS) {
        bits = (value * SUBNORMAL_SCALE).toRawBits()
        exponent = ((bits ushr MANTISSA_BITS) and EXPONENT_MASK).toInt() - EXPONENT_BIAS - SUBNORMAL_SHIFT
    }

    // Re-stamp the exponent field as zero, leaving the mantissa alone: the result is in [1, 2).
    var mantissa = Double.fromBits((bits and MANTISSA_MASK) or ONE_BITS)
    if (mantissa > SQRT_2) {
        mantissa *= 0.5
        exponent++
    }

    val s = (mantissa - 1.0) / (mantissa + 1.0)
    val square = s * s

    // Horner over the odd reciprocals, highest term first.
    var series = 1.0 / 17.0
    series = series * square + 1.0 / 15.0
    series = series * square + 1.0 / 13.0
    series = series * square + 1.0 / 11.0
    series = series * square + 1.0 / 9.0
    series = series * square + 1.0 / 7.0
    series = series * square + 1.0 / 5.0
    series = series * square + 1.0 / 3.0
    series = series * square + 1.0

    return exponent * LN_2 + 2.0 * s * series
}

private const val MANTISSA_BITS = 52
private const val EXPONENT_MASK = 0x7FFL
private const val EXPONENT_BIAS = 1023
private const val MANTISSA_MASK = 0x000FFFFFFFFFFFFFL
private const val ONE_BITS = 0x3FF0000000000000L

/** `2^54`, enough to lift any subnormal into the normal range in one exact multiplication. */
private const val SUBNORMAL_SCALE = 1.8014398509481984E16
private const val SUBNORMAL_SHIFT = 54

/** Spelled out rather than computed, so the fold point is the same literal on every target. */
private const val SQRT_2 = 1.4142135623730951

private const val LN_2 = 0.6931471805599453
