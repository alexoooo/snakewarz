package ao.snakewarz.ui

import ao.snakewarz.match.tournament.TournamentTable
import ao.snakewarz.match.tournament.fitRatings
import kotlin.math.roundToInt

/**
 * The batch's matrix read as a single ordering, for under the grid.
 *
 * A matrix says who beat whom; it cannot say who is *stronger* once the field is bigger than a pair,
 * because two contestants meet different opposition and a bot can win more matches than another
 * while losing to it. A rating answers that, and a batch running in front of somebody is exactly
 * where the answer is wanted.
 *
 * Rounded to whole points and never formatted as a `Double`, following the matrix above it: number
 * formatting is a surprisingly large thing to drag into a wasm bundle, and a rating is not precise
 * to a decimal place anyway.
 *
 * A rung marked `?` is one the fit could not bound from the results — nothing beat it, or it beat
 * nothing, or it has not played the rest of the field yet, which early in a batch is most of them.
 * Saying so matters more here than anywhere: the number appears while the evidence is still arriving.
 */
internal fun batchRatings(table: TournamentTable): String {
    val ratings = fitRatings(table)
    val ranked = ratings.ranking().filter { ratings.measured(it) }
    if (ranked.size < MIN_RATED) {
        return ""
    }

    val width = ranked.maxOf { table.contestants[it].label.length }
    var anyUnbounded = false

    return buildString {
        appendLine()
        appendLine("rating")
        for (contestant in ranked) {
            val points = ratings.rating(contestant).roundToInt()
            append(table.contestants[contestant].label.padEnd(width))
            append("  ")
            append((if (points > 0) "+$points" else "$points").padStart(RATING))
            if (ratings.priorDetermined(contestant)) {
                anyUnbounded = true
                append(" ?")
            }
            appendLine()
        }
        if (anyUnbounded) {
            appendLine("? not yet bounded by results")
        }
    }
}

/** Two rungs is the fewest that can be ordered; one rated bot is a rating of nothing. */
private const val MIN_RATED = 2

private const val RATING = 5
