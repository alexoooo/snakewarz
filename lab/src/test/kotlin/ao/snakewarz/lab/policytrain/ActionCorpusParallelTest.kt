package ao.snakewarz.lab.policytrain

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.lab.policy.PolicyReplay
import ao.snakewarz.lab.policy.PolicyReplayCorpus
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.tournament.Contestant
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ActionCorpusParallelTest {
    @Test
    fun `one and many replay workers produce the identical corpus and model literal`() {
        val replays = List(8) { index -> played(index) }
        val corpus = corpusOf(replays)
        val expert = Contestant(EXPERT_ID, budgetPerTurn = 1)
        val expertEntry = BotEntry(EXPERT_ID, "First legal", BotFactory { FirstLegalBot() })

        val serial = collectActionDataset(
            label = "synthetic",
            corpus = corpus,
            expert = expert,
            expertEntry = expertEntry,
            positionsPerPhase = 3,
            seed = 73_001L,
            threads = 1,
            includeBlock = { true },
        )
        val parallel = collectActionDataset(
            label = "synthetic",
            corpus = corpus,
            expert = expert,
            expertEntry = expertEntry,
            positionsPerPhase = 3,
            seed = 73_001L,
            threads = 4,
            includeBlock = { true },
        )

        assertCountsEqual(serial.counts, parallel.counts)
        assertEquals(serial.examples.map(::identity), parallel.examples.map(::identity))
        for (index in serial.examples.indices) {
            assertContentEquals(serial.examples[index].features, parallel.examples[index].features)
        }

        val serialFit = fitActionLinear(serial.examples, serial.examples, listOf(0.0), 3, 0.2)
        val parallelFit = fitActionLinear(parallel.examples, parallel.examples, listOf(0.0), 3, 0.2)
        assertEquals(
            quantizeActionLinear(serialFit.weights).encode(),
            quantizeActionLinear(parallelFit.weights).encode(),
        )
    }

    @Test
    fun `a failed replay cancels other replay workers`() {
        val otherStarted = CountDownLatch(1)
        val otherInterrupted = CountDownLatch(1)

        val failure = kotlin.test.assertFailsWith<IllegalStateException> {
            parallelReplayLabels(listOf(0, 1), threads = 2) { item ->
                if (item == 0) {
                    check(otherStarted.await(2, TimeUnit.SECONDS))
                    error("label failed")
                }
                otherStarted.countDown()
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    otherInterrupted.countDown()
                }
            }
        }

        assertEquals("label failed", failure.message)
        kotlin.test.assertTrue(otherInterrupted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `workers return in input order even when later work finishes first`() {
        val laterFinished = CountDownLatch(1)

        val results = parallelReplayLabels(listOf(0, 1), threads = 2) { item ->
            if (item == 0) {
                check(laterFinished.await(2, TimeUnit.SECONDS))
            } else {
                laterFinished.countDown()
            }
            "result-$item"
        }

        assertEquals(listOf("result-0", "result-1"), results)
    }

    @Test
    fun `interrupting the caller cancels workers and preserves its interrupt flag`() {
        val workersStarted = CountDownLatch(2)
        val workerInterrupted = CountDownLatch(1)
        val callerPreservedInterrupt = AtomicBoolean(false)
        val caller = Thread {
            try {
                parallelReplayLabels(listOf(0, 1), threads = 2) {
                    workersStarted.countDown()
                    try {
                        Thread.sleep(30_000)
                    } catch (_: InterruptedException) {
                        workerInterrupted.countDown()
                    }
                }
            } catch (_: IllegalStateException) {
                callerPreservedInterrupt.set(Thread.currentThread().isInterrupted)
            }
        }

        caller.start()
        kotlin.test.assertTrue(workersStarted.await(2, TimeUnit.SECONDS))
        caller.interrupt()
        caller.join(2_000)

        kotlin.test.assertFalse(caller.isAlive)
        kotlin.test.assertTrue(callerPreservedInterrupt.get())
        kotlin.test.assertTrue(workerInterrupted.await(2, TimeUnit.SECONDS))
    }

    private fun played(index: Int): PolicyReplay {
        val setup = MatchSetup.create(
            rows = 8,
            cols = 8,
            slots = listOf(BotId("space"), BotId("wallhug")),
            seed = 900L + index,
            budgetPerTurn = 0,
        )
        val match = Match(setup, ShippedBots)
        match.runToCompletion()
        return PolicyReplay("synthetic $index", "synthetic:$index", match.record())
    }

    private fun corpusOf(replays: List<PolicyReplay>): PolicyReplayCorpus {
        val setup = replays.first().record.setup
        return PolicyReplayCorpus(
            directory = Path.of("synthetic"),
            run = RunHeader(
                id = "synthetic-run",
                startedAt = "2026-08-01T00:00:00Z",
                build = "test",
                format = "HEAD_TO_HEAD",
                rows = setup.rows,
                cols = setup.cols,
                growEveryNthMove = setup.rules.growEveryNthMove,
                maxTurns = setup.rules.maxTurns,
                lastSnakeMustBeMoving = setup.rules.lastSnakeMustBeMoving,
                budgetPerTurn = 0,
                rounds = replays.size,
                seed = 900L,
                openings = "MIRRORED",
                threads = 1,
                map = "empty",
                contestants = listOf("space", "wallhug"),
            ),
            board = setup,
            replays = replays,
            encodedCount = replays.size,
            unreadableCount = 0,
        )
    }

    private fun assertCountsEqual(first: ActionDatasetCounts, second: ActionDatasetCounts) {
        assertEquals(first.encoded, second.encoded)
        assertEquals(first.readable, second.readable)
        assertEquals(first.unreadable, second.unreadable)
        assertEquals(first.selected, second.selected)
        assertContentEquals(first.choices, second.choices)
        assertContentEquals(first.forced, second.forced)
    }

    private fun identity(example: ActionExample): String =
        "${example.dataset}|${example.map}|${example.phase}|${example.block}|${example.replay}|" +
            "${example.turnIndex}|${example.legalBits}|${example.target}|${example.cartographerMaxima}"

    private class FirstLegalBot : Bot {
        override fun chooseMove(turn: Turn): Decision = Decision.Move(
            if (turn.legalMoves.isEmpty) ao.snakewarz.core.grid.Direction.NORTH else turn.legalMoves.nth(0),
        )
    }

    private companion object {
        val EXPERT_ID = BotId("first-legal")
    }
}
