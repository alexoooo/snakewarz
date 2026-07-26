package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.RulesConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TournamentTest {
    @Test
    fun `the schedule is every pair over every round`() {
        val tournament = tournamentOf(listOf("cycle", "last", "north"), rounds = 4)

        assertEquals(3, tournament.config.pairingCount)
        assertEquals(12, tournament.matchCount)
        assertEquals(0, tournament.matchesPlayed)
        assertNull(tournament.current, "nothing is seated until the first step")

        tournament.runToCompletion()

        assertEquals(12, tournament.matchesPlayed)
        assertTrue(tournament.finished)
        assertEquals(1.0, tournament.progress)

        val last = tournament.current
        assertNotNull(last, "the last match stays available once the batch is over")
        assertEquals(tournament.setupFor(11), last.setup)
        assertNotNull(last.outcome, "and it is a finished one")
    }

    @Test
    fun `a step plays exactly one turn`() {
        val tournament = tournamentOf(listOf("cycle", "last"), rounds = 2)

        repeat(5) { turns ->
            assertEquals(turns.toLong(), tournament.turnsPlayed)
            tournament.step()
        }
        assertEquals(5L, tournament.turnsPlayed)
        assertNotNull(tournament.current, "a match is in progress between steps")
    }

    @Test
    fun `results follow the contestant, not the seat`() {
        // "north" walks into the wall on its first move from either corner, so "cycle" wins every
        // match of the pairing -- which is only true of the *contestant* if both seatings are played
        // and both are attributed correctly.
        val table = tournamentOf(listOf("cycle", "north"), rounds = 10).runToCompletion()

        assertEquals(10, table.wins(0, 1))
        assertEquals(0, table.wins(1, 0))
        assertEquals(0, table.draws(0, 1))
        assertEquals(10, table.played(0, 1))

        assertEquals(1.0, table.scoreRate(0))
        assertEquals(0.0, table.scoreRate(1))
        assertEquals(listOf(0, 1), table.ranking())
    }

    @Test
    fun `a turn-limit draw is a draw for both, not a loss for one`() {
        // Four turns is two moves each, which neither of these two can die in.
        val table = tournamentOf(
            listOf("cycle", "last"),
            rounds = 6,
            rules = RulesConfig(maxTurns = 4),
        ).runToCompletion()

        assertEquals(6, table.draws(0, 1))
        assertEquals(6, table.draws(1, 0), "a draw is symmetric")
        assertEquals(0, table.wins(0, 1))
        assertEquals(0, table.wins(1, 0))

        assertEquals(0.5, table.scoreRate(0))
        assertEquals(0.5, table.scoreRate(1))
    }

    @Test
    fun `totals add up across every pairing`() {
        val table = tournamentOf(listOf("cycle", "last", "north"), rounds = 4).runToCompletion()

        for (contestant in 0 until table.size) {
            assertEquals(8, table.played(contestant), "each contestant meets the other two")
            assertEquals(
                table.played(contestant),
                table.wins(contestant) + table.losses(contestant) + table.draws(contestant),
            )
        }
    }

    @Test
    fun `the same config plays the same tournament twice`() {
        val contestants = listOf("cycle", "last", "north")
        val first = tournamentOf(contestants, rounds = 4).runToCompletion()
        val second = tournamentOf(contestants, rounds = 4).runToCompletion()

        assertEquals(first.toString(), second.toString())
        for (one in 0 until first.size) {
            for (other in 0 until first.size) {
                assertEquals(first.wins(one, other), second.wins(one, other))
                assertEquals(first.draws(one, other), second.draws(one, other))
            }
        }
    }

    @Test
    fun `a seed is played from both seats`() {
        // Two seeds, four matches. The seat-swapped pair share a seed, so the two matches of a pair
        // are played on the same board with the sides exchanged.
        val tournament = tournamentOf(listOf("cycle", "last"), rounds = 4)
        val schedule = List(tournament.matchCount) { tournament.setupFor(it) }

        assertEquals(schedule[0].seed, schedule[1].seed, "the first two matches share a seed")
        assertEquals(schedule[0].slots, schedule[1].slots.reversed(), "and swap seats")
        assertEquals(schedule[0].seed + 1, schedule[2].seed, "the next pair moves on a seed")
        assertEquals(schedule[0].slots, schedule[2].slots, "and starts from the same seating again")

        assertFailsWith<IllegalArgumentException> { tournament.setupFor(tournament.matchCount) }
    }

    @Test
    fun `the match being played is the one the schedule says`() {
        val tournament = tournamentOf(listOf("cycle", "last", "north"), rounds = 2)

        while (!tournament.finished) {
            // Read before the step, because a step that ends a match moves the counter on while
            // `current` quite deliberately stays on the match that just ended.
            val index = tournament.matchesPlayed
            tournament.step()

            assertEquals(tournament.setupFor(index), tournament.current?.setup)
        }
    }

    @Test
    fun `a copied table stops tracking the tournament that made it`() {
        val tournament = tournamentOf(listOf("cycle", "north"), rounds = 4)
        while (tournament.matchesPlayed < 2) {
            tournament.step()
        }

        val kept = tournament.table.copy()
        val soFar = kept.wins(0, 1)
        tournament.runToCompletion()

        assertEquals(soFar, kept.wins(0, 1), "the copy is frozen")
        assertEquals(4, tournament.table.wins(0, 1), "and the live one carried on")
    }

    @Test
    fun `a tournament refuses a seat somebody has to play by hand`() {
        val tournament = tournamentOf(listOf("cycle", "human"), rounds = 2)
        assertFailsWith<IllegalStateException> { tournament.step() }
    }

    @Test
    fun `a bot that throws loses rather than taking the batch down`() {
        val table = tournamentOf(listOf("cycle", "thrower"), rounds = 4).runToCompletion()

        assertEquals(4, table.wins(0, 1))
        assertEquals(0, table.wins(1, 0))
    }

    @Test
    fun `a config has to describe a measurable tournament`() {
        assertFailsWith<IllegalArgumentException> { TournamentConfig(listOf(BotId("cycle")), 9, 9) }
        assertFailsWith<IllegalArgumentException> {
            TournamentConfig(listOf(BotId("cycle"), BotId("cycle")), 9, 9)
        }
        assertFailsWith<IllegalArgumentException> {
            TournamentConfig(listOf(BotId("cycle"), BotId("last")), 9, 9, rounds = 3)
        }
    }

    @Test
    fun `the table renders as the matrix the docs quote`() {
        val table = tournamentOf(listOf("cycle", "north"), rounds = 10).runToCompletion()
        val rendered = table.toString().trim().lines()

        assertEquals(3, rendered.size, "a header and a row each")
        assertTrue(rendered[0].contains("cycle"), rendered[0])
        assertTrue(rendered[1].startsWith("cycle"), rendered[1])
        assertTrue(rendered[1].endsWith("100%"), rendered[1])
        assertTrue(rendered[2].endsWith("0%"), rendered[2])
    }

    @Test
    fun `an unplayed contestant scores zero rather than dividing by nothing`() {
        val table = TournamentTable(listOf(BotId("cycle"), BotId("north")))

        assertEquals(0, table.played(0))
        assertEquals(0.0, table.scoreRate(0))
        assertFalse(table.scoreRate(0).isNaN())
    }

    private fun tournamentOf(
        contestants: List<String>,
        rounds: Int,
        rules: RulesConfig = RulesConfig(),
    ): Tournament = Tournament(
        TournamentConfig(
            contestants = contestants.map(::BotId),
            rows = ROWS,
            cols = COLS,
            rounds = rounds,
            rules = rules,
            budgetPerTurn = 0,
        ),
        TestRegistry.ALL,
    )

    private companion object {
        const val ROWS = 9
        const val COLS = 9
    }
}
