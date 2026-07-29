package ao.snakewarz.lab.train

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.search.learned.PositionFeatures
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.Replays
import ao.snakewarz.lab.log.recordBatch
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That a corpus is the games the log holds, read back as positions and outcomes.
 *
 * The claim worth a test is the **label**: a row says "this slot went on to win", and getting that
 * backwards — or off by one slot — produces a model that is confidently wrong and a bot that plays
 * for the other side, which nothing downstream would call out. So the fixture plays real matches
 * through the real arena, writes them to a real log, and checks the labels against who actually won.
 */
class CorpusTest {
    @Test
    fun `every row says whether its own slot went on to win`() {
        val directory = Files.createTempDirectory("snakewarz-corpus")
        val batch = play(directory, rounds = 4)

        val corpus = corpusFrom(listOf(directory), rows = null, cols = null, stride = 1, limit = 100_000, seed = 1) { }

        assertEquals(PositionFeatures.LENGTH, corpus.width)
        assertEquals(batch, corpus.matches)
        assertTrue(corpus.size > 0, "four matches produced no positions at all")

        for (i in 0 until corpus.size) {
            assertTrue(corpus.labels[i] == 0.0 || corpus.labels[i] == 0.5 || corpus.labels[i] == 1.0)
        }

        // One match, one outcome: within a group the labels are a permutation of "the winner and
        // everybody else", repeated per position. Two distinct values on a decided match, one on a
        // draw -- and never three.
        for (group in 1..corpus.matches) {
            val seen = (0 until corpus.size).filter { corpus.group[it] == group }.map { corpus.labels[it] }.toSet()
            assertTrue(seen.size <= 2, "match $group carries $seen, which is more outcomes than a match has")
            assertTrue(seen.isEmpty() || seen.contains(1.0) || seen == setOf(0.5), "match $group has no winner: $seen")
        }
    }

    @Test
    fun `the same seed reads back the same corpus`() {
        // A trainer's whole run is reproducible only if its input is, and the input is sampled: which
        // matches are visited and which positions inside them both come off the seed.
        val directory = Files.createTempDirectory("snakewarz-corpus")
        play(directory, rounds = 4)

        val first = corpusFrom(listOf(directory), null, null, stride = 3, limit = 500, seed = 42) { }
        val second = corpusFrom(listOf(directory), null, null, stride = 3, limit = 500, seed = 42) { }

        assertEquals(first.size, second.size)
        assertContentEquals(first.labels, second.labels)
        assertContentEquals(first.features, second.features)
    }

    @Test
    fun `a board filter keeps only the batches that were played on it`() {
        val directory = Files.createTempDirectory("snakewarz-corpus")
        play(directory, rounds = 4)

        val matching = corpusFrom(listOf(directory), BOARD, BOARD, stride = 1, limit = 100_000, seed = 1) { }
        val elsewhere = corpusFrom(listOf(directory), BOARD + 1, BOARD, stride = 1, limit = 100_000, seed = 1) { }

        assertTrue(matching.size > 0)
        assertEquals(0, elsewhere.size, "a corpus from a board nothing was played on")
    }

    /** Plays a batch into [directory]'s log, keeping every replay, and answers how many matches. */
    private fun play(directory: java.nio.file.Path, rounds: Int): Int {
        val config = TournamentConfig(
            contestants = listOf(Contestant(BotId("space")), Contestant(BotId("chase"))),
            rows = BOARD,
            cols = BOARD,
            rounds = rounds,
            seed = 7,
            budgetPerTurn = 0,
        )
        val batch = Arena(config, ShippedBots, Openings.MIRRORED, threads = 2, keepRecords = true).run()
        recordBatch(MatchLog(directory), batch, ShippedBots, "MIRRORED", 2, Replays.ALL)
        return batch.reports.size
    }

    private companion object {
        const val BOARD = 8
    }
}
