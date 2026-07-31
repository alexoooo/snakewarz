package ao.snakewarz.match

import ao.snakewarz.core.grid.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MostDistantSpawnsTest {
    @Test
    fun `on an empty board the placement is the one every recorded match was played from`() {
        // Spawns travel in the replay header, so these are not "what the function returns today" but
        // the openings of every match anybody has shared, every logged game the corpus is fitted from
        // and every rung of the ladder. A map filter that moved one of them would invalidate the lot.
        assertEquals(EMPTY_BOARD_OPENINGS, emptyBoardOpenings())
    }

    @Test
    fun `every spawn is an open square, and one walk reaches them all`() {
        val grid = Grid(9, 9)
        val walls = pillarLattice(grid)

        for (seats in 1..4) {
            val spawns = mostDistantSpawns(grid, walls, seats)
            val where = "$seats seats"
            val reachable = openRegionFrom(grid.rows, grid.cols, walls, spawns[0])

            assertEquals(seats, spawns.toSet().size, "$where: two snakes share a square")
            for (spawn in spawns) {
                assertFalse(spawn in walls, "$where: a snake starts at $spawn, which is a wall")
                assertTrue(reachable[spawn], "$where: $spawn is cut off from the first spawn")
            }
        }
    }

    @Test
    fun `a snake is never seated in a pocket the first snake cannot walk to`() {
        // Walls at 19 and 23 are the only two neighbours of the far corner of a 5x5, so 24 is a
        // sealed square. Seat 1 takes the highest open index and lands in it -- that rule is the
        // half-turn image of seat 0's and is deliberately unconditional -- but seat 2 is scored over
        // the region a walk from seat 0 reaches, and so must go elsewhere.
        val grid = Grid(5, 5)
        val walls = intArrayOf(19, 23)

        val spawns = mostDistantSpawns(grid, walls, 3)

        assertEquals(0, spawns[0])
        assertEquals(SEALED_CORNER, spawns[1])
        assertTrue(openRegionFrom(grid.rows, grid.cols, walls, spawns[0])[spawns[2]])
    }

    @Test
    fun `a field that does not fit the open squares is refused`() {
        // The board has room for four snakes and the map leaves room for two.
        assertFailsWith<IllegalArgumentException> { mostDistantSpawns(Grid(2, 2), intArrayOf(1, 2), 3) }
    }

    private fun emptyBoardOpenings(): Map<String, List<Int>> = buildMap {
        for ((rows, cols) in GEOMETRIES) {
            for (seats in 1..4) {
                put("${rows}x$cols x$seats", mostDistantSpawns(Grid(rows, cols), IntArray(0), seats).toList())
            }
        }
    }

    private companion object {
        val GEOMETRIES = listOf(8 to 8, 12 to 12, 20 to 20, 13 to 17)

        /** The far corner of a 5x5, as a playable index. */
        const val SEALED_CORNER = 24

        val EMPTY_BOARD_OPENINGS = mapOf(
            "8x8 x1" to listOf(0),
            "8x8 x2" to listOf(0, 63),
            "8x8 x3" to listOf(0, 63, 7),
            "8x8 x4" to listOf(0, 63, 7, 56),
            "12x12 x1" to listOf(0),
            "12x12 x2" to listOf(0, 143),
            "12x12 x3" to listOf(0, 143, 11),
            "12x12 x4" to listOf(0, 143, 11, 132),
            "20x20 x1" to listOf(0),
            "20x20 x2" to listOf(0, 399),
            "20x20 x3" to listOf(0, 399, 19),
            "20x20 x4" to listOf(0, 399, 19, 380),
            "13x17 x1" to listOf(0),
            "13x17 x2" to listOf(0, 220),
            "13x17 x3" to listOf(0, 220, 16),
            "13x17 x4" to listOf(0, 220, 16, 204),
        )

        /** A pillar on every odd row and column, which leaves the open squares one connected lattice. */
        fun pillarLattice(grid: Grid): IntArray {
            val walls = ArrayList<Int>()
            for (row in 1 until grid.rows step 2) {
                for (col in 1 until grid.cols step 2) {
                    walls.add(row * grid.cols + col)
                }
            }
            return walls.toIntArray()
        }
    }
}
