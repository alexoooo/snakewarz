package ao.snakewarz.lab.championship

import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.LoggedSlot
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.match.tournament.TournamentFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChampionshipReportTest {
    @Test
    fun `complete finalist evidence produces every cell maximin and an incumbent gate`() {
        val fixture = fixture { one, other, opening ->
            when (setOf(one, other)) {
                setOf(ALPHA, BRAVO) -> if (opening < 32) ALPHA else BRAVO
                setOf(ALPHA, CHARLIE) -> if (opening < 24) ALPHA else CHARLIE
                setOf(BRAVO, CHARLIE) -> if (opening < 28) BRAVO else CHARLIE
                else -> error("unexpected pairing $one vs $other")
            }
        }

        val report = analyze(fixture)

        assertEquals(6, report.cells.size)
        assertEquals(0.80, report.cell(ALPHA, BRAVO).score, 1e-12)
        assertEquals(0.20, report.cell(BRAVO, ALPHA).score, 1e-12)
        assertEquals(80, report.cell(ALPHA, BRAVO).played)
        assertEquals(0.60, report.rankedFinalists.first { it.spec == ALPHA }.maximin, 1e-12)
        assertEquals(listOf(CHARLIE), report.rankedFinalists.first { it.spec == ALPHA }.worstOpponents)
        assertEquals(ALPHA, report.rankedFinalists.first().spec)
        assertEquals(ALPHA, report.incumbentGate.challenger)
        assertTrue(report.incumbentGate.clears)
        assertTrue(report.incumbentGate.directCell!!.interval.low > INCUMBENT_THRESHOLD)
        assertEquals(240, report.matchCount)
        assertEquals(240, report.distinctGames)
    }

    @Test
    fun `maximin interval recomputes the minimum inside each shared block draw`() {
        val fixture = fixture { one, other, opening ->
            when (setOf(one, other)) {
                setOf(ALPHA, BRAVO) -> if (opening < 20) ALPHA else BRAVO
                setOf(ALPHA, CHARLIE) -> if (opening < 20) CHARLIE else ALPHA
                setOf(BRAVO, CHARLIE) -> if (opening % 2 == 0) BRAVO else CHARLIE
                else -> error("unexpected pairing $one vs $other")
            }
        }

        val report = analyze(fixture)
        val alpha = report.rankedFinalists.first { it.spec == ALPHA }
        val independentCellHigh = minOf(
            report.cell(ALPHA, BRAVO).interval.high,
            report.cell(ALPHA, CHARLIE).interval.high,
        )

        // The two cells are exact complements by opening. Independently taking their high bounds
        // would claim the minimum can rise above half; recomputing min per draw correctly cannot.
        assertTrue(independentCellHigh > 0.5, "$independentCellHigh did not expose independent bounds")
        assertTrue(alpha.maximinInterval.high <= 0.5, alpha.maximinInterval.toString())
    }

    @Test
    fun `the same complete log has byte-stable bootstrap bounds`() {
        val fixture = fixture { one, _, opening -> if ((opening + one.length) % 3 == 0) one else null }

        val once = analyze(fixture)
        val again = analyze(fixture)

        assertEquals(once.cells, again.cells)
        assertEquals(once.rankedFinalists, again.rankedFinalists)
        assertEquals(CHAMPIONSHIP_BOOTSTRAP_SEED, 20_260_801L)
    }

    @Test
    fun `rendering names the population every directed cell residuals and the direct gate`() {
        val fixture = fixture { one, other, opening ->
            when (setOf(one, other)) {
                setOf(ALPHA, BRAVO) -> if (opening < 32) ALPHA else BRAVO
                setOf(ALPHA, CHARLIE) -> if (opening < 24) ALPHA else CHARLIE
                setOf(BRAVO, CHARLIE) -> if (opening < 28) BRAVO else CHARLIE
                else -> error("unexpected pairing $one vs $other")
            }
        }

        val lines = analyze(fixture).lines()

        assertTrue(lines.first().contains("empty 8x8, complete openings, 40 blocks"), lines.first())
        assertTrue(lines.any { it.contains("shared-opening-block 95% interval") }, lines.joinToString("\n"))
        assertEquals(6, lines.count { it.startsWith("  cell ") })
        assertTrue(lines.any { it.contains("rating residuals") }, lines.joinToString("\n"))
        assertTrue(lines.last().contains("incumbent gate: CLEAR"), lines.last())
        assertTrue(lines.last().contains("lower bound"), lines.last())
    }

    @Test
    fun `practical bands use rating then Chrome cost without a non-transitive comparator`() {
        val interval = ChampionshipInterval(0.4, 0.7)
        val ranked = rankFinalists(
            listOf(
                finalist("anchor", maximin = 0.60, rating = 0.0, cost = 8.0, interval = interval),
                finalist("rating", maximin = 0.56, rating = 100.0, cost = 8.0, interval = interval),
                finalist("cost", maximin = 0.56, rating = 100.0, cost = 4.0, interval = interval),
                finalist("next", maximin = 0.54, rating = 1_000.0, cost = 1.0, interval = interval),
            ),
        )

        assertEquals(listOf("cost", "rating", "anchor", "next"), ranked.map { it.spec })
        assertEquals(listOf(1, 1, 1, 2), ranked.map { it.practicalBand })
    }

    @Test
    fun `the incumbent stays when it ranks first`() {
        val fixture = fixture { one, _, _ -> one }

        val report = analyze(fixture, incumbent = ALPHA)

        assertEquals(ALPHA, report.incumbentGate.challenger)
        assertFalse(report.incumbentGate.clears)
        assertEquals(null, report.incumbentGate.directCell)
    }

    @Test
    fun `invalid championship evidence fails before producing a number`() {
        val complete = fixture { one, _, _ -> one }

        val wrongMap = complete.copy(run = header(map = "4w12345678"))
        val mapError = assertFailsWith<IllegalArgumentException> { analyze(wrongMap) }
        assertTrue(mapError.message.orEmpty().contains("map empty"), mapError.message)

        val missingOpening = complete.copy(
            matches = complete.matches.filter { it.openingIdentity != "empty8-rho-39" },
        )
        val openingError = assertFailsWith<IllegalArgumentException> { analyze(missingOpening) }
        assertTrue(openingError.message.orEmpty().contains("40 complete opening blocks"), openingError.message)

        val foreignRun = complete.copy(
            matches = complete.matches.mapIndexed { index, match ->
                if (index == 0) match.copyFor(run = "other") else match
            },
        )
        val runError = assertFailsWith<IllegalArgumentException> { analyze(foreignRun) }
        assertTrue(runError.message.orEmpty().contains("exactly one run"), runError.message)

        val missingSeat = complete.copy(
            matches = complete.matches.filterNot {
                it.openingIdentity == "empty8-rho-00" &&
                    it.slots.map { slot -> slot.spec }.toSet() == setOf(ALPHA, BRAVO) &&
                    it.slots.single { slot -> slot.spec == ALPHA }.seat == 1
            },
        )
        val seatError = assertFailsWith<IllegalArgumentException> { analyze(missingSeat) }
        assertTrue(seatError.message.orEmpty().contains("expected 2"), seatError.message)

        val forfeited = complete.copy(
            matches = complete.matches.mapIndexed { index, match ->
                if (index == 0) match.copyFor(forfeit = true) else match
            },
        )
        val forfeitError = assertFailsWith<IllegalArgumentException> { analyze(forfeited) }
        assertTrue(forfeitError.message.orEmpty().contains("forfeit"), forfeitError.message)
    }

    private fun analyze(fixture: Fixture, incumbent: String = BRAVO): ChampionshipReport =
        ChampionshipReport.of(
            run = fixture.run,
            matches = fixture.matches,
            finalists = SPECS,
            incumbent = incumbent,
            chromeWorstTurnMillis = COSTS,
            registry = ShippedBots,
        )

    private fun fixture(winner: (String, String, Int) -> String?): Fixture {
        var index = 0
        var pairing = 0
        val matches = buildList {
            for (one in SPECS.indices) {
                for (other in one + 1 until SPECS.size) {
                    for (opening in 0 until Openings.COMPLETE_POPULATION) {
                        for (seating in 0 until Openings.SEATINGS_PER_OPENING) {
                            val first = if (seating == 0) SPECS[one] else SPECS[other]
                            val second = if (seating == 0) SPECS[other] else SPECS[one]
                            val winnerSpec = winner(SPECS[one], SPECS[other], opening)
                            add(loggedMatch(index++, pairing, opening, first, second, winnerSpec))
                        }
                    }
                    pairing++
                }
            }
        }
        return Fixture(header(), matches)
    }

    private fun loggedMatch(
        index: Int,
        pairing: Int,
        opening: Int,
        first: String,
        second: String,
        winner: String?,
    ): LoggedMatch = LoggedMatch(
        run = RUN_ID,
        index = index,
        pairKey = pairing * Openings.COMPLETE_POPULATION + opening,
        openingIdentity = "empty8-rho-${opening.toString().padStart(2, '0')}",
        seed = 81_001L + opening,
        turnOrder = listOf(0, 1),
        end = if (winner == null) "TURN_LIMIT" else "LAST_SNAKE",
        turnsPlayed = 20,
        elapsedMicros = 1_000,
        moveStreamHash = index.toLong(),
        slots = listOf(
            slot(seat = 0, contestant = SPECS.indexOf(first), spec = first, winner = first == winner),
            slot(seat = 1, contestant = SPECS.indexOf(second), spec = second, winner = second == winner),
        ),
    )

    private fun slot(seat: Int, contestant: Int, spec: String, winner: Boolean): LoggedSlot = LoggedSlot(
        seat = seat,
        contestant = contestant,
        spec = spec,
        budget = 1_000,
        length = 10,
        movesMade = 10,
        alive = winner,
        fate = if (winner) "" else "TRAPPED",
        winner = winner,
    )

    private fun header(map: String = "empty"): RunHeader = RunHeader(
        id = RUN_ID,
        startedAt = "2026-08-01T00:00:00Z",
        build = "test",
        format = TournamentFormat.HEAD_TO_HEAD.name,
        rows = 8,
        cols = 8,
        growEveryNthMove = 2,
        maxTurns = 1_024,
        lastSnakeMustBeMoving = true,
        budgetPerTurn = 1_000,
        rounds = Openings.COMPLETE_ROUNDS_PER_REPLICATION,
        seed = 81_001L,
        openings = Openings.COMPLETE.name,
        threads = 1,
        map = map,
        contestants = SPECS,
    )

    private fun finalist(
        spec: String,
        maximin: Double,
        rating: Double,
        cost: Double,
        interval: ChampionshipInterval,
    ): ChampionshipFinalist = ChampionshipFinalist(
        spec = spec,
        label = spec,
        maximin = maximin,
        maximinInterval = interval,
        worstOpponents = listOf("opponent"),
        rating = rating,
        ratingPriorDetermined = false,
        chromeWorstTurnMillis = cost,
    )

    private fun LoggedMatch.copyFor(run: String = this.run, forfeit: Boolean = false): LoggedMatch = LoggedMatch(
        run = run,
        index = index,
        pairKey = pairKey,
        openingIdentity = openingIdentity,
        seed = seed,
        turnOrder = turnOrder,
        end = end,
        turnsPlayed = turnsPlayed,
        elapsedMicros = elapsedMicros,
        moveStreamHash = moveStreamHash,
        slots = slots.mapIndexed { slotIndex, slot ->
            LoggedSlot(
                seat = slot.seat,
                contestant = slot.contestant,
                spec = slot.spec,
                budget = slot.budget,
                length = slot.length,
                movesMade = slot.movesMade,
                alive = slot.alive,
                fate = if (forfeit && slotIndex == 0) "FORFEIT" else slot.fate,
                winner = slot.winner,
            )
        },
    )

    private data class Fixture(val run: RunHeader, val matches: List<LoggedMatch>)

    private companion object {
        const val RUN_ID = "championship-test"
        const val ALPHA = "alpha:budget=1000"
        const val BRAVO = "bravo:budget=1000"
        const val CHARLIE = "charlie:budget=1000"

        val SPECS = listOf(ALPHA, BRAVO, CHARLIE)
        val COSTS = mapOf(ALPHA to 8.0, BRAVO to 7.0, CHARLIE to 6.0)
    }
}
