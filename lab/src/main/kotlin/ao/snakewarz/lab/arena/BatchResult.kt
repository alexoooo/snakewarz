package ao.snakewarz.lab.arena

import ao.snakewarz.match.tournament.TournamentConfig
import ao.snakewarz.match.tournament.TournamentTable

/**
 * What a batch produced: the matrix, and the things a matrix cannot say.
 *
 * The matrix is filled through `TournamentTable.record`, the same way `Tournament` fills its own, so
 * a batch played here and a batch played there cannot come to different conclusions about the same
 * games.
 */
internal class BatchResult(
    val config: TournamentConfig,
    val reports: List<MatchReport>,
) {
    val table: TournamentTable = TournamentTable(config.contestants).also { filling ->
        for (report in reports) {
            for (comparison in report.comparisons) {
                filling.record(comparison, report.seating)
            }
        }
    }

    val turnsPlayed: Long = reports.sumOf { it.stats.turnsPlayed.toLong() }

    /** A bot that threw. Always a defect — a batch that reports one has measured a bug, not a bot. */
    val forfeits: Int = reports.sumOf { it.forfeits }

    /** Total wall clock across every worker, so it exceeds the elapsed time of a threaded run. */
    val botMicros: Long = reports.sumOf { it.elapsedMicros }

    /**
     * How many of the matches played were different games, per pairing and worst-first.
     *
     * **The honest sample size**, and the reason it is computed for every batch rather than on
     * request. Nothing else in a result distinguishes a hundred matches from four matches played
     * twenty-five times each, and the second of those is what a fixed opening gives two bots that
     * draw no randomness.
     */
    val diversity: List<PairingDiversity> = buildDiversity()

    val leastDiverse: PairingDiversity? = diversity.minByOrNull { it.fraction }

    /** One pairing's honest sample size: how many of its matches were distinct games. */
    class PairingDiversity(val label: String, val distinct: Int, val played: Int) {
        val fraction: Double get() = distinct.toDouble() / played

        override fun toString(): String = "$label: $distinct of $played distinct"
    }

    private fun buildDiversity(): List<PairingDiversity> {
        // A pairing is a block of `rounds` consecutive matches head to head, and the whole schedule
        // free for all -- where matchCount is rounds, so the same arithmetic lands on one block.
        val pairings = config.matchCount / config.rounds
        val played = List(pairings) { mutableListOf<MatchReport>() }
        for (report in reports) {
            played[report.index / config.rounds] += report
        }

        return played.filter { it.isNotEmpty() }.map { block ->
            PairingDiversity(
                label = block.first().seating.joinToString(" vs ") { config.contestants[it].label },
                distinct = block.mapTo(LinkedHashSet()) { it.moveStreamHash }.size,
                played = block.size,
            )
        }
    }
}
