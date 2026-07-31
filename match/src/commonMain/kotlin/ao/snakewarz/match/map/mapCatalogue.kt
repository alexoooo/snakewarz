package ao.snakewarz.match.map

import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.random.SplitMix64

/**
 * Draws [shape] onto [half], which mirrors every placement so the result is half-turn symmetric
 * whatever a shape does here.
 *
 * Split out of [generateMap] because eight shapes and one validated entry point are two different
 * jobs: the entry point is where the guarantees live, and this is where the pictures do.
 */
internal fun drawShape(shape: MapShape, half: HalfBoard, density: Double, seed: Long) {
    when (shape) {
        MapShape.EMPTY -> Unit
        MapShape.PILLARS -> pillars(half)
        MapShape.RING -> ring(half)
        MapShape.CROSS -> cross(half)
        MapShape.DIAGONALS -> diagonals(half)
        MapShape.ROOMS -> rooms(half)
        MapShape.DOUBLE_SPIRAL -> doubleSpiral(half)
        MapShape.SCATTER -> scatter(half, density, seed)
    }
}

// -- the shapes

/**
 * Lone squares on a lattice of period [PILLAR_PERIOD], inset one square from the border.
 *
 * The lattice is anchored by [symmetricAnchor] rather than started at a fixed offset so that ρ maps
 * it onto itself. Without that the mirrored half lands between the drawn half's rows and the pattern
 * shows a seam across the middle of the board — symmetric, but visibly not one lattice.
 */
private fun pillars(half: HalfBoard) {
    val firstRow = symmetricAnchor(half.rows, PILLAR_PERIOD, atLeast = 1)
    val firstCol = symmetricAnchor(half.cols, PILLAR_PERIOD, atLeast = 1)

    for (row in firstRow..half.rows - 2 step PILLAR_PERIOD) {
        for (col in firstCol..half.cols - 2 step PILLAR_PERIOD) {
            half.placeIsolated(row, col)
        }
    }
}

/**
 * A hollow rectangle inset from the border, with one gap in each of its four sides.
 *
 * Only the top side and the upper halves of the two side walls are drawn; the mirror supplies the
 * bottom side and the lower halves. That is where three of the four gaps come from too: the gap
 * drawn in the left wall reappears in the right wall's lower half, so each side is opened once.
 *
 * The left wall's gap may not sit on the middle row of an odd board, which ρ maps to *itself* — the
 * mirror would put the right wall's gap on the same row and the two would cancel, leaving both side
 * walls solid. [ringGapRow] is that row avoided.
 */
private fun ring(half: HalfBoard) {
    val rowInset = maxOf(RING_MIN_INSET, half.rows / RING_INSET_DIVISOR)
    val colInset = maxOf(RING_MIN_INSET, half.cols / RING_INSET_DIVISOR)
    val gapCol = half.cols / 2
    val gapRow = ringGapRow(half.rows)

    for (col in colInset..half.cols - 1 - colInset) {
        if (col != gapCol) {
            half.set(rowInset, col)
        }
    }
    for (row in rowInset + 1..half.halfRows - 1) {
        if (row != gapRow) {
            half.set(row, colInset)
        }
        half.set(row, half.cols - 1 - colInset)
    }
}

/**
 * A full-width bar and a full-height bar, each broken across the middle.
 *
 * A bar is one square thick on an odd side and two on an even one, because ρ maps the middle column
 * of an odd board to itself and the two middle columns of an even board to each other. The opening
 * is the same either way: [CROSS_GAP] squares of clearance on each side of the middle band, so the
 * four quadrants meet in a small central room rather than along a corridor.
 */
private fun cross(half: HalfBoard) {
    val rowLow = (half.rows - 1) / 2
    val colLow = (half.cols - 1) / 2
    val colHigh = half.cols / 2

    for (col in colLow..colHigh) {
        half.setColumn(col, 0, rowLow - CROSS_GAP - 1)
    }
    half.setRow(rowLow, 0, colLow - CROSS_GAP - 1)
    half.setRow(rowLow, colHigh + CROSS_GAP + 1, half.cols - 1)
}

/**
 * Anti-diagonal bars every [DIAGONAL_PERIOD] squares, each opened at its own middle.
 *
 * A bar is the set `row + col == k`, which ρ maps to the bar `row + col == rows + cols - 2 - k`, so
 * the family is invariant exactly when the anchor makes it so — again [symmetricAnchor], with the
 * anti-diagonal index standing in for a row.
 *
 * **The opening has to be the middle and nowhere else.** ρ reverses a bar end for end, so the middle
 * is the one position that lands on the middle of the image bar. Taking the middle *two* squares of
 * an even-length bar is the same argument: an even-length bar has no single middle square.
 *
 * A solid bar would cut the board in two, so every bar carries an opening, and the openings line up
 * into one corridor along the board's other diagonal. That is what keeps the open squares one region.
 */
