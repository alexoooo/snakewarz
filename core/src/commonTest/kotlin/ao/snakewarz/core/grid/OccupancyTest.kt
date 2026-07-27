package ao.snakewarz.core.grid

import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OccupancyTest {
    @Test
    fun `a new board is empty inside and walled all the way round`() {
        val grid = Grid(rows = 4, cols = 6)
        val occupancy = Occupancy(grid)

        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                assertTrue(occupancy.isFree(grid.cellAt(row, col)), "($row, $col) should start free")
            }
        }

        val walls = (0 until grid.cellCount).count { occupancy.isWall(Cell(it)) }
        assertEquals(grid.cellCount - grid.playableCount, walls, "the whole padded ring is wall")
        assertEquals(0L, occupancy.hash, "walls are constant and deliberately outside the hash")
    }

    @Test
    fun `occupy and vacate round trip, hash included`() {
        val grid = Grid(rows = 5, cols = 5)
        val occupancy = Occupancy(grid)
        val cell = grid.cellAt(2, 3)

        occupancy.occupy(cell, SnakeId(1))
        assertFalse(occupancy.isFree(cell))
        assertEquals(SnakeId(1), occupancy.ownerOf(cell))
        assertNotEquals(0L, occupancy.hash)

        occupancy.vacate(cell)
        assertTrue(occupancy.isFree(cell))
        assertEquals(SnakeId.NONE, occupancy.ownerOf(cell))
        assertEquals(0L, occupancy.hash, "the hash must come back to where it started")
    }

    @Test
    fun `the same squares held by different snakes hash differently`() {
        val grid = Grid(rows = 5, cols = 5)
        val cell = grid.cellAt(1, 1)

        val first = Occupancy(grid).also { it.occupy(cell, SnakeId(0)) }
        val second = Occupancy(grid).also { it.occupy(cell, SnakeId(1)) }

        assertNotEquals(first.hash, second.hash, "owner identity is part of the position")
    }

    @Test
    fun `the hash does not depend on the order the squares were taken`() {
        // Zobrist hashing is only useful to a search if two routes to the same position agree, and
        // xor gives that for free — but only if every key really is per (square, owner).
        val grid = Grid(rows = 6, cols = 6)
        val cells = listOf(0 to 0, 2 to 3, 4 to 1, 5 to 5, 1 to 4).map { grid.cellAt(it.first, it.second) }

        val forwards = Occupancy(grid)
        cells.forEach { forwards.occupy(it, SnakeId(0)) }

        val backwards = Occupancy(grid)
        cells.reversed().forEach { backwards.occupy(it, SnakeId(0)) }

        assertEquals(forwards.hash, backwards.hash)
    }

    @Test
    fun `an incrementally updated hash equals a freshly built one`() {
        // The guard on the whole optimization: nothing is ever rebuilt at runtime, so the
        // incremental path had better agree with the from-scratch one.
        val grid = Grid(rows = 8, cols = 8)
        val rng = SplitMix64(99L)
        val incremental = Occupancy(grid)
        val owners = IntArray(grid.cellCount) { -1 }

        repeat(4_000) {
            val cell = grid.cellAt(rng.nextInt(grid.rows), rng.nextInt(grid.cols))
            if (owners[cell.index] >= 0) {
                incremental.vacate(cell)
                owners[cell.index] = -1
            } else {
                val slot = rng.nextInt(3)
                incremental.occupy(cell, SnakeId(slot))
                owners[cell.index] = slot
            }
        }

        val rebuilt = Occupancy(grid)
        for (index in owners.indices) {
            if (owners[index] >= 0) {
                rebuilt.occupy(Cell(index), SnakeId(owners[index]))
            }
        }

        assertEquals(rebuilt.hash, incremental.hash, "hashes must agree")
        for (index in 0 until grid.cellCount) {
            assertEquals(rebuilt.ownerOf(Cell(index)), incremental.ownerOf(Cell(index)), "owner of cell $index")
        }
    }

    @Test
    fun `free neighbours see walls and bodies as the same thing`() {
        val grid = Grid(rows = 3, cols = 3)
        val occupancy = Occupancy(grid)

        assertEquals(DirectionSet.ALL, occupancy.freeNeighbors(grid.cellAt(1, 1)))
        assertEquals(
            DirectionSet.of(Direction.SOUTH, Direction.EAST),
            occupancy.freeNeighbors(grid.cellAt(0, 0)),
            "a corner has two walls",
        )

        occupancy.occupy(grid.cellAt(0, 1), SnakeId(0))
        assertEquals(
            DirectionSet.of(Direction.SOUTH),
            occupancy.freeNeighbors(grid.cellAt(0, 0)),
            "a body blocks exactly like a wall",
        )
    }

    @Test
    fun `copyFrom reproduces contents and hash, and clear leaves the walls alone`() {
        val grid = Grid(rows = 4, cols = 4)
        val source = Occupancy(grid)
        source.occupy(grid.cellAt(0, 0), SnakeId(0))
        source.occupy(grid.cellAt(3, 3), SnakeId(1))

        val copy = Occupancy(grid)
        copy.occupy(grid.cellAt(2, 2), SnakeId(2))
        copy.copyFrom(source)

        assertEquals(source.hash, copy.hash)
        assertEquals(SnakeId(0), copy.ownerOf(grid.cellAt(0, 0)))
        assertEquals(SnakeId(1), copy.ownerOf(grid.cellAt(3, 3)))
        assertTrue(copy.isFree(grid.cellAt(2, 2)), "the copy must not keep its own old contents")

        copy.clear()
        assertEquals(0L, copy.hash)
        assertTrue(copy.isFree(grid.cellAt(0, 0)))
        assertTrue(copy.isWall(Cell(0)), "clearing must not open a hole in the wall ring")
        assertTrue(copy.isWall(Cell(grid.cellCount - 1)))
    }
}
