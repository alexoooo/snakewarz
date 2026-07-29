package ao.snakewarz.bots.search.puct

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SurvivalHorizon] against an oracle, which is the reason this correction is worth making at all.
 *
 * "Moves until trapped" is exactly computable on a region small enough to search, so every claim the
 * estimate makes is checkable rather than arguable. [ExactHorizon] plays every legal continuation
 * under the shipped rules and reports the longest; the shapes are drawn by hand where the claim is
 * about a shape and generated where the claim is about all of them.
 *
 * **The bound is the non-negotiable one.** An evaluation is read as a comparison, so a reading that
 * is sometimes over and sometimes under the truth cannot be checked against anything; one that is
 * always over can be, and can be tightened. Everything else here is a tightness claim measured
 * against the same oracle and against [FillableSpace], which answers the question this replaces.
 */
class SurvivalHorizonTest {
    @Test
    fun `no region reads as worth more moves than a walk can take out of it`() {
        // Four hundred generated regions -- boards of 3x3 to 4x5, a quarter of their squares walled
        // off at random, a snake of one to five squares dropped somewhere in the rest. Small enough
        // to search exhaustively, varied enough that a rule which only holds on rooms fails here.
        val rng = SplitMix64(20260728)
        var checked = 0

        repeat(400) {
            val picture = randomPicture(rng) ?: return@repeat
            val shape = Shape(picture, growsNext = rng.nextInt(2) == 1)
            checked++

            val horizon = shape.horizon()
            assertTrue(horizon >= shape.exact(), "${shape.describe()} reads under the truth")
            assertTrue(horizon >= shape.fillable(), "${shape.describe()} reads under the square count")
        }

        assertTrue(checked > 300, "only $checked of the generated regions were usable")
    }

    @Test
    fun `an open room is worth twice its squares, which is what a snake gets out of one`() {
        // The half of the correction that is uniform: a walk that can loop spends every square twice,
        // because the tail frees one behind it every second move. Measured slack, in moves: 2, 1, 2,
        // 1, 0, 0 -- and the 3x4 and the 2x4 are exact, so this is not a bound that merely happens to
        // be large enough.
        for (room in openRooms()) {
            val exact = room.exact()
            val horizon = room.horizon()

            assertTrue(horizon >= exact, "${room.describe()} reads under the truth")
            assertTrue(horizon - exact <= 2, "${room.describe()} is not tight on an open room")
            assertTrue(room.fillable() < exact, "${room.describe()}: the square count should be reading low here")
        }
    }

    @Test
    fun `a neck a walk can cross twice reads far closer than the square count does`() {
        // Two rooms joined by one square, which is the shape the whole correction is named for: a
        // self-avoiding walk enters the far room and never leaves, and a retracting one crosses back
        // as soon as its own tail clears the neck. 21 against a true 17 where the square count says
        // 12, and 27 against 23 where it says 16.
        for (dumbbell in dumbbells()) {
            assertCloserThanSquares(dumbbell)
        }
    }

    @Test
    fun `a comb is worth its spine and one tooth, and it says so within a move`() {
        // The other half, and the one that keeps the estimate honest: a tooth is a place a walk goes
        // and stops, so a reading that doubled everything would be wrong here by as much as it is
        // right on a room. 8, 9 and 10 against a true 7, 8 and 9.
        for (comb in combs()) {
            assertCloserThanSquares(comb)
        }
    }

    @Test
    fun `a snake too long to turn round is left with exactly the square count`() {
        // The limit the correction has to have, because FillableSpace's premise is true there: a
        // snake long enough that no square clears before it runs out really is a self-avoiding walk,
        // and away from its own head this reads what that class reads, to the square.
        val comb = shape("@....", ".#.#.", ".#.#.")

        assertEquals(6, comb.fillable(), "the fixture is only interesting at the number it converges on")
        assertEquals(9, comb.horizon(length = 1), "a snake of one square gets round the teeth")
        for (length in 2..14) {
            assertEquals(6, comb.horizon(length), "a snake of $length squares should be reading the square count")
        }
    }

    @Test
    fun `the reading never rises as the snake gets longer`() {
        // Length costs a snake its detours and can never buy one, so a leaf that read otherwise would
        // give the search a reason to grow -- and growing is the one thing it does not choose.
        for (shape in dumbbells() + combs() + openRooms()) {
            var previous = Int.MAX_VALUE
            for (length in 1..14) {
                val reading = shape.horizon(length)
                assertTrue(reading <= previous, "${shape.describe()} rose from $previous at length $length")
                previous = reading
            }
        }
    }

    @Test
    fun `a snake with nowhere to go has no horizon at all`() {
        assertEquals(0, shape("@#.", "2..", "1#.").horizon(), "a walled-in snake gets no moves")
        assertEquals(2, shape("@.").horizon(), "and one square in front of it is worth two moves, not one")
    }

