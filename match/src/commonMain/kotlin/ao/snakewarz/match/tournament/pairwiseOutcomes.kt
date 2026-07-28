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
 * For two contestants the two rules agree, but only because of an engine detail: [ao.snakewarz.core.rules.Board]
 * resolves a field of two down to `LAST_SNAKE_STANDING` the instant one of them dies, so the
 * `movesMade` comparison below never has to break a tie. That is one rules change away from not
 * holding, which is the argument for asking the format rather than assuming the answer.
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
