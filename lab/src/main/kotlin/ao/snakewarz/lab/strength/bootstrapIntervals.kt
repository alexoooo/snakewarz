package ao.snakewarz.lab.strength

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.match.tournament.TournamentFormat

/** A rating's uncertainty: the middle [Bootstrap.CONFIDENCE] of what re-sampled evidence produced. */
internal class Interval(val low: Double, val high: Double) {
    val width: Double get() = high - low

    override fun toString(): String = "${low.toInt()}..${high.toInt()}"
}

/**
 * Error bars for a ladder, by refitting it over resampled evidence.
 *
 * ### Why resample matches and not games
 *
 * Because the matches are not independent, and the schedule makes them dependent **on purpose**. Two
 * matches of a mirrored pair are the same board with the players exchanged; a free-for-all writes
 * one comparison per pair of snakes out of a single game. A formula that assumed independence — a
 * standard error off the fit's curvature, say — would report intervals far tighter than the evidence
 * supports, and a tuner reading them would accept changes that are noise.
 *
 * Resampling whole **seed groups** sidesteps all of it. Whatever correlation lives inside a group
 * travels with it, and the resampling never has to know what that correlation was. It costs a refit
 * per draw, and a refit is milliseconds.
 *
 * ### Percentiles, not a standard deviation
 *
 * A rating distribution is skewed wherever the record is thin — a bot that lost every game has a
 * long tail downwards and none upwards — so a symmetric interval would claim precision on the wrong
 * side. The percentile interval simply reports where the refits landed.
 *
 * Deterministic: the draw comes from [SplitMix64] at a fixed seed, so the same log gives the same
 * bars every time. An error bar that moved when you looked again would be worse than none.
 */
internal fun bootstrapIntervals(
    ladder: Ladder,
    registry: BotRegistry,
    format: TournamentFormat,
    draws: Int = Bootstrap.DRAWS,
    seed: Long = Bootstrap.SEED,
): List<Interval> {
    require(draws > 1) { "an interval needs more than one resampling, was $draws" }

    val groups = ladder.matches.groupBy { it.run to it.pairKey }.values.toList()
    if (groups.size < 2) {
        // One group is one board. Nothing can be resampled out of it, and a bar drawn from that
        // would say "certain" about a single game.
        return List(ladder.size) { Interval(Double.NaN, Double.NaN) }
    }

    val samples = List(ladder.size) { DoubleArray(draws) }
    val rng = SplitMix64(seed)

    for (draw in 0 until draws) {
        val resampled = ArrayList<LoggedMatch>(ladder.matches.size)
        repeat(groups.size) {
            resampled += groups[rng.nextInt(groups.size)]
        }

        val refit = Ladder.of(resampled, registry, format)
        for (entrant in 0 until ladder.size) {
            // A resampling can leave an entrant out entirely, and a rating it did not earn would
            // widen its own bar with a number about nobody. Its own point estimate is the neutral
            // stand-in: it neither widens nor narrows what the other draws said.
            val index = refit.specs.indexOf(ladder.specs[entrant])
            samples[entrant][draw] =
                if (index >= 0 && refit.ratings.measured(index)) {
                    refit.ratings.rating(index)
                } else {
                    ladder.ratings.rating(entrant)
                }
        }
    }

    return List(ladder.size) { entrant ->
        if (!ladder.ratings.measured(entrant)) {
            return@List Interval(Double.NaN, Double.NaN)
        }

        val sorted = samples[entrant].sortedArray()
        Interval(sorted[percentile(draws, Bootstrap.LOW)], sorted[percentile(draws, Bootstrap.HIGH)])
    }
}

/**
 * The same bars for [Ladder.winShare], in points of percent rather than of Elo.
 *
 * A separate function rather than a column of the one above because it resamples the same groups and
 * then *counts* instead of refitting: a win share is an average over matches, so nothing has to be
 * solved and four hundred draws are microseconds. Reported on the same seed-group unit for the
 * reason that function gives — the three comparisons a free-for-all writes out of one game are not
 * independent, and neither are the matches of one seed group, which is exactly what a group is.
 *
 * Returned as a fraction in `0.0..1.0`, `NaN..NaN` where the entrant never played or there is only
 * one group to resample.
 */
internal fun winShareIntervals(
    ladder: Ladder,
    draws: Int = Bootstrap.DRAWS,
    seed: Long = Bootstrap.SEED,
): List<Interval> {
    require(draws > 1) { "an interval needs more than one resampling, was $draws" }

    val groups = ladder.matches.groupBy { it.run to it.pairKey }.values.toList()
    if (groups.size < 2) {
        return List(ladder.size) { Interval(Double.NaN, Double.NaN) }
    }

    val index = ladder.specs.withIndex().associate { (at, spec) -> spec to at }
    val samples = List(ladder.size) { DoubleArray(draws) }
    val rng = SplitMix64(seed)

    val seats = IntArray(ladder.size)
    val wins = IntArray(ladder.size)
    for (draw in 0 until draws) {
        seats.fill(0)
        wins.fill(0)
        repeat(groups.size) {
            for (match in groups[rng.nextInt(groups.size)]) {
                for (slot in match.slots) {
                    val entrant = index.getValue(slot.spec)
                    seats[entrant]++
                    if (slot.winner) {
                        wins[entrant]++
                    }
                }
            }
        }
        for (entrant in 0 until ladder.size) {
            // A draw that seated this entrant nowhere says nothing about it; its own figure neither
            // widens nor narrows what the rest said, the same stand-in the rating bars use.
            samples[entrant][draw] =
                if (seats[entrant] == 0) {
                    ladder.winShare(entrant) ?: Double.NaN
                } else {
                    wins[entrant].toDouble() / seats[entrant]
                }
        }
    }

    return List(ladder.size) { entrant ->
        if (ladder.winShare(entrant) == null) {
            return@List Interval(Double.NaN, Double.NaN)
        }

        val sorted = samples[entrant].sortedArray()
        Interval(sorted[percentile(draws, Bootstrap.LOW)], sorted[percentile(draws, Bootstrap.HIGH)])
    }
}

private fun percentile(draws: Int, fraction: Double): Int =
    ((draws - 1) * fraction).toInt().coerceIn(0, draws - 1)

internal object Bootstrap {
    /**
     * Enough draws that the ends of the interval are about the evidence rather than about the draw.
     *
     * A refit is a few hundred microseconds on a field this size, so this is well under a second and
     * there is no reason to be stingy.
     */
    const val DRAWS: Int = 400

    const val CONFIDENCE: String = "95%"

    const val LOW: Double = 0.025
    const val HIGH: Double = 0.975

    /** Fixed, so the same log always draws the same bars. */
    const val SEED: Long = 20_051_113L
}
