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
        MapShape.ROOMS -> rooms(half)
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

private const val ROOM_SIDE: Int = 7
private const val ROOM_MIN_BANDS: Int = 3

/** Squares of doorway an odd-length band gives up: the middle and its two neighbours. */
private const val ODD_BAND_DOOR: Int = 3

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
