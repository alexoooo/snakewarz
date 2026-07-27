package ao.snakewarz.match

import ao.snakewarz.core.grid.Grid
import kotlin.math.sqrt

/**
 * Starting squares for [count] snakes, each placed as far as possible from the ones before it.
 *
 * A semantic port of the legacy `BoardOccupancy.mostDistant`, including its two special cases: on an
 * empty board the first snake starts at `(0, 0)` and the second in the opposite corner — the reason
 * the old README said "you always start in the bottom right". Everything after that minimises the
 * sum of *inverse* distances, which penalises a candidate by its **nearest** neighbour rather than
 * by the average, and so keeps three-way spawns genuinely spread out instead of merely far from the
 * centroid.
 *
 * Deterministic by construction and not by luck: a row-major scan with a strict improvement test, so
 * ties resolve to the lowest row and then the lowest column. Only `+`, `/` and `sqrt` are involved,
 * all of which are exactly specified on both the JVM and wasm.
 *
 * Squares are returned as **playable** indices, `row * cols + col` — not padded [ao.snakewarz.core.grid.Cell]
 * indices, because these go into the replay header and must not encode the engine's padding scheme.
 */
internal fun mostDistantSpawns(grid: Grid, count: Int): IntArray {
    require(count >= 1) { "a match needs at least one snake, was $count" }
    require(count <= grid.playableCount) {
        "$count snakes do not fit on $grid, which has ${grid.playableCount} squares"
    }

    val spawns = IntArray(count)
    for (placed in 0 until count) {
        spawns[placed] = when (placed) {
            0 -> 0
            1 -> grid.playableCount - 1
            else -> generalMostDistant(grid, spawns, placed)
        }
    }
    return spawns
}

private fun generalMostDistant(grid: Grid, placed: IntArray, placedCount: Int): Int {
    var best = -1
    var bestScore = Double.MAX_VALUE

    for (row in 0 until grid.rows) {
        for (col in 0 until grid.cols) {
            val index = row * grid.cols + col
            if (isTaken(placed, placedCount, index)) {
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

    check(best >= 0) { "no free square left for a spawn on $grid" }
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
