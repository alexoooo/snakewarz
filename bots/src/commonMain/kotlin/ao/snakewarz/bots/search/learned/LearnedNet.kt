package ao.snakewarz.bots.search.learned

import ao.snakewarz.bots.search.puct.portableExp
import kotlin.math.abs

/**
 * The model a learned evaluation is: a fixed-length feature row in, a probability of winning out.
 *
 * One optional hidden layer of softsign units over a logistic output, which is the smallest shape
 * that can express a *gate* — "this reading matters once the snakes are separated and not before" —
 * and gates are most of what a hand-written leaf spends its branches on. At [hiddenUnits] of zero it
 * is plain logistic regression, which is the control the hidden layer has to beat.
 *
 * ### The activation and the squash are both `+ - * /`
 *
 * Softsign is `x / (1 + |x|)`: bounded, monotone, smooth enough to train, and exactly specified by
 * IEEE-754 on every target — where `tanh` is not. The output squash needs a real exponential and
 * takes it from [portableExp] for the same reason `MovePrior`'s softmax does, so a learned leaf could
 * enter `GoldenMoveStreamTest`'s cross-target set without an exception being written for it. SW-02 in
 * `docs/Coding-Standards.md` carries why that bar is a test rather than an argument.
 *
 * ### The squash is in the loss, not bolted on afterwards
 *
 * A value fitted with least squares and then clamped into `0..1` online is a *different function*
 * from the one that was fitted, and the difference reads as "the model is weak" rather than as the
 * calibration error it is. So the trainer's loss is the log-loss of exactly this logistic, and the
 * gradient it needs at the output is `p − y` with no derivative of its own to get wrong.
 *
 * ### Why the trainer runs its forward pass through here
 *
 * `:lab` cannot see `:bots`' internals and a second copy of a forward pass is a second definition of
 * the model. [forward] is that seam: it fills the caller's array with whatever the output layer read
 * — the hidden activations, or the inputs when there is no hidden layer — and [slopeOf] turns one of
 * those back into its own derivative. Between them a trainer can back-propagate without ever writing
 * down the activation, the squash or the layout, which is the whole of what could drift.
 *
 * ### Weights travel as a string, deliberately
 *
 * A large `doubleArrayOf` literal compiles to *code* in Kotlin/Wasm; a string literal becomes a data
 * segment. Each weight is a fixed-point integer over [WEIGHT_SCALE], a power of two, so decoding is
 * one exact division and the same literal is the same model on both targets. The quantisation is
 * about `1.5e-5` on a weight of order one, which the trainer checks against the unquantised fit
 * rather than assuming.
 */
