package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotParams
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
        assertFailsWith<IllegalArgumentException> { TournamentConfig(listOf(Contestant(BotId("cycle"))), 9, 9) }
        assertFailsWith<IllegalArgumentException>("the same bot at the same settings, twice") {
            TournamentConfig(listOf(Contestant(BotId("cycle")), Contestant(BotId("cycle"))), 9, 9)
        }
        assertFailsWith<IllegalArgumentException> {
            TournamentConfig(listOf(Contestant(BotId("cycle")), Contestant(BotId("last"))), 9, 9, rounds = 3)
        }
    }

    @Test
    fun `one bot may enter twice at two allowances`() {
        // The experiment the testbed exists for, and the one a list of ids could not express: until
        // contestant identity became the whole configuration, this was refused as a duplicate.
        val field = listOf(
            Contestant(BotId("cycle"), budgetPerTurn = 40_000),
            Contestant(BotId("cycle"), budgetPerTurn = 4_000),
        )
        val tournament = fieldOf(field, rounds = 2)

        assertEquals(1, tournament.config.pairingCount)
        assertEquals(40_000, tournament.setupFor(0).budgetFor(0))
        assertEquals(4_000, tournament.setupFor(0).budgetFor(1))

        // ...and the seat swap carries the allowance with the contestant rather than the seat.
        assertEquals(4_000, tournament.setupFor(1).budgetFor(0))
        assertEquals(40_000, tournament.setupFor(1).budgetFor(1))
    }

    @Test
    fun `a contestant's parameters reach the match it plays`() {
        val tuned = BotParams(mapOf("exploration" to "1.5"))
        val tournament = fieldOf(
            listOf(Contestant(BotId("cycle")), Contestant(BotId("cycle"), params = tuned)),
            rounds = 2,
        )

        assertEquals(BotParams.EMPTY, tournament.setupFor(0).paramsFor(0))
        assertEquals(tuned, tournament.setupFor(0).paramsFor(1))
    }

    @Test
    fun `an unconfigured contestant takes whatever the batch grants`() {
        // Which is why the allowance is absent rather than pre-filled: a contestant that always
        // carried a figure would override TournamentConfig.budgetPerTurn and leave it unusable.
        val tournament = fieldOf(listOf(Contestant(BotId("cycle")), Contestant(BotId("last"))), rounds = 2)

        assertEquals(0, tournament.setupFor(0).budgetFor(0), "the config's budgetPerTurn, which is 0 here")
        assertFalse(tournament.setupFor(0).configured)
    }

    @Test
    fun `the matrix names configured contestants apart and spells them out underneath`() {
        val table = fieldOf(
            listOf(
                Contestant(BotId("cycle")),
                Contestant(BotId("north"), budgetPerTurn = 4_000),
            ),
            rounds = 2,
        ).runToCompletion()

        val rendered = table.toString().trimEnd().lines()

        assertTrue(rendered[0].contains("cycle"), rendered[0])
        assertTrue(rendered[0].contains("north@4k"), "an overridden allowance is in the heading: ${rendered[0]}")
        assertTrue(
            rendered.last().contains("budget=4000"),
            "and spelled out in the legend: ${rendered.last()}",
        )
    }

    @Test
    fun `a contestant's suffix is the part of its label that is not the bot`() {
        assertEquals("", Contestant(BotId("cycle")).suffix, "a stock entry has nothing to add")
        assertEquals("@4k", Contestant(BotId("cycle"), budgetPerTurn = 4_000).suffix)
        assertEquals("*", Contestant(BotId("cycle"), params = BotParams(mapOf("a" to "1"))).suffix)

        val both = Contestant(BotId("cycle"), budgetPerTurn = 4_000, params = BotParams(mapOf("a" to "1")))
        assertEquals("@4k*", both.suffix)
        assertEquals(both.bot.slug + both.suffix, both.label, "a label is the slug and then the suffix")
    }

    @Test
    fun `two contestants that describe themselves the same way still get distinct columns`() {
        val table = TournamentTable(
            listOf(
                Contestant(BotId("cycle"), params = BotParams(mapOf("a" to "1"))),
                Contestant(BotId("cycle"), params = BotParams(mapOf("a" to "2"))),
            ),
        )

        val heading = table.toString().lines().first()

        assertTrue(heading.contains("cycle*"), heading)
        assertTrue(heading.contains("cycle*·2"), "a repeated label is numbered rather than duplicated: $heading")
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
    fun `a free-for-all seats everybody in every match`() {
        val tournament = tournamentOf(
            listOf("cycle", "last", "north"),
            rounds = 4,
            format = TournamentFormat.FREE_FOR_ALL,
        )

        assertEquals(4, tournament.matchCount, "a round is a match when everybody is in it")

        val schedule = List(tournament.matchCount) { tournament.setupFor(it) }
        val everybody = listOf(BotId("cycle"), BotId("last"), BotId("north"))
        for (setup in schedule) {
            assertEquals(everybody.toSet(), setup.slots.toSet(), "every contestant is on the board")
        }

        // A seed is shared by a group of three matches, the seating rotated a step each time...
        assertEquals(schedule[0].seed, schedule[1].seed)
        assertEquals(schedule[0].seed, schedule[2].seed)
        assertEquals(listOf(everybody[1], everybody[2], everybody[0]), schedule[1].slots)
        assertEquals(listOf(everybody[2], everybody[0], everybody[1]), schedule[2].slots)

        // ...and four rounds do not divide by three, so the last group is one match, cut short.
        assertEquals(schedule[0].seed + 1, schedule[3].seed)
        assertEquals(everybody, schedule[3].slots)
    }

    @Test
    fun `a free-for-all scores pairwise by outlasting`() {
        // "north" walks into the wall while the other two are still going, so both outlast it in
        // every match; and a turn limit the survivors both reach is a draw *between them* only.
        val table = tournamentOf(
            listOf("cycle", "last", "north"),
            rounds = 4,
            rules = RulesConfig(maxTurns = 30),
            format = TournamentFormat.FREE_FOR_ALL,
        ).runToCompletion()

        assertEquals(4, table.wins(0, 2), "cycle outlasted north every match")
        assertEquals(4, table.wins(1, 2), "last outlasted north every match")
        assertEquals(0, table.wins(2, 0))
        assertEquals(0, table.wins(2, 1))
        assertEquals(4, table.draws(0, 1), "the survivors drew with each other")

        for (one in 0 until table.size) {
            for (other in one + 1 until table.size) {
                assertEquals(4, table.played(one, other), "every pair is in every match")
            }
        }
        assertEquals(listOf(0, 1, 2).toSet(), table.ranking().toSet())
        assertEquals(2, table.ranking().last(), "and the one that keeps dying ranks last")
    }

    @Test
    fun `a free-for-all of two is the head-to-head schedule`() {
        // Rotating two seats is swapping them, so the generalization has to land on the same
        // matches — same seeds, same seatings — as the format it grew out of.
        val contestants = listOf("cycle", "last")
        val headToHead = tournamentOf(contestants, rounds = 4)
        val freeForAll = tournamentOf(contestants, rounds = 4, format = TournamentFormat.FREE_FOR_ALL)

        assertEquals(headToHead.matchCount, freeForAll.matchCount)
        for (index in 0 until headToHead.matchCount) {
            assertEquals(headToHead.setupFor(index), freeForAll.setupFor(index))
        }
    }

    @Test
    fun `the same free-for-all config plays the same tournament twice`() {
        val contestants = listOf("cycle", "last", "north")
        val first = tournamentOf(contestants, rounds = 4, format = TournamentFormat.FREE_FOR_ALL)
            .runToCompletion()
        val second = tournamentOf(contestants, rounds = 4, format = TournamentFormat.FREE_FOR_ALL)
            .runToCompletion()

        for (one in 0 until first.size) {
            for (other in 0 until first.size) {
                assertEquals(first.wins(one, other), second.wins(one, other))
                assertEquals(first.draws(one, other), second.draws(one, other))
            }
        }
    }

    @Test
    fun `an unplayed contestant scores zero rather than dividing by nothing`() {
        val table = TournamentTable(listOf(Contestant(BotId("cycle")), Contestant(BotId("north"))))

        assertEquals(0, table.played(0))
        assertEquals(0.0, table.scoreRate(0))
        assertFalse(table.scoreRate(0).isNaN())
    }

    private fun tournamentOf(
        contestants: List<String>,
        rounds: Int,
        rules: RulesConfig = RulesConfig(),
        format: TournamentFormat = TournamentFormat.HEAD_TO_HEAD,
    ): Tournament = fieldOf(contestants.map { Contestant(BotId(it)) }, rounds, rules, format)

    private fun fieldOf(
        contestants: List<Contestant>,
        rounds: Int,
        rules: RulesConfig = RulesConfig(),
        format: TournamentFormat = TournamentFormat.HEAD_TO_HEAD,
    ): Tournament = Tournament(
        TournamentConfig(
            contestants = contestants,
            rows = ROWS,
            cols = COLS,
            rounds = rounds,
            rules = rules,
            budgetPerTurn = 0,
            format = format,
        ),
        TestRegistry.ALL,
    )

    private companion object {
        const val ROWS = 9
        const val COLS = 9
    }
}
