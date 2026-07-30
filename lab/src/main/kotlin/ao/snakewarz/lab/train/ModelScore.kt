package ao.snakewarz.lab.train

import ao.snakewarz.bots.search.learned.LearnedNet
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What a model that was fitted somewhere else is worth on a corpus it never saw.
 *
 * [ValueFit] reports a holdout number, which answers *"would more capacity buy anything"* — an
 * in-distribution question about the run that is happening. This answers a different one:
 * **does a fit transfer**. The two are not the same reading and P4 exists because they were
 * conflated: the `LearnedWeights.ENCODED` P4 replaced was fitted `--rows 12 --cols 12`, its train and
 * holdout losses agreed to five places, and the leaf still collapsed on a 20x20. A holdout drawn from
 * a one-board corpus cannot see that, because every row in it is the board the fit was taken on.
 *
 * So this scores a **whole** corpus rather than a split of one. Held-out games are how you read a fit
 * against its own data; a corpus of games the model has never met needs no split, and splitting one
 * would throw away seven eighths of the evidence for no gain.
 *
 * ### Why the calibration readings are here and not just the loss
 *
 * A log-loss is only comparable across two populations if the two are equally predictable, and they
 * are not: a longer game on a bigger board is decided later, so a mid-game position on a 20x20 is
 * intrinsically harder to call than one on an 8x8. [meanLabel] against [meanValue] is what separates
 * *the model has the wrong prior here* from *this board is harder*: a model whose answers average
 * 0.61 where the rows average 0.50 is mispriced, whatever its loss does.
 */
internal class ModelScore(
    val rows: Int,
    val loss: Double,
    val accuracy: Double,
    val spread: Double,
    val meanValue: Double,
    val meanLabel: Double,
) {
    override fun toString(): String =
        "loss %.5f, accuracy %.4f, spread %.4f, mean %.4f against %.4f".format(
            loss,
            accuracy,
            spread,
            meanValue,
            meanLabel,
        )
}

/**
 * Runs [net] over [rows] of [corpus], or over every row when [rows] is left off.
 *
 * See [ModelScore] on why a model fitted elsewhere is scored over everything rather than a split.
 * The subset is what [ValueFit] hands its own held-out rows through, which is the same arithmetic
 * asked of a fit that *did* see the corpus.
 */
internal fun scoreOf(corpus: Corpus, net: LearnedNet, rows: IntArray? = null, count: Int = -1): ModelScore {
    require(net.inputs == corpus.width) {
        "this model reads ${net.inputs} features and the corpus holds ${corpus.width}"
    }

    val scored = if (rows == null) {
        corpus.size
    } else if (count < 0) {
        rows.size
    } else {
        count
    }
    require(scored > 0) { "there is nothing to score" }

    val row = DoubleArray(corpus.width)
    val buffer = DoubleArray(net.outputSources)

    var loss = 0.0
    var right = 0.0
    var total = 0.0
    var squares = 0.0
    var labels = 0.0

    for (i in 0 until scored) {
        val at = rows?.get(i) ?: i
        corpus.features.copyInto(row, 0, at * corpus.width, (at + 1) * corpus.width)
        val value = net.forward(row, buffer)
        val label = corpus.labels[at]

        val p = value.coerceIn(FLOOR, 1.0 - FLOOR)
        loss += -(label * ln(p) + (1.0 - label) * ln(1.0 - p))
        right += when {
            label == 0.5 -> 0.5
            (value > 0.5) == (label > 0.5) -> 1.0
            else -> 0.0
        }
        total += value
        squares += value * value
        labels += label
    }

    val mean = total / scored
    return ModelScore(
        rows = scored,
        loss = loss / scored,
        accuracy = right / scored,
        spread = sqrt((squares / scored) - mean * mean),
        meanValue = mean,
        meanLabel = labels / scored,
    )
}

/**
 * [net] over [rows], split by the geometry each row was played on.
 *
 * The reading a corpus spanning several boards has to be read on, and the one nothing printed before
 * P4: a pooled loss over a mixture says a fit is good on the mixture and cannot say it is good
 * anywhere. Boards with no rows in the subset are left out rather than reported as zero.
 */
internal fun scoreByBoard(
    corpus: Corpus,
    net: LearnedNet,
    rows: IntArray? = null,
    count: Int = -1,
): List<Pair<String, ModelScore>> {
    val scored = if (rows == null) {
        corpus.size
    } else if (count < 0) {
        rows.size
    } else {
        count
    }
    val perBoard = corpus.boards.indices.map { mutableListOf<Int>() }
    for (i in 0 until scored) {
        val at = rows?.get(i) ?: i
        perBoard[corpus.board[at]].add(at)
    }
    return corpus.boards.indices
        .filter { perBoard[it].isNotEmpty() }
        .map { corpus.boards[it] to scoreOf(corpus, net, perBoard[it].toIntArray()) }
}

/** Keeps `ln` finite at a saturated prediction — [ValueFit] floors its own loss the same way. */
private const val FLOOR = 1e-12

/** Reads a literal as [LearnedNet.decode] wants it, tolerating the whitespace a file picks up. */
internal fun modelOf(literal: String): LearnedNet = LearnedNet.decode(literal.trim())
