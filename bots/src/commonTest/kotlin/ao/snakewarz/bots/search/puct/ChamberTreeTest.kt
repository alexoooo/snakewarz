package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.HeadlessMatch
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.cornerSpawns
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That [ChamberTree] is [FillableSpace]'s decomposition rather than a second one, and what it keeps
 * that the other throws away.
 *
 * The first claim is the load-bearing one and it is checkable exactly: turn the parity blend all the
 * way to the cap and the frontier discount off, and the chain has to come out at the integer
 * [FillableSpace] returns — on every hand-drawn shape and on four hundred nobody drew. A
 * decomposition that agreed on rooms and disagreed on combs would be a different algorithm wearing
 * the same name, and the two would drift apart the first time either was touched.
 *
 * Everything after that is a claim about the readings the single integer cannot carry: the region's
 * area beside the chain's, how many chambers it came apart into, and how much of a chamber sits on
 * ground somebody else reaches first.
 */
class ChamberTreeTest {
    @Test
    fun `with the cap applied whole and nothing contested, the chain is the fillable square count`() {
        for (picture in shapes()) {
            val region = Region(picture)
            val tree = ChamberTree(region.grid, parityWeight = 1.0, frontierPenalty = 0.0)
            tree.measure(region.space, region.slot, region.head)

            assertEquals(
                region.fillable().toDouble(),
                tree.chainWorth,
                "${picture.joinToString("/")}: the chambers do not add up to the square count",
            )
        }
    }

    @Test
    fun `and it holds on four hundred regions nobody drew`() {
        // Generated rather than drawn, because the claim is about the decomposition and not about the
        // shapes somebody thought to try. A quarter of each board walled off at random makes combs,
        // dumbbells and dead ends without anybody having to name them.
        val rng = SplitMix64(20260728)
        var checked = 0

        repeat(400) {
            val picture = randomPicture(rng) ?: return@repeat
            val region = Region(picture)
            checked++

            val tree = ChamberTree(region.grid, parityWeight = 1.0, frontierPenalty = 0.0)
            tree.measure(region.space, region.slot, region.head)

            assertEquals(
                region.fillable().toDouble(),
                tree.chainWorth,
                "${picture.joinToString("/")}: the chambers do not add up to the square count",
            )
        }

        assertTrue(checked > 300, "only $checked of the generated regions were usable")
    }

    @Test
    fun `a plain room is one chamber, and the walk reaches all of it`() {
        val tree = treeOn(Region(listOf("@..", "...", "...")))

        assertEquals(1, tree.chamberCount, "a 3x3 has no articulation point, so it does not come apart")
        assertEquals(8, tree.regionArea, "eight squares of room in front of the head")
        assertEquals(8, tree.chainArea, "and a walk from a corner takes the lot")
        assertEquals(0.0, tree.sealed, "so nothing is sealed off")
    }

    @Test
    fun `a fork seals off whatever the chain does not choose`() {
        // The reading FillableSpace cannot carry. Both arms are two squares, the walk commits to one,
        // and the other is gone -- so the chain is worth half the region and the region says so. To
        // that class this position and a two-square corridor are the same number.
        val tree = treeOn(Region(listOf("..@..")))

        assertEquals(4, tree.regionArea)
        assertEquals(2, tree.chainArea, "a walk out of the middle can only have one arm")
        assertEquals(0.5, tree.sealed, "and the other arm is half the region")
        assertTrue(tree.chamberCount > 1, "a path comes apart at every square, so this is not one chamber")
    }

    @Test
    fun `a corridor entered at its end seals nothing, whatever its length`() {
        // The control for the fixture above: same chamber count, same chain, and no choice to make.
        for (length in 2..6) {
            val tree = treeOn(Region(listOf("@" + ".".repeat(length))))

            assertEquals(length, tree.regionArea)
            assertEquals(length, tree.chainArea, "a corridor of $length is all chain")
            assertEquals(0.0, tree.sealed)
        }
    }

    @Test
    fun `ground somebody else reaches first costs a chamber a share of its worth`() {
        // 1x4, heads at either end. The mover gets the square in front of it and its rival gets the
        // other, so the one square this snake owns is entirely boundary -- which is the position the
        // discount exists for, and the one every other leaf here reads as a square held outright.
        val board = boardOf(1, 4, 0 to 0, 0 to 3)
        val space = TempoOwnership(board.grid, 2)
        val owned = space.measure(board)
        assertEquals(listOf(1, 1), owned.toList(), "the fixture did not divide as intended")

        val head = board.snake(SnakeId(0)).head
        assertEquals(1.0, worthOf(board.grid, space, head, frontierPenalty = 0.0))
        assertEquals(0.5, worthOf(board.grid, space, head, frontierPenalty = 0.5))
    }

    @Test
    fun `and ground nobody else can reach costs it nothing`() {
        // The other half, or the discount would be a constant with extra steps. One snake on an empty
        // board has no boundary anywhere, so no setting of the knob may move its reading.
        val region = Region(listOf("@..", "...", "..."))

        assertEquals(
            worthOf(region.grid, region.space, region.head, frontierPenalty = 0.0),
            worthOf(region.grid, region.space, region.head, frontierPenalty = 1.0),
        )
    }

    @Test
    fun `the buffers survive being reused, which is the only way they are ever used`() {
        // One instance per bot per match, thousands of leaves a turn. A generation stamp that failed
        // to reset would make the second reading of a position depend on the first -- and the
        // contested stamp is a second one of those, so it is a second way to get this wrong.
        val board = boardOf(1, 4, 0 to 0, 0 to 3)
        val space = TempoOwnership(board.grid, 2)
        space.measure(board)

        val tree = ChamberTree(board.grid, parityWeight = 1.0, frontierPenalty = 0.5)
        val head = board.snake(SnakeId(0)).head

        tree.measure(space, 0, head)
        val first = tree.chainWorth
        tree.measure(space, 0, head)

        assertEquals(first, tree.chainWorth, "the same question, twice, one answer")
    }

