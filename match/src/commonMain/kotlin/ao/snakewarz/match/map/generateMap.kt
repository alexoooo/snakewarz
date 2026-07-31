package ao.snakewarz.match.map

import ao.snakewarz.match.openRegionFrom

/**
 * The map [shape] draws on a [rows] x [cols] board: fair, connected, and the same every time.
 *
 * **The recipe is universal.** Every placement a shape makes is mirrored through the half turn
 * `ρ(row, col) = (rows - 1 - row, cols - 1 - col)` by [HalfBoard], so symmetry costs a shape nothing
 * to guarantee and cannot be got wrong one shape at a time. That is also the whole fairness argument:
 * a row-major index `i` maps under ρ to `rows * cols - 1 - i`, so the lowest and the highest open
 * squares — where `mostDistantSpawns` seats slot 0 and slot 1 — are exact images of each other.
 * **A two-seat opening on a map from here is fair by construction rather than by measurement**, and
 * `:lab`'s mirrored openings compute the same ρ, so they stay fair on these maps too.
 *
 * [density] is read by [MapShape.SCATTER] alone, and `0.0` asks that shape for its shipped default.
 * [seed] likewise only reaches a shape that draws at random; everything else is a function of the
 * geometry, which is what lets one name mean the same idea at 8x8 and at 40x40.
 *
 * The four claims below are **checked, not assumed** — a shape that breaks one is a defect and must
 * not reach a match. They are the reason this returns a [BoardMap] rather than each shape returning
 * one: the guarantees live in one place, where a new shape inherits them.
 */
public fun generateMap(
    rows: Int,
    cols: Int,
    shape: MapShape,
    density: Double = 0.0,
    seed: Long = 0L,
): BoardMap {
    // Before generating rather than after, so a shape that cannot express itself at this size fails
    // with its own name instead of emitting a degenerate map that looks like a rule of the game.
    require(rows >= shape.minimumSide && cols >= shape.minimumSide) {
        "${shape.slug} needs a board of at least ${shape.minimumSide} squares a side, was ${rows}x$cols"
    }
    require(density >= 0.0 && density < 1.0) { "a density is a fraction of the board, was $density" }

    val half = HalfBoard(rows, cols)
    drawShape(shape, half, density, seed)

    val walls = half.walls()
    checkPlayable(rows, cols, shape, walls)
    checkSymmetric(rows, cols, shape, walls)
    checkOneRegion(rows, cols, shape, walls)
    checkEndsPair(rows, cols, shape, walls)

    return BoardMap(rows, cols, walls)
}

/** That every wall is a square of the board, that they ascend, and that none repeats. */
private fun checkPlayable(rows: Int, cols: Int, shape: MapShape, walls: IntArray) {
    val playableCount = rows * cols
    var previous = -1
    for (wall in walls) {
        check(wall in 0 until playableCount) { "${shape.slug} put a wall at $wall, off a ${rows}x$cols board" }
        check(wall > previous) { "${shape.slug} produced walls out of order: $wall follows $previous" }
        previous = wall
    }
}

/**
 * That ρ maps the wall set onto itself.
 *
 * [HalfBoard] mirrors every placement, so this can only fail if a shape reached past it — but it is
 * the property the opening's fairness rests on, and a property that load-bearing is asserted rather
 * than inferred from how it was built.
 */
private fun checkSymmetric(rows: Int, cols: Int, shape: MapShape, walls: IntArray) {
    val playableCount = rows * cols
    val isWall = BooleanArray(playableCount)
    for (wall in walls) {
        isWall[wall] = true
    }
    for (wall in walls) {
        check(isWall[playableCount - 1 - wall]) {
            "${shape.slug} is not symmetric on ${rows}x$cols: $wall is a wall and its half turn is not"
        }
    }
}

/**
 * That the open squares form **exactly one** region.
 *
 * Stronger than "the spawns can reach each other", and simpler to state. What it forbids is a sealed
 * decorative pocket — dead board that no snake can enter, which every share of the board is then
 * quietly taken against.
 */
private fun checkOneRegion(rows: Int, cols: Int, shape: MapShape, walls: IntArray) {
    val openCount = rows * cols - walls.size
    check(openCount > 0) { "${shape.slug} walled in the whole of a ${rows}x$cols board" }

    val reached = openRegionFrom(rows, cols, walls, lowestOpen(rows * cols, walls))
    var count = 0
    for (open in reached) {
        if (open) count++
    }
    check(count == openCount) {
        "${shape.slug} on ${rows}x$cols leaves $openCount open squares in more than one region; " +
            "${openCount - count} of them are sealed off"
    }
}

/**
 * That the lowest and the highest open squares are each other's image under ρ.
 *
 * It follows from the symmetry check, and it is asserted anyway because it *is* the fairness claim:
 * `mostDistantSpawns` seats slot 0 at the lowest open square and slot 1 at the highest, so this
 * sentence and the two-seat opening being fair are the same sentence.
 */
private fun checkEndsPair(rows: Int, cols: Int, shape: MapShape, walls: IntArray) {
    val playableCount = rows * cols
    val lowest = lowestOpen(playableCount, walls)
    var highest = playableCount - 1
    var wall = walls.size - 1
    while (wall >= 0 && walls[wall] == highest) {
        highest--
        wall--
    }

    check(playableCount - 1 - lowest == highest) {
        "${shape.slug} on ${rows}x$cols opens at $lowest and closes at $highest, which are not a " +
            "half turn apart — the two seats would not be equivalent"
    }
}

/** The lowest playable index that is not a wall. */
private fun lowestOpen(playableCount: Int, walls: IntArray): Int {
    var lowest = 0
    var wall = 0
    while (wall < walls.size && walls[wall] == lowest) {
        lowest++
        wall++
    }
    check(lowest < playableCount) { "every square of the board is a wall" }
    return lowest
}
