package ao.snakewarz.lab.train

import ao.snakewarz.bots.search.learned.LearnedNet
import ao.snakewarz.core.random.SplitMix64
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * That the fit is a fit: it finds a rule it is shown, it finds one only a hidden layer can express,
 * and it lands in the same place twice from the same seed.
 *
 * These are checks on the **optimiser**, deliberately run against corpora whose answer is known
 * exactly, because the thing they are guarding is invisible on real data. A backward pass with the
 * wrong sign on one term, or a layout accessor pointing at the bias row, still produces a run whose
 * loss falls — just not as far as it should have — and on a corpus nobody knows the floor of, "not as
 * far as it should have" is indistinguishable from "the features are weak", which is the conclusion
 * this phase is otherwise trying to reach honestly.
 *
 * So each corpus below has a **Bayes floor**: the loss a model that knew the generating rule exactly
 * would still pay, because the labels are sampled. A fit that gets within a hundredth of it has the
 * gradient right.
 */
class ValueFitTest {
    @Test
    fun `it recovers a rule a linear model can express, down to the noise it was sampled with`() {
        val truth = doubleArrayOf(2.5, -1.5, 0.0, 0.75, -0.25)
        val corpus = corpusOf(rows = 40_000, seed = 11) { row -> logitOf(truth, row) }

        val fit =
            ValueFit(corpus, hiddenUnits = 0, epochs = 30, learningRate = 0.05, decay = 0.0, batch = 128, seed = 3)
        val loss = fit.run(report = 1000) { }
        val floor = bayesFloor(corpus) { row -> logitOf(truth, row) }

        assertTrue(loss < floor + 0.01, "log-loss $loss against a floor of $floor")

        // And the coefficients are the ones it was shown, not merely some rule with the same loss.
        val model = LearnedNet.over(corpus.width, 0, fit.weights())
        val weights = fit.weights()
        for (i in truth.indices) {
            val learned = weights[model.outputWeightIndex(i)]
            assertTrue(
                kotlin.math.abs(learned - truth[i]) < 0.15,
                "coefficient $i came back as $learned against ${truth[i]}",
            )
        }
    }

    @Test
    fun `a hidden layer finds a gate the linear model cannot`() {
        // The rule the hidden layer exists for, and the shape ChamberEval spends a branch on: this
        // reading matters only when that one says the snakes are separated. A product of two inputs
        // has no linear expression at all, so the two fits below are a capacity comparison rather
        // than a tuning one.
        val corpus = corpusOf(rows = 40_000, seed = 12) { row -> 6.0 * row[0] * row[1] }

        val linear =
            ValueFit(corpus, hiddenUnits = 0, epochs = 20, learningRate = 0.05, decay = 0.0, batch = 128, seed = 5)
                .run(report = 1000) { }
        val gated =
            ValueFit(corpus, hiddenUnits = 8, epochs = 40, learningRate = 0.05, decay = 0.0, batch = 128, seed = 5)
                .run(report = 1000) { }
        val floor = bayesFloor(corpus) { row -> 6.0 * row[0] * row[1] }

        assertTrue(linear > floor + 0.15, "the linear model should be lost here, and scored $linear")
        assertTrue(gated < linear - 0.1, "the hidden layer scored $gated against the linear $linear")
    }

    @Test
    fun `the same seed fits the same model`() {
        // A training run has to be reproducible from its seed, exactly as a match is -- SW-01 applies
        // to the tool that produces a bot's constants as much as to the bot.
        val corpus = corpusOf(rows = 4_000, seed = 13) { row -> 3.0 * row[0] - row[2] }

        val first =
            ValueFit(corpus, hiddenUnits = 4, epochs = 6, learningRate = 0.05, decay = 0.0, batch = 64, seed = 7)
        first.run(report = 1000) { }
        val second =
            ValueFit(corpus, hiddenUnits = 4, epochs = 6, learningRate = 0.05, decay = 0.0, batch = 64, seed = 7)
        second.run(report = 1000) { }

        assertContentEquals(first.weights(), second.weights())
    }

    // -- internals

    /**
     * Rows drawn uniformly, labelled by sampling the logistic of [logit].
     *
     * Sampled rather than set to the probability itself, because a corpus of real positions carries
     * a *win or a loss* and never a probability — so the floor a fit can reach is the entropy of the
     * rule, and a test that handed the probabilities over would be measuring a regression this
     * trainer does not do.
     */
    private fun corpusOf(rows: Int, seed: Long, logit: (DoubleArray) -> Double): Corpus {
        val rng = SplitMix64(seed)
        val features = DoubleArray(rows * WIDTH)
        val labels = DoubleArray(rows)
        val group = IntArray(rows)
        val row = DoubleArray(WIDTH)

        for (i in 0 until rows) {
            for (j in 0 until WIDTH) {
                row[j] = rng.nextDouble() * 2.0 - 1.0
            }
            row.copyInto(features, i * WIDTH)
            labels[i] = if (rng.nextDouble() < logisticOf(logit(row))) 1.0 else 0.0
            group[i] = i / ROWS_PER_GROUP
        }
        return Corpus(rows, WIDTH, features, labels, group, rows / ROWS_PER_GROUP, 0, rows)
    }

    /** The log-loss a model that knew the rule exactly would still pay on this sample. */
    private fun bayesFloor(corpus: Corpus, logit: (DoubleArray) -> Double): Double {
        val row = DoubleArray(corpus.width)
        var total = 0.0
        for (i in 0 until corpus.size) {
            corpus.features.copyInto(row, 0, i * corpus.width, (i + 1) * corpus.width)
            val p = logisticOf(logit(row))
            total += -(p * ln(p) + (1.0 - p) * ln(1.0 - p))
        }
        return total / corpus.size
    }

    private fun logitOf(weights: DoubleArray, row: DoubleArray): Double {
        var sum = 0.0
        for (i in weights.indices) {
            sum += weights[i] * row[i]
        }
        return sum
    }

    private fun logisticOf(logit: Double): Double = 1.0 / (1.0 + kotlin.math.exp(-logit))

    private companion object {
        const val WIDTH = 5

        /** Rows to a synthetic "match", so the holdout split has whole groups to hold out. */
        const val ROWS_PER_GROUP = 20
    }
}