    @Test
    fun `the ground a body gives back is beyond this reading, and beyond the square count too`() {
        // The limitation, stated where somebody will find it. The sweep hands out free squares, so a
        // snake's own body is not the region -- and yet the squares under it come free as the tail
        // passes, and can open ground no reading taken now can see. Both readings are under the truth
        // here, and by the same mechanism: this is not something the moves reading introduces.
        val coiled = shape("#.@", "..2", "#.1")

        val horizon = coiled.horizon()
        assertEquals(horizon, coiled.exact(), "within the region the sweep gave it, the estimate is exact here")
        assertTrue(
            coiled.exactWithBody() > horizon,
            "the fixture is only interesting if the body gives ground back: ${coiled.describe()}",
        )
        assertTrue(coiled.fillable() <= horizon, "and the square count is no better placed to see it")
    }

    @Test
    fun `the buffers survive being reused, which is the only way they are ever used`() {
        // One instance per bot per match, thousands of leaves a turn. A generation stamp that failed
        // to reset would make the second reading of a position depend on the first.
        val room = shape("@..", "...", "...")
        val first = room.horizon()

        assertEquals(first, room.horizon(), "the same question, twice, one answer")
    }

    // -- internals

    private fun assertCloserThanSquares(shape: Shape) {
        val exact = shape.exact()
        val horizon = shape.horizon()
        val fillable = shape.fillable()

        assertTrue(horizon >= exact, "${shape.describe()} reads under the truth")
        assertTrue(
            horizon - exact < exact - fillable,
            "${shape.describe()}: the moves reading has to be nearer the truth than the square count",
        )
    }

    private fun openRooms(): List<Shape> = listOf(
        shape("@..", "...", "..."),
        shape(".@.", "...", "..."),
        shape("...", ".@.", "..."),
        shape("@..", "...", "...", growsNext = true),
        shape("@...", "...."),
        shape("@...", "....", "...."),
    )

    private fun dumbbells(): List<Shape> = listOf(
        shape("@.#..", ".....", "..#.."),
        shape("@.#..", "..#..", "..#..", "....."),
    )

    private fun combs(): List<Shape> = listOf(
        shape("@....", ".#.#."),
        shape("@....", ".#.#.", ".#.#."),
        shape("@....", ".#.#.", ".#.#.", ".#.#."),
    )

    private fun shape(vararg picture: String, growsNext: Boolean = false): Shape = Shape(picture.toList(), growsNext)

    /**
     * A region of three to twenty squares with a snake somewhere in it, or `null` if it came out too
     * small to say anything. The body is a self-avoiding walk back from the head, so it is a snake
     * rather than a scattering of obstacles.
     */
    private fun randomPicture(rng: SplitMix64): List<String>? {
        val rows = 3 + rng.nextInt(2)
        val cols = 3 + rng.nextInt(3)
        val cells = Array(rows) { CharArray(cols) { if (rng.nextInt(4) == 0) WALL else FREE } }

        val open = mutableListOf<Pair<Int, Int>>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (cells[row][col] == FREE) {
                    open += row to col
                }
            }
        }
        if (open.size < 3) {
            return null
        }

        var here = open[rng.nextInt(open.size)]
        val path = mutableListOf(here)
        val wanted = 1 + rng.nextInt(5)
        while (path.size < wanted) {
            val steps = Direction.entries
                .map { here.first + it.dRow to here.second + it.dCol }
                .filter { it.first in 0 until rows && it.second in 0 until cols }
                .filter { cells[it.first][it.second] == FREE && it !in path }
            if (steps.isEmpty()) {
                break
            }
            here = steps[rng.nextInt(steps.size)]
            path += here
        }

        cells[path[0].first][path[0].second] = HEAD
        for (i in 1 until path.size) {
            cells[path[i].first][path[i].second] = '0' + (path.size - i)
        }
        return cells.map { it.concatToString() }
    }

    private companion object {
        const val FREE = '.'
        const val WALL = '#'
        const val HEAD = '@'
    }
}

/**
 * A hand-drawn position: `#` wall, `.` free, `@` the snake's head, `1`..`9` its body from the tail up.
 *
 * Walls and body squares are one-square snakes eliminated before anything is measured, because a dead
 * snake's body stays on the board as an obstacle and a dead snake seeds no sweep. The measured snake
 * is the last slot and the only survivor, so [TempoOwnership] hands it every free square it can reach
 * — which is what makes the picture the region rather than merely a board it is drawn on.
 */
private class Shape(private val picture: List<String>, val growsNext: Boolean) {
    private val grid = Grid(picture.size, picture[0].length)
    private val board: Board
    private val space: TempoOwnership
    private val head: Cell
    private val slot: Int

    /** The body from the tail up to and including the head, which is what the exact search unwinds. */
    private val body: IntArray

