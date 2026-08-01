package ao.snakewarz.lab.policy

import ao.snakewarz.core.random.SplitMix64

internal class PolicyMetricPoint(
    val block: String,
    val yes: Boolean,
)

/** A binary rate and its deterministic match-block bootstrap interval. */
internal class PolicyRate(
    val count: Int,
    val total: Int,
    val low: Double,
    val high: Double,
) {
    val rate: Double get() = if (total == 0) Double.NaN else count.toDouble() / total
}

internal fun policyRate(
    points: List<PolicyMetricPoint>,
    seed: Long,
    draws: Int = DRAWS,
): PolicyRate {
    require(draws > 1) { "an interval needs more than one draw, was $draws" }

    val count = points.count { it.yes }
    val groups = points.groupBy { it.block }.values.toList()
    // A finite saturated sample cannot bootstrap the unseen outcome. Printing [0,0] or [1,1]
    // would claim certainty the experiment did not earn, so that boundary interval is unavailable.
    if (groups.size < 2 || count == 0 || count == points.size) {
        return PolicyRate(count, points.size, Double.NaN, Double.NaN)
    }

    val samples = DoubleArray(draws)
    val rng = SplitMix64(seed)
    for (draw in samples.indices) {
        var yes = 0
        var total = 0
        repeat(groups.size) {
            val group = groups[rng.nextInt(groups.size)]
            yes += group.count { it.yes }
            total += group.size
        }
        samples[draw] = yes.toDouble() / total
    }
    samples.sort()
    return PolicyRate(
        count = count,
        total = points.size,
        low = samples[percentile(draws, LOW)],
        high = samples[percentile(draws, HIGH)],
    )
}

private fun percentile(draws: Int, fraction: Double): Int =
    ((draws - 1) * fraction).toInt().coerceIn(0, draws - 1)

private const val DRAWS = 400
private const val LOW = 0.025
private const val HIGH = 0.975
