package ao.snakewarz.lab.train

import ao.snakewarz.bots.search.learned.LearnedNet
import ao.snakewarz.core.random.SplitMix64
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Fits a [LearnedNet] to a [Corpus] by Adam, and reports whether the fit went anywhere.
 *
 * ### The forward pass is the bot's, not a copy of it
 *
 * `:lab` cannot see `:bots`' internals, so the tempting shape is a second implementation of the
 * model here and a string handed over at the end. That is the failure the whole design is arranged
 * against: a duplicate that drifts by one term produces a bot which is merely mediocre, and nothing
 * fails. So every value here comes from [LearnedNet.forward] — the same code the leaf runs — and the
 * only thing this file adds is the **backward** pass, which is derived from two things the net
 * exposes rather than from anything restated: [LearnedNet.slopeOf] for the activation, and the
 * layout accessors for where a weight lives.
 *
 * The output layer needs no derivative at all. Log-loss over a logistic gives `dL/dz = p − y`
 * exactly, whatever the logistic is implemented with, which is the other half of why the squash
 * belongs in the loss rather than online.
 *
 * ### Held out by game
 *
 * Consecutive positions of a match differ by one move and share a label, so a row-wise split reports
 * the training loss under another name. [Corpus.group] is the match, and the split is on it.
 */
