package ao.snakewarz.lab.allowance

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.LabCommand
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.expandedSpec
import java.nio.file.Path

/** Reproduces an allowance-curve report from retained pair runs without playing or appending. */
internal class AllowanceCurveReadCommand(
    private val plan: AllowanceCurvePlan,
    private val logDirectory: Path,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val matchLog = MatchLog(logDirectory)
        val variants = plan.variants.map { contestant ->
            AllowanceVariant(
                spec = expandedSpec(contestant, registry, FIXED_ALLOWANCE_FALLBACK),
                allowance = checkNotNull(contestant.budgetPerTurn),
            )
        }
        val panel = plan.panel.map { contestant ->
            expandedSpec(contestant, registry, FIXED_ALLOWANCE_FALLBACK)
        }
        val report = AllowanceCurveReport.of(
            runs = matchLog.runs(),
            matches = matchLog.matches(),
            variants = variants,
            panel = panel,
            replications = plan.replications,
            seed = plan.seed,
        )
        report.lines().forEach(log)
    }
}

private const val FIXED_ALLOWANCE_FALLBACK = 0
