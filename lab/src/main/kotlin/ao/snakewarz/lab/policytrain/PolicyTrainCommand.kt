package ao.snakewarz.lab.policytrain

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.bots.reactive.policy.ActionFeatures
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.lab.LabCommand
import ao.snakewarz.lab.policy.PolicyMetricPoint
import ao.snakewarz.lab.policy.PolicyPhase
import ao.snakewarz.lab.policy.PolicyReplayCorpus
import ao.snakewarz.lab.policy.loadPolicyReplayCorpus
import ao.snakewarz.lab.policy.policyRate
import ao.snakewarz.match.tournament.Contestant
import java.nio.file.Path
import kotlin.math.roundToInt

internal class PolicyTrainDataset(
    val spec: ActionDatasetSpec,
    val expert: Contestant,
)

/** Fits and appraises P3's shared linear action policy without admitting holdout data to selection. */
internal class PolicyTrainCommand(
    private val training: List<PolicyTrainDataset>,
    private val validation: List<PolicyTrainDataset>,
    private val holdout: List<PolicyTrainDataset>,
    private val epochs: Int,
    private val learningRate: Double,
    private val l2Candidates: List<Double>,
    private val seed: Long,
    private val threads: Int,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        validateSources()
        log(
            "[policy-train] schema=${ActionFeatures.SCHEMA} features=${ActionFeatures.LENGTH} " +
                "epochs=$epochs rate=$learningRate l2=${l2Candidates.joinToString(",")} seed=$seed " +
                "folds=$DEFAULT_FOLDS validation-fold=$VALIDATION_FOLD threads=$threads",
        )

        val sourceCache = LinkedHashMap<Path, PolicyReplayCorpus>()
        val trainingSources = training.mapTo(LinkedHashSet()) { sourceKey(it.spec.directory) }
        val validationSources = validation.mapTo(LinkedHashSet()) { sourceKey(it.spec.directory) }

        val trainingData = collectRole(
            datasets = training,
            role = ActionDatasetRole.TRAINING,
            registry = registry,
            sourceCache = sourceCache,
        ) { source, corpus, block ->
            source !in validationSources || blockFold(corpus, block) != VALIDATION_FOLD
        }
        reportSources(ActionDatasetRole.TRAINING, trainingData, log)
        val trainingCorpus = disjointActionRole(ActionDatasetRole.TRAINING, trainingData, emptySet())
        reportRole(trainingCorpus, log)

        val validationData = collectRole(
            datasets = validation,
            role = ActionDatasetRole.VALIDATION,
            registry = registry,
            sourceCache = sourceCache,
        ) { source, corpus, block ->
            source !in trainingSources || blockFold(corpus, block) == VALIDATION_FOLD
        }
        reportSources(ActionDatasetRole.VALIDATION, validationData, log)
        val validationCorpus = disjointActionRole(
            ActionDatasetRole.VALIDATION,
            validationData,
            inputKeysOf(trainingCorpus),
        )
        reportRole(validationCorpus, log)

        val fit = fitActionLinear(
            training = trainingCorpus.examples,
            validation = validationCorpus.examples,
            l2Candidates = l2Candidates,
            epochs = epochs,
            learningRate = learningRate,
        )
        val literal = quantizeActionLinear(fit.weights).encode()
        val deployed = ao.snakewarz.bots.reactive.policy.ActionModel.decode(
            literal,
            ActionFeatures.SCHEMA,
            ActionFeatures.LENGTH,
        )
        log(
            "[policy-train] selected l2=${fit.l2} train-loss=${decimal(fit.trainingLoss)} " +
                "validation-loss=${decimal(fit.validationLoss)} " +
                "quantized-model=$deployed",
        )
        log("[policy-train] model=$literal")
        reportMetrics(trainingCorpus, deployed, log)
        reportMetrics(validationCorpus, deployed, log)

        // Holdout paths have not even been opened above this line. The selected and quantized model
        // is now immutable, so labels in this role can affect only the report that follows.
        log("[policy-train] hyperparameters and quantized model frozen; opening holdout sources")
        val holdoutData = collectRole(
            datasets = holdout,
            role = ActionDatasetRole.HOLDOUT,
            registry = registry,
            sourceCache = LinkedHashMap(),
        ) { _, _, _ -> true }
        reportSources(ActionDatasetRole.HOLDOUT, holdoutData, log)
        val developmentKeys = inputKeysOf(trainingCorpus) + inputKeysOf(validationCorpus)
        val holdoutCorpus = disjointActionRole(ActionDatasetRole.HOLDOUT, holdoutData, developmentKeys)
        reportRole(holdoutCorpus, log)
        reportMetrics(holdoutCorpus, deployed, log)
    }

    private fun collectRole(
        datasets: List<PolicyTrainDataset>,
        role: ActionDatasetRole,
        registry: BotRegistry,
        sourceCache: MutableMap<Path, PolicyReplayCorpus>,
        include: (Path, PolicyReplayCorpus, String) -> Boolean,
    ): List<CollectedActionDataset> = datasets.map { dataset ->
        val source = sourceKey(dataset.spec.directory)
        val corpus = sourceCache.getOrPut(source) { loadPolicyReplayCorpus(dataset.spec.directory) }
        collectActionDataset(
            label = dataset.spec.label,
            corpus = corpus,
            expert = dataset.expert,
            expertEntry = registry.entryOf(dataset.expert.bot),
            positionsPerPhase = dataset.spec.positionsPerPhase,
            seed = roleSeed(role, dataset.spec.label),
            threads = threads,
            includeBlock = { block -> include(source, corpus, block) },
        )
    }

    private fun validateSources() {
        val development = (training + validation).groupBy { sourceKey(it.spec.directory) }
        for ((source, uses) in development) {
            val experts = uses.map { it.spec.expertSpec }.distinct()
            require(experts.size == 1) {
                "source $source has different development experts: ${experts.joinToString()}"
            }
        }

        val developmentSources = development.keys
        for (dataset in holdout) {
            val source = sourceKey(dataset.spec.directory)
            require(source !in developmentSources) {
                "holdout source $source also appears in train or validation"
            }
        }
    }

    private fun blockFold(corpus: PolicyReplayCorpus, block: String): Int =
        actionBlockFold(seed, "${corpus.run.id}|${corpus.run.map}", block)

    private fun roleSeed(role: ActionDatasetRole, label: String): Long {
        var hash = seed xor (role.ordinal + 1).toLong() * ROLE_SALT
        for (character in label) {
            hash = (hash xor character.code.toLong()) * LABEL_PRIME
        }
        return hash
    }

    private fun reportSources(
        role: ActionDatasetRole,
        datasets: List<CollectedActionDataset>,
        log: (String) -> Unit,
    ) {
        for (dataset in datasets) {
            log(
                "[policy-train] role=${role.label} dataset=${dataset.label} map=${dataset.map} " +
                    "replays=${dataset.counts.readable}/${dataset.counts.encoded} " +
                    "unreadable=${dataset.counts.unreadable} selected=${dataset.counts.selected}",
            )
            for (phase in PolicyPhase.entries) {
                log(
                    "[policy-train] role=${role.label} dataset=${dataset.label} phase=${phase.label} " +
                        "choices=${dataset.counts.choices[phase.ordinal]} " +
                        "forced=${dataset.counts.forced[phase.ordinal]} " +
                        "selected=${dataset.examples.count { it.phase == phase.label }}",
                )
            }
        }
    }

    private fun reportRole(corpus: ActionRoleCorpus, log: (String) -> Unit) {
        val classes = IntArray(Direction.entries.size)
        val branches = IntArray(Direction.entries.size + 1)
        for (example in corpus.examples) {
            classes[example.target]++
            branches[Integer.bitCount(example.legalBits)]++
        }
        log(
            "[policy-train] role=${corpus.role.label} retained=${corpus.examples.size} " +
                "duplicate-inputs=${corpus.duplicateInputs} conflicting-labels=${corpus.conflictingLabels} " +
                "earlier-role-overlap-dropped=${corpus.earlierRoleOverlap} " +
                "branches=${branches.indices.filter { branches[it] > 0 }.joinToString { "$it:${branches[it]}" }} " +
                "classes=${Direction.entries.joinToString { "${it.name.lowercase()}:${classes[it.ordinal]}" }}",
        )
    }

    private fun reportMetrics(
        corpus: ActionRoleCorpus,
        model: ao.snakewarz.bots.reactive.policy.ActionModel,
        log: (String) -> Unit,
    ) {
        val groups = corpus.examples.groupBy { example -> example.dataset to example.phase }
        for ((coordinate, examples) in groups.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            val learned = rates(examples, model, learned = true)
            val cartographer = rates(examples, model, learned = false)
            log(
                "[policy-train] role=${corpus.role.label} dataset=${coordinate.first} " +
                    "phase=${coordinate.second} n=${examples.size} " +
                    "learned=${describe(learned)} cartographer=${describe(cartographer)} " +
                    "top1-delta=${percent(learned.topOne.rate - cartographer.topOne.rate)}",
            )
        }
    }

    private fun rates(
        examples: List<ActionExample>,
        model: ao.snakewarz.bots.reactive.policy.ActionModel,
        learned: Boolean,
    ): ActionRates {
        val maxima = examples.map { example ->
            example to if (learned) actionMaxima(example, model) else example.cartographerMaxima
        }
        val salt = if (learned) LEARNED_SALT else CARTOGRAPHER_SALT
        fun rate(metric: Int, predicate: (ActionExample, Int) -> Boolean) = policyRate(
            maxima.map { (example, bits) ->
                PolicyMetricPoint("${example.map}:${example.block}", predicate(example, bits))
            },
            seed xor salt xor metric.toLong() * METRIC_SALT,
        )
        return ActionRates(
            tie = rate(1) { _, bits -> Integer.bitCount(bits) > 1 },
            topOne = rate(2) { example, bits -> Integer.bitCount(bits) == 1 && bits and (1 shl example.target) != 0 },
            ceiling = rate(3) { example, bits -> bits and (1 shl example.target) != 0 },
        )
    }

    private fun describe(rates: ActionRates): String =
        "tie=${describe(rates.tie)} top1=${describe(rates.topOne)} ceiling=${describe(rates.ceiling)}"

    private fun describe(rate: ao.snakewarz.lab.policy.PolicyRate): String =
        "${rate.count}/${rate.total}(${percent(rate.rate)};95% ${percent(rate.low)}..${percent(rate.high)})"

    private fun decimal(value: Double): String = "%.6f".format(value)

    private fun percent(value: Double): String {
        if (value.isNaN()) return "--"
        val tenths = (value * 1_000).roundToInt()
        val sign = if (tenths < 0) "-" else ""
        val magnitude = kotlin.math.abs(tenths)
        return "$sign${magnitude / 10}.${magnitude % 10}%"
    }

    private fun sourceKey(path: Path): Path = path.toAbsolutePath().normalize()

    private class ActionRates(
        val tie: ao.snakewarz.lab.policy.PolicyRate,
        val topOne: ao.snakewarz.lab.policy.PolicyRate,
        val ceiling: ao.snakewarz.lab.policy.PolicyRate,
    )

    companion object {
        const val DEFAULT_EPOCHS: Int = 80
        const val DEFAULT_RATE: Double = 0.20
        const val DEFAULT_SEED: Long = 73_001L
        const val DEFAULT_L2: String = "0,0.0001,0.001,0.01"

        private const val ROLE_SALT = -7046029254386353131L
        private const val LABEL_PRIME = 0x100000001b3L
        private const val LEARNED_SALT = -3335678366873096957L
        private const val CARTOGRAPHER_SALT = -7723592293110705685L
        private const val METRIC_SALT = -4658895280553007687L
    }
}
