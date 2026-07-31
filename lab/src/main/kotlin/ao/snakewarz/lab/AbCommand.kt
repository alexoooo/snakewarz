package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.Replays
import ao.snakewarz.lab.log.expandedSpec
import ao.snakewarz.lab.log.recordBatch
import ao.snakewarz.lab.strength.SequentialTest
import ao.snakewarz.lab.strength.Sprt
import ao.snakewarz.match.tournament.Contestant
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
 * [SequentialTest] plays it; what this adds is the reading — the running bar, the match log, and the
 * note that says when a null verdict is this instrument's blind spot rather than the change's size.
 */
internal class AbCommand(
    val baseline: Contestant,
    val candidate: Contestant,
    val rows: Int,
    val cols: Int,
    val seed: Long,
    val budgetPerTurn: Int,
    val walls: IntArray,
    val openings: Openings,
    val threads: Int,
    val sprt: Sprt,
    val blockPairs: Int,
    val maxPairs: Int,
    val logDirectory: Path?,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        log(
            "[lab] $candidate against $baseline on ${rows}x$cols with ${walls.size} walls " +
                "at $budgetPerTurn evaluations",
        )
        log("[lab] $sprt, stopping between ${sprt.lower.round()} and ${sprt.upper.round()}")

        val started = TimeSource.Monotonic.markNow()
        val outcome = SequentialTest(
            baseline = baseline,
            candidate = candidate,
            rows = rows,
            cols = cols,
            seed = seed,
            budgetPerTurn = budgetPerTurn,
            walls = walls,
            openings = openings,
            threads = threads,
            sprt = sprt,
            blockPairs = blockPairs,
            maxPairs = maxPairs,
            keepRecords = logDirectory != null,
        ).run(registry) { batch, sofar ->
            if (batch.forfeits > 0) {
                log("[lab] ${batch.forfeits} FORFEITS -- a bot threw. Fix that before believing anything below.")
            }
            logDirectory?.let {
                recordBatch(MatchLog(it), batch, registry, openings.name, threads, Replays.DECISIVE)
            }
            log(
                "[lab] ${sofar.boards} boards, LLR ${sofar.report.llr.round()} " +
                    "${bar(sofar.report)} ${elo(sofar.report)}",
            )
        }

        log("")
        log(conclusion(outcome.report))
        log(
            "[lab] ${outcome.boards} boards in ${started.elapsedNow().inWholeSeconds}s, " +
                "standardized effect ${outcome.report.effect.round(2)} per board",
        )
        if (outcome.cappedOut) {
            log("[lab] stopped at the --max-pairs ceiling, not because the evidence settled.")
        }
        blindness(outcome, registry, log)
    }

    /**
     * Warns when a null result may be this test being unable to see the change rather than its size.
     *
     * A head-to-head test can only measure what two entrants do **to each other**, and a change can
     * be worth a great deal without altering that. `ChaseBot.ROOM_SHARE` is the worked example: it
     * refuses a step into a pocket, the pocket is one the bot's own approach walks into, so an
     * opponent running the same approach is in the same corridor at the same moment and the guard
     * changes nothing between them. `ab` measured `1 Elo +-3` over 260 boards; a field of other
     * opponents rated it `+14`. Both numbers are correct answers to different questions.
     *
     * The signature is the split rate. Two entrants that play the same game share every mirrored
     * board exactly — that is what makes a mirrored opening fair, and identical entrants score 0.5
     * on every board of it. So a `NO_BETTER` verdict sitting on top of a pile of exact splits is a
     * change that mostly did not happen in this pairing, and the honest next move is a field.
     */
    private fun blindness(outcome: SequentialTest.Outcome, registry: BotRegistry, log: (String) -> Unit) {
        if (outcome.report.verdict != Sprt.Verdict.NO_BETTER || outcome.boards == 0) {
            return
        }
        if (outcome.splits * 2 < outcome.boards) {
            return
        }

        // Spelled out rather than labelled, because this line is meant to be pasted: `uct@4k` names a
        // column and `uct:budget=4000` names an entrant, and only one of them parses.
        val entrants = listOf(baseline, candidate).joinToString(" ") { expandedSpec(it, registry, budgetPerTurn) }

        log(
            "[lab] NOTE: ${outcome.splits} of ${outcome.boards} boards split exactly, so on most of " +
                "them these two played the same game and this test never saw the change. That is " +
                "expected of a change that only shows against *other* opponents -- measure one " +
                "against a field:",
        )
        log("[lab]   play $entrants <others...> --rounds 600   then   rate")
    }

    override fun toString(): String = "Ab($candidate vs $baseline, $sprt)"

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

    private fun elo(report: Sprt.Report): String {
        val measured = report.elo ?: return "no losses yet"
        return "${measured.round()} Elo"
    }

    private fun Double.round(places: Int = 0): String =
        if (places == 0) {
            roundToInt().toString()
        } else {
            val factor = if (places == 1) 10 else 100
            "${(this * factor).roundToInt().toDouble() / factor}"
        }

    private companion object {
        const val BAR = 20
    }
}
