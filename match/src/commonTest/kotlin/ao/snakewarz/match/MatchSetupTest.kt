package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MatchSetupTest {
    @Test
    fun `two snakes start in opposite corners, as they always have`() {
        // The legacy special case, and the reason the old README said "you always start in the
        // bottom right".
        val spawns = mostDistantSpawns(Grid(20, 20), 2)

        assertEquals(listOf(0, 399), spawns.toList())
    }

    @Test
    fun `further snakes are placed away from everyone already down`() {
        val grid = Grid(9, 9)
        val spawns = mostDistantSpawns(grid, 4)

        assertEquals(spawns.size, spawns.toSet().size, "no two snakes share a square")
        for (spawn in spawns) {
            assertTrue(spawn in 0 until grid.playableCount)
        }

        // Penalising by the nearest occupant rather than the average is what keeps these apart; a
        // centroid-based score would pile the third and fourth snakes into the middle.
        val third = spawns[2] / 9 to spawns[2] % 9
        assertTrue(third.first > 2 || third.second > 2, "third spawn $third crowded the first")
    }

    @Test
    fun `placement is deterministic, and stated to be`() {
        assertEquals(
            mostDistantSpawns(Grid(13, 17), 4).toList(),
            mostDistantSpawns(Grid(13, 17), 4).toList(),
        )
    }

    @Test
    fun `a board too small for the field is refused`() {
        assertFailsWith<IllegalArgumentException> { mostDistantSpawns(Grid(1, 2), 3) }
    }

    @Test
    fun `turn order is shuffled from the seed, so acting first is not always slot zero`() {
        val slots = List(4) { BotId("bot$it") }

        val orders = (1L..40L).map { MatchSetup.create(10, 10, slots, seed = it).turnOrder().toList() }

        assertTrue(orders.toSet().size > 1, "every seed produced the same turn order")
        for (order in orders) {
            assertEquals(setOf(0, 1, 2, 3), order.toSet(), "$order is not a permutation")
        }
    }

    @Test
    fun `the same seed sets up the same match`() {
        val slots = List(3) { BotId("bot$it") }

        val first = MatchSetup.create(11, 13, slots, seed = 5150)
        val second = MatchSetup.create(11, 13, slots, seed = 5150)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `shuffling the turn order does not disturb the per-slot streams`() {
        // Setup draws from its own stream precisely so that adding a slot, or reordering who moves
        // first, cannot shift the randomness a bot already recorded a match with.
        val two = MatchSetup.create(10, 10, List(2) { BotId("bot$it") }, seed = 42)
        val three = MatchSetup.create(10, 10, List(3) { BotId("bot$it") }, seed = 42)

        assertEquals(two.seed, three.seed)
        assertEquals(listOf(0, two.rows * two.cols - 1), two.spawns().toList())
        assertEquals(listOf(0, three.rows * three.cols - 1), three.spawns().take(2))
    }

    @Test
    fun `an impossible setup is refused at construction, not at the first move`() {
        val slots = listOf(BotId("a"), BotId("b"))

        assertFailsWith<IllegalArgumentException>("duplicate spawn") {
            MatchSetup(1, 5, 5, ao.snakewarz.core.RulesConfig(), 0, slots, intArrayOf(0, 1), intArrayOf(3, 3))
        }
        assertFailsWith<IllegalArgumentException>("turn order names a slot twice") {
            MatchSetup(1, 5, 5, ao.snakewarz.core.RulesConfig(), 0, slots, intArrayOf(1, 1), intArrayOf(0, 3))
        }
        assertFailsWith<IllegalArgumentException>("spawn off the board") {
            MatchSetup(1, 5, 5, ao.snakewarz.core.RulesConfig(), 0, slots, intArrayOf(0, 1), intArrayOf(0, 999))
        }
        assertFailsWith<IllegalArgumentException>("negative budget") {
            MatchSetup(1, 5, 5, ao.snakewarz.core.RulesConfig(), -1, slots, intArrayOf(0, 1), intArrayOf(0, 3))
        }
    }

    @Test
    fun `spawns are playable indices, not the engine's padded ones`() {
        // The replay format must not encode the padded-grid layout, or changing the padding would
        // invalidate every recorded match.
        val setup = MatchSetup.create(4, 4, List(2) { BotId("bot$it") }, seed = 1)

        assertEquals(listOf(0, 15), setup.spawns().toList())
        assertEquals(
            listOf(setup.grid().cellAt(0, 0).index, setup.grid().cellAt(3, 3).index),
            setup.spawnCells(setup.grid()).toList(),
        )
    }
}