private fun diagonals(half: HalfBoard) {
    val last = half.rows + half.cols - 2
    val first = symmetricAnchor(last + 1, DIAGONAL_PERIOD, atLeast = 0)

    for (bar in first..last step DIAGONAL_PERIOD) {
        val fromRow = maxOf(0, bar - (half.cols - 1))
        val toRow = minOf(half.rows - 1, bar)
        val length = toRow - fromRow + 1

        for (row in fromRow..toRow) {
            val along = row - fromRow
            if (along >= (length - 1) / 2 && along <= length / 2) {
                continue
            }
            half.set(row, bar - row)
        }
    }
}

/**
 * Chambers on a grid of corridors: [bandsOf] rooms each way, one doorway per shared wall.
 *
 * The band count is forced **odd**, which is what puts a room at the centre of the board rather than
 * a wall. A wall through the centre would be one square thick on an odd side and two on an even one,
 * so the middle chamber of a 12-row board would be a different shape from the middle chamber of a
 * 13-row one; a room at the centre is the same idea at both parities.
 *
 * A doorway sits at the middle of every band a wall line crosses, so every pair of adjacent chambers
 * is joined and the chamber graph is the whole grid. Connectivity is that, rather than a check that
 * happened to pass. The mirror moves a doorway by at most a square, which is still inside its band.
 */
private fun rooms(half: HalfBoard) {
    val wallRows = bandWalls(half.rows)
    val wallCols = bandWalls(half.cols)
    val doorRows = bandMiddles(half.rows, wallRows)
    val doorCols = bandMiddles(half.cols, wallCols)

    for (row in wallRows) {
        if (row >= half.halfRows) continue
        for (col in 0 until half.cols) {
            if (col !in doorCols) {
                half.set(row, col)
            }
        }
    }
    for (col in wallCols) {
        for (row in 0 until half.halfRows) {
            if (row !in doorRows) {
                half.set(row, col)
            }
        }
    }
}

/**
 * One arm of a rectangular spiral, wound from the border inward; the mirror supplies the other.
 *
 * The arm walks side after side — top, right, bottom, left, top… — [spiralInsets] squares further in
 * each time, and each side runs from where the previous one ended to where the next one starts, so
 * the arm is one unbroken curve.
 *
 * **Why the two arms never close a ring.** A ring needs all four sides of one rectangle. This arm
 * visits an inset on one side only, and its image visits that inset a half turn away — so an inset is
 * covered on two *opposite* sides at most, and an opposite pair encloses nothing. The arm also stops
 * short of the centre, which is where the two halves of the corridor meet: that is the "one long
 * corridor" the shape is for.
 */
private fun doubleSpiral(half: HalfBoard) {
    val sides = spiralSides(half.rows, half.cols)

    for (side in 0 until sides) {
        val inset = spiralInset(side)
        val before = spiralInset(maxOf(0, side - 1))
        val after = spiralInset(side + 1)

        when (side % SPIRAL_SIDES) {
            0 -> half.setRow(inset, before, half.cols - 1 - after)
            1 -> half.setColumn(half.cols - 1 - inset, before, half.rows - 1 - after)
            2 -> half.setRow(half.rows - 1 - inset, half.cols - 1 - before, after)
            else -> half.setColumn(inset, half.rows - 1 - before, after)
        }
    }
}

/**
 * Lone squares placed at random until [density] of the board is wall, or until nowhere is left.
 *
 * There is no resampling loop. Every candidate square in the board's first half is offered exactly
 * once, in a shuffled order, and [HalfBoard.placeIsolated] refuses any that would touch a wall
 * already placed — so the walk terminates whatever is asked of it, and what it produces is connected
 * by construction rather than by a retry that happened to succeed.
 *
 * A density this rule cannot reach therefore **fails**, reporting what it managed, rather than
 * sampling until it passes. The ceiling is a quarter of the board in principle and well under that
 * from a random order, so a request far past [SCATTER_DEFAULT_DENSITY] is a request for a different
 * shape.
 */
private fun scatter(half: HalfBoard, density: Double, seed: Long) {
    val requested = if (density > 0.0) density else SCATTER_DEFAULT_DENSITY
    val target = (requested * half.rows * half.cols).toInt()
    val candidates = shuffledHalf(half, SplitMix64(seed).fork(MAP_STREAM))

    var placed = 0
    for (index in candidates) {
        if (placed >= target) break

        val row = index / half.cols
        val col = index % half.cols
        if (half.placeIsolated(row, col)) {
            // The exact centre of an odd board is its own image, so it is one square and not two.
            placed += if (2 * row == half.rows - 1 && 2 * col == half.cols - 1) 1 else 2
        }
    }

    require(placed >= target) {
        "a ${half.rows}x${half.cols} board takes $placed isolated walls, short of the $target a " +
            "density of $requested asks for"
    }
}

// -- shared geometry

