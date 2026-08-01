package ao.snakewarz.lab.policytrain

import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.bots.reactive.policy.ActionFeatures
import ao.snakewarz.bots.reactive.policy.PolicyResearch
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.lab.policy.PolicyCaptureFactory
import ao.snakewarz.lab.policy.PolicyPhase
import ao.snakewarz.lab.policy.PolicyReplayCorpus
import ao.snakewarz.lab.policy.PolicySampleCapture
import ao.snakewarz.lab.policy.observePolicyReplay
import ao.snakewarz.lab.policy.selectPolicyPositions
import ao.snakewarz.match.tournament.Contestant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal enum class ActionDatasetRole(val label: String) {
    TRAINING("train"),
    VALIDATION("validation"),
    HOLDOUT("holdout"),
}

/** Counts from sampling before expert search, retained beside the examples they qualify. */
internal class ActionDatasetCounts(
    val encoded: Int,
    val readable: Int,
    val unreadable: Int,
    val choices: IntArray,
    val forced: IntArray,
    val selected: Int,
)

internal class CollectedActionDataset(
    val label: String,
    val map: String,
    val examples: List<ActionExample>,
    val counts: ActionDatasetCounts,
)

/** A role after exact duplicate inputs and earlier-role overlap have been removed. */
internal class ActionRoleCorpus(
    val role: ActionDatasetRole,
    val examples: List<ActionExample>,
    val duplicateInputs: Int,
    val conflictingLabels: Int,
    val earlierRoleOverlap: Int,
)

/**
 * Labels a bounded choice sample while following the source replay exactly.
 *
 * The shared observer calls the expert on every turn. Action features and Cartographer are read only
 * at sampled turns, from seat-local objects that persist for the replay's full lifetime.
 */
internal fun collectActionDataset(
    label: String,
    corpus: PolicyReplayCorpus,
    expert: Contestant,
    expertEntry: BotEntry,
    positionsPerPhase: Int,
    seed: Long,
    threads: Int,
    includeBlock: (String) -> Boolean,
): CollectedActionDataset {
    require(threads > 0) { "action replay labeling needs at least one thread, was $threads" }
    val eligible = corpus.replays.filter { replay -> includeBlock(replay.block) }
    require(eligible.isNotEmpty()) { "dataset '$label' has no experimental blocks in its assigned role" }
    val selection = selectPolicyPositions(eligible, positionsPerPhase, seed)
    val targetsByReplay = selection.samples.groupBy { it.replay.key }.values.toList()
    val examples = parallelReplayLabels(targetsByReplay, threads) { targets ->
        val local = mutableListOf<ActionExample>()
        val replay = targets.first().replay
        try {
            observePolicyReplay(
                record = replay.record,
                targets = targets,
                expert = expert,
                expertEntry = expertEntry,
                cases = emptyList(),
                capture = ActionCaptureFactory(label, corpus.run.map, local),
            )
        } catch (failure: Throwable) {
            throw IllegalStateException("dataset '$label' replay '${replay.key}' labeling failed", failure)
        }
        local
    }.flatten()

    val ordered = examples.sortedWith(
        compareBy<ActionExample> { it.phase }
            .thenBy { it.replay }
            .thenBy { it.turnIndex },
    )
    return CollectedActionDataset(
        label = label,
        map = corpus.run.map,
        examples = ordered,
        counts = ActionDatasetCounts(
            encoded = corpus.encodedCount,
            readable = corpus.replays.size,
            unreadable = corpus.unreadableCount,
            choices = selection.choices,
            forced = selection.forced,
            selected = ordered.size,
        ),
    )
}

/** Runs replay-local work concurrently and returns results in the caller's order. */
internal fun <T, R> parallelReplayLabels(items: List<T>, threads: Int, work: (T) -> R): List<R> {
    require(threads > 0) { "action replay labeling needs at least one thread, was $threads" }
    if (threads == 1 || items.size <= 1) {
        return items.map(work)
    }

    val pool = Executors.newFixedThreadPool(minOf(threads, items.size))
    val running = mutableListOf<Future<R>>()
    var completed = false
    return try {
        for (item in items) {
            running += pool.submit(Callable { work(item) })
        }
        val results = running.map { finished ->
            try {
                finished.get()
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("action replay labeling was interrupted", failure)
            }
        }
        completed = true
        results
    } finally {
        if (completed) {
            pool.shutdown()
        } else {
            for (future in running) {
                future.cancel(true)
            }
            pool.shutdownNow()
        }
    }
}

