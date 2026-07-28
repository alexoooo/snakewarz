package ao.snakewarz.lab.strength

import ao.snakewarz.lab.arena.BatchResult

/**
 * What [subject] scored on each board, in `0.0..1.0`, one entry per group of matches sharing one.
 *
 * ### Why the board and not the match is the unit
 *
 * The schedule plays each seed twice with the seats exchanged, so the two matches of a pair are the
 * same position from both sides. Counted separately they are two correlated observations and every
 * interval drawn from them is too tight; counted together they are one observation of "how did this
 * bot do on *this board*", and the seating — which is a real advantage here — cancels inside it
 * rather than adding noise across the sample.
 *
 * The pair score takes five values (`0`, `¼`, `½`, `¾`, `1`), which is what the chess engine world
 * calls a pentanomial. Its variance is what a sequential test needs, and it is a great deal smaller
 * than twice a single game's, which is the whole reason the schedule is built this way.
 *
 * A free-for-all group is a rotation of the seating rather than a swap, and the same argument
 * applies to it unchanged: everybody has started from every seat on that board by the end of one.
 */
internal fun pairScores(batch: BatchResult, subject: Int): List<Double> {
    val boards = LinkedHashMap<Int, DoubleArray>()

    for (report in batch.reports) {
        val tally = boards.getOrPut(report.pairKey) { DoubleArray(2) }
        for (comparison in report.comparisons) {
            val one = report.seating[comparison.one.index]
            val other = report.seating[comparison.other.index]
            if (one != subject && other != subject) {
                continue
            }

            tally[PLAYED]++
            tally[SCORED] += when {
                comparison.isDraw -> DRAW
                report.seating[comparison.winner.index] == subject -> WIN
                else -> LOSS
            }
        }
    }

    return boards.values.filter { it[PLAYED] > 0 }.map { it[SCORED] / it[PLAYED] }
}

private const val SCORED = 0
private const val PLAYED = 1

private const val WIN = 1.0
private const val DRAW = 0.5
private const val LOSS = 0.0
