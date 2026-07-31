package ao.snakewarz.match.map

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wall set as a type: what it promises, and the two things it deliberately does not.
 *
 * [BoardMap] guarantees a canonical wall array and nothing else — symmetry and connectivity belong to
 * [generateMap]. A hand-drawn fixture is legitimately neither, and so is a map decoded out of a
 * stranger's replay, so a type that demanded them could not hold either.
 */
class BoardMapTest {
    @Test
    fun `a picture round-trips against isWall`() {
        val picture = listOf(
            "..#..",
            ".###.",
            "..#..",
            ".....",
            "#...#",
        )
        val map = BoardMap.of(picture)

        assertEquals(5, map.rows)
        assertEquals(5, map.cols)
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                assertEquals(picture[row][col] == '#', map.isWall(row, col), "($row, $col)")
            }
        }
        assertEquals(picture.sumOf { line -> line.count { it == '#' } }, map.wallCount)
    }

    @Test
    fun `a picture is playable indices, row by row`() {
        val map = BoardMap.of(listOf("#..", "...", "..#"))
        assertContentEquals(intArrayOf(0, 8), map.walls())
    }

    @Test
    fun `an empty map has no walls anywhere`() {
        val map = BoardMap.empty(4, 6)
        assertEquals(0, map.wallCount)
        assertContentEquals(IntArray(0), map.walls())
        assertFalse(map.isWall(3, 5))
    }

    @Test
    fun `walls are handed out as a copy`() {
        val map = BoardMap.of(listOf("#.", ".."))
        val walls = map.walls()
        walls[0] = 3
        assertTrue(map.isWall(0, 0))
        assertFalse(map.isWall(1, 1))
    }

    @Test
    fun `an unsorted or repeated wall is refused`() {
        assertFailsWith<IllegalArgumentException> { BoardMap(3, 3, intArrayOf(4, 1)) }
        assertFailsWith<IllegalArgumentException> { BoardMap(3, 3, intArrayOf(1, 1)) }
    }

    @Test
    fun `a wall off the board is refused`() {
        assertFailsWith<IllegalArgumentException> { BoardMap(3, 3, intArrayOf(9)) }
        assertFailsWith<IllegalArgumentException> { BoardMap(3, 3, intArrayOf(-1)) }
    }

    @Test
    fun `a picture that is not squares of the two characters is refused`() {
        assertFailsWith<IllegalArgumentException> { BoardMap.of(listOf("##", "#")) }
        assertFailsWith<IllegalArgumentException> { BoardMap.of(listOf("#x")) }
        assertFailsWith<IllegalArgumentException> { BoardMap.of(emptyList()) }
    }

    @Test
    fun `a map says its size and its weight, which is what a failure message carries`() {
        assertEquals("BoardMap(12x12, 2 walls)", BoardMap(12, 12, intArrayOf(0, 143)).toString())
    }
}
