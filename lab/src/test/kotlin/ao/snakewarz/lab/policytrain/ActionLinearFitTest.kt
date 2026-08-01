package ao.snakewarz.lab.policytrain

import ao.snakewarz.bots.reactive.policy.ActionFeatures
import ao.snakewarz.core.grid.Direction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionLinearFitTest {
    @Test
    fun `a shared linear scorer learns an action-relative synthetic rule reproducibly`() {
        val training = List(40) { index -> example(index, if (index % 2 == 0) 0 else 1) }
        val validation = List(12) { index -> example(index + 100, if (index % 2 == 0) 0 else 1) }

        val first = fitActionLinear(training, validation, listOf(0.0, 0.01), epochs = 40, learningRate = 0.2)
        val second = fitActionLinear(training, validation, listOf(0.0, 0.01), epochs = 40, learningRate = 0.2)

        assertContentEquals(first.weights, second.weights)
        assertEquals(first.l2, second.l2)
        assertTrue(first.validationLoss < 0.2)
        for (candidate in validation) {
            assertEquals(1 shl candidate.target, actionMaxima(candidate, first.weights))
        }

        val model = quantizeActionLinear(first.weights)
        val literal = model.encode()
        assertTrue(literal.startsWith("1|${ActionFeatures.SCHEMA}|${ActionFeatures.LENGTH}|0|"))
        val deployed = ao.snakewarz.bots.reactive.policy.ActionModel.decode(
            literal,
            ActionFeatures.SCHEMA,
            ActionFeatures.LENGTH,
        )
        for (candidate in validation) {
            assertEquals(1 shl candidate.target, actionMaxima(candidate, deployed))
        }
    }

    @Test
    fun `later roles drop exact inputs already seen by development`() {
        val trainingExample = example(1, 0)
        val duplicate = example(2, 0, featuresFrom = trainingExample)
        val conflict = example(3, 1, featuresFrom = trainingExample)
        val training = disjointActionRole(
            ActionDatasetRole.TRAINING,
            listOf(dataset("train", listOf(trainingExample))),
            emptySet(),
        )
        val validation = disjointActionRole(
            ActionDatasetRole.VALIDATION,
            listOf(dataset("validation", listOf(duplicate, conflict, example(4, 1)))),
            inputKeysOf(training),
        )

        assertEquals(1, training.examples.size)
        assertEquals(1, validation.examples.size)
        assertEquals(2, validation.earlierRoleOverlap)
        assertTrue(inputKeysOf(training).intersect(inputKeysOf(validation)).isEmpty())
    }

    @Test
    fun `experimental block folds are stable and source-sensitive`() {
        val first = actionBlockFold(73_001L, "run-a|map-a", "opening:rho-00")
        assertEquals(first, actionBlockFold(73_001L, "run-a|map-a", "opening:rho-00"))
        assertTrue(
            (0 until 20).map { actionBlockFold(73_001L, "run-a|map-a", "block-$it") }.toSet().size > 1,
        )
    }

    private fun example(
        index: Int,
        target: Int,
        featuresFrom: ActionExample? = null,
    ): ActionExample {
        val features = featuresFrom?.features?.copyOf()
            ?: DoubleArray(Direction.entries.size * ActionFeatures.LENGTH).also { values ->
                values[target * ActionFeatures.LENGTH] = 1.0
                values[(1 - target) * ActionFeatures.LENGTH] = -1.0
            }
        return ActionExample(
            dataset = "synthetic",
            map = "empty",
            phase = "early",
            block = "block-$index",
            replay = "replay-$index",
            turnIndex = index,
            legalBits = (1 shl 0) or (1 shl 1),
            target = target,
            cartographerMaxima = (1 shl 0) or (1 shl 1),
            features = features,
        )
    }

    private fun dataset(label: String, examples: List<ActionExample>): CollectedActionDataset =
        CollectedActionDataset(
            label = label,
            map = "empty",
            examples = examples,
            counts = ActionDatasetCounts(
                encoded = examples.size,
                readable = examples.size,
                unreadable = 0,
                choices = IntArray(3),
                forced = IntArray(3),
                selected = examples.size,
            ),
        )
}
