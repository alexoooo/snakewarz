package ao.snakewarz.bots.reactive.policy

/**
 * A portable linear score over one candidate move's bounded action features.
 *
 * Features are rounded onto a signed Q8 grid before they meet integer weights. The resulting [Long]
 * is compared directly between legal moves: no probability, exponential, logarithm, or floating-point
 * tie boundary enters online inference. A model literal carries the exact feature-schema fingerprint,
 * so changing the meaning or order of a column makes [decode] fail instead of silently weakening play.
 *
 * The literal reserves a hidden-width field but this implementation accepts zero only. Adding an
 * integer hidden activation is therefore possible without making old linear literals ambiguous, while
 * the first experiment stays the smallest model its gate calls for.
 */
public class ActionModel private constructor(
    public val schema: String,
    public val inputs: Int,
    private val weights: IntArray,
) {
    /**
     * Scores [features] after deterministic Q8 rounding.
     *
     * Allocation-free. Extra entries are ignored so a bot may reuse a board-sized work array.
     */
    public fun score(features: DoubleArray): Long {
        require(features.size >= inputs) { "this model reads $inputs features, was handed ${features.size}" }

        var sum = 0L
        for (input in 0 until inputs) {
            sum += weights[input].toLong() * quantize(features[input])
        }
        return sum
    }

    /**
     * Scores features already represented on the signed Q8 grid.
     *
     * Allocation-free. This is the trainer-facing seam for checking that quantisation preserves the
     * ordering measured before a literal is adopted.
     */
    public fun scoreQ8(features: IntArray): Long {
        require(features.size >= inputs) { "this model reads $inputs features, was handed ${features.size}" }

        var sum = 0L
        for (input in 0 until inputs) {
            val feature = features[input]
            require(feature >= -FEATURE_SCALE && feature <= FEATURE_SCALE) {
                "feature $input is outside signed Q8: $feature"
            }
            sum += weights[input].toLong() * feature
        }
        return sum
    }

    /** This model as a checked, versioned source literal. */
    public fun encode(): String {
        val digits = StringBuilder()
        for (weight in weights) {
            if (digits.isNotEmpty()) {
                digits.append(',')
            }
            digits.append(weight)
        }
        return "$FORMAT_VERSION|$schema|$inputs|$LINEAR_HIDDEN_UNITS|$digits"
    }

    override fun toString(): String = "ActionModel($schema:$inputs->1)"

    public companion object {
        /** The exact integer value representing `1.0` in an input row. */
        public const val FEATURE_SCALE: Int = 256

        /** Coefficient bound used to prove that every supported model accumulates safely in a [Long]. */
        public const val MAX_WEIGHT: Int = 16_777_215

        /** Feature-count bound checked before a decoder allocates its coefficient array. */
        public const val MAX_INPUTS: Int = 256

        /**
         * Builds an immutable linear model with one coefficient per schema column.
         *
         * There is deliberately no intercept: it would be identical for every candidate action in
         * one position, so it cancels from both softmax fitting and online argmax.
         */
        public fun linear(schema: String, featureWeights: IntArray): ActionModel {
            validateSchema(schema)
            require(featureWeights.size in 1..MAX_INPUTS) {
                "a model reads 1..$MAX_INPUTS features, was ${featureWeights.size}"
            }
            for (input in featureWeights.indices) {
                require(featureWeights[input] in -MAX_WEIGHT..MAX_WEIGHT) {
                    "weight $input is outside ${-MAX_WEIGHT}..$MAX_WEIGHT: ${featureWeights[input]}"
                }
            }
            return ActionModel(schema, featureWeights.size, featureWeights.copyOf())
        }

        /**
         * Decodes a literal only when it names the feature schema and column count compiled beside it.
         * Bounds that protect allocations run before the coefficient array is created.
         */
        public fun decode(encoded: String, expectedSchema: String, expectedInputs: Int): ActionModel {
            validateSchema(expectedSchema)
            require(expectedInputs in 1..MAX_INPUTS) {
                "the expected schema has $expectedInputs features"
            }
            require(encoded.length <= MAX_LITERAL_LENGTH) { "an action-model literal is too long" }

            val parts = encoded.split('|')
            require(parts.size == FIELD_COUNT) {
                "an action-model literal is version|schema|inputs|hidden|weights, was ${parts.size} fields"
            }
            require(parts[0] == FORMAT_VERSION.toString()) {
                "action-model literal version '${parts[0]}' is not supported"
            }
            require(parts[1] == expectedSchema) {
                "action-model schema '${parts[1]}' does not match '$expectedSchema'"
            }

            val inputs = wholeNumber(parts[2], "input count")
            require(inputs in 1..MAX_INPUTS) { "an action-model literal claims $inputs features" }
            require(inputs == expectedInputs) {
                "action-model schema '$expectedSchema' has $expectedInputs features, literal has $inputs"
            }

            val hiddenUnits = wholeNumber(parts[3], "hidden count")
            require(hiddenUnits == LINEAR_HIDDEN_UNITS) {
                "action-model hidden width $hiddenUnits is not supported"
            }

            val digits = parts[4].split(',')
            require(digits.size == inputs) {
                "a linear model with $inputs inputs wants $inputs coefficients, was ${digits.size}"
            }
            val featureWeights = IntArray(inputs)
            for (input in 0 until inputs) {
                featureWeights[input] = boundedWeight(digits[input], "weight $input")
            }
            return ActionModel(expectedSchema, inputs, featureWeights)
        }

        /** Rounds one bounded feature to signed Q8, with halves away from zero. */
        public fun quantize(feature: Double): Int {
            require(feature.isFinite() && feature >= -1.0 && feature <= 1.0) {
                "an action feature must be finite and in -1..1, was $feature"
            }
            val scaled = feature * FEATURE_SCALE
            return (if (scaled < 0.0) scaled - 0.5 else scaled + 0.5).toInt()
        }

        private fun boundedWeight(text: String, name: String): Int {
            val value = text.toIntOrNull()
                ?: throw IllegalArgumentException("action-model $name is not a whole number: '$text'")
            require(value in -MAX_WEIGHT..MAX_WEIGHT) {
                "action-model $name is outside ${-MAX_WEIGHT}..$MAX_WEIGHT: $value"
            }
            return value
        }

        private fun wholeNumber(text: String, name: String): Int =
            text.toIntOrNull() ?: throw IllegalArgumentException("action-model $name is not a whole number: '$text'")

        private fun validateSchema(schema: String) {
            require(schema.isNotEmpty() && schema.length <= MAX_SCHEMA_LENGTH) {
                "an action-model schema has 1..$MAX_SCHEMA_LENGTH characters"
            }
            for (character in schema) {
                require(
                    character in 'a'..'z' || character in '0'..'9' ||
                        character == '-' || character == '_' || character == '.',
                ) { "an action-model schema contains '$character'" }
            }
        }

        /** Bumped only if an existing field or its meaning changes. */
        private const val FORMAT_VERSION = 1
        private const val LINEAR_HIDDEN_UNITS = 0
        private const val FIELD_COUNT = 5
        private const val MAX_SCHEMA_LENGTH = 64

        /** Larger than the longest valid literal, and checked before `split` sizes anything from it. */
        private const val MAX_LITERAL_LENGTH = 4_096
    }
}
