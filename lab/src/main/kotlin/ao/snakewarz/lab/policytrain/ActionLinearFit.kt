package ao.snakewarz.lab.policytrain

import ao.snakewarz.bots.reactive.policy.ActionFeatures
import ao.snakewarz.bots.reactive.policy.ActionModel
import ao.snakewarz.core.grid.Direction
import kotlin.math.exp
import kotlin.math.ln

/** One expert-labelled choice position, with one shared-schema row per direction. */
internal class ActionExample(
    val dataset: String,
    val map: String,
    val phase: String,
    val block: String,
    val replay: String,
    val turnIndex: Int,
    val legalBits: Int,
    val target: Int,
    val cartographerMaxima: Int,
    val features: DoubleArray,
) {
    val input: ActionInputKey = ActionInputKey(legalBits, features)

    init {
        require(features.size == Direction.entries.size * ActionFeatures.LENGTH) {
            "an action example needs ${Direction.entries.size * ActionFeatures.LENGTH} values, " +
                "was ${features.size}"
        }
        require(legalBits and (1 shl target) != 0) { "target ${Direction.entries[target]} is not legal" }
    }
}

/** Exact model-input identity; equality, rather than a hash alone, keeps split leakage observable. */
internal class ActionInputKey(private val legalBits: Int, features: DoubleArray) {
    private val inputs = IntArray(features.size) { index -> ActionModel.quantize(features[index]) }

    override fun equals(other: Any?): Boolean =
        other is ActionInputKey && legalBits == other.legalBits && inputs.contentEquals(other.inputs)

    override fun hashCode(): Int = 31 * legalBits + inputs.contentHashCode()
}

/** A fitted shared action scorer and the validation choice that selected it. */
internal class ActionLinearResult(
    val weights: DoubleArray,
    val l2: Double,
    val trainingLoss: Double,
    val validationLoss: Double,
)

/**
 * Fits one shared linear score over every legal action with multiclass softmax loss.
 *
 * The API deliberately has no holdout argument. Hyperparameter selection therefore cannot inspect
 * a primary holdout accidentally; the command loads that role only after this returns.
 */
internal fun fitActionLinear(
    training: List<ActionExample>,
    validation: List<ActionExample>,
    l2Candidates: List<Double>,
    epochs: Int,
    learningRate: Double,
): ActionLinearResult {
    require(training.isNotEmpty()) { "action training corpus is empty" }
    require(validation.isNotEmpty()) { "action validation corpus is empty" }
    require(l2Candidates.isNotEmpty()) { "--l2 names no candidates" }
    require(l2Candidates.all { it >= 0.0 && it.isFinite() }) { "--l2 values must be finite and non-negative" }
    require(epochs > 0) { "--epochs must be positive, was $epochs" }
    require(learningRate > 0.0 && learningRate.isFinite()) {
        "--rate must be finite and positive, was $learningRate"
    }

    var best: ActionLinearResult? = null
    for (l2 in l2Candidates) {
        val weights = DoubleArray(ActionFeatures.LENGTH)
        val gradient = DoubleArray(ActionFeatures.LENGTH)
        repeat(epochs) {
            gradient.fill(0.0)
            for (example in training) {
                accumulateGradient(example, weights, gradient)
            }
            val scale = 1.0 / training.size
            for (feature in weights.indices) {
                weights[feature] -= learningRate * (gradient[feature] * scale + l2 * weights[feature])
            }
        }

        val candidate = ActionLinearResult(
            weights = weights,
            l2 = l2,
            trainingLoss = actionLogLoss(training, weights),
            validationLoss = actionLogLoss(validation, weights),
        )
        val incumbent = best
        if (incumbent == null || candidate.validationLoss < incumbent.validationLoss) {
            best = candidate
        }
    }
    return checkNotNull(best)
}

internal fun actionLogLoss(examples: List<ActionExample>, weights: DoubleArray): Double {
    require(examples.isNotEmpty()) { "cannot score an empty action corpus" }
    var loss = 0.0
    for (example in examples) {
        val scores = scoresOf(example, weights)
        var maximum = Double.NEGATIVE_INFINITY
        for (direction in Direction.entries) {
            if (example.legalBits and (1 shl direction.ordinal) != 0 && scores[direction.ordinal] > maximum) {
                maximum = scores[direction.ordinal]
            }
        }

        var denominator = 0.0
        for (direction in Direction.entries) {
            if (example.legalBits and (1 shl direction.ordinal) != 0) {
                denominator += exp(scores[direction.ordinal] - maximum)
            }
        }
        val targetProbability = exp(scores[example.target] - maximum) / denominator
        loss -= ln(targetProbability.coerceAtLeast(MINIMUM_PROBABILITY))
    }
    return loss / examples.size
}

