package ao.snakewarz.lab.arena

import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.match.MatchSetup
import kotlin.math.abs

/**
 * [setup] with its starting squares drawn from its own seed, or unchanged under [Openings.FIXED].
 *
 * Everything else about the match is left exactly as the schedule built it — the seed, the turn
 * order, the allowances and the knob values all travel through untouched — so the only thing this
 * varies is the position, which is the thing that was not varying.
 *
 * Reads the seed off [setup] rather than taking one, and that is what keeps a mirrored pair mirrored:
 * two matches of a pair share a seed, so they get the same squares and the swap exchanges the players
 * on one board rather than moving the board too.
 *
 * A placement that cannot be found is not an error. On a board too small to separate the snakes,
 * this hands back the setup it was given, and the run says so.
 */
internal fun openingSetup(setup: MatchSetup, openings: Openings): MatchSetup {
    if (openings == Openings.FIXED) {
        return setup
    }

    val spawns = spreadSpawns(
        rows = setup.rows,
        cols = setup.cols,
        count = setup.slotCount,
        rng = SplitMix64(setup.seed).fork(SPAWN_STREAM),
    ) ?: return setup

    return MatchSetup(
        seed = setup.seed,
        rows = setup.rows,
        cols = setup.cols,
        rules = setup.rules,
        budgetPerTurn = setup.budgetPerTurn,
        slots = setup.slots,
        turnOrder = setup.turnOrder(),
        spawns = spawns,
        budgets = setup.budgets(),
        slotParams = List(setup.slotCount) { setup.paramsFor(it) },
    )
}

/**
 * A stream of its own, so drawing an opening cannot shift what any slot draws.
 *
 * `MatchSetup.create` forks `-1` off the same seed for the turn order; anything but that would do,
 * and a distinctive number makes the two streams recognisable in a trace.
 */
private const val SPAWN_STREAM = 0x5A17

/** Enough draws that a board with room to spare effectively never falls back. */
private const val ATTEMPTS = 64

/**
 * [count] starting squares at least [separationFloor] apart, or `null` if none were found.
 *
 * Rejection sampling rather than construction, because the acceptable region is awkward to enumerate
 * and cheap to test: over half of all draws pass on a twelve-square board, and the loop is bounded so
 * a board with no room at all ends the search instead of hunting forever.
 */
private fun spreadSpawns(rows: Int, cols: Int, count: Int, rng: Rng): IntArray? {
    val floor = separationFloor(rows, cols, count)
    val playable = rows * cols

    repeat(ATTEMPTS) {
        val spawns = if (count == 2) reflectedPair(rows, cols, rng) else scattered(playable, count, rng)
        if (spawns != null && wellSpread(spawns, cols, floor)) {
            return spawns
        }
    }
    return null
}

/**
 * A square and its image through the centre of the board.
 *
 * `null` when the draw lands on the centre square of an odd-by-odd board, which is its own image and
 * so cannot seat two snakes.
 */
private fun reflectedPair(rows: Int, cols: Int, rng: Rng): IntArray? {
    val first = rng.nextInt(rows * cols)
    val second = (rows - 1 - first / cols) * cols + (cols - 1 - first % cols)
    return if (first == second) null else intArrayOf(first, second)
}

/** [count] distinct squares, drawn one at a time. `null` if a draw repeats one already taken. */
private fun scattered(playable: Int, count: Int, rng: Rng): IntArray? {
    val spawns = IntArray(count)
    for (slot in 0 until count) {
        val square = rng.nextInt(playable)
        for (taken in 0 until slot) {
            if (spawns[taken] == square) {
                return null
            }
        }
        spawns[slot] = square
    }
    return spawns
}

/**
 * How far apart starting squares have to be, in steps.
 *
 * Half the greatest separation the board allows for two snakes, and proportionately less as the
 * field grows, because more snakes cannot all be far from each other. A floor at all is the
 * difference between sampling openings and sampling *games*: two snakes that start adjacent play a
 * short, drawish match that says very little about either of them, and a batch full of those is
 * noise with a large sample size.
 */
private fun separationFloor(rows: Int, cols: Int, count: Int): Int = (rows - 1 + cols - 1) / count

private fun wellSpread(spawns: IntArray, cols: Int, floor: Int): Boolean {
    for (one in spawns.indices) {
        for (other in one + 1 until spawns.size) {
            val rowGap = spawns[one] / cols - spawns[other] / cols
            val colGap = spawns[one] % cols - spawns[other] % cols
            if (abs(rowGap) + abs(colGap) < floor) {
                return false
            }
        }
    }
    return true
}
