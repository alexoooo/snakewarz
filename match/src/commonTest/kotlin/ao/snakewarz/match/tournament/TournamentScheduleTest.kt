package ao.snakewarz.match.tournament

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TournamentScheduleTest {
    @Test
    fun `a schedule needs no registry to say what is coming`() {
        val schedule = scheduleOf(listOf("cycle", "last", "north"), rounds = 4)

        assertEquals(12, schedule.matchCount)
        assertEquals(listOf(0, 1), schedule.seatingFor(0).toList())
        assertEquals(listOf(1, 0), schedule.seatingFor(1).toList(), "the pair swaps seats")
        assertEquals(listOf(0, 2), schedule.seatingFor(4).toList(), "and the next pairing follows")
    }

    @Test
    fun `a pair of matches shares a seed and a pair key`() {
        val schedule = scheduleOf(listOf("cycle", "last"), rounds = 4)

        assertEquals(schedule.seedFor(0), schedule.seedFor(1))
        assertEquals(schedule.pairKeyFor(0), schedule.pairKeyFor(1))

        assertEquals(schedule.seedFor(0) + 1, schedule.seedFor(2), "the next pair moves on a seed")
        assertTrue(schedule.pairKeyFor(2) != schedule.pairKeyFor(0), "and is a different pair")
    }

    @Test
    fun `a pair key is unique across pairings, not only within one`() {
        // Two pairings drawing from the same seeds is the whole point of the schedule, so a key that
        // named only the seed would merge two different comparisons into one measurement.
        val schedule = scheduleOf(listOf("cycle", "last", "north"), rounds = 4)
        val keys = (0 until schedule.matchCount).map { schedule.pairKeyFor(it) }

        assertEquals(schedule.matchCount / 2, keys.toSet().size, "two matches to a key and no more")
        for (index in 0 until schedule.matchCount step 2) {
            assertEquals(keys[index], keys[index + 1])
        }
    }

    @Test
    fun `a free-for-all groups a seed by the size of the field`() {
        val schedule = scheduleOf(listOf("cycle", "last", "north"), rounds = 4, format = TournamentFormat.FREE_FOR_ALL)

        assertEquals(4, schedule.matchCount)
        assertEquals(schedule.pairKeyFor(0), schedule.pairKeyFor(1))
        assertEquals(schedule.pairKeyFor(0), schedule.pairKeyFor(2), "three seats, three matches to a seed")

        // Four rounds do not divide by three, so the last group is one match, cut short.
        assertTrue(schedule.pairKeyFor(3) != schedule.pairKeyFor(0))
        assertEquals(schedule.seedFor(0) + 1, schedule.seedFor(3))
    }

    @Test
    fun `a tournament plays the schedule it hands out`() {
        val config = configOf(listOf("cycle", "last", "north"), rounds = 4)
        val tournament = Tournament(config, ao.snakewarz.match.TestRegistry.ALL)

        for (index in 0 until tournament.matchCount) {
            assertEquals(tournament.schedule.setupFor(index), tournament.setupFor(index))
        }
    }

    @Test
    fun `the map reaches every seating of every pairing`() {
        // The field a rebuild forgets. `setupFor` names the config's fields one at a time, so a map
        // left off is a whole batch played on a bare rectangle while the log says otherwise -- and
        // nothing else in the setup would look wrong.
        val walls = generateMap(rows = 9, cols = 9, shape = MapShape.CROSS).walls()
        val schedule = TournamentSchedule(configOf(listOf("cycle", "last", "north"), rounds = 4, walls = walls))

        for (index in 0 until schedule.matchCount) {
            val setup = schedule.setupFor(index)
            assertEquals(walls.toList(), setup.walls().toList(), "match $index")
            for (spawn in setup.spawns()) {
                assertTrue(spawn !in walls, "match $index seats a snake at $spawn, which is a wall")
            }
        }
    }

    @Test
    fun `a config refuses a map it could never seat a match on`() {
        assertFailsWith<IllegalArgumentException> {
            configOf(listOf("cycle", "last"), rounds = 2, walls = intArrayOf(9 * 9))
        }
        assertFailsWith<IllegalArgumentException> {
            configOf(listOf("cycle", "last"), rounds = 2, walls = intArrayOf(4, 4))
        }
    }

    @Test
    fun `a schedule refuses a match it does not contain`() {
        val schedule = scheduleOf(listOf("cycle", "last"), rounds = 2)

        assertFailsWith<IllegalArgumentException> { schedule.setupFor(schedule.matchCount) }
        assertFailsWith<IllegalArgumentException> { schedule.seedFor(-1) }
        assertFailsWith<IllegalArgumentException> { schedule.pairKeyFor(schedule.matchCount) }
    }

    private fun scheduleOf(
        contestants: List<String>,
        rounds: Int,
        format: TournamentFormat = TournamentFormat.HEAD_TO_HEAD,
    ): TournamentSchedule = TournamentSchedule(configOf(contestants, rounds, format))

    private fun configOf(
        contestants: List<String>,
        rounds: Int,
        format: TournamentFormat = TournamentFormat.HEAD_TO_HEAD,
        walls: IntArray = IntArray(0),
    ): TournamentConfig = TournamentConfig(
        contestants = contestants.map { Contestant(BotId(it)) },
        rows = 9,
        cols = 9,
        rounds = rounds,
        budgetPerTurn = 0,
        format = format,
        walls = walls,
    )
}
