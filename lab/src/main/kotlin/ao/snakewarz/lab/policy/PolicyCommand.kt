package ao.snakewarz.lab.policy

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.bots.reactive.policy.PolicyResearch
import ao.snakewarz.lab.LabCommand
import ao.snakewarz.match.tournament.Contestant
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * How often each unreleased P2 policy agrees with a fixed expert on retained P1 positions.
 *
 * This plays no new games. The first pass walks every replay without search and chooses a bounded
 * match-balanced sample; the second follows the same recorded line while asking one persistent
 * expert and one persistent probe per seat at the sampled turns. Agreement is visibility, not a
 * strength result, and the three readings stay separate so a tie cannot masquerade as a top choice.
 */
internal class PolicyCommand(
    val logDirectory: Path,
    val expert: Contestant,
    val positionsPerPhase: Int,
    val seed: Long,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val corpus = loadPolicyReplayCorpus(logDirectory)

        val probes = PolicyResearch.cases.map { candidate ->
            PolicyProbeCase(candidate.key) { setup ->
                val probe = candidate.probeFactory.create(setup)
                PolicyTurnProbe { turn ->
                    val selected = probe.choose(turn)
                    PolicyProbeChoice(selected, probe.rawMaxima)
                }
            }
        }
        val expertEntry = registry.entryOf(expert.bot)
        val selection = selectPolicyPositions(corpus.replays, positionsPerPhase, seed)
        val observations = selection.samples.groupBy { it.replay.key }.values.flatMap { targets ->
            observePolicyReplay(targets.first().replay.record, targets, expert, expertEntry, probes)
        }

        val setup = corpus.board
        log(
            "[policy] expert=${expert.bot.slug}:${expert.summary} log=$logDirectory " +
                "board=${setup.rows}x${setup.cols} walls=${setup.wallCount} seed=$seed",
        )
        log(
            "[policy] replays=${corpus.encodedCount} matches=${corpus.replays.size} " +
                "unreadable=${corpus.unreadableCount} " +
                "selected-choice-positions=${observations.size}",
        )

        for (phase in PolicyPhase.entries) {
            val inPhase = observations.filter { it.sample.phase == phase }
            val fill = median(inPhase.map { it.fill })
            log(
                "[policy] phase=${phase.label} stream-choices=${selection.choices[phase.ordinal]} " +
                    "forced=${selection.forced[phase.ordinal]} selected=${inPhase.size} median-fill=${percent(fill)}",
            )

            for ((caseIndex, candidate) in probes.withIndex()) {
                val readings = inPhase.map { observation ->
                    observation.sample.replay.block to observation.readings.single { it.key == candidate.key }
                }
                val tie = metric(readings, caseIndex, phase, METRIC_TIE) { it.tied }
                val topOne = metric(readings, caseIndex, phase, METRIC_TOP_ONE) { it.topOne }
                val ceiling = metric(readings, caseIndex, phase, METRIC_CEILING) { it.ceiling }
                log(
                    "[policy] phase=${phase.label} case=${candidate.key} " +
                        "tie=${describe(tie)} top1=${describe(topOne)} ceiling=${describe(ceiling)}",
                )
            }
        }
    }

    override fun toString(): String =
        "Policy($logDirectory, expert=$expert, positions=$positionsPerPhase, seed=$seed)"

    private fun metric(
        readings: List<Pair<String, PolicyCaseReading>>,
        caseIndex: Int,
        phase: PolicyPhase,
        metric: Int,
        selected: (PolicyCaseReading) -> Boolean,
    ): PolicyRate {
        val points = readings.map { (block, reading) -> PolicyMetricPoint(block, selected(reading)) }
        val stream = seed xor
            (caseIndex + 1).toLong() * CASE_SALT xor
            (phase.ordinal + 1).toLong() * PHASE_SALT xor
            metric.toLong() * METRIC_SALT
        return policyRate(points, stream)
    }

    private fun describe(rate: PolicyRate): String =
        "${rate.count}/${rate.total} " +
            "(${percent(rate.rate)}; experimental-block 95% ${percent(rate.low)}..${percent(rate.high)})"

    private fun median(values: List<Double>): Double =
        if (values.isEmpty()) Double.NaN else values.sorted()[values.size / 2]

    private fun percent(value: Double): String {
        if (value.isNaN()) {
            return "--"
        }
        val tenths = (value * 1_000).roundToInt()
        return "${tenths / 10}.${tenths % 10}%"
    }
    private companion object {
        const val METRIC_TIE = 1
        const val METRIC_TOP_ONE = 2
        const val METRIC_CEILING = 3

        const val CASE_SALT = -7046029254386353131L
        const val PHASE_SALT = -3335678366873096957L
        const val METRIC_SALT = -7723592293110705685L
    }
}
