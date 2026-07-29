package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.bots.search.learned.LearnedNet
import ao.snakewarz.bots.search.learned.PositionFeatures
import ao.snakewarz.lab.train.ValueFit
import ao.snakewarz.lab.train.corpusFrom
import ao.snakewarz.lab.train.logDirectoriesUnder
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.math.abs

/**
 * Fits the value function `puct:eval=learned` plays, out of positions replayed from the match log.
 *
 * The one subcommand here that produces **source** rather than a number, and it is deliberately
 * kept to producing a *literal*: it prints what `LearnedWeights.ENCODED` should be and never edits
 * it, for the reason `tune` and `spsa` never edit a default. Adopting a fit changes how the bot
 * plays every game, which is a decision a person makes with a field rating in hand.
 *
 * ### Every number it prints is a held-out one
 *
 * The split is by **game**, not by position — consecutive positions of a match differ by one move
 * and share a label, so a row-wise split would report the training loss under another name. What to
 * read: the holdout log-loss against `0.693`, which is what a model that always answers even scores,
 * and the gap between the training and holdout columns, which is what says whether more capacity
 * would buy anything.
 *
 * ### The literal is checked, not assumed
 *
 * Weights are quantised on the way into the string, so the model a bot plays is not bit-for-bit the
 * model that was fitted. The run re-scores the **decoded** literal over the same held-out games and
 * prints both, so a quantisation that mattered would be visible rather than arriving later as a bot
 * that is slightly worse than its own training run said.
 */
internal class TrainCommand(
    val logDirectories: List<Path>,
    val rows: Int?,
    val cols: Int?,
    val stride: Int,
    val positions: Int,
    val hiddenUnits: Int,
    val epochs: Int,
    val learningRate: Double,
    val decay: Double,
    val batch: Int,
    val seed: Long,
    val out: Path?,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val directories = logDirectories.flatMap { logDirectoriesUnder(it) }.distinct()
        require(directories.isNotEmpty()) { "no replays under ${logDirectories.joinToString()}" }

        val corpus = corpusFrom(directories, rows, cols, stride, positions, seed, log)
        log(
            "[train] ${corpus.size} rows from ${corpus.matches} matches " +
                "(${corpus.positionsSeen} positions kept, ${corpus.drawn} matches drawn)",
        )
        require(corpus.size > 0) { "every replay was filtered out -- check --rows/--cols against the log" }

        val even = corpus.labels.count { it > 0.5 }.toDouble() / corpus.size
        log("[train] ${"%.3f".format(even)} of rows are a slot that went on to win")

        val fit = ValueFit(corpus, hiddenUnits, epochs, learningRate, decay, batch, seed)
        log("[train] $fit, ${fit.trainingRows} training rows and ${fit.holdoutRows} held out")

        fit.run(report = maxOf(1, epochs / EPOCH_LINES), log = log)

        val weights = fit.weights()
        require(ValueFit.withinFixedPoint(weights)) {
            "a fitted weight is too large for the fixed-point literal -- raise the decay and re-run"
        }

        // Scored through the literal rather than through the fit, because quantisation is the one
        // step between the two and a run that reported only the fitted number would leave it
        // unmeasured until a bot played slightly worse than its own training run said it would.
        val fitted = fit.holdoutLoss()
        val baked = LearnedNet.decode(fit.model.encode())
        val quantised = fit.holdoutLossOf(baked)

        log("")
        log("[train] holdout log-loss ${"%.5f".format(fitted)} fitted, ${"%.5f".format(quantised)} quantised")
        log("[train] against ${"%.5f".format(EVEN_LOSS)} for a model that always answers even")
        log("[train] holdout accuracy ${"%.4f".format(fit.accuracy())}")
        log("[train] holdout answers spread ${"%.4f".format(fit.spread())} either side of even")

        if (hiddenUnits == 0) {
            reportCoefficients(weights, baked, log)
        }

        val encoded = baked.encode()
        log("")
        log("[train] ${encoded.length} characters, ${weights.size} weights")

        // Wrapped into concatenated chunks because the literal is far past the 120-column gate and
        // nothing can break a string for you. Constant folding puts it back together, so what the
        // bundle carries is still one data segment -- and a re-fit then diffs line by line instead
        // of as one changed line three kilobytes long.
        log("    const val ENCODED: String =")
        val chunks = encoded.chunked(LITERAL_WIDTH)
        for ((i, chunk) in chunks.withIndex()) {
            log("        \"$chunk\"${if (i == chunks.size - 1) "" else " +"}")
        }

        if (out != null) {
            out.writeText(encoded)
            log("[train] written to $out")
        }
        log("[train] a fit is an attempt, not a finding -- rate it against eval=chamber over a field")
    }

    override fun toString(): String =
        "Train(${logDirectories.joinToString()}, $hiddenUnits hidden, $epochs epochs, seed $seed)"

    /**
     * The weights beside the readings they price, largest first.
     *
     * Only for a model with no hidden layer, where a coefficient *is* the reading's worth. Under a
     * hidden layer the same number is one path of many into the answer and printing it would invite
     * exactly the reading it cannot support.
     */
    private fun reportCoefficients(weights: DoubleArray, model: LearnedNet, log: (String) -> Unit) {
        log("")
        log("[train] what it learned each reading is worth:")

        val order = (0 until model.inputs).sortedByDescending { abs(weights[model.outputWeightIndex(it)]) }
        for (i in order) {
            log("[train]   %+8.3f  %s".format(weights[model.outputWeightIndex(i)], PositionFeatures.NAMES[i]))
        }
        log("[train]   %+8.3f  (bias)".format(weights[model.outputBiasIndex]))
    }

    internal companion object {
        /**
         * Every default below is the setting the shipped `LearnedWeights` was fitted at.
         *
         * The same trick the evaluation itself is built on: a run with no flags reproduces what is
         * in the tree, so a run *with* one is a run about that flag. `--hidden 0` is the control the
         * hidden layer had to beat, and it is a flag rather than the default for exactly that reason.
         */
        const val DEFAULT_STRIDE: Int = 14

        /** Rows, not matches: enough to fit a few hundred weights and small enough to hold in memory. */
        const val DEFAULT_POSITIONS: Int = 600_000

        const val DEFAULT_EPOCHS: Int = 60
        const val DEFAULT_HIDDEN: Int = 16
        const val DEFAULT_RATE: Double = 0.01
        const val DEFAULT_DECAY: Double = 1e-5
        const val DEFAULT_BATCH: Int = 256

        /** How many progress lines a run prints, whatever `--epochs` it was given. */
        private const val EPOCH_LINES = 15

        /** Characters of literal per source line, inside the 120-column gate at eight of indent. */
        private const val LITERAL_WIDTH = 100

        /** `-ln(0.5)`, the log-loss of a model that has learned nothing at all. */
        private const val EVEN_LOSS = 0.6931471805599453
    }
}