/**
 * The playable indices of rows `0 until halfRows`, in a seeded random order.
 *
 * Fisher-Yates over an array rather than a stream of random squares: sampling with replacement has
 * no termination argument, and this makes "offer every square exactly once" the shape of the loop.
 */
private fun shuffledHalf(half: HalfBoard, rng: Rng): IntArray {
    val order = IntArray(half.halfRows * half.cols) { it }
    for (i in order.size - 1 downTo 1) {
        val j = rng.nextInt(i + 1)
        val swap = order[i]
        order[i] = order[j]
        order[j] = swap
    }
    return order
}

/**
 * The first index of a period-[period] lattice on [extent] squares that ρ maps onto itself, at or
 * above [atLeast].
 *
 * ρ sends index `i` to `extent - 1 - i`, so the lattice `{a, a + period, …}` is invariant exactly
 * when `2a ≡ extent - 1 (mod period)`. Solved by trying every residue rather than by inverting 2,
 * which exists only for an odd period — a caller should not have to know that its period had to be
 * odd for the arithmetic to work.
 */
private fun symmetricAnchor(extent: Int, period: Int, atLeast: Int): Int {
    for (anchor in atLeast until atLeast + period) {
        if ((2 * anchor - (extent - 1)) % period == 0) {
            return anchor
        }
    }
    error("no period-$period lattice on $extent squares survives the half turn")
}

/** The row the ring's left wall is opened at: the middle, moved off the self-mirroring middle row. */
private fun ringGapRow(rows: Int): Int = (rows - 1) / 2 - rows % 2

/** How many chambers [extent] squares divide into: odd, at least three, and about [ROOM_SIDE] apart. */
private fun bandsOf(extent: Int): Int {
    val bands = extent / ROOM_SIDE
    return maxOf(ROOM_MIN_BANDS, if (bands % 2 == 0) bands - 1 else bands)
}

/**
 * The wall lines dividing [extent] squares into [bandsOf] bands, ascending.
 *
 * The lower half is spaced evenly and the upper half is its mirror, so the set is ρ-invariant by
 * construction — even spacing on its own drifts by a square wherever the division is not exact, and
 * a map symmetric everywhere except one wall line is worse than one that is not symmetric at all.
 */
private fun bandWalls(extent: Int): IntArray {
    val bands = bandsOf(extent)
    val walls = IntArray(bands - 1)
    for (i in 1..bands / 2) {
        val wall = i * (extent + 1) / bands - 1
        walls[i - 1] = wall
        walls[bands - 1 - i] = extent - 1 - wall
    }
    return walls
}

/** The middle square of each band [walls] cuts [extent] into — where a doorway goes. */
private fun bandMiddles(extent: Int, walls: IntArray): IntArray {
    val middles = IntArray(walls.size + 1)
    var start = 0
    for (i in middles.indices) {
        val end = if (i < walls.size) walls[i] - 1 else extent - 1
        middles[i] = (start + end) / 2
        start = end + 2
    }
    return middles
}

/**
 * How far in the spiral arm's [side]th side sits.
 *
 * Two sides advance by [SPIRAL_STEP], which leaves a corridor [SPIRAL_STEP] - 1 wide at every angle:
 * an arm is one square of wall, and the arm a half turn away is the next thing out. Advancing a
 * whole step per side would only allow 1 — a corridor nothing can turn round in — or 2, which spends
 * a third of a small board on wall.
 */
private fun spiralInset(side: Int): Int = SPIRAL_MARGIN + side * SPIRAL_STEP / 2

/** How many sides the arm has room for before its rectangle would collapse onto the centre. */
private fun spiralSides(rows: Int, cols: Int): Int {
    val limit = (minOf(rows, cols) - 1) / 2
    var sides = 0
    while (spiralInset(sides) < limit) {
        sides++
    }
    return sides
}

// -- constants

/**
 * The stream map generation draws from.
 *
 * Kept clear of `MatchSetup.SETUP_STREAM`, of the per-slot streams and of `:lab`'s opening stream, so
 * that generating a map can never shift a bot's randomness or an opening's.
 */
private const val MAP_STREAM: Int = 0x4D41

private const val PILLAR_PERIOD: Int = 3

private const val RING_MIN_INSET: Int = 1
private const val RING_INSET_DIVISOR: Int = 5

/** Open squares between a cross bar's end and the middle band, on each side. */
private const val CROSS_GAP: Int = 1

private const val DIAGONAL_PERIOD: Int = 5

private const val ROOM_SIDE: Int = 5
private const val ROOM_MIN_BANDS: Int = 3

private const val SPIRAL_SIDES: Int = 4

/** Squares the spiral advances every two sides: one of wall and the rest of corridor. */
private const val SPIRAL_STEP: Int = 3

/** The spiral starts one square in, so the border is an open lane the whole way round. */
private const val SPIRAL_MARGIN: Int = 1

/** What [MapShape.SCATTER] scatters when nobody asked for a density. */
private const val SCATTER_DEFAULT_DENSITY: Double = 0.12