/** Stable five-way assignment of a complete-opening or mirrored-pair experimental block. */
internal fun actionBlockFold(seed: Long, source: String, block: String, folds: Int = DEFAULT_FOLDS): Int {
    require(folds > 1) { "a grouped split needs at least two folds, was $folds" }
    var hash = FNV_OFFSET xor seed
    for (character in "$source\u0000$block") {
        hash = (hash xor character.code.toLong()) * FNV_PRIME
    }
    return ((SplitMix64(hash).nextLong() ushr 1) % folds).toInt()
}

/**
 * Makes one role disjoint from itself and every earlier role by exact model input.
 *
 * First occurrence wins in stable dataset/replay/turn order. A repeated input with a different
 * teacher answer is counted separately because it is irreducible label noise, not mere duplication.
 */
internal fun disjointActionRole(
    role: ActionDatasetRole,
    datasets: List<CollectedActionDataset>,
    earlier: Set<ActionInputKey>,
): ActionRoleCorpus {
    val ordered = datasets.flatMap { it.examples }.sortedWith(
        compareBy<ActionExample> { it.dataset }
            .thenBy { it.replay }
            .thenBy { it.turnIndex },
    )
    val retained = mutableListOf<ActionExample>()
    val labels = LinkedHashMap<ActionInputKey, Int>()
    var duplicates = 0
    var conflicts = 0
    var overlap = 0

    for (example in ordered) {
        if (example.input in earlier) {
            overlap++
            continue
        }
        val previous = labels[example.input]
        if (previous != null) {
            duplicates++
            if (previous != example.target) {
                conflicts++
            }
            continue
        }
        labels[example.input] = example.target
        retained += example
    }
    return ActionRoleCorpus(role, retained, duplicates, conflicts, overlap)
}

internal fun inputKeysOf(corpus: ActionRoleCorpus): Set<ActionInputKey> =
    corpus.examples.mapTo(LinkedHashSet()) { it.input }

private class ActionCaptureFactory(
    private val label: String,
    private val map: String,
    private val examples: MutableList<ActionExample>,
) : PolicyCaptureFactory {
    override fun create(setup: BotSetup): PolicySampleCapture {
        val features = ActionFeatures(setup.grid, setup.opponents.size + 1)
        val row = DoubleArray(ActionFeatures.LENGTH)
        val matrix = DoubleArray(Direction.entries.size * ActionFeatures.LENGTH)
        val cartographerCase = requireNotNull(PolicyResearch.case(CARTOGRAPHER_CASE)) {
            "Cartographer's research probe '$CARTOGRAPHER_CASE' is unavailable"
        }
        val cartographer = cartographerCase.probeFactory.create(setup)

        return PolicySampleCapture { turn, sample, expertMove ->
            features.measure(turn.board, turn.self, turn.legalMoves)
            matrix.fill(0.0)
            for (index in 0 until turn.legalMoves.size) {
                val direction = turn.legalMoves.nth(index)
                features.into(direction, row)
                row.copyInto(matrix, direction.ordinal * ActionFeatures.LENGTH)
            }

            val consumed = turn.budget.consumed
            val selected = cartographer.choose(turn)
            check(turn.budget.consumed == consumed) {
                "Cartographer consumed ${turn.budget.consumed - consumed} evaluations in action capture"
            }
            check(selected in turn.legalMoves && selected in cartographer.rawMaxima) {
                "Cartographer returned $selected / ${cartographer.rawMaxima} from ${turn.legalMoves}"
            }

            examples += ActionExample(
                dataset = label,
                map = map,
                phase = sample.phase.label,
                block = sample.replay.block,
                replay = sample.replay.key,
                turnIndex = sample.turnIndex,
                legalBits = directionBits(turn.legalMoves),
                target = expertMove.ordinal,
                cartographerMaxima = directionBits(cartographer.rawMaxima),
                features = matrix.copyOf(),
            )
        }
    }
}

private fun directionBits(directions: ao.snakewarz.core.grid.DirectionSet): Int {
    var bits = 0
    for (index in 0 until directions.size) {
        bits = bits or (1 shl directions.nth(index).ordinal)
    }
    return bits
}

internal const val DEFAULT_FOLDS: Int = 5
internal const val VALIDATION_FOLD: Int = 0
private const val CARTOGRAPHER_CASE = "full-owned"
private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
