package ao.snakewarz.lab.strength

/**
 * What a turn of each entrant cost, solved out of matches whose clocks covered several of them.
 *
 * ### The problem this exists for
 *
 * An allowance is counted in **evaluations**, and an evaluation is not a fixed price: `EvaluationCost`
 * charges every kind `1` while the measured spread between a rollout and a survival appraisal is well
 * over twofold. So "equal allowance" is not "equal time", and a ladder that reports only strength
 * answers half the question — a rung that buys twelve points for two and a half times the clock is a
 * different decision from one that buys them free.
 *
 * ### Why it has to be solved rather than read
 *
 * A match's elapsed time covers **every seat on the board**. Charge it to each of them and a bot that
 * thinks for a microsecond reports its opponent's cost, which is worse than reporting nothing.
 *
 * But a batch plays many pairings, and each match is one equation: `turns_i * c_i + turns_j * c_j`
 * came to the elapsed clock. Hundreds of matches over a handful of entrants is a heavily
 * over-determined system, and the least-squares fit is the per-entrant cost that best explains all of
 * it at once. Costs cannot be negative, so the fit is clamped at zero — non-negative least squares by
 * projected coordinate descent, which has a closed form per coordinate and converges in a few passes.
 *
 * ### What the numbers are and are not
 *
 * Wall clock under whatever else the machine was doing, and inflated by however many workers were
 * competing — so the **ratios** between entrants of one batch are the measurement, and the absolute
 * figures are not. `time` is what measures one bot properly, against an opponent that spends nothing.
 */
internal fun turnCosts(ladder: Ladder): DoubleArray {
    val costs = DoubleArray(ladder.size)
    val turns = Array(ladder.matches.size) { DoubleArray(ladder.size) }
    val elapsed = DoubleArray(ladder.matches.size)
    val entrant = LinkedHashMap<String, Int>()
    ladder.specs.forEachIndexed { index, spec -> entrant[spec] = index }

    for ((match, logged) in ladder.matches.withIndex()) {
        elapsed[match] = logged.elapsedMicros.toDouble()
        for (slot in logged.slots) {
            entrant[slot.spec]?.let { turns[match][it] += slot.movesMade.toDouble() }
        }
    }

    // Sum of squared turns per entrant, which is the denominator of every coordinate step and does
    // not change as the fit moves.
    val squared = DoubleArray(ladder.size)
    for (match in turns.indices) {
        for (index in 0 until ladder.size) {
            squared[index] += turns[match][index] * turns[match][index]
        }
    }

    val residual = DoubleArray(ladder.matches.size) { elapsed[it] }
    repeat(PASSES) {
        for (index in 0 until ladder.size) {
            if (squared[index] == 0.0) {
                continue
            }

            var numerator = 0.0
            for (match in turns.indices) {
                // The residual with this entrant's current contribution added back in, which is what
                // the coordinate is being re-solved against.
                numerator += turns[match][index] * (residual[match] + turns[match][index] * costs[index])
            }

            val updated = (numerator / squared[index]).coerceAtLeast(0.0)
            val change = updated - costs[index]
            if (change != 0.0) {
                costs[index] = updated
                for (match in turns.indices) {
                    residual[match] -= turns[match][index] * change
                }
            }
        }
    }

    return costs
}

/**
 * Enough passes for a handful of entrants over a batch to settle.
 *
 * Coordinate descent on a least-squares problem this small converges in a few; the count is fixed
 * rather than tested for so that the same log always produces the same figure.
 */
private const val PASSES = 200
