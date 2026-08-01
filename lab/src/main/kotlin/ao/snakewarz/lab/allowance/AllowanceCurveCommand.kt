package ao.snakewarz.lab.allowance

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.LabCommand
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.BatchResult
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.Replays
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.lab.log.expandedSpec
import ao.snakewarz.lab.log.recordBatch
import java.nio.file.Path

/**
 * Plays only allowance-family variants against a fixed panel and retains every pair run.
 *
 * CLI parsing deliberately lives elsewhere: this command takes a validated [AllowanceCurvePlan],
 * so adding it to the lab command line cannot weaken the schedule it measures.
 */
internal class AllowanceCurveCommand(
    private val plan: AllowanceCurvePlan,
    private val logDirectory: Path,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        log(
            "[allowance] ${plan.variants.size} fixed points x ${plan.panel.size} opponents, " +
                "${plan.replications} complete-opening replications, ${plan.matchCount} matches",
        )
        log("[allowance] every entrant carries an explicit fixed per-turn allowance")

        val matchLog = MatchLog(logDirectory)
        val runs = ArrayList<RunHeader>(plan.pairings.size)
        val matches = ArrayList<LoggedMatch>(plan.matchCount)
        for ((index, pairing) in plan.pairings.withIndex()) {
            val variant = expandedSpec(pairing.variant, registry, pairing.config.budgetPerTurn)
            val opponent = expandedSpec(pairing.opponent, registry, pairing.config.budgetPerTurn)
            log("[allowance] pair ${index + 1}/${plan.pairings.size}: $variant vs $opponent")

            val batch = Arena(
                config = pairing.config,
                registry = registry,
                openings = Openings.COMPLETE,
                threads = plan.threads,
                keepRecords = false,
            ).run()
            validateBatch(batch, plan.seed)

            val header = recordBatch(
                log = matchLog,
                batch = batch,
                registry = registry,
                openings = Openings.COMPLETE.name,
                threads = plan.threads,
                replays = Replays.NONE,
            )
            runs += header
            matches += batch.reports.map { report ->
                LoggedMatch.of(header.id, pairing.config, registry, report)
            }
            val distinct = batch.reports.mapTo(LinkedHashSet()) { it.moveStreamHash }.size
            log("[allowance] retained ${header.id}: $distinct/${batch.reports.size} distinct, no forfeits")
        }

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
            runs = runs,
            matches = matches,
            variants = variants,
            panel = panel,
            replications = plan.replications,
            seed = plan.seed,
        )
        log("")
        report.lines().forEach(log)
        log("[allowance] match summaries retained under $logDirectory")
    }
}

private fun validateBatch(batch: BatchResult, seed: Long) {
    require(batch.forfeits == 0) { "a forfeit invalidates an allowance pair before it is retained" }
    require(batch.reports.size == batch.config.rounds) {
        "allowance pair played ${batch.reports.size} matches, expected ${batch.config.rounds}"
    }
    require(batch.leastOpeningCoverage?.covered == Openings.COMPLETE_POPULATION) {
        "allowance pair did not cover all ${Openings.COMPLETE_POPULATION} complete openings"
    }

    for (report in batch.reports) {
        val group = report.index / Openings.SEATINGS_PER_OPENING
        val expectedOpening = "empty8-rho-${(group % Openings.COMPLETE_POPULATION).toString().padStart(2, '0')}"
        val expectedSeating = if (report.index % 2 == 0) listOf(0, 1) else listOf(1, 0)
        require(report.pairKey == group && report.seed == seed + group) {
            "match ${report.index} broke the shared-seed pair schedule"
        }
        require(report.openingIdentity == expectedOpening) {
            "match ${report.index} expected $expectedOpening, was ${report.openingIdentity}"
        }
        require(report.seating.toList() == expectedSeating) {
            "match ${report.index} did not swap the two contestants"
        }
    }
}

private const val FIXED_ALLOWANCE_FALLBACK = 0
