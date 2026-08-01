package ao.snakewarz.lab.policy

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.replay.MatchRecord
import ao.snakewarz.match.tournament.Contestant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyReplayObserverTest {
    @Test
    fun `the persistent expert is called on unsampled turns before its sampled answer`() {
        val record = played(LineRegistry(resignFirst = false))
        val target = selectedAfterUnsampledCalls(record)
        val calls = IntArray(record.setup.slotCount)
        val expert = expertEntry { setup -> CountingExpert(setup, calls) }
        val probe = firstLegalProbe()

        val observations = observePolicyReplay(
            record = record,
            targets = listOf(target.sample),
            expert = Contestant(EXPERT_ID, budgetPerTurn = 5),
            expertEntry = expert,
            cases = listOf(probe),
        )

        assertEquals(turnsPerSlot(record).toList(), calls.toList())
        assertEquals(1, observations.size)
        // The probe picks nth(0). This target was chosen where a continuously-called expert picks a
        // later move; an expert called fresh or only at sampled turns would pick nth(0) and agree.
        assertFalse(observations.single().readings.single().topOne)
    }

    @Test
    fun `recorded terminal events stay on the fixed line`() {
        val record = played(LineRegistry(resignFirst = true))
        val terminal = record.terminals.first()
        val replay = PolicyReplay("terminal 0", "terminal:0", record)
        val sample = PolicySample(
            replay = replay,
            turnIndex = terminal.turnIndex,
            phase = PolicyPhase.at(terminal.turnIndex, record.turnCount),
            rank = 0,
        )

        val observations = observePolicyReplay(
            record = record,
            targets = listOf(sample),
            expert = Contestant(EXPERT_ID, budgetPerTurn = 5),
            expertEntry = expertEntry { FirstLegalBot() },
            cases = listOf(firstLegalProbe()),
        )

        assertEquals(1, observations.size)
        assertEquals(terminal.turnIndex, observations.single().sample.turnIndex)
    }

    @Test
    fun `a policy probe that consumes an evaluation fails the instrument`() {
        val record = played(LineRegistry(resignFirst = false))
        val target = firstChoice(record)
        val spending = PolicyProbeCase("spending") {
            PolicyTurnProbe { turn ->
                assertTrue(turn.budget.tryConsume())
                val direction = turn.legalMoves.nth(0)
                PolicyProbeChoice(direction, DirectionSet(1 shl direction.ordinal))
            }
        }

        val failure = assertFailsWith<IllegalStateException> {
            observePolicyReplay(
                record = record,
                targets = listOf(target),
                expert = Contestant(EXPERT_ID, budgetPerTurn = 5),
                expertEntry = expertEntry { FirstLegalBot() },
                cases = listOf(spending),
            )
        }
        assertTrue(failure.message.orEmpty().contains("consumed 1 evaluations"), failure.message)
    }

    @Test
    fun `a raw tie can reach the ceiling but is never unique top one`() {
        val record = played(LineRegistry(resignFirst = false))
        val target = firstChoice(record)
        val tied = PolicyProbeCase("tied") {
            PolicyTurnProbe { turn ->
                PolicyProbeChoice(turn.legalMoves.nth(0), turn.legalMoves)
            }
        }

        val reading = observePolicyReplay(
            record = record,
            targets = listOf(target),
            expert = Contestant(EXPERT_ID, budgetPerTurn = 5),
            expertEntry = expertEntry { FirstLegalBot() },
            cases = listOf(tied),
        ).single().readings.single()

        assertTrue(reading.tied)
        assertFalse(reading.topOne)
        assertTrue(reading.ceiling)
    }

    private fun played(registry: BotRegistry): MatchRecord {
        val match = Match(
            MatchSetup.create(
                rows = 8,
                cols = 8,
                slots = listOf(LINE_A, LINE_B),
                seed = 991L,
                budgetPerTurn = 0,
            ),
            registry,
        )
        match.runToCompletion()
        return match.record()
    }

    private fun selectedAfterUnsampledCalls(record: MatchRecord): Target {
        val match = Match.playback(record)
        val calls = IntArray(record.setup.slotCount)
        while (match.outcome == null) {
            val slot = match.view.toAct.index
            calls[slot]++
            val legal = match.view.legalMoves(match.view.toAct)
            if (legal.size >= 2 && (calls[slot] - 1) % legal.size != 0) {
                val replay = PolicyReplay("line 0", "line:0", record)
                return Target(
                    PolicySample(
                        replay = replay,
                        turnIndex = match.turnIndex,
                        phase = PolicyPhase.at(match.turnIndex, record.turnCount),
                        rank = 0,
                    ),
                )
            }
            if (match.step() == StepResult.AwaitingInput) break
        }
        error("the line exposed no multi-choice turn after an unsampled call")
    }

    private fun firstChoice(record: MatchRecord): PolicySample {
        val match = Match.playback(record)
        while (match.outcome == null) {
            if (match.view.legalMoves(match.view.toAct).size >= 2) {
                val replay = PolicyReplay("line 0", "line:0", record)
                return PolicySample(
                    replay = replay,
                    turnIndex = match.turnIndex,
                    phase = PolicyPhase.at(match.turnIndex, record.turnCount),
                    rank = 0,
                )
            }
            if (match.step() == StepResult.AwaitingInput) break
        }
        error("the line exposed no choice turn")
    }

    private fun turnsPerSlot(record: MatchRecord): IntArray {
        val turns = IntArray(record.setup.slotCount)
        val match = Match.playback(record)
        while (match.outcome == null) {
            turns[match.view.toAct.index]++
            if (match.step() == StepResult.AwaitingInput) break
        }
        return turns
    }

    private fun firstLegalProbe(): PolicyProbeCase = PolicyProbeCase("first") {
        PolicyTurnProbe { turn ->
            val direction = turn.legalMoves.nth(0)
            PolicyProbeChoice(direction, DirectionSet(1 shl direction.ordinal))
        }
    }

    private fun expertEntry(create: (BotSetup) -> Bot): BotEntry =
        BotEntry(EXPERT_ID, "Counting expert", BotFactory(create))

    private class CountingExpert(setup: BotSetup, private val calls: IntArray) : Bot {
        private val slot = setup.self.index

        override fun chooseMove(turn: Turn): Decision {
            calls[slot]++
            val legal = turn.legalMoves
            val direction = if (legal.isEmpty) Direction.NORTH else legal.nth((calls[slot] - 1) % legal.size)
            return Decision.Move(direction)
        }
    }

    private class FirstLegalBot : Bot {
        override fun chooseMove(turn: Turn): Decision = Decision.Move(
            if (turn.legalMoves.isEmpty) Direction.NORTH else turn.legalMoves.nth(0),
        )
    }

    private class LineRegistry(private val resignFirst: Boolean) : BotRegistry {
        override val entries: List<BotEntry> = listOf(entry(LINE_A), entry(LINE_B))

        override fun get(id: BotId): BotEntry? = entries.firstOrNull { it.id == id }

        private fun entry(id: BotId): BotEntry = BotEntry(
            id,
            id.slug,
            BotFactory { setup ->
                if (resignFirst && setup.self.index == 0) {
                    object : Bot {
                        override fun chooseMove(turn: Turn): Decision = Decision.Resign
                    }
                } else {
                    FirstLegalBot()
                }
            },
        )
    }

    private class Target(val sample: PolicySample)

    private companion object {
        val LINE_A = BotId("line-a")
        val LINE_B = BotId("line-b")
        val EXPERT_ID = BotId("counting-expert")
    }
}
