package ao.snakewarz.lab.championship

import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.strength.Bootstrap

/** Point estimates and shared-opening-block intervals for every directed cell and maximin. */
internal class ChampionshipStatistics(
    val cells: Array<Array<ChampionshipCell?>>,
    val maximinIntervals: List<ChampionshipInterval>,
)

/**
 * Recomputes every matrix cell and each entrant's minimum on the same resampled openings.
 *
 * A maximin interval is therefore the distribution of the minimum itself, not the minimum of
 * independently computed cell bounds. That distinction is material when two styles fail on
 * different openings, which is exactly the non-transitivity this report is meant to preserve.
 */
internal fun championshipStatistics(
    matches: List<LoggedMatch>,
    specs: List<String>,
): ChampionshipStatistics {
    val index = specs.withIndex().associate { (at, spec) -> spec to at }
    val point = scoreCounts(matches, index, specs.size)
    val groups = (0 until Openings.COMPLETE_POPULATION).map { opening ->
        val identity = "empty8-rho-${opening.toString().padStart(2, '0')}"
        scoreCounts(matches.filter { it.openingIdentity == identity }, index, specs.size)
    }

    val cellSamples = Array(specs.size * specs.size) { DoubleArray(Bootstrap.DRAWS) }
    val maximinSamples = Array(specs.size) { DoubleArray(Bootstrap.DRAWS) }
    val rng = SplitMix64(CHAMPIONSHIP_BOOTSTRAP_SEED)
    for (draw in 0 until Bootstrap.DRAWS) {
        val sample = ScoreCounts(specs.size)
        repeat(groups.size) {
            sample.add(groups[rng.nextInt(groups.size)])
        }
        for (one in specs.indices) {
            var minimum = Double.POSITIVE_INFINITY
            for (other in specs.indices) {
                if (one == other) {
                    continue
                }
                val score = sample.score(one, other)
                cellSamples[one * specs.size + other][draw] = score
                if (score < minimum) {
                    minimum = score
                }
            }
            maximinSamples[one][draw] = minimum
        }
    }

    val cells = Array(specs.size) { arrayOfNulls<ChampionshipCell>(specs.size) }
    for (one in specs.indices) {
        for (other in specs.indices) {
            if (one == other) {
                continue
            }
            val wins = point.wins(one, other)
            val draws = point.draws(one, other)
            cells[one][other] = ChampionshipCell(
                one = specs[one],
                other = specs[other],
                wins = wins,
                draws = draws,
                losses = point.wins(other, one),
                score = point.score(one, other),
                interval = interval(cellSamples[one * specs.size + other]),
            )
        }
    }

    return ChampionshipStatistics(
        cells = cells,
        maximinIntervals = maximinSamples.map(::interval),
    )
}

private class ScoreCounts(private val size: Int) {
    private val wins = IntArray(size * size)
    private val draws = IntArray(size * size)

    fun recordWin(winner: Int, loser: Int) {
        wins[winner * size + loser]++
    }

    fun recordDraw(one: Int, other: Int) {
        draws[one * size + other]++
        draws[other * size + one]++
    }

    fun wins(one: Int, other: Int): Int = wins[one * size + other]

    fun draws(one: Int, other: Int): Int = draws[one * size + other]

    fun score(one: Int, other: Int): Double {
        val wins = wins(one, other)
        val draws = draws(one, other)
        val played = wins + wins(other, one) + draws
        check(played > 0) { "no championship evidence for entrant $one against $other" }
        return (wins + draws / 2.0) / played
    }

    fun add(other: ScoreCounts) {
        check(size == other.size) { "cannot combine championship matrices of different sizes" }
        for (cell in wins.indices) {
            wins[cell] += other.wins[cell]
            draws[cell] += other.draws[cell]
        }
    }
}

private fun scoreCounts(
    matches: List<LoggedMatch>,
    index: Map<String, Int>,
    size: Int,
): ScoreCounts {
    val counts = ScoreCounts(size)
    for (match in matches) {
        val one = index.getValue(match.slots[0].spec)
        val other = index.getValue(match.slots[1].spec)
        val winners = match.slots.filter { it.winner }
        check(winners.size <= 1) { "match ${match.run}#${match.index} recorded more than one winner" }
        if (winners.isEmpty()) {
            counts.recordDraw(one, other)
        } else {
            val winner = index.getValue(winners.single().spec)
            counts.recordWin(winner, if (winner == one) other else one)
        }
    }
    return counts
}

private fun interval(samples: DoubleArray): ChampionshipInterval {
    val sorted = samples.sortedArray()
    return ChampionshipInterval(
        low = sorted[percentile(sorted.size, Bootstrap.LOW)],
        high = sorted[percentile(sorted.size, Bootstrap.HIGH)],
    )
}

private fun percentile(size: Int, fraction: Double): Int =
    ((size - 1) * fraction).toInt().coerceIn(0, size - 1)

/** Fixed so rereading the same championship log cannot move a confidence bound. */
internal const val CHAMPIONSHIP_BOOTSTRAP_SEED: Long = 20_260_801L
