package ao.snakewarz.lab.endgame

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.arena.moveStreamHash
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.LoggedSlot
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.lab.log.expandedSpec
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.TerminalEvent
import ao.snakewarz.match.replay.DirectionStream
import ao.snakewarz.match.replay.MatchRecord
import ao.snakewarz.match.tournament.Contestant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndgameCorpusTest {
    @Test
    fun `threshold sampling is deterministic and retains one replay per opening block`() {
        val record = completeRecord()
        val replays = listOf(
            EndgameReplay("run 0", "opening:empty8-rho-00", SnakeId(0), record),
            EndgameReplay("run 1", "opening:empty8-rho-00", SnakeId(0), record),
            EndgameReplay("run 2", "opening:empty8-rho-01", SnakeId(0), record),
        )

        val one = selectEndgamePositions(replays, intArrayOf(62, 61), positionsPerThreshold = 8, seed = 91L)
        val two = selectEndgamePositions(replays.reversed(), intArrayOf(61, 62), positionsPerThreshold = 8, seed = 91L)

        assertContentEquals(intArrayOf(61, 62), one.thresholds)
        assertEquals(3, one.candidates[0])
        assertEquals(3, one.candidates[1])
        assertEquals(selectionIdentity(one), selectionIdentity(two))
        for (threshold in one.thresholds) {
            val samples = one.at(threshold)
            assertEquals(2, samples.size)
            assertEquals(2, samples.map { it.replay.block }.toSet().size)
        }
        assertTrue(one.at(61).all { it.turnIndex == 2 && it.remaining == 60 })
        assertTrue(one.at(62).all { it.turnIndex == 0 && it.remaining == 62 })
    }

    @Test
    fun `sampled structural state rebuilds from the recorded prefix`() {
        val record = completeRecord()
        val replay = EndgameReplay("run 0", "opening:empty8-rho-00", SnakeId(0), record)
        val sample = selectEndgamePositions(listOf(replay), intArrayOf(61), 1, seed = 4L).samples.single()

        val rebuilt = LongArray(ExactStateCodec.WORDS)
        ExactStateCodec.encode(replayBoardAt(record, sample.turnIndex), rebuilt, 0)

        assertTrue(sample.state.sameAs(rebuilt))
        assertEquals(Direction.SOUTH, sample.recordedMove)
    }

    @Test
    fun `endgame validation rejects geometry walls and forfeits`() {
        val wrongGeometry = completeRecord(rows = 7, cols = 8, secondSpawn = 55)
        assertFailsWith<IllegalArgumentException> {
            validateEndgameReplay(
                runHeader(),
                loggedMatch(wrongGeometry),
                wrongGeometry,
                "wrong geometry",
                ShippedBots,
            )
        }

        val walled = completeRecord(walls = intArrayOf(2))
        assertFailsWith<IllegalArgumentException> {
            validateEndgameReplay(runHeader(), loggedMatch(walled), walled, "walled", ShippedBots)
        }

        val forfeit = forfeitRecord()
        assertFailsWith<IllegalArgumentException> {
            validateEndgameReplay(runHeader(), loggedMatch(forfeit), forfeit, "forfeit", ShippedBots)
        }
    }

    @Test
    fun `threshold sampler rejects values above the initial free-cell count`() {
        val replay = EndgameReplay(
            "run 0",
            "opening:empty8-rho-00",
            SnakeId(0),
            completeRecord(),
        )

        assertFailsWith<IllegalArgumentException> {
            selectEndgamePositions(listOf(replay), intArrayOf(63), 1, seed = 4L)
        }
    }

    private fun selectionIdentity(selection: EndgameSelection): List<String> =
        selection.samples.map { "${it.threshold}:${it.replay.block}:${it.replay.key}:${it.turnIndex}" }

    private fun completeRecord(
        rows: Int = 8,
        cols: Int = 8,
        secondSpawn: Int = 63,
        walls: IntArray = IntArray(0),
    ): MatchRecord {
        val moves = DirectionStream().apply {
            add(Direction.EAST)
            add(Direction.WEST)
            add(Direction.SOUTH)
            add(Direction.NORTH)
        }
        return MatchRecord(
            setup = MatchSetup(
                seed = 19L,
                rows = rows,
                cols = cols,
                rules = RulesConfig(growEveryNthMove = 1, maxTurns = moves.size),
                budgetPerTurn = 0,
                slots = listOf(BotId("chase"), BotId("space")),
                turnOrder = intArrayOf(0, 1),
                spawns = intArrayOf(0, secondSpawn),
                walls = walls,
            ),
            moves = moves,
            terminals = emptyList(),
            outcome = MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT),
        )
    }

    private fun forfeitRecord(): MatchRecord = MatchRecord(
        setup = MatchSetup(
            seed = 19L,
            rows = 8,
            cols = 8,
            rules = RulesConfig(growEveryNthMove = 1, maxTurns = 4),
            budgetPerTurn = 0,
            slots = listOf(BotId("chase"), BotId("space")),
            turnOrder = intArrayOf(0, 1),
            spawns = intArrayOf(0, 63),
        ),
        moves = DirectionStream(),
        terminals = listOf(TerminalEvent(0, SnakeId(0), EliminationReason.FORFEIT)),
        outcome = MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING),
    )

    private fun runHeader(): RunHeader = RunHeader(
        id = "endgame-test",
        startedAt = "2026-08-01T00:00:00Z",
        build = "test",
        format = "HEAD_TO_HEAD",
        rows = 8,
        cols = 8,
        growEveryNthMove = 1,
        maxTurns = 4,
        lastSnakeMustBeMoving = true,
        budgetPerTurn = 0,
        rounds = 80,
        seed = 19L,
        openings = Openings.COMPLETE.name,
        threads = 1,
        map = "empty",
        contestants = listOf(spec(BotId("chase")), spec(BotId("space"))),
    )

    private fun loggedMatch(record: MatchRecord): LoggedMatch {
        val outcome = requireNotNull(record.outcome)
        val winner = outcome.winner
        return LoggedMatch(
            run = "endgame-test",
            index = 0,
            pairKey = 0,
            openingIdentity = "empty8-rho-00",
            seed = record.setup.seed,
            turnOrder = record.setup.turnOrder().toList(),
            end = outcome.end.name,
            turnsPlayed = record.turnCount,
            elapsedMicros = 0,
            moveStreamHash = moveStreamHash(record.moves.toList()),
            slots = List(2) { seat ->
                val forfeited = record.terminals.any { it.slot.index == seat && it.reason == EliminationReason.FORFEIT }
                LoggedSlot(
                    seat = seat,
                    contestant = seat,
                    spec = spec(record.setup.slots[seat]),
                    budget = 0,
                    length = 1,
                    movesMade = 0,
                    alive = !forfeited,
                    fate = if (forfeited) EliminationReason.FORFEIT.name else "",
                    winner = winner.index == seat,
                )
            },
        )
    }

    private fun spec(bot: BotId): String = expandedSpec(Contestant(bot, 0), ShippedBots, 0)
}
