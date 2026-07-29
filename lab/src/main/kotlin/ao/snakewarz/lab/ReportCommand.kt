package ao.snakewarz.lab

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.resolveSpec
import ao.snakewarz.lab.report.Diagnosis
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * Why one entrant lost, over everything the log holds about it.
 *
 * The half of the loop a rating cannot do. A rating orders bots; this says what to change — whether
 * the losses are blunders or squeezes, early or late, from tempo or from position — and then hands
 * over the worst of them as links, because the last step of understanding a loss is watching it.
 *
 * [subject] and [against] are names in the sense [resolveSpec] defines: a slug plus whatever subset
 * of its knobs it takes to pick one of the entrants the log holds.
 */
internal class ReportCommand(
    val subject: String,
    val against: String?,
    val worst: Int,
    val logDirectory: Path,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val store = MatchLog(logDirectory)
        val runs = store.runs().associateBy { it.id }
        require(runs.isNotEmpty()) { "nothing has been played into $logDirectory yet. Run `play` first." }

        val everything = store.matches()
        val specs = everything.flatMapTo(LinkedHashSet()) { match -> match.slots.map { it.spec } }

        val spec = resolveSpec(subject, specs)
        val opponent = against?.let { resolveSpec(it, specs) }
        val mine = everything.filter { match ->
            match.slots.any { it.spec == spec } &&
                (opponent == null || match.slots.any { it.spec == opponent })
        }
        require(mine.isNotEmpty()) {
            "$spec has played nothing" + if (opponent == null) " in $logDirectory" else " against $opponent"
        }

        val diagnosis = Diagnosis(spec, mine, runs)
        log("[lab] $spec" + if (opponent == null) "" else " against $opponent")
        log("")
        record(diagnosis, log)
        fates(diagnosis, log)
        shape(diagnosis, log)
        tempo(diagnosis, log)
        opponent?.let { flips(mine, spec, it, log) }
        complaints(diagnosis, store, log)
    }

    override fun toString(): String = "Report($subject, against=$against)"

    private fun record(diagnosis: Diagnosis, log: (String) -> Unit) {
        log(
            "record   ${diagnosis.wins}W ${diagnosis.draws}D ${diagnosis.losses}L " +
                "of ${diagnosis.played}   ${percent(diagnosis.scoreRate)}",
        )
    }

    /**
     * How it goes out, which is the first thing to look at and the cheapest to fix.
     *
     * `TRAPPED` is losing a position. `SUICIDE` is losing a *decision* — a free square was there and
     * it moved into an occupied one — so a bot showing many is leaving something on the table that
     * no amount of tuning will find. `FORFEIT` is not a result at all; it is a bot that threw.
     */
    private fun fates(diagnosis: Diagnosis, log: (String) -> Unit) {
        if (diagnosis.fates.isEmpty()) {
            return
        }

        log("")
        log("how it went out")
        for ((fate, count) in diagnosis.fates.entries.sortedByDescending { it.value }) {
            log("  ${fate.lowercase().padEnd(FATE)} $count".padEnd(COLUMN) + explain(fate))
        }
        diagnosis.fates["FORFEIT"]?.let {
            log("  FORFEIT is a bot that threw. That is a defect, not a result -- fix it first.")
        }
    }

    private fun explain(fate: String): String = when (fate) {
        "TRAPPED" -> "no free square was left -- a position it had already lost"
        "SUICIDE" -> "a free square was there and it moved into an occupied one"
        "RESIGNED" -> "it gave up"
        "FORFEIT" -> "it threw"
        else -> ""
    }

    /**
     * How its losses differ from its wins, which is what says where to look.
     *
     * Losses much shorter than the wins is a bot being taken apart early — a strategy problem.
     * Losses the same length is a bot losing endgames it reached — a tuning one. The board's fill
     * says the same thing from the other side.
     */
    private fun shape(diagnosis: Diagnosis, log: (String) -> Unit) {
        val losing = diagnosis.movesWhenLosing.sorted()
        if (losing.isEmpty()) {
            return
        }

        log("")
        log("shape of its losses")
        val winning = diagnosis.movesWhenWinning.sorted()
        if (winning.isEmpty()) {
            log("  lasted         ${median(losing)} moves at the median, ${losing.first()} at the shortest")
        } else {
            log(
                "  lasted         ${median(losing)} moves at the median, against ${median(winning)} when it wins" +
                    if (median(losing) * EARLY < median(winning)) "  <- it is being taken apart early" else "",
            )
        }
        if (diagnosis.fillAtLoss.isNotEmpty()) {
            val fill = median(diagnosis.fillAtLoss.sorted())
            log(
                "  board was      ${percent(fill)} full -- " +
                    if (fill > CRAMPED) {
                        "these are endgames in a maze it helped build"
                    } else {
                        "it is dying with the board still open"
                    },
            )
        }
        log(
            "  match ended    " + diagnosis.endings.entries.sortedByDescending { it.value }
                .joinToString { "${it.key.lowercase().replace('_', ' ')} ${it.value}" },
        )
    }

    /**
     * Whether it only wins when it moves first.
     *
     * Acting first is a real advantage on this board, which is why every board is played from both
     * seats. A wide gap here is a bot whose strength is the tempo rather than the play, and it will
     * not survive a fair schedule.
     */
    private fun tempo(diagnosis: Diagnosis, log: (String) -> Unit) {
        val first = diagnosis.tempo(first = true)
        val later = diagnosis.tempo(first = false)
        if (first.of == 0 || later.of == 0) {
            return
        }

        log("")
        log("tempo")
        log("  moving first   ${percent(first.rate)} of ${first.of}")
        log("  moving later   ${percent(later.rate)} of ${later.of}")
        if (first.rate - later.rate > TEMPO_GAP) {
            log("  Most of its score is the first move, not the play.")
        }
    }

    /**
     * Which boards the two split, for a paired comparison.
     *
     * The question a change is really being asked: not "is the total better" but "which positions
     * did it start winning, and which did it stop". A board where both matches flipped is where the
     * difference lives.
     */
    private fun flips(matches: List<LoggedMatch>, spec: String, opponent: String, log: (String) -> Unit) {
        val boards = matches.groupBy { it.run to it.pairKey }
        val swept = mutableListOf<Long>()
        val lost = mutableListOf<Long>()

        for ((_, played) in boards) {
            val scored = played.sumOf { match ->
                val mine = match.of(spec) ?: return@sumOf 0.0
                if (mine.winner) {
                    1.0
                } else if (match.isDraw) {
                    0.5
                } else {
                    0.0
                }
            }
            when {
                scored == played.size.toDouble() -> swept += played.first().seed
                scored == 0.0 -> lost += played.first().seed
            }
        }

        log("")
        log("boards against ${opponent.substringBefore(':')}  (${boards.size} of them)")
        log("  swept ${swept.size}${sample(swept)}")
        log("  lost outright ${lost.size}${sample(lost)}")
        log("  split ${boards.size - swept.size - lost.size}")
    }

    private fun sample(seeds: List<Long>): String =
        if (seeds.isEmpty()) {
            ""
        } else {
            "   seeds ${seeds.sorted().take(SEEDS).joinToString()}" +
                if (seeds.size > SEEDS) ", ..." else ""
        }

    /**
     * The losses worth opening, as links.
     *
     * The last step of understanding a loss is watching it, and this is where the numbers hand over
     * to a person. A payload is a whole match — board, seed, allowances, knob values, every move — so
     * the link is the game and not a pointer to a file that has to still exist.
     */
    private fun complaints(diagnosis: Diagnosis, store: MatchLog, log: (String) -> Unit) {
        val worst = diagnosis.worst(this.worst)
        if (worst.isEmpty()) {
            return
        }

        log("")
        log("worst losses")
        for (complaint in worst) {
            val payload = store.replay(complaint.match.run, complaint.match.index)
            log(
                "  seed ${complaint.match.seed}, out after ${complaint.mine.movesMade} moves, " +
                    complaint.mine.fate.lowercase() +
                    (if (complaint.blunder) "  <- a decision, not a position" else ""),
            )
            log("    " + (payload?.let { "#r=$it" } ?: "(not recorded -- this batch ran with --replays none)"))
        }
        log("")
        log("  Paste a link after the page URL to watch it.")
    }

    private fun median(sorted: List<Int>): Int = sorted[sorted.size / 2]

    private fun median(sorted: List<Double>): Double = sorted[sorted.size / 2]

    private fun percent(rate: Double): String = "${(rate * 100).roundToInt()}%"

    private companion object {
        const val FATE = 10
        const val COLUMN = 20
        const val SEEDS = 6

        /** Above this the board is a maze, and a loss in one is an endgame rather than a blunder. */
        const val CRAMPED = 0.5

        /** Losing in under half the moves a win takes is not the same game going slightly wrong. */
        const val EARLY = 2

        /** Twenty points of score between the two seatings is a bot living on the first move. */
        const val TEMPO_GAP = 0.2
    }
}
