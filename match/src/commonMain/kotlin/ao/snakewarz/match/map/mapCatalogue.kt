package ao.snakewarz.match.map

import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.random.SplitMix64

/**
 * Draws [shape] onto [half], which mirrors every placement so the result is half-turn symmetric
 * whatever a shape does here.
 *
 * Split out of [generateMap] because a catalogue of shapes and one validated entry point are two
 * different jobs: the entry point is where the guarantees live, and this is where the pictures do.
 */
internal fun drawShape(shape: MapShape, half: HalfBoard, density: Double, seed: Long) {
    when (shape) {
        MapShape.EMPTY -> Unit
        MapShape.ARENA -> arena(half)
        MapShape.PILLARS -> pillars(half)
        MapShape.PINWHEEL -> pinwheel(half)
        MapShape.RING -> ring(half)
        MapShape.CROSS -> cross(half)
        MapShape.DIAGONALS -> diagonals(half)
        MapShape.ROOMS -> rooms(half)
        MapShape.DOUBLE_SPIRAL -> doubleSpiral(half)
        MapShape.SCATTER -> scatter(half, density, seed)
        MapShape.ISLANDS -> islands(half, density, seed)
    }
}

// -- the shapes

/**
 * A block at the centre of the board and a satellite square between it and each corner.
 *
 * Two placements and the mirror makes four satellites. Everything is placed through
 * [HalfBoard.placeIsolatedBlock], so the open squares are one region for the reason that method
 * states rather than by a check that happened to pass — and the failures below are `check`s because
 * the geometry is a function of the size alone, so a board that cannot hold this picture is a
 * `minimumSide` that is wrong rather than a board somebody asked for.
 *
 * The satellite sits half way between the border and the block, which is one square in at the
 * smallest board and drifts outward with the side, so it stays a satellite rather than becoming a
 * corner mark.
 */
private fun arena(half: HalfBoard) {
    val height = arenaCentre(half.rows)
    val width = arenaCentre(half.cols)
    val top = (half.rows - height) / 2
    val left = (half.cols - width) / 2
    check(half.placeIsolatedBlock(top, left, height, width)) {
        "arena cannot centre a ${height}x$width block on a ${half.rows}x${half.cols} board"
    }

    val satelliteRow = top / 2
    for (col in intArrayOf(left / 2, half.cols - 1 - left / 2)) {
        check(half.placeIsolatedBlock(satelliteRow, col, 1, 1)) {
            "arena has no room for a satellite at ($satelliteRow, $col) of a " +
                "${half.rows}x${half.cols} board"
        }
    }
}

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
 * One horizontal arm and one vertical arm, each offset from a centre line; the mirror supplies the
 * other two.
 *
 * Each arm reaches from the border lane to the centre line it is *not* offset from, so the four of
 * them turn about the middle of the board without ever meeting. **The joints are what keep the open
 * squares one region**: the horizontal arm stops on the centre column and the vertical arm stands
 * [PINWHEEL_MIN_OFFSET] or more columns past it, so the loop the four arms almost draw is broken at
 * every corner and encloses nothing.
 *
 * The offset scales with the board so the lanes between the arms widen with it, and the two-square
 * border margin is why the shape reads as lanes at all: an arm against the border would be a wall
 * with a slot in it.
 */