public class LearnedNet private constructor(
    public val inputs: Int,
    public val hiddenUnits: Int,
    /** Read live rather than copied — see [over]. */
    private val weights: DoubleArray,
) {
    /** What the output layer sums over: the hidden units, or the inputs when there are none. */
    public val outputSources: Int = if (hiddenUnits == 0) inputs else hiddenUnits

    private val sources = DoubleArray(outputSources)

    /**
     * `P(this slot wins)` for one feature row.
     *
     * Allocation-free and **not reentrant**: it writes a buffer of its own, so one instance belongs
     * to one bot in one slot, exactly as every other search buffer here does.
     */
    public fun value(features: DoubleArray): Double = forward(features, sources)

    /**
     * As [value], but writing what the output layer summed over into [into].
     *
     * The trainer's half of the seam. [into] holds [outputSources] entries; [slopeOf] is what turns
     * one of them into the derivative of the unit that produced it.
     */
    public fun forward(features: DoubleArray, into: DoubleArray): Double {
        require(features.size >= inputs) { "this model reads $inputs features, was handed ${features.size}" }
        require(into.size >= outputSources) { "the output layer sums $outputSources sources, was ${into.size}" }

        if (hiddenUnits == 0) {
            for (i in 0 until inputs) {
                into[i] = features[i]
            }
        } else {
            for (unit in 0 until hiddenUnits) {
                var sum = weights[hiddenBiasIndex(unit)]
                val base = unit * inputs
                for (i in 0 until inputs) {
                    sum += weights[base + i] * features[i]
                }
                into[unit] = sum / (1.0 + abs(sum))
            }
        }

        var logit = weights[outputBiasIndex]
        for (source in 0 until outputSources) {
            logit += weights[outputWeightIndex(source)] * into[source]
        }
        return logisticOf(logit)
    }

    /** How steeply the unit that produced [activation] responds — softsign's derivative, in its own output. */
    public fun slopeOf(activation: Double): Double {
        if (hiddenUnits == 0) {
            return 1.0
        }
        val slack = 1.0 - abs(activation)
        return slack * slack
    }

    public fun hiddenWeightIndex(unit: Int, input: Int): Int = unit * inputs + input

    public fun hiddenBiasIndex(unit: Int): Int = hiddenUnits * inputs + unit

    public fun outputWeightIndex(source: Int): Int =
        if (hiddenUnits == 0) source else hiddenUnits * inputs + hiddenUnits + source

    public val outputBiasIndex: Int = weightCountOf(inputs, hiddenUnits) - 1

    /** This model as a string literal a source file can hold — see the class KDoc on why not an array. */
    public fun encode(): String {
        val digits = StringBuilder()
        for (weight in weights) {
            val scaled = weight * WEIGHT_SCALE
            require(abs(scaled) < Int.MAX_VALUE.toDouble()) { "a weight of $weight does not fit the fixed point" }
            if (digits.isNotEmpty()) {
                digits.append(',')
            }
            // Round to nearest, halves away from zero, out of `+ - *` and a truncation, so that the
            // literal a trainer writes is the literal every target reads back.
            digits.append((if (scaled < 0.0) scaled - 0.5 else scaled + 0.5).toInt())
        }
        return "$FORMAT_VERSION|$inputs|$hiddenUnits|$digits"
    }

    override fun toString(): String = "LearnedNet($inputs->$hiddenUnits->1)"

    public companion object {
        /** How many weights a model of this shape holds — see [hiddenWeightIndex] for the layout. */
        public fun weightCountOf(inputs: Int, hiddenUnits: Int): Int =
            if (hiddenUnits == 0) inputs + 1 else hiddenUnits * (inputs + 2) + 1

        /**
         * A model reading [weights] **live**, so a trainer can step them in place between passes.
         *
         * The sharing is the point and it is why this is not a constructor: fitting allocates one
         * array and one model, and every pass over the corpus reads the weights the last step wrote.
         * [decode] owns its array instead, which is what a bot wants.
         */
        public fun over(inputs: Int, hiddenUnits: Int, weights: DoubleArray): LearnedNet {
            require(inputs > 0) { "a model reads at least one feature, was $inputs" }
            require(hiddenUnits >= 0) { "a hidden layer cannot have $hiddenUnits units" }
            require(weights.size == weightCountOf(inputs, hiddenUnits)) {
                "a $inputs->$hiddenUnits model holds ${weightCountOf(inputs, hiddenUnits)} weights, " +
                    "was handed ${weights.size}"
            }
            return LearnedNet(inputs, hiddenUnits, weights)
        }

        /** The inverse of [encode]. Bounded before it allocates, for SW-09's reason. */
        public fun decode(encoded: String): LearnedNet {
            val parts = encoded.split('|')
            require(parts.size == 4) { "a model literal is version|inputs|hidden|weights, was ${parts.size} fields" }
            require(parts[0] == FORMAT_VERSION.toString()) { "model literal version '${parts[0]}' is not supported" }

            val inputs = parts[1].toIntOrNull() ?: error("model literal input count '${parts[1]}' is not a number")
            val hiddenUnits =
                parts[2].toIntOrNull() ?: error("model literal hidden count '${parts[2]}' is not a number")
            require(inputs in 1..MAX_INPUTS) { "a model literal claims $inputs features" }
            require(hiddenUnits in 0..MAX_HIDDEN) { "a model literal claims $hiddenUnits hidden units" }

            val digits = parts[3].split(',')
            val expected = weightCountOf(inputs, hiddenUnits)
            require(digits.size == expected) {
                "a $inputs->$hiddenUnits model wants $expected weights, was ${digits.size}"
            }

            val weights = DoubleArray(expected) { i ->
                val fixed = digits[i].toIntOrNull() ?: error("model weight $i is not a whole number: '${digits[i]}'")
                fixed / WEIGHT_SCALE
            }
            return LearnedNet(inputs, hiddenUnits, weights)
        }

        /** Bumped only for a layout change; a decoder rejects what it does not recognise. */
        private const val FORMAT_VERSION = 1

        /**
         * A power of two, so `fixed / WEIGHT_SCALE` is exact and the same on every target.
         *
         * `2^16` puts the step at `1.5e-5`, which on a weight of order one is four decimal places
         * further than a value function can tell apart.
         */
        public const val WEIGHT_SCALE: Double = 65_536.0

        /** Bounds so a corrupt literal is refused before an array is sized from it — SW-09. */
        private const val MAX_INPUTS = 1_024
        private const val MAX_HIDDEN = 1_024

        /** Past this the logistic is flat to the last bit, and `1 + exp(-z)` starts losing precision. */
        private const val LOGIT_LIMIT = 30.0

        private fun logisticOf(logit: Double): Double {
            val bounded = if (logit > LOGIT_LIMIT) {
                LOGIT_LIMIT
            } else if (logit < -LOGIT_LIMIT) {
                -LOGIT_LIMIT
            } else {
                logit
            }
            return 1.0 / (1.0 + portableExp(-bounded))
        }
    }
}
