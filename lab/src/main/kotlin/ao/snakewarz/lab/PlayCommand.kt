package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.BatchResult
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.Replays
import ao.snakewarz.lab.log.recordBatch
import ao.snakewarz.match.tournament.TournamentConfig
import java.nio.file.Path
import kotlin.time.TimeSource

/** A batch, printed as the win matrix the sidebar shows plus what it cost and what it measured. */
internal class PlayCommand(
    val config: TournamentConfig,
    val openings: Openings,
    val threads: Int,
    val replays: Replays,
    val logDirectory: Path?,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        log("[lab] ${config.contestants.joinToString(" vs ")}")
        log("[lab] $config, $openings openings, $threads threads")

        val started = TimeSource.Monotonic.markNow()
        val batch = Arena(config, registry, openings, threads, keepRecords = replays != Replays.NONE).run()
        val elapsed = started.elapsedNow()

        log("")
        log(batch.table.toString())
        log(
            "[lab] ${batch.reports.size} matches, ${batch.turnsPlayed} turns " +
                "in ${elapsed.inWholeMilliseconds} ms",
        )
        reportDiversity(batch, log)
        if (batch.forfeits > 0) {
            log("[lab] ${batch.forfeits} FORFEITS -- a bot threw. That is a defect, and this batch measured it.")
        }

        if (logDirectory != null) {
            val header = recordBatch(MatchLog(logDirectory), batch, registry, openings.name, threads, replays)
            log("[lab] recorded as ${header.id} at ${header.build} under $logDirectory")
        }
    }

    override fun toString(): String = "Play($config, $openings, $threads threads, $replays)"

    /**
     * How much of the nominal sample was a real sample.
     *
     * Printed for every batch, never on request, because a result and a result over four repeated
     * games look identical and only one of them means anything. The threshold below is a prompt to
     * look rather than a verdict: a genuinely drawish pairing can repeat a game honestly.
     */
    private fun reportDiversity(batch: BatchResult, log: (String) -> Unit) {
        val worst = batch.leastDiverse ?: return
        val total = batch.reports.mapTo(LinkedHashSet()) { it.moveStreamHash }.size

        log("[lab] $total of ${batch.reports.size} matches were distinct games (worst pairing -- $worst)")
        if (worst.fraction > SUSPICIOUS_DIVERSITY) {
            return
        }

        // Which of the two causes it is decides what the reader should do about it, and the advice
        // for one is useless for the other. Blaming the schedule for a batch that already diversified
        // it sends somebody to re-run a flag they are already passing.
        log(
            if (openings == Openings.FIXED) {
                "[lab] that pairing repeated itself: with a fixed opening, bots that draw no " +
                    "randomness play the same few games however many rounds are asked for. Try " +
                    "--openings mirrored before believing any number above."
            } else {
                "[lab] that pairing repeated itself from diversified openings, so it is the entrants " +
                    "rather than the schedule -- two that differ only in positions neither reaches " +
                    "answer most seeds identically. More rounds will not buy more sample; a field of " +
                    "other opponents will."
            },
        )
    }

    private companion object {
        /**
         * At half or below, a pairing is being asked the same question twice and answering it twice.
         *
         * Half exactly is not an arbitrary line, it is a floor with a meaning: two entrants that play
         * the same game reproduce each board's seat swap move for move, so *every* board contributes
         * one distinct game instead of two and the fraction lands on `0.5` however many rounds are
         * asked for. Measured, not assumed — `LabCommandTest` plays a bot against a re-spelling of
         * itself and pins it. So the comparison includes the boundary; a strict `<` would stay quiet
         * on the most degenerate sample there is.
         */
        const val SUSPICIOUS_DIVERSITY = 0.5
    }
}
