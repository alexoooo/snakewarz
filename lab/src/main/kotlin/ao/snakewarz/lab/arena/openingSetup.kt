package ao.snakewarz.lab.arena

import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.openRegionFrom
import kotlin.math.abs

/**
 * [setup] with its starting squares drawn from its own seed, or unchanged under [Openings.FIXED].
 *
 * Everything else about the match is left exactly as the schedule built it — the seed, the turn
 * order, the map, the allowances and the knob values all travel through untouched — so the only thing
 * this varies is the position, which is the thing that was not varying.
 *
 * **Every field is rebuilt by name here, so a field left out is a batch that plays a different game
 * from the one it reports.** `OpeningSetupTest` asserts the whole header survives rather than the two
 * fields this function is about.
 *
 * Reads the seed off [setup] rather than taking one, and that is what keeps a mirrored pair mirrored:
 * two matches of a pair share a seed, so they get the same squares and the swap exchanges the players
 * on one board rather than moving the board too.
 *
 * A placement that cannot be found is not an error. On a board too small to separate the snakes, or
 * on a map that leaves too little open ground, this hands back the setup it was given, and the run
 * says so.
 */
internal fun openingSetup(setup: MatchSetup, openings: Openings, openingIndex: Int? = null): MatchSetup {
    if (openings == Openings.FIXED) {
        return setup
    }

    val walls = setup.walls()
    val spawns = when (openings) {
        Openings.FIXED -> error("handled above")
        Openings.MIRRORED -> spreadSpawns(
            rows = setup.rows,
            cols = setup.cols,
            walls = walls,
            count = setup.slotCount,
            rng = SplitMix64(setup.seed).fork(SPAWN_STREAM),
        ) ?: return setup
        Openings.COMPLETE -> completeOpeningSpawns(
            checkNotNull(openingIndex) { "a complete opening needs its population index" },
        )
    }

    return MatchSetup(
        seed = setup.seed,
        rows = setup.rows,
        cols = setup.cols,
        rules = setup.rules,
        budgetPerTurn = setup.budgetPerTurn,
        slots = setup.slots,
        turnOrder = setup.turnOrder(),
        spawns = spawns,
        walls = walls,
        budgets = setup.budgets(),
        slotParams = List(setup.slotCount) { setup.paramsFor(it) },
    )
}

/** The stable identity written to the match log for one member of the complete population. */
internal fun completeOpeningIdentity(index: Int): String {
    require(index in 0 until Openings.COMPLETE_POPULATION) {
        "complete opening $index is outside 0 until ${Openings.COMPLETE_POPULATION}"
    }
    return "empty8-rho-${index.toString().padStart(2, '0')}"
}

/**
 * One of the forty oriented starts the mirrored rule accepts on an empty 8x8.
 *
 * Ascending slot-zero square is the order. That definition, rather than a random seed that happens
 * to visit the population, makes the identity above stable and keeps opening selection out of the
 * match seed and both bots' random streams.
 */
internal fun completeOpeningSpawns(index: Int): IntArray {
    require(index in COMPLETE_FIRST_SPAWNS.indices) {
        "complete opening $index is outside ${COMPLETE_FIRST_SPAWNS.indices}"
    }
    val first = COMPLETE_FIRST_SPAWNS[index]
    return intArrayOf(first, reflectedSquare(Openings.COMPLETE_ROWS, Openings.COMPLETE_COLS, first))
}

/**
 * A stream of its own, so drawing an opening cannot shift what any slot draws.
 *
 * `MatchSetup.create` forks `-1` off the same seed for the turn order and `generateMap` forks
 * `0x4D41`; anything but those would do, and a distinctive number makes the streams recognisable in
 * a trace.
 */
private const val SPAWN_STREAM = 0x5A17

/** Enough draws that a board with room to spare effectively never falls back. */
private const val ATTEMPTS = 64

/**
 * [count] starting squares at least [separationFloor] apart and all in one region, or `null`.
 *
 * Rejection sampling rather than construction, because the acceptable region is awkward to enumerate
 * and cheap to test: over half of all draws pass on a twelve-square board, and the loop is bounded so
 * a board with no room at all ends the search instead of hunting forever.
 */
private fun spreadSpawns(rows: Int, cols: Int, walls: IntArray, count: Int, rng: Rng): IntArray? {
    val floor = separationFloor(rows, cols, count)
    val playable = rows * cols

    repeat(ATTEMPTS) {
        val spawns = if (count == 2) reflectedPair(rows, cols, rng) else scattered(playable, count, rng)
        if (spawns != null && wellSpread(spawns, cols, floor) && seatable(rows, cols, walls, spawns)) {
            return spawns
        }
    }
    return null
}

/**
 * Whether [spawns] are open squares of one region — a wall between two snakes is not an opening.
 *
 * Both halves of that matter and they fail differently. A spawn on a wall is refused outright by
 * `MatchSetup`, so it would end a batch; a spawn in a *sealed pocket* would be accepted and would
 * play a match nobody could win, which is worse. `generateMap` guarantees a single region, so on a
 * catalogue map this only ever rejects the first — but the wall array is an `IntArray` and nothing in
 * the type says where it came from.
 */
private fun seatable(rows: Int, cols: Int, walls: IntArray, spawns: IntArray): Boolean {
    for (wall in walls) {
        for (spawn in spawns) {
            if (wall == spawn) {
                return false
            }
        }
    }

    val reached = openRegionFrom(rows, cols, walls, spawns[0])
    for (spawn in spawns) {
        if (!reached[spawn]) {
            return false
        }
    }
    return true
}

/**
 * A square and its image through the centre of the board.
 *
 * `null` when the draw lands on the centre square of an odd-by-odd board, which is its own image and
 * so cannot seat two snakes.
 *
 * The half turn is the same ρ the map catalogue draws under, so a mirrored opening on a catalogue map
 * stays fair for free: whatever the first square faces, the second faces its image.
 */
private fun reflectedPair(rows: Int, cols: Int, rng: Rng): IntArray? {
    val first = rng.nextInt(rows * cols)
    val second = reflectedSquare(rows, cols, first)
    return if (first == second) null else intArrayOf(first, second)
}

private fun reflectedSquare(rows: Int, cols: Int, first: Int): Int =
    (rows - 1 - first / cols) * cols + (cols - 1 - first % cols)

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

private val COMPLETE_FIRST_SPAWNS: IntArray =
    (0 until Openings.COMPLETE_ROWS * Openings.COMPLETE_COLS)
        .filter { first ->
            val pair = intArrayOf(
                first,
                reflectedSquare(Openings.COMPLETE_ROWS, Openings.COMPLETE_COLS, first),
            )
            wellSpread(
                pair,
                Openings.COMPLETE_COLS,
                separationFloor(Openings.COMPLETE_ROWS, Openings.COMPLETE_COLS, pair.size),
            )
        }
        .toIntArray()
        .also { starts ->
            check(starts.size == Openings.COMPLETE_POPULATION) {
                "the mirrored empty-8x8 population changed from ${Openings.COMPLETE_POPULATION} to ${starts.size}"
            }
        }