private fun pinwheel(half: HalfBoard) {
    val rowMiddle = (half.rows - 1) / 2
    val colMiddle = (half.cols - 1) / 2
    val rowOffset = maxOf(PINWHEEL_MIN_OFFSET, half.rows / PINWHEEL_OFFSET_DIVISOR)
    val colOffset = maxOf(PINWHEEL_MIN_OFFSET, half.cols / PINWHEEL_OFFSET_DIVISOR)

    half.setRow(rowMiddle - rowOffset, PINWHEEL_MARGIN, colMiddle)
    half.setColumn(colMiddle + colOffset, PINWHEEL_MARGIN, rowMiddle)
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
 * **The opening has to be centred on the middle and nowhere else.** ρ reverses a bar end for end, so
 * a run centred on the middle is the one that lands on the image bar's opening. That is also why the
 * opening is three squares on an odd-length bar and four on an even one rather than a flat three: an
 * even-length bar has no single middle square, so a run centred on it has even length too.
 *
 * A solid bar would cut the board in two, so every bar carries an opening, and the openings line up
 * into one corridor along the board's other diagonal. That is what keeps the open squares one region.
 * A bar no longer than its own opening simply does not appear, which is what happens to the short
 * bars in two of the corners.
 */
private fun diagonals(half: HalfBoard) {
    // The bar index runs to `rows + cols - 2`, and an even period survives the half turn only where
    // that is even. Refused here rather than by symmetricAnchor so the message names the shape and
    // the board, which is what every other refusal in generateMap does.
    require((half.rows + half.cols) % 2 == 0) {
        "diagonals needs a board whose sides are both odd or both even, was ${half.rows}x${half.cols}"
    }

    val last = half.rows + half.cols - 2
    val first = symmetricAnchor(last + 1, DIAGONAL_PERIOD, atLeast = 0)

    for (bar in first..last step DIAGONAL_PERIOD) {
        val fromRow = maxOf(0, bar - (half.cols - 1))
        val toRow = minOf(half.rows - 1, bar)
        val length = toRow - fromRow + 1

        for (row in fromRow..toRow) {
            val along = row - fromRow
            if (along >= (length - 1) / 2 - DIAGONAL_REACH && along <= length / 2 + DIAGONAL_REACH) {
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
 * happened to pass. The doorway is a *band* rather than a square — see [bandDoors] — so a chamber is
 * something two snakes can pass each other in.
 */
private fun rooms(half: HalfBoard) {
    val wallRows = bandWalls(half.rows)
    val wallCols = bandWalls(half.cols)
    val doorRows = bandDoors(half.rows, wallRows)
    val doorCols = bandDoors(half.cols, wallCols)

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

/**
 * Isolated blocks of the sizes in [ISLAND_BLOCKS], placed at random until [density] of the board is
 * wall, or until nowhere is left.
 *
 * [scatter]'s walk with a block where it places a square, and it terminates for the same reason:
 * every candidate in the board's first half is offered exactly once, in a shuffled order, with one
 * block size drawn for each. A density this rule cannot reach fails the same way too, saying what it
 * managed. Density counts **squares** placed and not blocks, so a number here asks the board for the
 * same amount of wall it would ask [scatter] for.
 *
 * The draws come from [scatter]'s own stream rather than a new one: a second stream would be a
 * second thing that has to be kept clear of every slot's randomness and of `:lab`'s openings.
 *
 * Connectivity is [HalfBoard.placeIsolatedBlock]'s to guarantee, and the argument there is why a
 * block is allowed to be bigger than a square at all.
 */
private fun islands(half: HalfBoard, density: Double, seed: Long) {
    val requested = if (density > 0.0) density else ISLAND_DEFAULT_DENSITY
    val target = (requested * half.rows * half.cols).toInt()
    val rng = SplitMix64(seed).fork(MAP_STREAM)
    val candidates = shuffledHalf(half, rng)

    var placed = 0
    for (index in candidates) {
        if (placed >= target) break
        placed += island(half, index / half.cols, index % half.cols, rng.nextInt(ISLAND_SHAPES))
    }

    require(placed >= target) {
        "a ${half.rows}x${half.cols} board takes $placed squares of isolated blocks, short of the " +
            "$target a density of $requested asks for"
    }
}

/**
 * Places the first of [ISLAND_BLOCKS] that fits at `(row, col)`, starting from [first] and wrapping,
 * and answers how many squares of wall that put on the board.
 *
 * Every size is offered rather than only the one drawn, and one of them is a single square, so a
 * candidate with room for something is never spent on a block that does not fit. Without that a
 * board could refuse a density it has the room for, and the failure would be a *seed* rather than a
 * request — which is the one thing [scatter]'s "offer every square exactly once" was shaped to avoid.
 */
private fun island(half: HalfBoard, row: Int, col: Int, first: Int): Int {
    for (offset in 0 until ISLAND_SHAPES) {
        val block = BLOCK_SIDES * ((first + offset) % ISLAND_SHAPES)
        val height = ISLAND_BLOCKS[block]
        val width = ISLAND_BLOCKS[block + 1]
        if (half.placeIsolatedBlock(row, col, height, width)) {
            // A block centred on the board is its own image, so it is one block and not two.
            val ownImage = 2 * row + height == half.rows && 2 * col + width == half.cols
            return if (ownImage) height * width else 2 * height * width
        }
    }
    return 0
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

/**
 * The side of the block [MapShape.ARENA] centres on an axis of [extent] squares.
 *
 * About a quarter of the axis, never under [ARENA_MIN_CENTRE], and **nudged to the axis's own
 * parity**: a block is centred only when `2 * top + side == extent`, so a block of the wrong parity
 * cannot be its own image under ρ and the mirror would put a second one a square off it.
 */
private fun arenaCentre(extent: Int): Int {
    val side = maxOf(ARENA_MIN_CENTRE, extent / ARENA_CENTRE_DIVISOR)
    return side + (extent - side) % 2
}

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

/**
 * The doorway in each band [walls] cuts [extent] into: the **two** central squares of an even-length
 * band and the **three** central squares of an odd-length one, ascending.
 *
 * Two-or-three rather than a flat two, because ρ reverses a band end for end and only a run centred
 * on the band's middle lands on the image band's doorway. An even-length band has no single middle
 * square, so its centred runs have even length; an odd-length band's do not. A flat two would put
 * the doorway of an odd band a square off its own image, and the mirror would open a *second*
 * doorway rather than the same one.
 */
private fun bandDoors(extent: Int, walls: IntArray): IntArray {
    val doors = IntArray((walls.size + 1) * ODD_BAND_DOOR)
    var next = 0
    var start = 0
    for (band in 0..walls.size) {
        val end = if (band < walls.size) walls[band] - 1 else extent - 1
        val middle = (start + end) / 2
        val from = if ((end - start) % 2 == 0) middle - 1 else middle
        for (door in from..middle + 1) {
            doors[next++] = door
        }
        start = end + 2
    }
    return doors.copyOf(next)
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

/** The smallest block [MapShape.ARENA] centres, and the fraction of the side it grows to. */
private const val ARENA_MIN_CENTRE: Int = 2
private const val ARENA_CENTRE_DIVISOR: Int = 4

private const val PILLAR_PERIOD: Int = 3

/** Open squares between a pinwheel arm and the border it runs beside. */
private const val PINWHEEL_MARGIN: Int = 2

/**
 * How far a pinwheel arm stands off the centre line it is parallel to.
 *
 * At least two, so the arms never meet at a corner and the four of them enclose nothing, and a fifth
 * of the side once the board is wide enough to spend it — the lanes between the arms are what the
 * shape is for, so they grow with the board rather than staying a fixed width on a large one.
 */
private const val PINWHEEL_MIN_OFFSET: Int = 2
private const val PINWHEEL_OFFSET_DIVISOR: Int = 5

private const val RING_MIN_INSET: Int = 1
private const val RING_INSET_DIVISOR: Int = 5

/** Open squares between a cross bar's end and the middle band, on each side. */
private const val CROSS_GAP: Int = 1

/**
 * The spacing of the anti-diagonal bars.
 *
 * **Even, which costs the shape a board whose two sides differ in parity.** A period-`p` family is
 * ρ-invariant only where `2a ≡ extent - 1 (mod p)` has a solution, and for even `p` that needs
 * `rows + cols` even — so [diagonals] refuses a 12x13 board. Every board the picker, the gauntlet
 * and the test sweep offer is square or of one parity, and the alternative is a spacing of five or
 * seven: the first is what this shape was too tight at, and the second drops a bar from every board
 * in the catalogue.
 */
private const val DIAGONAL_PERIOD: Int = 6

/**
 * How far past a bar's middle its opening reaches, on each side.
 *
 * One, so the opening is the three central squares of an odd-length bar and the four central squares
 * of an even one.
 */
private const val DIAGONAL_REACH: Int = 1

private const val ROOM_SIDE: Int = 7
private const val ROOM_MIN_BANDS: Int = 3

/** Squares of doorway an odd-length band gives up: the middle and its two neighbours. */
private const val ODD_BAND_DOOR: Int = 3

private const val SPIRAL_SIDES: Int = 4

/** Squares the spiral advances every two sides: one of wall and the rest of corridor. */
private const val SPIRAL_STEP: Int = 4

/** The spiral starts one square in, so the border is an open lane the whole way round. */
private const val SPIRAL_MARGIN: Int = 1

/** What [MapShape.SCATTER] scatters when nobody asked for a density. */
private const val SCATTER_DEFAULT_DENSITY: Double = 0.12

/**
 * What [MapShape.ISLANDS] scatters when nobody asked for a density.
 *
 * Two thirds of [SCATTER_DEFAULT_DENSITY], because a block keeps a free square from every edge and
 * that costs the shape the whole border ring: the worst order the shuffle can offer fills about a
 * tenth of the smallest board this draws on, where lone squares reach an eighth of it. A default
 * that a bad seed cannot reach would fail on the seed rather than on the request, which is exactly
 * what the walk in [islands] is shaped to avoid.
 */
private const val ISLAND_DEFAULT_DENSITY: Double = 0.08

/** A height and a width per entry, which is what [ISLAND_BLOCKS] is read in pairs of. */
private const val BLOCK_SIDES: Int = 2

/**
 * The blocks [MapShape.ISLANDS] scatters, as height/width pairs, largest first.
 *
 * A flat array rather than a list of pairs because one is drawn per candidate square and the shape
 * is asked for a whole board's worth. Nothing longer than three squares, because a block has to
 * leave a lane round it on the smallest board the shape draws on. **The single square has to be in
 * the list**: [island] wraps through every entry, so its presence is what makes a candidate square
 * fit whenever it would have fitted [scatter].
 */
private val ISLAND_BLOCKS: IntArray = intArrayOf(2, 2, 3, 1, 1, 3, 2, 1, 1, 2, 1, 1)

private val ISLAND_SHAPES: Int = ISLAND_BLOCKS.size / BLOCK_SIDES
