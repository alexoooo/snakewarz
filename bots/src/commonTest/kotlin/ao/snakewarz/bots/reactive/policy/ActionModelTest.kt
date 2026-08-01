package ao.snakewarz.bots.reactive.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActionModelTest {
    @Test
    fun `a checked literal round-trips exactly`() {
        val weights = IntArray(ActionFeatures.LENGTH) { input -> input * 137 - 503 }
        val model = ActionModel.linear(ActionFeatures.SCHEMA, weights)
        weights.fill(1)

        assertTrue(model.encode().startsWith("1|${ActionFeatures.SCHEMA}|${ActionFeatures.LENGTH}|0|"))

        val decoded = ActionModel.decode(model.encode(), ActionFeatures.SCHEMA, ActionFeatures.LENGTH)
        val row = IntArray(ActionFeatures.LENGTH) { input -> input * 31 - 128 }
        assertEquals(model.scoreQ8(row), decoded.scoreQ8(row))
        assertEquals(ActionFeatures.SCHEMA, decoded.schema)
        assertEquals(ActionFeatures.LENGTH, decoded.inputs)
    }

    @Test
    fun `a decoder refuses malformed or unsupported literals`() {
        assertFailsWith<IllegalArgumentException> { ActionModel.decode("1|$TEST_SCHEMA|2|0", TEST_SCHEMA, 2) }
        assertFailsWith<IllegalArgumentException> { ActionModel.decode("2|$TEST_SCHEMA|2|0|1,2,3", TEST_SCHEMA, 2) }
        assertFailsWith<IllegalArgumentException> { ActionModel.decode("1|$TEST_SCHEMA|x|0|1,2,3", TEST_SCHEMA, 2) }
        assertFailsWith<IllegalArgumentException> { ActionModel.decode("1|$TEST_SCHEMA|2|1|1,2,3", TEST_SCHEMA, 2) }
        assertFailsWith<IllegalArgumentException> { ActionModel.decode("1|$TEST_SCHEMA|2|0|1", TEST_SCHEMA, 2) }
        assertFailsWith<IllegalArgumentException> { ActionModel.decode("1|$TEST_SCHEMA|2|0|1,x", TEST_SCHEMA, 2) }
        assertFailsWith<IllegalArgumentException> {
            ActionModel.decode("1|$TEST_SCHEMA|2|0|1,${ActionModel.MAX_WEIGHT + 1}", TEST_SCHEMA, 2)
        }
        assertFailsWith<IllegalArgumentException> { ActionModel.decode("x".repeat(4_097), TEST_SCHEMA, 2) }
    }

    @Test
    fun `schema and column mismatches fail closed`() {
        val literal = ActionModel.linear(TEST_SCHEMA, intArrayOf(1, 2)).encode()

        assertFailsWith<IllegalArgumentException> {
            ActionModel.decode(literal, "other-schema", expectedInputs = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            ActionModel.decode(literal, TEST_SCHEMA, expectedInputs = 3)
        }
    }

    @Test
    fun `weights features and shapes are bounded`() {
        assertFailsWith<IllegalArgumentException> { ActionModel.linear(TEST_SCHEMA, IntArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            ActionModel.linear(TEST_SCHEMA, IntArray(ActionModel.MAX_INPUTS + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            ActionModel.linear(TEST_SCHEMA, intArrayOf(ActionModel.MAX_WEIGHT + 1))
        }

        val model = ActionModel.linear(TEST_SCHEMA, intArrayOf(1))
        assertFailsWith<IllegalArgumentException> { model.score(doubleArrayOf(1.0 + 1e-12)) }
        assertFailsWith<IllegalArgumentException> { model.score(doubleArrayOf(Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { model.scoreQ8(intArrayOf(ActionModel.FEATURE_SCALE + 1)) }
        assertFailsWith<IllegalArgumentException> { model.score(doubleArrayOf()) }

        val excessiveInputs = "1|$TEST_SCHEMA|${ActionModel.MAX_INPUTS + 1}|0|0"
        assertFailsWith<IllegalArgumentException> {
            ActionModel.decode(excessiveInputs, TEST_SCHEMA, expectedInputs = ActionModel.MAX_INPUTS)
        }
    }

    @Test
    fun `every supported extreme accumulates exactly without overflow`() {
        val weights = IntArray(ActionModel.MAX_INPUTS) { ActionModel.MAX_WEIGHT }
        val positive = ActionModel.linear(TEST_SCHEMA, weights)
        val negativeWeights = IntArray(ActionModel.MAX_INPUTS) {
            -ActionModel.MAX_WEIGHT
        }
        val negative = ActionModel.linear(TEST_SCHEMA, negativeWeights)
        val features = IntArray(ActionModel.MAX_INPUTS) { ActionModel.FEATURE_SCALE }
        val expected =
            ActionModel.MAX_WEIGHT.toLong() * ActionModel.FEATURE_SCALE * ActionModel.MAX_INPUTS

        assertTrue(expected > Int.MAX_VALUE)
        assertEquals(expected, positive.scoreQ8(features))
        assertEquals(-expected, negative.scoreQ8(features))
    }

    @Test
    fun `Q8 rounding and a literal preserve candidate ordering`() {
        assertEquals(1, ActionModel.quantize(0.5 / ActionModel.FEATURE_SCALE))
        assertEquals(-1, ActionModel.quantize(-0.5 / ActionModel.FEATURE_SCALE))
        assertEquals(0, ActionModel.quantize(0.49 / ActionModel.FEATURE_SCALE))
        assertEquals(0, ActionModel.quantize(-0.49 / ActionModel.FEATURE_SCALE))

        val model = ActionModel.linear(TEST_SCHEMA, intArrayOf(4, -3, 7))
        val decoded = ActionModel.decode(model.encode(), TEST_SCHEMA, expectedInputs = 3)
        val candidates = arrayOf(
            doubleArrayOf(0.5, -0.25, 0.125),
            doubleArrayOf(0.496, -0.25, 0.125),
            doubleArrayOf(-0.5, 0.25, -0.125),
        )

        val originalScores = LongArray(candidates.size) { model.score(candidates[it]) }
        val decodedScores = LongArray(candidates.size) { decoded.score(candidates[it]) }
        assertTrue(originalScores[0] > originalScores[1])
        assertTrue(originalScores[1] > originalScores[2])
        assertEquals(originalScores.toList(), decodedScores.toList())
    }

    @Test
    fun `Double and prequantized input paths have identical raw scores`() {
        val features = doubleArrayOf(
            1.0,
            -1.0,
            0.5 / ActionModel.FEATURE_SCALE,
            -0.5 / ActionModel.FEATURE_SCALE,
            0.49 / ActionModel.FEATURE_SCALE,
            -0.49 / ActionModel.FEATURE_SCALE,
            0.371,
            -0.824,
        )
        val quantized = IntArray(features.size) { input -> ActionModel.quantize(features[input]) }
        val model = ActionModel.linear(TEST_SCHEMA, intArrayOf(19, -17, 13, -11, 7, -5, 3, -2))

        assertEquals(model.scoreQ8(quantized), model.score(features))
    }

    private companion object {
        const val TEST_SCHEMA = "test-action-schema"
    }
}
