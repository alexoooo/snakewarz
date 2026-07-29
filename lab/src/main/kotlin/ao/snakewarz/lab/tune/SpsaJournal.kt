package ao.snakewarz.lab.tune

import java.nio.file.Path

/**
 * Every iteration an SPSA run took, on disk: the two arms, the sign vector, and what was measured.
 *
 * Enough to audit a run rather than merely to summarise it. The sign vector and the seed are what
 * make an iteration reproducible; the two specs are what make a replayed one *checkable*, because a
 * resume that silently walked a different trajectory than the one recorded would be worse than no
 * resume at all — see [Iteration.matches].
 *
 * ### A search iteration reaches no verdict, and the column says so
 *
 * A step of this search is a handful of boards played to estimate a direction, not an experiment run
 * until it settles, so its [Iteration.verdict] is [NONE] and stays that way however good the gap
 * looks. Reading one row as evidence about a setting is the exact misreading the confirming run
 * exists to prevent. The one row that does carry a verdict is that confirmation, marked by an
 * [Iteration.iteration] below zero — the convention `TuneJournal` uses, and for the same reason: a
 * log holding the sixty attempts that led to a recommendation but not the run that accepted or threw
 * it out would be missing the only row anybody acts on.
 *
 * [Iteration.gap] means one thing in both kinds of row — the score of the plus arm minus the score
 * of the minus arm — so the confirmation is legible against the search that produced it rather than
 * being a different measurement wearing the same columns.
 */
internal class SpsaJournal(file: Path) {
    private val journal = Journal(file, COLUMNS)

    /** One iteration: where it looked, what it saw, and where it left the point. */
    class Iteration(
        val iteration: Int,
        val spread: String,
        val signs: String,
        val plus: String,
        val minus: String,
        val seed: Long,
        val boards: Int,
        val gap: String,
        val verdict: String,
        val point: String,
    ) {
        /** The confirming run rather than a step of the search. */
        val confirming: Boolean get() = iteration < 0

        /**
         * Whether a recorded iteration is the one this run would play now.
         *
         * A resume replays a measurement instead of buying it again, which is only sound while the
         * search is walking the same path. A different board, allowance, knob set or start point
         * produces different arms at the same iteration, and the two specs are where that shows.
         */
        fun matches(plus: String, minus: String): Boolean = this.plus == plus && this.minus == minus

        override fun toString(): String = "$signs gap $gap over $boards boards -> $point"
    }

    /** The search's own iterations, in order, with any confirming row left out. */
    fun read(): List<Iteration> = journal.rows()
        .map {
            Iteration(
                iteration = it[0].toInt(),
                spread = it[1],
                signs = it[2],
                plus = it[3],
                minus = it[4],
                seed = it[5].toLong(),
                boards = it[6].toInt(),
                gap = it[7],
                verdict = it[8],
                point = it[9],
            )
        }
        .filter { !it.confirming }

    fun append(iteration: Iteration) {
        journal.append(
            listOf(
                iteration.iteration.toString(),
                iteration.spread,
                iteration.signs,
                iteration.plus,
                iteration.minus,
                iteration.seed.toString(),
                iteration.boards.toString(),
                iteration.gap,
                iteration.verdict,
                iteration.point,
            ),
        )
    }

    companion object {
        /** What a search iteration's verdict column holds, because a search iteration has none. */
        const val NONE: String = "-"

        /** Below this an iteration is the confirming run — see the class doc. */
        const val CONFIRMATION: Int = -1

        private val COLUMNS = listOf(
            "iteration", "spread", "signs", "plus", "minus", "seed", "boards", "gap", "verdict", "point",
        )
    }
}
