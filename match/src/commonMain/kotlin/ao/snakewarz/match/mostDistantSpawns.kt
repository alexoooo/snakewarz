package ao.snakewarz.match

import ao.snakewarz.core.grid.Grid
import kotlin.math.sqrt

/**
 * Starting squares for [count] snakes, none of them on a square of [walls], each placed as far as
 * possible from the ones before it.
 *
 * A semantic port of the legacy `BoardOccupancy.mostDistant`, including its two special cases: the
 * first snake starts at the lowest open square and the second at the highest — on an empty board,
 * `(0, 0)` and the opposite corner, which is the reason the old README said "you always start in the
 * bottom right". Everything after that minimises the sum of *inverse* distances, which penalises a
 * candidate by its **nearest** neighbour rather than by the average, and so keeps three-way spawns
 * genuinely spread out instead of merely far from the centroid.
 *
 * ### Why the two corner rules are the corner rules
 *
 * Under the half turn `ρ(row, col) = (rows - 1 - row, cols - 1 - col)` a row-major index `i` maps to
 * `playableCount - 1 - i`, so *lowest open index* and *highest open index* are exact images of each
 * other. A two-seat opening on a ρ-symmetric map is therefore fair by construction rather than by
 * measurement. A vertical mirror maps `(0, 0)` to `cols - 1` instead, and the corner rule would not
 * be fair under it — which is why the map catalogue ships the half turn and nothing else.
 *
 * ### Why the metric is the plane's and not the board's
 *
 * The score is `1 / (sqrt(dRow² + dCol²) + 1)` — Euclidean. Graph distance on a wall-free 4-connected
 * rectangle is **Manhattan**: a different metric, a different argmin, so switching would move seat 3
 * and beyond *on an empty board* and invalidate every three-seat replay header and every empty-board
 * measurement already taken. So the plane metric stays and only a reachability *filter* is added:
 * candidates for seat 2 and beyond must be open squares a walk from seat 0 can reach.
 *
 * A maze wants the graph metric, and the escalation is available and unspent: the two seats a match
 * and a ladder are measured at never reach the scored branch at all, so the day three-seat maps are
 * measured the metric can become graph distance **for non-empty maps only**, which re-pins nothing
 * because no three-seat map replay will exist.
 *
 * Deterministic by construction and not by luck: a row-major scan with a strict improvement test, so
 * ties resolve to the lowest row and then the lowest column. Only `+`, `/` and `sqrt` are involved,
 * all of which are exactly specified on both the JVM and wasm.
 *
 * Squares are returned as **playable** indices, `row * cols + col` — not padded [ao.snakewarz.core.grid.Cell]
 * indices, because these go into the replay header and must not encode the engine's padding scheme.
 */
internal fun mostDistantSpawns(grid: Grid, walls: IntArray, count: Int): IntArray {
    val openCount = grid.playableCount - walls.size
    require(count >= 1) { "a match needs at least one snake, was $count" }
    require(count <= openCount) {
        "$count snakes do not fit on $grid, which leaves them $openCount squares to stand on"
    }

    val blocked = BooleanArray(grid.playableCount)
    for (index in walls) {
        require(index in 0 until grid.playableCount) { "a wall at $index is off $grid" }
        blocked[index] = true
    }

    val spawns = IntArray(count)
    spawns[0] = openEnd(blocked, blocked.indices)
    if (count > 1) {
        spawns[1] = openEnd(blocked, blocked.size - 1 downTo 0)
    }
    if (count > 2) {
        val reachable = openRegionFrom(grid.rows, grid.cols, walls, spawns[0])
        for (placed in 2 until count) {
            spawns[placed] = generalMostDistant(grid, reachable, spawns, placed)
        }
    }
    return spawns
}

/** The first open square [scan] passes, which is the lowest or the highest depending on its order. */
private fun openEnd(blocked: BooleanArray, scan: IntProgression): Int {
    for (index in scan) {
        if (!blocked[index]) {
            return index
        }
    }
    error("every square of the board is a wall")
}

private fun generalMostDistant(grid: Grid, reachable: BooleanArray, placed: IntArray, placedCount: Int): Int {
    var best = -1
    var bestScore = Double.MAX_VALUE

    for (row in 0 until grid.rows) {
        for (col in 0 until grid.cols) {
            val index = row * grid.cols + col
            if (!reachable[index] || isTaken(placed, placedCount, index)) {
                continue
            }

            var score = 0.0
            for (i in 0 until placedCount) {
                val dRow = (row - placed[i] / grid.cols).toDouble()
                val dCol = (col - placed[i] % grid.cols).toDouble()
                score += 1.0 / (sqrt(dRow * dRow + dCol * dCol) + 1.0)
            }

            if (score < bestScore) {
                bestScore = score
                best = index
            }
        }
    }

    check(best >= 0) { "no square reachable from the first spawn is left for a spawn on $grid" }
    return best
}

private fun isTaken(placed: IntArray, placedCount: Int, index: Int): Boolean {
    for (i in 0 until placedCount) {
        if (placed[i] == index) {
            return true
        }
    }
    return false
}
