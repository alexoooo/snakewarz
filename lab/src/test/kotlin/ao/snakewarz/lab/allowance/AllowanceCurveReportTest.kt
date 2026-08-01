package ao.snakewarz.lab.allowance

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.LoggedSlot
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AllowanceCurveReportTest {
    @Test
    fun `the plan buys exactly variant by fixed-panel complete schedules`() {
        val params = BotParams(mapOf("exploration" to "3.0"))
        val variants = listOf(
            Contestant(BotId("uct"), 400, params),
            Contestant(BotId("uct"), 800, params),
        )
        val panel = listOf(
            Contestant(BotId("puct"), 600),
            Contestant(BotId("alphabeta"), 800),
        )

        val plan = AllowanceCurvePlan(variants, panel, replications = 2, seed = SEED, threads = 3)

        assertEquals(4, plan.pairings.size)
        assertEquals(640, plan.matchCount)
        assertEquals(
            listOf(
                variants[0] to panel[0],
                variants[0] to panel[1],
                variants[1] to panel[0],
                variants[1] to panel[1],
            ),
            plan.pairings.map { it.variant to it.opponent },
        )
        for (pairing in plan.pairings) {
            val config = pairing.config
            assertEquals(listOf(pairing.variant, pairing.opponent), config.contestants)
            assertEquals(8, config.rows)
            assertEquals(8, config.cols)
            assertEquals(160, config.rounds)
            assertEquals(SEED, config.seed)
            assertEquals(0, config.budgetPerTurn)
            assertEquals(0, config.wallCount)
            assertEquals(TournamentFormat.HEAD_TO_HEAD, config.format)
        }
    }

    @Test
    fun `the plan rejects implicit effort mixed families and overlapping roles`() {
        val fixed = Contestant(BotId("uct"), 400)

        val implicit = assertFailsWith<IllegalArgumentException> {
            AllowanceCurvePlan(
                variants = listOf(Contestant(BotId("uct")), Contestant(BotId("uct"), 800)),
                panel = listOf(Contestant(BotId("puct"), 600)),
                replications = 1,
                seed = SEED,
                threads = 1,
            )
        }
        assertTrue(implicit.message.orEmpty().contains("explicit fixed"), implicit.message)

        val mixed = assertFailsWith<IllegalArgumentException> {
            AllowanceCurvePlan(
                variants = listOf(fixed, Contestant(BotId("puct"), 800)),
                panel = listOf(Contestant(BotId("alphabeta"), 600)),
                replications = 1,
                seed = SEED,
                threads = 1,
            )
        }
        assertTrue(mixed.message.orEmpty().contains("differ only"), mixed.message)

        val overlap = assertFailsWith<IllegalArgumentException> {
            AllowanceCurvePlan(
                variants = listOf(fixed, Contestant(BotId("uct"), 800)),
                panel = listOf(fixed),
                replications = 1,
                seed = SEED,
                threads = 1,
            )
        }
        assertTrue(overlap.message.orEmpty().contains("disjoint"), overlap.message)
    }

    @Test
    fun `complete pair runs produce opponent cells and the strongest eligible fixed point`() {
        val fixture = fixture { variant, opponent, opening ->
            when (variant to opponent) {
                VARIANT_400 to PANEL_ONE -> if (opening < 32) variant else opponent
                VARIANT_400 to PANEL_TWO -> if (opening < 24) variant else opponent
                VARIANT_800 to PANEL_ONE -> if (opening < 28) variant else opponent
                VARIANT_800 to PANEL_TWO -> if (opening < 30) variant else opponent
                else -> error("unexpected pairing $variant against $opponent")
            }
        }

        val report = analyze(fixture)

        assertEquals(4, report.cells.size)
        assertEquals(80, report.cell(VARIANT_400, PANEL_ONE).played)
        assertEquals(0.80, report.cell(VARIANT_400, PANEL_ONE).score, 1e-12)
        assertEquals(0.60, report.variants.single { it.spec == VARIANT_400 }.maximin, 1e-12)
        assertEquals(0.70, report.variants.single { it.spec == VARIANT_800 }.maximin, 1e-12)
        assertEquals(VARIANT_800, report.strongest.spec)
        assertEquals(800, report.strongest.allowance)
        assertEquals(320, report.matchCount)
        assertEquals(320, report.distinctGames)
        assertEquals(4, report.runIds.size)
        assertTrue(report.lines().last().contains("retained pair runs"), report.lines().last())
    }

    @Test
    fun `maximin resamples the minimum on shared openings rather than independent cell bounds`() {
        val fixture = fixture { variant, opponent, opening ->
            when {
                variant == VARIANT_400 && opponent == PANEL_ONE ->
                    if (opening < 20) variant else opponent

                variant == VARIANT_400 && opponent == PANEL_TWO ->
                    if (opening < 20) opponent else variant

                else -> variant
            }
        }

        val report = analyze(fixture)
        val point = report.variants.single { it.spec == VARIANT_400 }
        val independentHigh = minOf(
            report.cell(VARIANT_400, PANEL_ONE).interval.high,
            report.cell(VARIANT_400, PANEL_TWO).interval.high,
        )

        assertTrue(independentHigh > 0.5, "$independentHigh did not expose independent cell bounds")
        assertTrue(point.maximinInterval.high <= 0.5, point.maximinInterval.toString())
        assertEquals(report.variants, analyze(fixture).variants, "bootstrap bounds must be byte-stable")
        assertEquals(20_260_802L, ALLOWANCE_BOOTSTRAP_SEED)
    }

    @Test
    fun `incomplete or forfeited pair evidence fails before producing a curve`() {
        val complete = fixture { variant, _, _ -> variant }

        val incomplete = complete.copy(matches = complete.matches.dropLast(1))
        val missingError = assertFailsWith<IllegalArgumentException> { analyze(incomplete) }
        assertTrue(missingError.message.orEmpty().contains("exactly indices"), missingError.message)

        val forfeited = complete.copy(
            matches = complete.matches.mapIndexed { index, match ->
                if (index == 0) match.withForfeit() else match
            },
        )
        val forfeitError = assertFailsWith<IllegalArgumentException> { analyze(forfeited) }
        assertTrue(forfeitError.message.orEmpty().contains("forfeit"), forfeitError.message)
    }

    private fun analyze(fixture: Fixture): AllowanceCurveReport = AllowanceCurveReport.of(
        runs = fixture.runs,
        matches = fixture.matches,
        variants = VARIANTS,
        panel = PANEL,
        replications = 1,
        seed = SEED,
    )

    private fun fixture(winner: (String, String, Int) -> String?): Fixture {
        val runs = mutableListOf<RunHeader>()
        val matches = mutableListOf<LoggedMatch>()
        var runOrdinal = 0
        for (variant in VARIANTS) {
            for (opponent in PANEL) {
                val run = "allowance-test-${runOrdinal++}"
                runs += header(run, variant.spec, opponent)
                repeat(Openings.COMPLETE_ROUNDS_PER_REPLICATION) { index ->
                    val group = index / Openings.SEATINGS_PER_OPENING
                    val first = if (index % 2 == 0) variant.spec else opponent
                    val second = if (index % 2 == 0) opponent else variant.spec
                    matches += loggedMatch(
                        run = run,
                        index = index,
                        first = first,
                        second = second,
                        variant = variant.spec,
                        opponent = opponent,
                        winner = winner(variant.spec, opponent, group),
                        hash = matches.size.toLong(),
                    )
                }
            }
        }
        return Fixture(runs, matches)
    }

    private fun loggedMatch(
        run: String,
        index: Int,
        first: String,
        second: String,
        variant: String,
        opponent: String,
        winner: String?,
        hash: Long,
    ): LoggedMatch {
        val group = index / Openings.SEATINGS_PER_OPENING
        return LoggedMatch(
            run = run,
            index = index,
            pairKey = group,
            openingIdentity = "empty8-rho-${(group % Openings.COMPLETE_POPULATION).toString().padStart(2, '0')}",
            seed = SEED + group,
            turnOrder = listOf(0, 1),
            end = if (winner == null) "TURN_LIMIT" else "LAST_SNAKE",
            turnsPlayed = 20,
            elapsedMicros = 1_000,
            moveStreamHash = hash,
            slots = listOf(
                slot(
                    seat = 0,
                    contestant = if (first == variant) 0 else 1,
                    spec = first,
                    budget = if (first == variant) allowanceOf(variant) else allowanceOf(opponent),
                    winner = first == winner,
                ),
                slot(
                    seat = 1,
                    contestant = if (second == variant) 0 else 1,
                    spec = second,
                    budget = if (second == variant) allowanceOf(variant) else allowanceOf(opponent),
                    winner = second == winner,
                ),
            ),
        )
    }

    private fun slot(seat: Int, contestant: Int, spec: String, budget: Int, winner: Boolean): LoggedSlot = LoggedSlot(
        seat = seat,
        contestant = contestant,
        spec = spec,
        budget = budget,
        length = 10,
        movesMade = 10,
        alive = winner,
        fate = if (winner) "" else "TRAPPED",
        winner = winner,
    )

    private fun header(run: String, variant: String, opponent: String): RunHeader {
        val rules = RulesConfig()
        return RunHeader(
            id = run,
            startedAt = "2026-08-01T00:00:00Z",
            build = "test",
            format = TournamentFormat.HEAD_TO_HEAD.name,
            rows = Openings.COMPLETE_ROWS,
            cols = Openings.COMPLETE_COLS,
            growEveryNthMove = rules.growEveryNthMove,
            maxTurns = rules.maxTurns,
            lastSnakeMustBeMoving = rules.lastSnakeMustBeMoving,
            budgetPerTurn = 0,
            rounds = Openings.COMPLETE_ROUNDS_PER_REPLICATION,
            seed = SEED,
            openings = Openings.COMPLETE.name,
            threads = 1,
            map = "empty",
            contestants = listOf(variant, opponent),
        )
    }

    private fun allowanceOf(spec: String): Int =
        spec.substringAfter("budget=").substringBefore(',').toInt()

    private fun LoggedMatch.withForfeit(): LoggedMatch = LoggedMatch(
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
        slots = slots.mapIndexed { index, slot ->
            LoggedSlot(
                seat = slot.seat,
                contestant = slot.contestant,
                spec = slot.spec,
                budget = slot.budget,
                length = slot.length,
                movesMade = slot.movesMade,
                alive = slot.alive,
                fate = if (index == 0) "FORFEIT" else slot.fate,
                winner = slot.winner,
            )
        },
    )

    private data class Fixture(
        val runs: List<RunHeader>,
        val matches: List<LoggedMatch>,
    )

    private companion object {
        const val SEED = 91_001L

        const val VARIANT_400 = "uct:budget=400,exploration=3.0"
        const val VARIANT_800 = "uct:budget=800,exploration=3.0"
        const val PANEL_ONE = "puct:budget=600,eval=learned"
        const val PANEL_TWO = "alphabeta:budget=1000,eval=chamber"

        val VARIANTS = listOf(
            AllowanceVariant(VARIANT_400, 400),
            AllowanceVariant(VARIANT_800, 800),
        )
        val PANEL = listOf(PANEL_ONE, PANEL_TWO)
    }
}
