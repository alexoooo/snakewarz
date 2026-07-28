package ao.snakewarz.lab.strength

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.match.tournament.Ratings
import ao.snakewarz.match.tournament.TournamentFormat
import ao.snakewarz.match.tournament.TournamentTable
import ao.snakewarz.match.tournament.fitRatings
import ao.snakewarz.match.tournament.pairwiseOutcomes

/**
 * Every entrant in a set of logged matches, rated against each other.
 *
 * Built from the matches rather than from anybody's running total, which is the point of keeping
 * them: a ladder over six weeks of batches is the same computation as a ladder over one, and nothing
 * had to be summed correctly at the time.
 *
 * Entrants are keyed on the **expanded spec**, so `uct` at two allowances is two rungs, a swept knob
 * is a rung of its own, and two runs that both say `uct` are pooled only when they really were the
 * same bot at the same settings.
 */
internal class Ladder private constructor(
    val specs: List<String>,
    val table: TournamentTable,
    val matches: List<LoggedMatch>,
    private val turnCounts: LongArray,
) {
    val ratings: Ratings = fitRatings(table)

    private val solvedCosts: DoubleArray by lazy { turnCosts(this) }

    val size: Int get() = specs.size

    fun label(entrant: Int): String = table.contestants[entrant].label

    /** Turns this entrant played, so a cost can be read per turn rather than per batch. */
    fun turns(entrant: Int): Long = turnCounts[entrant]

    /**
     * What this entrant cost a turn, or `null` where the batch cannot say — see [turnCosts].
     *
     * Solved out of the batch rather than read off it, because a match's clock covers every seat and
     * charging it to all of them makes a bot that thinks for a microsecond report its opponent's
     * cost. Ratios between rungs are the measurement; the absolute figures carry whatever else the
     * machine was doing at the time.
     *
     * **`null` for an entrant that met only one opponent**, and that is not caution, it is the
     * arithmetic: with a single pairing the two seats play near-identical numbers of turns in every
     * match, so the clock can be split between them any number of ways and least squares picks one
     * of them for no reason. It takes a field to separate two costs. `time` is what measures one bot
     * on its own.
     */
    fun microsPerTurn(entrant: Int): Double? =
        if (turnCounts[entrant] == 0L || opponents(entrant) < SEPARABLE_OPPONENTS) null else solvedCosts[entrant]

    /** How many different entrants this one has faced. */
    fun opponents(entrant: Int): Int =
        (0 until size).count { it != entrant && table.played(entrant, it) > 0 }

    /**
     * How far the ladder's prediction sits from what happened, in points of score, or `null` if the
     * two never met.
     *
     * The check on the whole idea of a single number. Snakes are a rock-paper-scissors sort of game,
     * so a pair can sit a long way from what their ratings imply — and a rating that cannot say
     * where it is wrong is one nobody should tune against.
     */
    fun residual(one: Int, other: Int): Double? {
        val played = table.played(one, other)
        if (played == 0) {
            return null
        }

        val observed = (table.wins(one, other) + table.draws(one, other) / 2.0) / played
        return observed - ratings.expectedScore(one, other)
    }

    override fun toString(): String = "Ladder($size entrants over ${matches.size} matches)"

    companion object {
        /**
         * Two opponents is the fewest that can pull two costs apart.
         *
         * With one, every match seats the same pair for near-identical numbers of turns, and the
         * design matrix has no variety for a solve to work with.
         */
        private const val SEPARABLE_OPPONENTS = 2

        /**
         * Rates every entrant appearing in [matches].
         *
         * [format] decides how a match becomes comparisons, and has to be given rather than guessed:
         * a three-snake free-for-all and three head-to-head matches leave very different evidence,
         * and their rows on disk look alike.
         */
        fun of(matches: List<LoggedMatch>, registry: BotRegistry, format: TournamentFormat): Ladder {
            val specs = matches
                .flatMapTo(LinkedHashSet()) { match -> match.slots.map { it.spec } }
                .sorted()
            val entrant = LinkedHashMap<String, Int>()
            specs.forEachIndexed { index, spec -> entrant[spec] = index }

            val table = TournamentTable(specs.map { entrantOf(it, registry) })
            val turnCounts = LongArray(specs.size)

            for (match in matches) {
                val seating = IntArray(match.slots.size) { entrant.getValue(match.slots[it].spec) }
                for (comparison in comparisonsIn(match, format)) {
                    table.record(comparison, seating)
                }
                for (slot in match.slots) {
                    turnCounts[entrant.getValue(slot.spec)] += slot.movesMade.toLong()
                }
            }

            return Ladder(specs, table, matches, turnCounts)
        }

        /**
         * The recorded match, scored by the same rule that scored it when it was played.
         *
         * Through `:match`'s own function rather than by reading the winner flags here, so a batch
         * and a re-read of that batch cannot come to different conclusions.
         */
        private fun comparisonsIn(match: LoggedMatch, format: TournamentFormat) = pairwiseOutcomes(
            format = format,
            alive = BooleanArray(match.slots.size) { match.slots[it].alive },
            movesMade = IntArray(match.slots.size) { match.slots[it].movesMade },
            winner = match.winner?.let { SnakeId(it.seat) } ?: SnakeId.NONE,
        )
    }
}