    @Test
    fun `how often a region has any structure at all, which bounds what the chambers can be worth`() {
        // The ceiling on this whole evaluation, measured rather than assumed: where a region is one
        // chamber the chain is the region and the reading falls back on SurvivalEval's. `chase` plays
        // the space-filling endgame this is about and costs a test nothing, so the positions are real
        // games rather than boards somebody drew. Printed as well as asserted, because the rate is
        // what a later batch has to be read against.
        //
        // Measured: 311 of 597 positions came apart into more than one chamber and 81 of 597 had
        // ground the chain could not reach. So the structure is there about half the time and the
        // seal term fires on one position in seven -- against MCTS-Solver's 0.19%, which is what a
        // rate too low to matter looks like. The assertion below is loose on purpose: the number
        // moves with the bots and the board, and what it is guarding is the order of magnitude.
        val chase = ShippedBots.entryOf(BotId("chase"))
        var positions = 0
        var split = 0
        var sealedOff = 0

        for (seed in 1L..4L) {
            val match = HeadlessMatch(listOf(chase, chase), rows = 12, cols = 12, seed = seed)
            match.run()

            val grid = Grid(12, 12)
            val board = Board(grid, cornerSpawns(grid, 2))
            val space = TempoOwnership(grid, 2)
            val tree = ChamberTree(grid, parityWeight = 1.0, frontierPenalty = 0.5)

            for (move in match.moves()) {
                if (board.outcome != null) {
                    break
                }
                val id = board.toAct
                space.measure(board)
                tree.measure(space, id.index, board.snake(id).head)

                positions++
                if (tree.chamberCount > 1) {
                    split++
                }
                if (tree.sealed > 0.0) {
                    sealedOff++
                }
                board.apply(id, move)
            }
        }

        println("[chambers] $split of $positions positions hold more than one chamber")
        println("[chambers] $sealedOff of $positions have ground the chain cannot reach")

        assertTrue(positions > 200, "only $positions positions to measure")
        assertTrue(
            split * 4 > positions,
            "only $split of $positions regions came apart, so the chambers have almost nothing to say",
        )
    }

    // -- internals

    private fun treeOn(region: Region): ChamberTree {
        val tree = ChamberTree(region.grid, parityWeight = 1.0, frontierPenalty = 0.0)
        tree.measure(region.space, region.slot, region.head)
        return tree
    }

    private fun worthOf(grid: Grid, space: TempoOwnership, head: Cell, frontierPenalty: Double): Double {
        val tree = ChamberTree(grid, parityWeight = 1.0, frontierPenalty = frontierPenalty)
        tree.measure(space, 0, head)
        return tree.chainWorth
    }

    private fun shapes(): List<List<String>> = listOf(
        listOf("@.."),
        listOf("@..", "...", "..."),
        listOf(".@.", "...", "..."),
        listOf("...", ".@.", "..."),
        listOf("..@.."),
        listOf("@....", ".#.#."),
        listOf("@....", ".#.#.", ".#.#."),
        listOf("@....", ".#.#.", ".#.#.", ".#.#."),
        listOf("@.#..", ".....", "..#.."),
        listOf("@.#..", "..#..", "..#..", "....."),
        listOf("@#.", "...", ".#."),
        listOf("####", "#@.#", "####"),
    )

    /** A region of a few squares with a head somewhere in it, or `null` if it came out too small. */
    private fun randomPicture(rng: SplitMix64): List<String>? {
        val rows = 3 + rng.nextInt(3)
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

        val head = open[rng.nextInt(open.size)]
        cells[head.first][head.second] = HEAD
        return cells.map { it.concatToString() }
    }

    private companion object {
        const val FREE = '.'
        const val WALL = '#'
        const val HEAD = '@'
    }
}

/**
 * A hand-drawn region: `#` wall, `.` free, `@` the snake's head.
 *
 * Walls are one-square snakes eliminated before anything is measured, because a dead snake's body
 * stays on the board as an obstacle and a dead snake seeds no sweep. The measured snake is the last
 * slot and the only survivor, so [TempoOwnership] hands it every free square it can reach — which is
 * what makes the picture the region rather than merely a board it is drawn on.
 */
private class Region(picture: List<String>) {
    val grid = Grid(picture.size, picture[0].length)
    val head: Cell
    val slot: Int
    val space: TempoOwnership

    init {
        val walls = mutableListOf<Int>()
        var headCell = -1

        for (row in picture.indices) {
            require(picture[row].length == grid.cols) { "row $row is ${picture[row].length} squares, not ${grid.cols}" }
            for (col in 0 until grid.cols) {
                val cell = grid.cellAt(row, col).index
                when (val symbol = picture[row][col]) {
                    '.' -> Unit
                    '#' -> walls += cell
                    '@' -> headCell = cell
                    else -> error("'$symbol' is not a square")
                }
            }
        }
        require(headCell >= 0) { "the picture has no head" }

        val spawns = (walls + headCell).toIntArray()
        val board = Board(grid, spawns)
        for (dead in 0 until spawns.size - 1) {
            board.eliminate(SnakeId(dead), EliminationReason.RESIGNED)
        }

        slot = spawns.size - 1
        head = Cell(headCell)
        space = TempoOwnership(grid, board.snakeCount)
        space.measure(board)
    }

    /** What [FillableSpace] says about the same region, in squares. */
    fun fillable(): Int = FillableSpace(grid).measure(space, slot, head)
}
