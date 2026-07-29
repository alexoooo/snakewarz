package ao.snakewarz.bots.search.learned

import ao.snakewarz.core.random.SplitMix64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * That the model a trainer fits and the model a bot plays are the same function.
 *
 * Three separate ways that can fail, and each has a case here. The **literal** can round-trip
 * wrongly, which would arrive as a bot that is merely weak. The **layout** accessors can disagree
 * with the forward pass, which would arrive as a trainer optimising a different model from the one
 * it is measuring. And [LearnedNet.slopeOf] can disagree with the activation it claims to
 * differentiate, which would arrive as a fit that never converges and reads as a hyperparameter
 * problem.
 */
class LearnedNetTest {
    @Test
    fun `a literal round-trips to the weight it was written from`() {
        val weights = DoubleArray(LearnedNet.weightCountOf(INPUTS, HIDDEN)) { i ->
            // On the quantisation grid, so the round trip is exact rather than nearly so -- what a
            // tolerance would hide is a scale that changed under the literal.
            (i - 40) / LearnedNet.WEIGHT_SCALE * 137.0
        }
        val net = LearnedNet.over(INPUTS, HIDDEN, weights)
        val decoded = LearnedNet.decode(net.encode())

        assertEquals(INPUTS, decoded.inputs)
        assertEquals(HIDDEN, decoded.hiddenUnits)

        val row = rowOf(1)
        assertEquals(net.value(row), decoded.value(row), absoluteTolerance = 0.0)
    }

    @Test
    fun `and a malformed one is refused rather than read as something`() {
        assertFailsWith<IllegalArgumentException> { LearnedNet.decode("1|4|0") }
        assertFailsWith<IllegalArgumentException> { LearnedNet.decode("9|4|0|0,0,0,0,0") }
        assertFailsWith<IllegalArgumentException> { LearnedNet.decode("1|4|0|0,0,0") }
        assertFailsWith<IllegalStateException> { LearnedNet.decode("1|4|0|0,0,x,0,0") }
    }

    @Test
    fun `a model with no hidden layer is logistic regression, worked out by hand`() {
        // Two features, one weight each, and a bias -- small enough that the expected value is an
        // arithmetic statement rather than another implementation of the same forward pass.
        val net = LearnedNet.over(2, 0, doubleArrayOf(2.0, -1.0, 0.5))

        // `2 * 0.25 - 1 * 0.75 + 0.5` is a logit of `0.25`, and the constants below are that logistic
        // and the one on the last line, written out rather than taken from `kotlin.math.exp`: SW-02
        // allows exactly two tests to call it, and both are the ones proving the portable series
        // right. Anywhere else it is a second implementation dressed as an oracle.
        assertEquals(LOGISTIC_AT_QUARTER, net.value(doubleArrayOf(0.25, 0.75)), absoluteTolerance = 1e-12)
    }

    @Test
    fun `the layout accessors address the weights the forward pass reads`() {
        // Move one weight at a time and check that only the unit it is supposed to belong to moves.
        // A layout accessor that is off by the bias row would still produce a well-formed net and a
        // trainer that quietly optimises the wrong coefficient.
        val weights = DoubleArray(LearnedNet.weightCountOf(INPUTS, HIDDEN))
        val net = LearnedNet.over(INPUTS, HIDDEN, weights)
        val row = rowOf(7)
        val activations = DoubleArray(net.outputSources)

        for (unit in 0 until HIDDEN) {
            weights.fill(0.0)
            weights[net.hiddenBiasIndex(unit)] = 4.0
            net.forward(row, activations)

            for (other in 0 until HIDDEN) {
                val expected = if (other == unit) 4.0 / 5.0 else 0.0
                assertEquals(expected, activations[other], 1e-12, "bias of unit $unit moved unit $other")
            }
        }

        weights.fill(0.0)
        weights[net.outputBiasIndex] = 3.0
        assertEquals(LOGISTIC_AT_THREE, net.value(row), absoluteTolerance = 1e-12, message = "the output bias is last")
    }

    @Test
    fun `the slope it reports is the derivative of the unit that produced the activation`() {
        // Central differences against the forward pass itself, through a single hidden bias, so the
        // comparison is against the real activation rather than against a second copy of softsign.
        val weights = DoubleArray(LearnedNet.weightCountOf(INPUTS, HIDDEN))
        val net = LearnedNet.over(INPUTS, HIDDEN, weights)
        val row = rowOf(3)
        val activations = DoubleArray(net.outputSources)
        val step = 1e-6

        for (bias in listOf(-8.0, -1.5, -0.2, 0.0, 0.3, 2.0, 11.0)) {
            weights.fill(0.0)

            weights[net.hiddenBiasIndex(0)] = bias
            net.forward(row, activations)
            val analytic = net.slopeOf(activations[0])

            weights[net.hiddenBiasIndex(0)] = bias + step
            net.forward(row, activations)
            val above = activations[0]

            weights[net.hiddenBiasIndex(0)] = bias - step
            net.forward(row, activations)
            val below = activations[0]

            val numeric = (above - below) / (2 * step)
            assertTrue(abs(analytic - numeric) < 1e-6, "at $bias the slope is $analytic against $numeric")
        }
    }

    @Test
    fun `it answers on the scale a tree credits, whatever it is handed`() {
        val rng = SplitMix64(20260729)
        val weights = DoubleArray(LearnedNet.weightCountOf(INPUTS, HIDDEN)) { rng.nextDouble() * 40.0 - 20.0 }
        val net = LearnedNet.over(INPUTS, HIDDEN, weights)

        repeat(200) {
            val row = DoubleArray(INPUTS) { rng.nextDouble() * 2.0 - 1.0 }
            val value = net.value(row)
            assertTrue(value > 0.0 && value < 1.0 && value.isFinite(), "read $value")
        }
    }

    @Test
    fun `the shipped literal is the model PositionFeatures produces rows for`() {
        // The one assertion that catches the failure this whole design exists to prevent: a feature
        // added on one side and not baked into the other. LearnedEval's own `require` says the same
        // thing at construction; this says it without seating a match.
        assertEquals(PositionFeatures.LENGTH, LearnedNet.decode(LearnedWeights.ENCODED).inputs)
    }

    private fun rowOf(seed: Long): DoubleArray {
        val rng = SplitMix64(seed)
        return DoubleArray(INPUTS) { rng.nextDouble() * 2.0 - 1.0 }
    }

    private companion object {
        const val INPUTS = 6
        const val HIDDEN = 3

        /** `1 / (1 + e^-0.25)` and `1 / (1 + e^-3)`, to the last bit a `Double` holds. */
        const val LOGISTIC_AT_QUARTER = 0.5621765008857981
        const val LOGISTIC_AT_THREE = 0.9525741268224334
    }
}