internal class ValueFit(
    private val corpus: Corpus,
    private val hiddenUnits: Int,
    private val epochs: Int,
    private val learningRate: Double,
    private val decay: Double,
    private val batch: Int,
    seed: Long,
) {
    private val width = corpus.width
    private val weights = DoubleArray(LearnedNet.weightCountOf(width, hiddenUnits))
    private val net = LearnedNet.over(width, hiddenUnits, weights)

    private val gradient = DoubleArray(weights.size)
    private val moment = DoubleArray(weights.size)
    private val velocity = DoubleArray(weights.size)
    private val activations = DoubleArray(net.outputSources)
    private val row = DoubleArray(width)

    private val rng = SplitMix64(seed)
    private val training = IntArray(corpus.size)
    private var trainingSize = 0
    private val holdout = IntArray(corpus.size)
    private var holdoutSize = 0

    init {
        require(corpus.size > 0) { "there is nothing to fit" }

        for (i in 0 until corpus.size) {
            if (corpus.group[i] % HOLDOUT_IN == 0) {
                holdout[holdoutSize++] = i
            } else {
                training[trainingSize++] = i
            }
        }
        require(trainingSize > 0 && holdoutSize > 0) { "the corpus holds too few games to split" }

        // Uniform over a fan-in scale: large enough that the softsign units start apart, small
        // enough that none of them starts saturated, where its slope is nearly zero and Adam would
        // need a hundred epochs to pull it back.
        val spread = 1.0 / sqrt(width.toDouble())
        if (hiddenUnits > 0) {
            for (unit in 0 until hiddenUnits) {
                for (input in 0 until width) {
                    weights[net.hiddenWeightIndex(unit, input)] = (rng.nextDouble() * 2.0 - 1.0) * spread
                }
            }
        }
        for (source in 0 until net.outputSources) {
            weights[net.outputWeightIndex(source)] = (rng.nextDouble() * 2.0 - 1.0) * spread
        }
    }

    /** The model as it stands. Reads the weights live, so it moves with every [run]. */
    val model: LearnedNet get() = net

    val trainingRows: Int get() = trainingSize

    val holdoutRows: Int get() = holdoutSize

    /**
     * Runs every epoch, handing [log] a line per [report] of them.
     *
     * The loss reported is over the held-out games and is the number a run is read on; the training
     * loss is beside it because the *gap* between them is what says whether more capacity would help
     * or is already being spent on memorising games.
     */
    fun run(report: Int, log: (String) -> Unit): Double {
        log("[train] epoch  train    holdout  holdout accuracy")

        var step = 0
        log(
            "[train] %5d  %.5f  %.5f  %.4f".format(
                0,
                lossOver(training, trainingSize),
                lossOver(holdout, holdoutSize),
                accuracy(),
            ),
        )

        for (epoch in 1..epochs) {
            shuffle(training, trainingSize)

            // Wound down to nothing across the run. Without it the answer is whichever epoch the
            // run happened to stop on, and a noisy objective visits its best-looking point by
            // construction -- the same reason `Spsa` averages a tail rather than keeping its best
            // iterate. With it the last epochs barely move and the final weights are the answer.
            val rate = learningRate * (1.0 - (epoch - 1).toDouble() / epochs)

            var at = 0
            while (at < trainingSize) {
                val end = minOf(at + batch, trainingSize)
                gradient.fill(0.0)
                for (i in at until end) {
                    accumulate(training[i])
                }
                step++
                apply(step, (end - at).toDouble(), rate)
                at = end
            }

            // The best epoch is deliberately not tracked. A search over a noisy objective visits its
            // best-looking point by construction, so the minimum of a trajectory is a statement about
            // the noise -- `Spsa` refuses its own best iterate for the same reason, and the wound-down
            // rate above is what makes the last epoch the honest answer instead.
            if (epoch % report == 0 || epoch == epochs) {
                log(
                    "[train] %5d  %.5f  %.5f  %.4f".format(
                        epoch,
                        lossOver(training, trainingSize),
                        lossOver(holdout, holdoutSize),
                        accuracy(),
                    ),
                )
            }
        }
        return lossOver(holdout, holdoutSize)
    }

    /** Log-loss over the held-out games, which is the number the run is judged on. */
    fun holdoutLoss(): Double = lossOver(holdout, holdoutSize)

    /**
     * The held-out games split by the board they were played on, for [model] or another.
     *
     * A pooled holdout over a corpus spanning several geometries is the reading that hid the shipped
     * fit's board dependence: it says the model serves the *mixture*, which is not a claim about any
     * board in it. [Corpus] carries why.
     */
    fun holdoutByBoard(other: LearnedNet = net): List<Pair<String, ModelScore>> =
        scoreByBoard(corpus, other, holdout, holdoutSize)

    /** The same, for a model that is not this one — what the quantised literal is checked with. */
    fun holdoutLossOf(other: LearnedNet): Double {
        val buffer = DoubleArray(other.outputSources)
        var total = 0.0
        for (i in 0 until holdoutSize) {
            val at = holdout[i]
            corpus.features.copyInto(row, 0, at * width, (at + 1) * width)
            total += lossAt(other.forward(row, buffer), corpus.labels[at])
        }
        return total / holdoutSize
    }

    /** How often the model is on the right side of even, over the held-out games. */
    fun accuracy(): Double {
        var right = 0.0
        for (i in 0 until holdoutSize) {
            val at = holdout[i]
            corpus.features.copyInto(row, 0, at * width, (at + 1) * width)
            val value = net.forward(row, activations)
            val label = corpus.labels[at]
            right += when {
                label == 0.5 -> 0.5
                (value > 0.5) == (label > 0.5) -> 1.0
                else -> 0.0
            }
        }
        return right / holdoutSize
    }

    /**
     * How far the model's answers spread, over the held-out games.
     *
     * A calibrated value function is not automatically a useful *ranking* function, and this is the
     * reading that says which one has been fitted: `PuctTree` compares sibling leaves against an
     * exploration term whose constant was swept against the spread `ChamberEval` produces, so a
     * model that is right on average and answers `0.5 ± 0.05` everywhere hands the search a
     * near-uniform signal however good its log-loss is.
     */
    fun spread(): Double {
        var total = 0.0
        var squares = 0.0
        for (i in 0 until holdoutSize) {
            val at = holdout[i]
            corpus.features.copyInto(row, 0, at * width, (at + 1) * width)
            val value = net.forward(row, activations)
            total += value
            squares += value * value
        }
        val mean = total / holdoutSize
        return sqrt((squares / holdoutSize) - mean * mean)
    }

    /** The fitted weights, in [LearnedNet]'s layout. A copy, so a caller cannot move the model. */
    fun weights(): DoubleArray = weights.copyOf()

    override fun toString(): String = "ValueFit($width->$hiddenUnits, $trainingSize rows)"

    // -- internals

    private fun accumulate(at: Int) {
        corpus.features.copyInto(row, 0, at * width, (at + 1) * width)
        val value = net.forward(row, activations)

        // Log-loss over a logistic: the derivative at the logit is the residual and nothing else.
        val residual = value - corpus.labels[at]

        for (source in 0 until net.outputSources) {
            gradient[net.outputWeightIndex(source)] += residual * activations[source]
        }
        gradient[net.outputBiasIndex] += residual

        if (hiddenUnits == 0) {
            return
        }
        for (unit in 0 until hiddenUnits) {
            val through = residual * weights[net.outputWeightIndex(unit)] * net.slopeOf(activations[unit])
            if (through == 0.0) {
                continue
            }
            val base = net.hiddenWeightIndex(unit, 0)
            for (input in 0 until width) {
                gradient[base + input] += through * row[input]
            }
            gradient[net.hiddenBiasIndex(unit)] += through
        }
    }

    /**
     * One Adam step over the batch just accumulated.
     *
     * The two biases are left out of the weight decay: the output bias is the model's prior on how
     * often the slot it is reading wins at all, and shrinking that toward zero is shrinking a
     * calibration toward a claim nobody makes.
     */
    private fun apply(step: Int, batchSize: Double, rate: Double) {
        val correctionOne = 1.0 - powOf(BETA_ONE, step)
        val correctionTwo = 1.0 - powOf(BETA_TWO, step)

        for (i in gradient.indices) {
            var grad = gradient[i] / batchSize
            if (decay != 0.0 && i != net.outputBiasIndex && !isHiddenBias(i)) {
                grad += decay * weights[i]
            }

            moment[i] = BETA_ONE * moment[i] + (1.0 - BETA_ONE) * grad
            velocity[i] = BETA_TWO * velocity[i] + (1.0 - BETA_TWO) * grad * grad

            val m = moment[i] / correctionOne
            val v = velocity[i] / correctionTwo
            weights[i] -= rate * m / (sqrt(v) + EPSILON)
        }
    }

    private fun isHiddenBias(index: Int): Boolean =
        hiddenUnits > 0 && index >= net.hiddenBiasIndex(0) && index < net.hiddenBiasIndex(0) + hiddenUnits

    private fun lossOver(rows: IntArray, count: Int): Double {
        var total = 0.0
        for (i in 0 until count) {
            val at = rows[i]
            corpus.features.copyInto(row, 0, at * width, (at + 1) * width)
            total += lossAt(net.forward(row, activations), corpus.labels[at])
        }
        return total / count
    }

    private fun lossAt(value: Double, label: Double): Double {
        val p = value.coerceIn(FLOOR, 1.0 - FLOOR)
        return -(label * ln(p) + (1.0 - label) * ln(1.0 - p))
    }

    private fun shuffle(order: IntArray, count: Int) {
        for (i in count - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val swap = order[i]
            order[i] = order[j]
            order[j] = swap
        }
    }

    /** `base^step` by squaring — Adam's bias correction, and the one power this file needs. */
    private fun powOf(base: Double, step: Int): Double {
        var result = 1.0
        var factor = base
        var remaining = step
        while (remaining > 0) {
            if (remaining and 1 == 1) {
                result *= factor
            }
            factor *= factor
            remaining = remaining shr 1
        }
        return result
    }

    internal companion object {
        /** One game in this many is held out, whole, so a split never cuts inside a match. */
        const val HOLDOUT_IN = 8

        private const val BETA_ONE = 0.9
        private const val BETA_TWO = 0.999
        private const val EPSILON = 1e-8

        /** Keeps `ln` finite at a saturated prediction, where the logistic really does answer zero. */
        private const val FLOOR = 1e-12

        /** How far a weight may sit from zero before the fixed-point literal cannot carry it. */
        fun withinFixedPoint(weights: DoubleArray): Boolean =
            weights.all { abs(it) * LearnedNet.WEIGHT_SCALE < Int.MAX_VALUE.toDouble() }
    }
}
