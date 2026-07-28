package ao.snakewarz.lab.arena

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.Tournament
import ao.snakewarz.match.tournament.TournamentConfig
import ao.snakewarz.match.tournament.TournamentFormat
import ao.snakewarz.match.tournament.TournamentTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArenaTest {
    @Test
    fun `a batch played in parallel is the batch the driver plays`() {
        // The whole justification for not using `Tournament` to play: this has to be the same
        // measurement, taken faster. Under a fixed opening the two are the same schedule, so any
        // disagreement is the runner and nothing else.
        val config = configOf(listOf("space", "wallhug", "pressure"), rounds = 6)

        val driven = Tournament(config, ShippedBots).runToCompletion()
        val arena = Arena(config, ShippedBots, Openings.FIXED, threads = 4).run()

        assertSameTable(driven, arena.table)
    }

    @Test
    fun `the same batch on one thread and on many is the same batch`() {
        val config = configOf(listOf("space", "wallhug", "pressure", "chase"), rounds = 4)

        val alone = Arena(config, ShippedBots, Openings.MIRRORED, threads = 1).run()
        val crowded = Arena(config, ShippedBots, Openings.MIRRORED, threads = 8).run()

        assertSameTable(alone.table, crowded.table)
        assertEquals(
            alone.reports.map { it.moveStreamHash },
            crowded.reports.map { it.moveStreamHash },
            "results are collected by schedule index, never by whichever worker finished first",
        )
    }

    @Test
    fun `a mirrored opening buys the sample size a fixed one does not`() {
        // The measurement this whole package exists for. `wallhug` draws no randomness at all, so
        // with the snakes always in the same corners its pairing is a handful of games however many
        // rounds are asked for -- and a rating over that would be confident and meaningless.
        val config = configOf(listOf("wallhug", "burninhell"), rounds = 20)

        val fixed = Arena(config, ShippedBots, Openings.FIXED, threads = 2).run()
        val mirrored = Arena(config, ShippedBots, Openings.MIRRORED, threads = 2).run()

        assertTrue(fixed.leastDiverse!!.distinct <= 4, "fixed openings played ${fixed.leastDiverse}")
        assertTrue(
            mirrored.leastDiverse!!.distinct > 3 * fixed.leastDiverse!!.distinct,
            "mirrored openings played only ${mirrored.leastDiverse}",
        )
    }

    @Test
    fun `every match of a pair shares a key, and the batch reports what it cost`() {
        val batch = Arena(configOf(listOf("space", "wallhug"), rounds = 4), ShippedBots, threads = 2).run()

        assertEquals(4, batch.reports.size)
        assertEquals(batch.reports[0].pairKey, batch.reports[1].pairKey)
        assertEquals(batch.reports[2].pairKey, batch.reports[3].pairKey)
        assertTrue(batch.reports[0].pairKey != batch.reports[2].pairKey)

        assertEquals(0, batch.forfeits, "a forfeit is a bot that threw, and none of these do")
        assertTrue(batch.turnsPlayed > 0)
        assertEquals(batch.reports.indices.toList(), batch.reports.map { it.index })
    }

    @Test
    fun `a free-for-all is scored the same way here as it is by the driver`() {
        val config = configOf(listOf("space", "wallhug", "pressure"), rounds = 6, TournamentFormat.FREE_FOR_ALL)

        val driven = Tournament(config, ShippedBots).runToCompletion()
        val arena = Arena(config, ShippedBots, Openings.FIXED, threads = 3).run()

        assertSameTable(driven, arena.table)
    }

    private fun assertSameTable(expected: TournamentTable, actual: TournamentTable) {
        assertEquals(expected.size, actual.size)
        for (one in 0 until expected.size) {
            for (other in 0 until expected.size) {
                assertEquals(expected.wins(one, other), actual.wins(one, other), "wins($one, $other)")
                assertEquals(expected.draws(one, other), actual.draws(one, other), "draws($one, $other)")
            }
        }
    }

    private fun configOf(
        slugs: List<String>,
        rounds: Int,
        format: TournamentFormat = TournamentFormat.HEAD_TO_HEAD,
    ): TournamentConfig = TournamentConfig(
        contestants = slugs.map { Contestant(BotId(it)) },
        rows = 9,
        cols = 9,
        rounds = rounds,
        format = format,
        budgetPerTurn = 0,
    )
}
