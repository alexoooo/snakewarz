package ao.snakewarz.match.tournament

import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.stats.MatchStats
import ao.snakewarz.match.stats.SlotStats

/**
 * A finished match as the comparisons it settles: one per pair of slots that were on the board.
 *
 * The single place a match becomes a win, a loss or a draw. [Tournament] fills its matrix from this,
 * and so does anything measuring a batch from outside this module — which is the point, because the
 * two would otherwise be free to disagree about the same match and nothing would notice.
 *
 * ### Why the two formats are one function and not two rules
 *
 * A cell of a win matrix is "how often did the row do better than the column". Head to head that is
 * *beat*, and the engine has already decided it. Free for all it is *outlasted*, because a four-way
 * game does not produce one loser per winner: the winner outlasted the whole field, snakes eliminated
 * on the same move drew with each other, and survivors of a turn-limit game drew among themselves.
 *
 * For two contestants the two rules usually agree. A trapped sole survivor is the exception: the
 * engine calls the two fatal turns a draw, while free-for-all scoring can still distinguish how many
 * moves each snake survived. That is the argument for asking the format rather than assuming the
 * answer. Before moving-winner draws existed, 14,400 two-seat matches on a 12x12 put the outlasting
 * score and outright win rate on the same last digit for all nine shipped bots.
 *
 * ### Past two seats this rates a survival order, and that is not a victory order
 *
 * Worth stating here because a free-for-all **rating** is fitted to what this function emits, so a
 * three-seat Elo in this repository is an Elo of *outlasting*. A snake that never contests ground
 * and goes out second of the two losers scores here exactly as one that goes out second having
 * fought. P7 of the 2026-07-29 agenda measured what that costs, over all 84 triples of the nine
 * shipped bots at a 12x12, 25,200 matches, every one a distinct game:
 *
 * - **The direction survives.** With the company held still — one triple, the same matches scored
 *   twice — the two rules put the three entrants in the same order in **79 of 84** triples, and each
 *   of the five flips is between two entrants five points of win share or less apart. The rules never
 *   disagreed about a gap either of them could see.
 * - **The scale does not, and how badly is a function of how often a third snake wins.** Most of
 *   what this rule resolves is games *neither* of the compared pair won, and there it is grading two
 *   doomed snakes on the order they died in.
 *
 * | pair, 2,100 matches each | a third snake won | this rule says | of the two, who won |
 * |---|---|---|---|
 * | `pressure` vs `wallhug` | 67% | 65 / 35 | **90 / 10** |
 * | `chase` vs `space` | 55% | 63 / 37 | **76 / 24** |
 * | `alphabeta` vs `uct` | 8% | 53 / 47 | 52 / 48 |
 * | `puct` vs `uct` | 8% | 51 / 49 | 50 / 50 |
 *
 * So the top of a three-seat field is the part to trust: there a third snake almost never takes the
 * match away and the two rules nearly coincide. Down the field they diverge by the width of the
 * table, always in the same direction — **this rule flatters whoever merely survives**, which is
 * `wallhug` exactly, and `wallhug` wins 3% of its matches at three seats.
 *
 * Sound as an ordering, then, and misleading as a magnitude — which is why `:lab`'s `rate` prints
 * the outright win share beside every free-for-all rating. A rating of *winning* would need a second
 * rule here, the winner beating every loser and the losers drawing with each other, and a second
 * rule is exactly what this function exists to stop two callers inventing separately. Nobody has
 * needed one yet, and the 79 of 84 is the check that says so.
 */
public fun pairwiseOutcomes(format: TournamentFormat, stats: MatchStats): List<PairwiseOutcome> {
    val outcome = requireNotNull(stats.outcome) { "a match still being played has settled nothing" }

    return pairwiseOutcomes(
        format = format,
        alive = BooleanArray(stats.slots.size) { stats.slots[it].alive },
        movesMade = IntArray(stats.slots.size) { stats.slots[it].movesMade },
        winner = outcome.winner,
    )
}

/**
 * The same rule over the bare numbers, for a caller that has a finished match but not a live one.
 *
 * Which is what reading a match back off disk gives you: a row of numbers and no [MatchStats] to
 * rebuild them into. Having the rule in one place matters more than it looks — a batch is scored as
 * it is played *and* again whenever the log is re-read, and two implementations of "who outlasted
 * whom" would drift into disagreeing about the same recorded match.
 *
 * [winner] is what the engine decided and is used head to head; free for all it is ignored, because
 * there the question is not who won but who lasted longer than whom.
 */
public fun pairwiseOutcomes(
    format: TournamentFormat,
    alive: BooleanArray,
    movesMade: IntArray,
    winner: SnakeId,
): List<PairwiseOutcome> {
    require(alive.size == movesMade.size) {
        "${alive.size} snakes are alive or not and ${movesMade.size} of them moved"
    }

    return when (format) {
        TournamentFormat.HEAD_TO_HEAD -> {
            require(alive.size == TournamentConfig.HEAD_TO_HEAD_SEATS) {
                "head to head is a statement about a pair, and ${alive.size} snakes were playing"
            }
            listOf(PairwiseOutcome(SnakeId(0), SnakeId(1), winner))
        }

        TournamentFormat.FREE_FOR_ALL ->
            buildList {
                for (one in alive.indices) {
                    for (other in one + 1 until alive.size) {
                        add(outlasting(one, other, alive, movesMade))
                    }
                }
            }
    }
}

/**
 * Which of two snakes lasted longer, with everybody else on the board interfering.
 *
 * Both alive is a draw, and so is both eliminated on the same move — [SlotStats.movesMade] counts
 * moves *survived*, so two snakes that went out on the same turn hold the same figure.
 */
private fun outlasting(one: Int, other: Int, alive: BooleanArray, movesMade: IntArray): PairwiseOutcome {
    val winner = when {
        alive[one] == alive[other] && (alive[one] || movesMade[one] == movesMade[other]) -> SnakeId.NONE
        alive[one] || (!alive[other] && movesMade[one] > movesMade[other]) -> SnakeId(one)
        else -> SnakeId(other)
    }
    return PairwiseOutcome(SnakeId(one), SnakeId(other), winner)
}
