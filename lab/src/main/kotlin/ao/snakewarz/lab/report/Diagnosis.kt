package ao.snakewarz.lab.report

import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.LoggedSlot
import ao.snakewarz.lab.log.RunHeader

/**
 * One entrant's matches, taken apart into the things that explain them.
 *
 * A rating says a bot is worse. It cannot say *why*, and "why" is the only part a person can act on:
 * a bot that keeps walking into walls with a free square beside it needs a different fix from one
 * that keeps getting sealed into a corner, and the two look identical in a win matrix.
 *
 * Everything here is derived from the log and nothing was counted as the matches ran — the same rule
 * `MatchStats` follows, and for the same reason. It is also why a diagnosis can be taken of a batch
 * played weeks ago against a bot that no longer exists.
 */
internal class Diagnosis(
    val spec: String,
    val matches: List<LoggedMatch>,
    private val boards: Map<String, RunHeader>,
) {
    val played: Int = matches.size

    private val seats: List<Seat> = matches.mapNotNull { match ->
        match.of(spec)?.let { Seat(match, it) }
    }

    val wins: Int = seats.count { it.mine.winner }
    val draws: Int = seats.count { it.match.isDraw }
    val losses: Int = played - wins - draws

    val scoreRate: Double = if (played == 0) 0.0 else (wins + draws / 2.0) / played

    /** How it went out, over the matches it lost. A `SUICIDE` is a blunder; a `FORFEIT` is a defect. */
    val fates: Map<String, Int> = seats
        .filter { !it.mine.alive }
        .groupingBy { it.mine.fate.ifEmpty { "UNKNOWN" } }
        .eachCount()

    /** How the match finished, over all of them. */
    val endings: Map<String, Int> = seats.groupingBy { it.match.end }.eachCount()

    /**
     * Moves survived when losing, against moves survived when winning.
     *
     * The shape of the problem, and the pair has to be read together. Losses much shorter than the
     * wins is a bot being dismantled early, which is a strategy question. Losses the same length as
     * the wins is a bot losing the endgame it reached, which is a tuning one.
     *
     * Deliberately *not* the gap to the opponent: head to head the match ends the moment somebody
     * dies, so that figure is one move whoever wins and says nothing at all.
     */
    val movesWhenLosing: List<Int> = seats.filter { it.lost }.map { it.mine.movesMade }

    val movesWhenWinning: List<Int> = seats.filter { it.mine.winner }.map { it.mine.movesMade }

    /**
     * How full the board was when it ended, in `0.0..1.0`, over the matches it lost.
     *
     * A high figure is a bot losing endgames in a maze it helped build; a low one is a bot dying
     * with the board still open, which is a different and usually cruder mistake.
     */
    val fillAtLoss: List<Double> = seats
        .filter { it.lost }
        .mapNotNull { seat ->
            boards[seat.match.run]?.let { run ->
                seat.match.slots.sumOf { it.length }.toDouble() / (run.rows * run.cols)
            }
        }

    /** Score when acting first against score when not — a bot that only wins with tempo shows here. */
    fun tempo(first: Boolean): Rate {
        val relevant = seats.filter { it.actedFirst == first }
        val scored = relevant.sumOf {
            if (it.mine.winner) {
                1.0
            } else if (it.match.isDraw) {
                0.5
            } else {
                0.0
            }
        }
        return Rate(scored, relevant.size)
    }

    /** The matches worth opening, worst first — see [Complaint]. */
    fun worst(count: Int): List<Complaint> = seats
        .filter { it.lost }
        .map { Complaint(it.match, it.mine) }
        .sortedWith(compareByDescending<Complaint> { it.blunder }.thenBy { it.mine.movesMade })
        .take(count)

    class Rate(val scored: Double, val of: Int) {
        val rate: Double get() = if (of == 0) 0.0 else scored / of

        override fun toString(): String = "$scored/$of"
    }

    /**
     * A loss worth looking at, and why this one.
     *
     * A `SUICIDE` comes first however long the game was: it means a free square was available and
     * the bot moved into an occupied one anyway, which is a decision that went wrong rather than a
     * position that was lost, and it is fixable. After that, the shortest games — the ones it was
     * never in.
     */
    class Complaint(val match: LoggedMatch, val mine: LoggedSlot) {
        val blunder: Boolean get() = mine.fate == "SUICIDE"

        override fun toString(): String =
            "${match.run}#${match.index} seed ${match.seed}, ${mine.movesMade} moves, ${mine.fate}"
    }

    private class Seat(val match: LoggedMatch, val mine: LoggedSlot) {
        val actedFirst: Boolean get() = match.turnOrder.firstOrNull() == mine.seat

        val lost: Boolean get() = !mine.winner && !match.isDraw
    }
}
