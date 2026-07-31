package ao.snakewarz.match.map

import ao.snakewarz.match.openRegionFrom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every shipped map, at every size the game offers, is symmetric, connected and worth playing on.
 *
 * The sweep is the point: a map is not reviewed by looking at it, because a sealed pocket or an
 * asymmetry that only appears at 40x40 is exactly what an eye misses. The half turn is the fairness
 * claim — [ao.snakewarz.match.mostDistantSpawns] seats slot 0 at the lowest open square and slot 1 at
 * the highest, and on a ρ-invariant map those two are exact images — so it is asserted here as well
 * as inside [generateMap], where a shape that broke it could not reach a match at all.
 */
class GenerateMapTest {
    @Test
    fun `every shape at every size is symmetric, one region, and mostly open`() {
        forEachShapeAndSize { shape, rows, cols ->
            val map = generateMap(rows, cols, shape, seed = SEED)
            val where = "${shape.slug} at ${rows}x$cols"

            val playableCount = rows * cols
            for (wall in map.walls()) {
                assertTrue(
                    map.isWall((playableCount - 1 - wall) / cols, (playableCount - 1 - wall) % cols),
                    "$where: $wall is a wall and its half turn is not",
                )
            }

            val openCount = playableCount - map.wallCount
            val reached = openRegionFrom(rows, cols, map.walls(), lowestOpen(map))
            assertEquals(openCount, reached.count { it }, "$where: the open squares are not one region")

            assertTrue(
                openCount * 100 >= playableCount * MINIMUM_OPEN_PERCENT,
                "$where: only $openCount of $playableCount squares are open",
            )
        }
    }

    @Test
    fun `the lowest and the highest open square are a half turn apart on every map`() {
        forEachShapeAndSize { shape, rows, cols ->
            val map = generateMap(rows, cols, shape, seed = SEED)
            assertEquals(
                rows * cols - 1 - lowestOpen(map),
                highestOpen(map),
                "${shape.slug} at ${rows}x$cols: the two seats would not be equivalent",
            )
        }
    }

    @Test
    fun `a shape too big for the board fails by name`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            generateMap(8, 8, MapShape.DOUBLE_SPIRAL)
        }
        assertTrue(MapShape.DOUBLE_SPIRAL.slug in failure.message.orEmpty(), failure.message)

        for (shape in MapShape.entries) {
            if (shape.minimumSide <= 1) continue
            assertFailsWith<IllegalArgumentException>(shape.slug) {
                generateMap(shape.minimumSide - 1, shape.minimumSide, shape)
            }
            assertFailsWith<IllegalArgumentException>(shape.slug) {
                generateMap(shape.minimumSide, shape.minimumSide - 1, shape)
            }
        }
    }

    @Test
    fun `a shape at its minimum side is drawn rather than refused`() {
        for (shape in MapShape.entries) {
            val side = shape.minimumSide
            val map = generateMap(side, side, shape, seed = SEED)
            assertEquals(side, map.rows, shape.slug)
            if (shape != MapShape.EMPTY) {
                assertTrue(map.wallCount > 0, "${shape.slug} draws nothing at its own minimum size")
            }
        }
    }

    @Test
    fun `the same shape, size and seed is the same map twice`() {
        forEachShapeAndSize { shape, rows, cols ->
            assertContentEquals(
                generateMap(rows, cols, shape, seed = SEED).walls(),
                generateMap(rows, cols, shape, seed = SEED).walls(),
                "${shape.slug} at ${rows}x$cols",
            )
        }
    }

    @Test
    fun `only the scattered shape reads the seed, and it reads it`() {
        for (shape in MapShape.entries) {
            val one = generateMap(20, 20, shape, seed = 1).walls()
            val other = generateMap(20, 20, shape, seed = 2).walls()
            if (shape == MapShape.SCATTER) {
                assertTrue(!one.contentEquals(other), "${shape.slug} ignores its seed")
            } else {
                assertContentEquals(one, other, "${shape.slug} is not a function of its geometry alone")
            }
        }
    }

    @Test
    fun `a density the scatter rule cannot reach fails loudly, saying what it managed`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            generateMap(12, 12, MapShape.SCATTER, density = UNREACHABLE_DENSITY, seed = SEED)
        }
        assertTrue("density of $UNREACHABLE_DENSITY" in failure.message.orEmpty(), failure.message)
        assertTrue("takes" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a density asked for is a density delivered`() {
        val map = generateMap(20, 20, MapShape.SCATTER, density = 0.1, seed = SEED)
        assertTrue(map.wallCount >= 40, "asked for a tenth of 400 squares, got ${map.wallCount}")
    }

    @Test
    fun `a slug is unique, url-safe and not the enum name`() {
        val slugs = MapShape.entries.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size, "two shapes share a slug")
        for (shape in MapShape.entries) {
            assertTrue(shape.slug.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }, shape.slug)
            assertEquals(shape, MapShape.ofSlug(shape.slug))
        }
        assertEquals("double-spiral", MapShape.DOUBLE_SPIRAL.slug)
        assertNull(MapShape.ofSlug("DOUBLE_SPIRAL"))
        assertNull(MapShape.ofSlug("labyrinth"))
    }

    @Test
    fun `the empty shape is the incumbent, at any size the game allows`() {
        for (side in intArrayOf(1, 8, 40)) {
            assertEquals(0, generateMap(side, side, MapShape.EMPTY).wallCount)
        }
    }

    private fun forEachShapeAndSize(check: (MapShape, Int, Int) -> Unit) {
        for (shape in MapShape.entries) {
            for (size in SIZES) {
                val rows = size.first
                val cols = size.second
                if (rows >= shape.minimumSide && cols >= shape.minimumSide) {
                    check(shape, rows, cols)
                }
            }
        }
    }

    private fun lowestOpen(map: BoardMap): Int {
        for (index in 0 until map.rows * map.cols) {
            if (!map.isWall(index / map.cols, index % map.cols)) return index
        }
        throw AssertionError("$map is entirely wall")
    }

    private fun highestOpen(map: BoardMap): Int {
        for (index in map.rows * map.cols - 1 downTo 0) {
            if (!map.isWall(index / map.cols, index % map.cols)) return index
        }
        throw AssertionError("$map is entirely wall")
    }

    private companion object {
        /**
         * Every square board the picker offers, plus two non-square ones of each parity.
         *
         * All seven, because the picker gates a shape on [MapShape.minimumSide] alone — so any size
         * it offers and this list misses is a Start match that could throw at a player.
         */
        val SIZES = listOf(8 to 8, 10 to 10, 12 to 12, 16 to 16, 20 to 20, 28 to 28, 40 to 40, 14 to 22, 9 to 15)

        const val SEED: Long = 20260730

        /**
         * Under 40% wall on every shipped map.
         *
         * The densest is the double spiral, which is one square of wall per two of corridor by
         * construction. A shape past this is a shape that has stopped being a map and started being a
         * maze, and this is where that gets noticed.
         */
        const val MINIMUM_OPEN_PERCENT: Int = 60

        /**
         * Past what isolated squares can pack into.
         *
         * The rule tops out at a quarter of the board even placed perfectly, and well under that from
         * a shuffled order.
         */
        const val UNREACHABLE_DENSITY: Double = 0.4
    }
}
