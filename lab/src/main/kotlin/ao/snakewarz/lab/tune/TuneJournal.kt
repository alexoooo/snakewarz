package ao.snakewarz.lab.tune

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Every decision a search has taken, on disk, so an interrupted sweep resumes instead of restarting.
 *
 * A search is a long chain of short experiments, and the expensive part is the games rather than the
 * arithmetic between them. Writing each verdict down as it lands means a sweep left running overnight
 * survives a kill, a reboot or somebody needing the machine — and, just as usefully, that what it
 * concluded can be read afterwards without trusting a summary printed at the end.
 *
 * A replayed decision is **not** re-played: the verdict is read back and the search walks the same
 * path to where it stopped. Which is only sound because a decision is a pure function of the
 * incumbent, the proposal and the seeds, all of which are recorded here.
 */
internal class TuneJournal(private val file: Path) {
    /** One experiment: what was tried, against what, over which boards, and what came of it. */
    class Decision(
        val pass: Int,
        val stride: Int,
        val knob: String,
        val incumbent: String,
        val proposal: String,
        val seed: Long,
        val verdict: String,
        val elo: String,
        val boards: Int,
    ) {
        val accepted: Boolean get() = verdict == ACCEPTED

        /**
         * A confirming run rather than a step of the search, marked by a [pass] below zero.
         *
         * It is written to the same journal because it is the one decision anybody acts on, and a
         * log that recorded the eleven experiments leading to a recommendation but not the run that
         * accepted or threw it out would be missing the only row that matters. It is filtered back
         * out on a resume: replaying it as a search step would seat a decision taken against
         * different seeds, at a different bound, into the middle of the descent.
         */
        val confirming: Boolean get() = pass < 0

        override fun toString(): String = "$verdict, $elo Elo over $boards boards"

        companion object {
            const val ACCEPTED: String = "BETTER"
        }
    }

    fun read(): List<Decision> {
        if (!file.exists()) {
            return emptyList()
        }

        return file.readLines()
            .drop(1)
            .map { it.split('\t') }
            .filter { it.size == COLUMNS.size }
            .map {
                Decision(
                    pass = it[0].toInt(),
                    stride = it[1].toInt(),
                    knob = it[2],
                    incumbent = it[3],
                    proposal = it[4],
                    seed = it[5].toLong(),
                    verdict = it[6],
                    elo = it[7],
                    boards = it[8].toInt(),
                )
            }
    }

    fun append(decision: Decision) {
        Files.createDirectories(file.parent)

        val row = listOf(
            decision.pass.toString(),
            decision.stride.toString(),
            decision.knob,
            decision.incumbent,
            decision.proposal,
            decision.seed.toString(),
            decision.verdict,
            decision.elo,
            decision.boards.toString(),
        )
        for (field in row) {
            require(field.none { it == '\t' || it == '\n' || it == '\r' }) {
                "a separator inside a field would corrupt the journal silently: '$field'"
            }
        }

        val text = buildString {
            if (!file.exists()) {
                appendLine(COLUMNS.joinToString("\t"))
            }
            appendLine(row.joinToString("\t"))
        }
        Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private companion object {
        val COLUMNS = listOf(
            "pass", "stride", "knob", "incumbent", "proposal", "seed", "verdict", "elo", "boards",
        )
    }
}
