package ao.snakewarz.bots.search.puct

import ao.snakewarz.bots.search.uct.portableLog

/**
 * The exponential, computed from arithmetic that is bit-identical on every target.
 *
 * Deliberately **not** `kotlin.math.exp`, and [portableLog] is the standing precedent: `+ - * /` and
 * `sqrt` are exactly specified by IEEE-754 and so produce the same bits on the JVM and in wasm, while
 * `exp` and `log` are free to differ in the last place between them. A softmax turns a last-place
 * difference into a different prior, a different selection, a different move and then a different
 * match — and `GoldenMoveStreamTest` re-runs `puct`'s move stream in a real browser, so the divergence
 * would arrive as a browser-only failure with no bug behind it.
 *
 * That is why this exists rather than the rule being relaxed for the bot that wanted a temperature:
 * `puct` is in the cross-target golden set, and a bounded, deterministic series keeps it there.
 * [MovePrior] is the one caller.
 *
 * ### The decomposition
 *
 * `exp(x) = 2^k * exp(r)`, with `k` the nearest integer to `x / ln2` and `|r| <= ln2 / 2 = 0.3466`.
 * The scaling is exact: `2^k` is written straight into a `Double`'s exponent field, so nothing about
 * it can round. `ln2` is subtracted in two halves — [LN_2_HIGH] carries 21 significant bits and
 * `|k|` needs at most 11, so `k * LN_2_HIGH` is an exact product and the reduction spends no
 * precision it will need back. That is the textbook range reduction, and it is what keeps a
 * fifteen-term Taylor series enough.
 *
 * At `|r| <= 0.3466` the term at `r^15` is below `10^-18`, past the last bit of a `Double` near 1, so
 * this agrees with a correctly rounded `exp` to an ulp or two — and, far more importantly, agrees
 * with *itself* everywhere. Accuracy is not the point. Reproducibility is.
 *
 * `PortableExpTest` pins the raw bits at a set of arguments and runs on both targets, which is what
 * turns "these operations are specified" into evidence.
 */
internal fun portableExp(value: Double): Double {
    require(value >= MIN_INPUT && value <= MAX_INPUT) {
        "portableExp is defined over $MIN_INPUT..$MAX_INPUT, was $value"
    }

    // Round to nearest, halves away from zero: adding the half before truncating toward zero is the
    // whole of it, and every step is exactly specified where kotlin.math.round is a call this file
    // exists to avoid depending on.
    val scaled = value * INV_LN_2
    val whole = (if (scaled < 0.0) scaled - 0.5 else scaled + 0.5).toInt()
    val k = whole.toDouble()

    val r = (value - k * LN_2_HIGH) - k * LN_2_LOW

    // Horner over the reciprocal factorials, highest term first.
    var series = 1.0 / 87_178_291_200.0
    series = series * r + 1.0 / 6_227_020_800.0
    series = series * r + 1.0 / 479_001_600.0
    series = series * r + 1.0 / 39_916_800.0
    series = series * r + 1.0 / 3_628_800.0
    series = series * r + 1.0 / 362_880.0
    series = series * r + 1.0 / 40_320.0
    series = series * r + 1.0 / 5_040.0
    series = series * r + 1.0 / 720.0
    series = series * r + 1.0 / 120.0
    series = series * r + 1.0 / 24.0
    series = series * r + 1.0 / 6.0
    series = series * r + 1.0 / 2.0
    series = series * r + 1.0
    series = series * r + 1.0

    return series * Double.fromBits((whole + EXPONENT_BIAS).toLong() shl MANTISSA_BITS)
}

/**
 * The widest argument whose `2^k` is still a normal `Double`, rounded inward to a whole number.
 *
 * A bound rather than a saturation, for CC-08's reason: nothing here has a use for `exp` of a
 * thousand, so an argument that far out is a caller that has lost track of its scale rather than a
 * value to answer.
 */
private const val MAX_INPUT = 700.0
private const val MIN_INPUT = -700.0

/** Spelled out rather than computed, so the reduction point is the same literal on every target. */
private const val INV_LN_2 = 1.4426950408889634

/** `ln 2` split so that the high half multiplies a whole `k` exactly — its low 32 bits are zero. */
private const val LN_2_HIGH = 6.93147180369123816490e-01
private const val LN_2_LOW = 1.90821492927058770002e-10

private const val MANTISSA_BITS = 52
private const val EXPONENT_BIAS = 1023