    init {
        val walls = mutableListOf<Int>()
        val numbered = arrayOfNulls<Int>(BODY_LIMIT)
        var headCell = -1

        for (row in picture.indices) {
            require(picture[row].length == grid.cols) { "row $row is ${picture[row].length} squares, not ${grid.cols}" }
            for (col in 0 until grid.cols) {
                val cell = grid.cellAt(row, col).index
                when (val symbol = picture[row][col]) {
                    '.' -> Unit
                    '#' -> walls += cell
                    '@' -> headCell = cell
                    in '1'..'9' -> numbered[symbol - '0'] = cell
                    else -> error("'$symbol' is not a square")
                }
            }
        }
        require(headCell >= 0) { "the picture has no head" }

        val trail = mutableListOf<Int>()
        while (numbered[trail.size + 1] != null) {
            trail += numbered[trail.size + 1]!!
        }
        require(trail.size == numbered.count { it != null }) { "the body skips a number" }

        body = (trail + headCell).toIntArray()
        for (i in 1 until body.size) {
            require(Direction.entries.any { body[i - 1] + grid.offsetOf(it) == body[i] }) {
                "the body is not a path at square $i"
            }
        }

        val spawns = (walls + trail + headCell).toIntArray()
        board = Board(grid, spawns)
        for (dead in 0 until spawns.size - 1) {
            board.eliminate(SnakeId(dead), EliminationReason.RESIGNED)
        }

        slot = spawns.size - 1
        head = Cell(headCell)
        space = TempoOwnership(grid, board.snakeCount)
        space.measure(board)
    }

    /** What [SurvivalHorizon] says, for a snake of [length] rather than only the one that was drawn. */
    fun horizon(length: Int = body.size): Int =
        SurvivalHorizon(grid).measure(space, slot, head, length, growsNext)

    /** What [FillableSpace] says, in squares. */
    fun fillable(): Int = FillableSpace(grid).measure(space, slot, head)

    /** The true answer within the region the sweep handed over. */
    fun exact(): Int = ExactHorizon(grid, occupiedMask(), body, growsNext, sealBody = true).measure()

    /** The true answer on the whole board, where the squares under the opening body come back too. */
    fun exactWithBody(): Int = ExactHorizon(grid, occupiedMask(), body, growsNext, sealBody = false).measure()

    /** Region squares other than the head — what the ceiling is computed from. */
    fun freeCount(): Int = (0 until grid.cellCount).count { space.ownerOf(Cell(it)) == slot } - 1

    fun describe(): String =
        "${picture.joinToString("/")} grows=$growsNext free=${freeCount()} " +
            "horizon=${horizon()} exact=${exact()} fillable=${fillable()}"

    private fun occupiedMask(): BooleanArray = BooleanArray(grid.cellCount) { !board.isFree(Cell(it)) }

    private companion object {
        /** Digits `1`..`9` and a slot at zero nothing uses, so a symbol indexes straight in. */
        const val BODY_LIMIT = 10
    }
}

/**
 * Moves until trapped, exactly, by exhaustive depth-first search over a private board.
 *
 * The shipped rules: legality is tested before the tail retracts, and the tail retracts on
 * alternating moves so the body runs `1, 1, 2, 2, 3…`. [sealBody] is the one thing that is a choice —
 * the squares of the *opening* body come free as the tail passes them, and they are not the region
 * [SurvivalHorizon] was handed, because the sweep gives out free squares and they are occupied. Seal
 * them and the search answers the question the estimate answers; leave them and it answers what the
 * board would actually allow, which is more and which no reading taken now can see.
 *
 * Pruned on the counting bound the estimate itself ends with: a walk with `f` free squares in front of
 * it has at most `2 * f` moves left. That is what keeps a twenty-square region inside a test suite.
 */
private class ExactHorizon(
    grid: Grid,
    occupied: BooleanArray,
    body: IntArray,
    growsNext: Boolean,
    sealBody: Boolean,
) {
    private val occupied = occupied.copyOf()
    private val sealed = BooleanArray(occupied.size)
    private val offsets = IntArray(Direction.entries.size) { grid.offsetOf(Direction.entries[it]) }
    private val ring = IntArray(2 * occupied.size + body.size)

    private var tail = 0
    private var top = body.size
    private var grows = growsNext
    private var free = 0
    private var best = 0

    init {
        body.copyInto(ring)
        if (sealBody) {
            for (i in 0 until body.size - 1) {
                sealed[body[i]] = true
            }
        }
        for (cell in occupied.indices) {
            if (!occupied[cell]) {
                free++
            }
        }
    }

    fun measure(): Int {
        search(0)
        return best
    }

    private fun search(made: Int) {
        if (made > best) {
            best = made
        }
        if (made + 2 * free - (if (grows) 1 else 0) <= best) {
            return
        }

        val from = ring[top - 1]
        for (i in offsets.indices) {
            val next = from + offsets[i]
            if (occupied[next]) {
                continue
            }

            val grew = grows
            val vacated = if (grew) -1 else ring[tail]
            if (!grew) {
                tail++
                if (!sealed[vacated]) {
                    occupied[vacated] = false
                    free++
                }
            }
            occupied[next] = true
            free--
            ring[top++] = next
            grows = !grows

            search(made + 1)

            grows = !grows
            top--
            free++
            occupied[next] = false
            if (!grew) {
                if (!sealed[vacated]) {
                    occupied[vacated] = true
                    free--
                }
                tail--
            }
        }
    }
}
