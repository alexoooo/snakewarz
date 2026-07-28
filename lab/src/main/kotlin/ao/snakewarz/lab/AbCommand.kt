package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.Replays
import ao.snakewarz.lab.log.recordBatch
import ao.snakewarz.lab.strength.Sprt
import ao.snakewarz.lab.strength.pairScores
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Plays a candidate against a baseline until the result is conclusive, then stops.
 *
 * The decision procedure the whole tuning loop rests on. A batch of a fixed size answers "what
 * happened"; this answers "is it better, and how sure are we" — which is the only question worth
 * asking of a change, and the one a win matrix cannot be read for without inventing a threshold.
 *
 * Boards are played in blocks and the test consulted after each, so a plain result costs a fraction
 * of a conclusive-looking one. Each block moves on to fresh seeds: replaying the same boards would
 * add matches without adding evidence, which is precisely the failure a sequential test is most
 * exposed to.
 */
internal class AbCommand(
    val baseline: Contestant,
    val candidate: Contestant,
    val rows: Int,
    val cols: Int,
    val seed: Long,
    val budgetPerTurn: Int,
    val openings: Openings,
    val threads: Int,
    val sprt: Sprt,
    val blockPairs: Int,
    val maxPairs: Int,
    val logDirectory: Path?,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        log("[lab] $candidate against $baseline on ${rows}x$cols at $budgetPerTurn evaluations")
        log("[lab] $sprt, stopping between ${sprt.lower.round()} and ${sprt.upper.round()}")

        val started = TimeSource.Monotonic.markNow()
        val scores = mutableListOf<Double>()
        var report = sprt.test(scores)
        var block = 0

        while (report.verdict == Sprt.Verdict.UNDECIDED && scores.size < maxPairs) {
            val batch = Arena(
                config = configFor(block),
                registry = registry,
                openings = openings,
                threads = threads,
                keepRecords = logDirectory != null,
            ).run()

            if (batch.forfeits > 0) {
                log("[lab] ${batch.forfeits} FORFEITS -- a bot threw. Fix that before believing anything below.")
            }
            logDirectory?.let {
                recordBatch(MatchLog(it), batch, registry, openings.name, threads, Replays.DECISIVE)
            }

            scores += pairScores(batch, CANDIDATE)
            report = sprt.test(scores)
            block++

            log("[lab] ${scores.size} boards, LLR ${report.llr.round()} ${bar(report)} ${outcome(report)}")
        }

        log("")
        log(conclusion(report))
        log(
            "[lab] ${scores.size} boards in ${started.elapsedNow().inWholeSeconds}s, " +
                "standardized effect ${report.effect.round(2)} per board",
        )
        if (report.verdict == Sprt.Verdict.UNDECIDED && scores.size >= maxPairs) {
            log("[lab] stopped at the --max-pairs ceiling, not because the evidence settled.")
        }
    }

    override fun toString(): String = "Ab($candidate vs $baseline, $sprt)"

    /**
     * Fresh boards for every block.
     *
     * A block is [blockPairs] boards, each played from both seats, so the seeds it consumes are
     * `seed + block * blockPairs` onwards and the next block starts where this one stopped. Blocks
     * that shared seeds would add matches to the sample without adding anything to the evidence, and
     * the test has no way of telling the difference.
     */
    private fun configFor(block: Int): TournamentConfig = TournamentConfig(
        contestants = listOf(baseline, candidate),
        rows = rows,
        cols = cols,
        rounds = blockPairs * MATCHES_PER_BOARD,
        seed = seed + block.toLong() * blockPairs,
        budgetPerTurn = budgetPerTurn,
    )

    private fun conclusion(report: Sprt.Report): String {
        val measured = report.elo?.let { elo ->
            val margin = report.eloMargin
            "measured ${elo.round()} Elo" + if (margin == null) "" else " +-${margin.round()}"
        } ?: "won or lost every board, so the difference has no upper bound"

        return when (report.verdict) {
            Sprt.Verdict.BETTER ->
                "[lab] BETTER: ${candidate.label} is at least ${sprt.elo1.round()} Elo above " +
                    "${baseline.label} -- $measured"

            Sprt.Verdict.NO_BETTER ->
                "[lab] NO BETTER: ${candidate.label} is not ${sprt.elo1.round()} Elo above " +
                    "${baseline.label} -- $measured"

            Sprt.Verdict.UNDECIDED ->
                "[lab] UNDECIDED: the evidence sits between the bounds -- $measured"
        }
    }

    /** Where the likelihood ratio has got to, between the two stopping bounds. */
    private fun bar(report: Sprt.Report): String {
        val span = report.upper - report.lower
        val at = ((report.llr - report.lower) / span * BAR).toInt().coerceIn(0, BAR)
        return "[" + "=".repeat(at) + " ".repeat(BAR - at) + "]"
    }

    private fun outcome(report: Sprt.Report): String {
        val elo = report.elo ?: return "no losses yet"
        return "${elo.round()} Elo"
    }

    private fun Double.round(places: Int = 0): String =
        if (places == 0) {
            roundToInt().toString()
        } else {
            val factor = if (places == 1) 10 else 100
            "${(this * factor).roundToInt().toDouble() / factor}"
        }

    private companion object {
        /** Baseline enters first, so the candidate is contestant one. */
        const val CANDIDATE = 1

        /** A board is played from both seats, which is what makes it one observation. */
        const val MATCHES_PER_BOARD = 2

        const val BAR = 20
    }
}
