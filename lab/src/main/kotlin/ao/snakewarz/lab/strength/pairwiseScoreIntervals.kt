package ao.snakewarz.lab.strength

import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.lab.log.LoggedMatch

/** One direct head-to-head score and its shared-opening percentile interval. */
internal class PairwiseScoreInterval(
    val score: Double,
    val low: Double,
    val high: Double,
)

/**
 * Direct cells resampled by the same experimental block as the rating intervals.
 *
 * Every matchup from one sampled seed or complete opening travels together. This preserves common
 * random numbers across a field while answering the narrower qualification question: whether one
 * entrant directly clears another, independent of the fitted rating's intransitive compromises.
 */
internal fun pairwiseScoreIntervals(
    matches: List<LoggedMatch>,
    specs: List<String>,
    draws: Int = Bootstrap.DRAWS,
    seed: Long = Bootstrap.SEED,
): List<List<PairwiseScoreInterval?>> {
    require(draws > 1) { "an interval needs more than one resampling, was $draws" }
    val groups = bootstrapGroups(matches)
    val size = specs.size
    val index = specs.withIndex().associate { (at, spec) -> spec to at }
    val pointScores = DoubleArray(size * size)
    val pointGames = IntArray(size * size)
    accumulate(matches, index, size, pointScores, pointGames)

    if (groups.size < 2) {
        return List(size) { one ->
            List(size) { other ->
                val at = one * size + other
                if (one == other || pointGames[at] == 0) {
                    null
                } else {
                    PairwiseScoreInterval(pointScores[at] / pointGames[at], Double.NaN, Double.NaN)
                }
            }
        }
    }

    val samples = Array(size * size) { DoubleArray(draws) }
    val rng = SplitMix64(seed)
    val scores = DoubleArray(size * size)
    val games = IntArray(size * size)
    for (draw in 0 until draws) {
        scores.fill(0.0)
        games.fill(0)
        repeat(groups.size) {
            accumulate(groups[rng.nextInt(groups.size)], index, size, scores, games)
        }
        for (at in samples.indices) {
            samples[at][draw] = if (games[at] == 0) {
                pointScores[at] / pointGames[at]
            } else {
                scores[at] / games[at]
            }
        }
    }

    return List(size) { one ->
        List(size) { other ->
            val at = one * size + other
            if (one == other || pointGames[at] == 0) {
                null
            } else {
                val sorted = samples[at].sortedArray()
                PairwiseScoreInterval(
                    score = pointScores[at] / pointGames[at],
                    low = sorted[percentileIndex(draws, Bootstrap.LOW)],
                    high = sorted[percentileIndex(draws, Bootstrap.HIGH)],
                )
            }
        }
    }
}

private fun accumulate(
    matches: List<LoggedMatch>,
    index: Map<String, Int>,
    size: Int,
    scores: DoubleArray,
    games: IntArray,
) {
    for (match in matches) {
        if (match.slots.size != 2) {
            continue
        }
        val one = index.getValue(match.slots[0].spec)
        val other = index.getValue(match.slots[1].spec)
        val oneScore = when {
            match.isDraw -> 0.5
            match.slots[0].winner -> 1.0
            else -> 0.0
        }
        val oneAt = one * size + other
        val otherAt = other * size + one
        scores[oneAt] += oneScore
        scores[otherAt] += 1.0 - oneScore
        games[oneAt]++
        games[otherAt]++
    }
}

private fun percentileIndex(draws: Int, fraction: Double): Int =
    ((draws - 1) * fraction).toInt().coerceIn(0, draws - 1)
