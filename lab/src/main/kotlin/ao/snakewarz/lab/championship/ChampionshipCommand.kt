package ao.snakewarz.lab.championship

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.LabCommand
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.resolveSpec
import java.nio.file.Path

/** Rebuilds the declared empty-8x8 finalist decision from one retained confirmation run. */
internal class ChampionshipCommand(
    private val logDirectory: Path,
    private val finalists: List<String>,
    private val incumbent: String,
    private val chromeWorstTurnMillis: List<Double>,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        require(finalists.size == chromeWorstTurnMillis.size) {
            "championship needs one Chrome cost per finalist, was ${chromeWorstTurnMillis.size} for ${finalists.size}"
        }

        val store = MatchLog(logDirectory)
        val runs = store.runs()
        require(runs.size == 1) {
            "championship reads exactly one retained run, found ${runs.size} in $logDirectory"
        }
        val run = runs.single()
        val available = run.contestants.toSet()
        val expandedFinalists = finalists.map { resolveSpec(it, available) }
        val expandedIncumbent = resolveSpec(incumbent, available)
        val report = ChampionshipReport.of(
            run = run,
            matches = store.matches().filter { it.run == run.id },
            finalists = expandedFinalists,
            incumbent = expandedIncumbent,
            chromeWorstTurnMillis = expandedFinalists.zip(chromeWorstTurnMillis).toMap(),
            registry = registry,
        )
        report.lines().forEach(log)
    }
}
