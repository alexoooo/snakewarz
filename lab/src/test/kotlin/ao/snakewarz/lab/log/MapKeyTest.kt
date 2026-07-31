package ao.snakewarz.lab.log

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The line that decides whether a rating is about one game or two.
 *
 * A batch on a walled board and a batch on a bare one differ in nothing else a run header records —
 * same geometry, same rules, same allowance — so without the map in the key they pool, and every
 * figure fitted over the pair describes neither.
 */
class MapKeyTest {
    @Test
    fun `a board with no walls is named rather than fingerprinted`() {
        assertEquals(EMPTY_MAP, mapKey(IntArray(0)))
    }

    @Test
    fun `every shape of the catalogue gets a key of its own`() {
        val keys = MapShape.entries
            .filter { SIDE >= it.minimumSide }
            .associateWith { mapKey(generateMap(SIDE, SIDE, it).walls()) }

        assertEquals(keys.size, keys.values.toSet().size, keys.toString())
        for ((shape, key) in keys) {
            if (shape == MapShape.EMPTY) {
                continue
            }
            assertTrue(key.startsWith("${generateMap(SIDE, SIDE, shape).wallCount}w"), "${shape.slug} -> $key")
        }
    }

    @Test
    fun `a key is a function of the squares and not of how many there are`() {
        // Two maps of the same size are the case a wall *count* would miss, and it is not exotic:
        // two scatterings at one density are exactly that.
        val one = mapKey(intArrayOf(5, 9))
        val other = mapKey(intArrayOf(6, 10))

        assertNotEquals(one, other)
        assertEquals(one, mapKey(intArrayOf(5, 9)), "the same walls fingerprint the same way")
    }

    @Test
    fun `two maps of one size are two measurements, and the header says so`() {
        val crossWalls = generateMap(SIDE, SIDE, MapShape.CROSS).walls()
        val cross = headerOf(crossWalls)
        val rooms = headerOf(generateMap(SIDE, SIDE, MapShape.ROOMS).walls())
        val bare = headerOf(IntArray(0))

        assertEquals(EMPTY_MAP, bare.map)
        assertNotEquals(cross.comparabilityKey, rooms.comparabilityKey)
        assertNotEquals(cross.comparabilityKey, bare.comparabilityKey)
        assertEquals(cross.comparabilityKey, headerOf(crossWalls).comparabilityKey)
    }

    private fun headerOf(walls: IntArray): RunHeader = RunHeader.of(
        config = TournamentConfig(
            contestants = listOf(Contestant(BotId("uct")), Contestant(BotId("space"))),
            rows = SIDE,
            cols = SIDE,
            budgetPerTurn = 0,
            walls = walls,
        ),
        registry = ShippedBots,
        openings = "MIRRORED",
        threads = 1,
    )

    private companion object {
        /** Large enough for every shape in the catalogue to draw itself. */
        const val SIDE = 13
    }
}