/** Directions tied at the model's raw maximum, before any deployment tie-break. */
internal fun actionMaxima(example: ActionExample, weights: DoubleArray): Int {
    val scores = scoresOf(example, weights)
    var maxima = 0
    var best = Double.NEGATIVE_INFINITY
    for (direction in Direction.entries) {
        val ordinal = direction.ordinal
        if (example.legalBits and (1 shl ordinal) == 0) {
            continue
        }
        when {
            scores[ordinal] > best -> {
                best = scores[ordinal]
                maxima = 1 shl ordinal
            }

            scores[ordinal] == best -> maxima = maxima or (1 shl ordinal)
        }
    }
    return maxima
}

/** Quantises fitted coefficients into the exact portable model used online. */
internal fun quantizeActionLinear(weights: DoubleArray): ActionModel {
    require(weights.size == ActionFeatures.LENGTH) {
        "${ActionFeatures.SCHEMA} needs ${ActionFeatures.LENGTH} weights, was ${weights.size}"
    }
    val fixed = IntArray(weights.size) { input ->
        val scaled = weights[input] * MODEL_WEIGHT_SCALE
        require(kotlin.math.abs(scaled) <= ActionModel.MAX_WEIGHT.toDouble()) {
            "weight $input does not fit ActionModel: ${weights[input]}"
        }
        (if (scaled < 0.0) scaled - 0.5 else scaled + 0.5).toInt()
    }
    return ActionModel.linear(ActionFeatures.SCHEMA, fixed)
}

/** Directions tied under the quantised model's exact Q8 score. */
internal fun actionMaxima(example: ActionExample, model: ActionModel): Int {
    val row = IntArray(ActionFeatures.LENGTH)
    var maxima = 0
    var best = Long.MIN_VALUE
    for (direction in Direction.entries) {
        val ordinal = direction.ordinal
        if (example.legalBits and (1 shl ordinal) == 0) {
            continue
        }
        val offset = ordinal * ActionFeatures.LENGTH
        for (feature in row.indices) {
            row[feature] = ActionModel.quantize(example.features[offset + feature])
        }
        val score = model.scoreQ8(row)
        when {
            score > best -> {
                best = score
                maxima = 1 shl ordinal
            }

            score == best -> maxima = maxima or (1 shl ordinal)
        }
    }
    return maxima
}

private fun accumulateGradient(example: ActionExample, weights: DoubleArray, gradient: DoubleArray) {
    val scores = scoresOf(example, weights)
    var maximum = Double.NEGATIVE_INFINITY
    for (direction in Direction.entries) {
        val ordinal = direction.ordinal
        if (example.legalBits and (1 shl ordinal) != 0 && scores[ordinal] > maximum) {
            maximum = scores[ordinal]
        }
    }

    var denominator = 0.0
    for (direction in Direction.entries) {
        val ordinal = direction.ordinal
        if (example.legalBits and (1 shl ordinal) != 0) {
            scores[ordinal] = exp(scores[ordinal] - maximum)
            denominator += scores[ordinal]
        }
    }

    for (direction in Direction.entries) {
        val ordinal = direction.ordinal
        if (example.legalBits and (1 shl ordinal) == 0) {
            continue
        }
        val error = scores[ordinal] / denominator - if (ordinal == example.target) 1.0 else 0.0
        val offset = ordinal * ActionFeatures.LENGTH
        for (feature in weights.indices) {
            val input = ActionModel.quantize(example.features[offset + feature]).toDouble() /
                ActionModel.FEATURE_SCALE
            gradient[feature] += error * input
        }
    }
}

private fun scoresOf(example: ActionExample, weights: DoubleArray): DoubleArray {
    require(weights.size == ActionFeatures.LENGTH) {
        "${ActionFeatures.SCHEMA} needs ${ActionFeatures.LENGTH} weights, was ${weights.size}"
    }
    val scores = DoubleArray(Direction.entries.size)
    for (direction in Direction.entries) {
        val ordinal = direction.ordinal
        if (example.legalBits and (1 shl ordinal) == 0) {
            continue
        }
        val offset = ordinal * ActionFeatures.LENGTH
        var score = 0.0
        for (feature in weights.indices) {
            score += weights[feature] *
                (ActionModel.quantize(example.features[offset + feature]).toDouble() / ActionModel.FEATURE_SCALE)
        }
        scores[ordinal] = score
    }
    return scores
}

private const val MODEL_WEIGHT_SCALE = 65_536.0
private const val MINIMUM_PROBABILITY = 1e-300
