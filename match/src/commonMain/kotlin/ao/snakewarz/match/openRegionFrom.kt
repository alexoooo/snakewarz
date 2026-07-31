package ao.snakewarz.match

import ao.snakewarz.core.grid.Direction

/**
 * Which open squares a walk from [from] can reach, as **playable** indices `row * cols + col`.
 *
 * Walls only — no snakes, no spawns. What this answers is a property of the *map*: which squares a
 * snake could reach if it were alone on an empty board, so a map that seals a pocket off is a map
 * whose pocket never shows up here.
 *
 * `:bots` has a flood fill and `:match` may not see it — `:match` → `:bots` is a forbidden edge — so
 * this is a breadth-first walk of its own rather than a dependency. It runs once per setup and never
 * inside a search, which is why it can afford to speak in rows and columns rather than in the
 * engine's padded cells.
 *
 * Public because a spawn is not the only thing that has to land inside one region: `:lab` draws its
 * own openings and asks the same question of them, and a second walk over there would be an
 * opportunity for the two to disagree about what a map connects.
 */
public fun openRegionFrom(rows: Int, cols: Int, walls: IntArray, from: Int): BooleanArray {
    val playableCount = rows * cols
    require(from in 0 until playableCount) { "a walk cannot start at $from, which is off a ${rows}x$cols board" }

    val blocked = BooleanArray(playableCount)
    for (index in walls) {
        require(index in 0 until playableCount) { "a wall at $index is off a ${rows}x$cols board" }
        blocked[index] = true
    }
    require(!blocked[from]) { "a walk cannot start at $from, which is a wall" }

    val reached = BooleanArray(playableCount)
    val frontier = IntArray(playableCount)
    var head = 0
    var tail = 0

    reached[from] = true
    frontier[tail++] = from

    while (head < tail) {
        val index = frontier[head++]
        val row = index / cols
        val col = index % cols

        for (direction in Direction.entries) {
            val nextRow = row + direction.dRow
            val nextCol = col + direction.dCol
            if (nextRow !in 0 until rows || nextCol !in 0 until cols) {
                continue
            }

            val next = nextRow * cols + nextCol
            if (blocked[next] || reached[next]) {
                continue
            }
            reached[next] = true
            frontier[tail++] = next
        }
    }

    return reached
}
