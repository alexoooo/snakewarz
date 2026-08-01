package ao.snakewarz.lab.log

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.match.replay.ReplayCodec
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig
import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchLogTest {
    @Test
    fun `a batch written out reads back as the matches it was`() {
        val directory = Files.createTempDirectory("snakewarz-log")
        val config = configOf(listOf(Contestant(BotId("space")), Contestant(BotId("wallhug"))), rounds = 4)
        val batch = Arena(config, ShippedBots, Openings.MIRRORED, threads = 2, keepRecords = true).run()

        val log = MatchLog(directory)
        val header = recordBatch(log, batch, ShippedBots, "MIRRORED", 2, Replays.DECISIVE)

        val read = log.matches()
        assertEquals(4, read.size)
        for ((index, match) in read.withIndex()) {
            val played = batch.reports[index]
            assertEquals(header.id, match.run)
            assertEquals(played.index, match.index)
            assertEquals(played.pairKey, match.pairKey)
            assertEquals(played.seed, match.seed)
            assertEquals(played.moveStreamHash, match.moveStreamHash)
            assertEquals(played.stats.turnsPlayed, match.turnsPlayed)
            assertEquals(played.stats.slots.map { it.movesMade }, match.slots.map { it.movesMade })
            assertEquals(played.stats.slots.map { it.alive }, match.slots.map { it.alive })
        }
    }

    @Test
    fun `a run says what would make it incomparable with another`() {
        val directory = Files.createTempDirectory("snakewarz-log")
        val config = configOf(listOf(Contestant(BotId("space")), Contestant(BotId("wallhug"))), rounds = 2)
        val batch = Arena(config, ShippedBots, Openings.FIXED, threads = 1).run()

        val log = MatchLog(directory)
        val written = recordBatch(log, batch, ShippedBots, "FIXED", 1, Replays.NONE)

        val read = log.runs().single()
        assertEquals(written.id, read.id)
        assertEquals(written.comparabilityKey, read.comparabilityKey)
        assertEquals(BOARD, read.rows)
        assertTrue(read.lastSnakeMustBeMoving)
        assertEquals("FIXED", read.openings)
        assertEquals(listOf("space:budget=0", "wallhug:budget=0"), read.contestants)
    }

    @Test
    fun `an entrant is recorded in full, so a moved default cannot rewrite history`() {
        // `uct` and `uct:exploration=3.0` are the same bot today. `uct:exploration=5.0` was that same
        // bot until a sweep moved the default -- which is the event this guards, and it has now
        // happened once. The log holds the long form so a line means the same thing forever.
        val spec = expandedSpec(Contestant(BotId("uct")), ShippedBots, budgetPerTurn = 1_000)

        assertContains(spec, "uct:budget=1000")
        assertContains(spec, "exploration=3.0")
        assertContains(spec, "maxNodes=")
        assertContains(spec, "rolloutDepth=")
    }

    @Test
    fun `an entrant's own settings win over the batch's`() {
        val tuned = Contestant(BotId("uct"), budgetPerTurn = 4_000, params = BotParams(mapOf("exploration" to "2.5")))

        val spec = expandedSpec(tuned, ShippedBots, budgetPerTurn = 1_000)

        assertContains(spec, "budget=4000")
        assertContains(spec, "exploration=2.5")
    }

    @Test
    fun `two batches append to one log rather than replacing it`() {
        val directory = Files.createTempDirectory("snakewarz-log")
        val log = MatchLog(directory)
        val config = configOf(listOf(Contestant(BotId("space")), Contestant(BotId("wallhug"))), rounds = 2)

        repeat(2) {
            recordBatch(log, Arena(config, ShippedBots, threads = 1).run(), ShippedBots, "MIRRORED", 1, Replays.NONE)
        }

        assertEquals(2, log.runs().size)
        assertEquals(4, log.matches().size)
        assertEquals(2, log.runs().map { it.id }.toSet().size, "two runs started apart get two ids")
    }

    @Test
    fun `replays are kept apart and only for the matches asked for`() {
        val directory = Files.createTempDirectory("snakewarz-log")
        val config = configOf(listOf(Contestant(BotId("space")), Contestant(BotId("wallhug"))), rounds = 2)
        val batch = Arena(config, ShippedBots, threads = 1, keepRecords = true).run()

        val log = MatchLog(directory)
        val header = recordBatch(log, batch, ShippedBots, "MIRRORED", 1, Replays.ALL)

        for (report in batch.reports) {
            assertNotNull(log.replay(header.id, report.index), "match ${report.index}")
        }
        assertNull(log.replay(header.id, 99))
        assertTrue(directory.resolve("replays.tsv").toFile().exists())
    }

    @Test
    fun `a logged replay decodes and replays the match it was taken from`() {
        // What makes a diagnosed loss watchable: the link in a report has to open the game the
        // numbers describe. A drawn opening travels in the record's header, so this also pins that
        // varying the spawns did not put the batch outside what a replay can express.
        val directory = Files.createTempDirectory("snakewarz-log")
        val config = configOf(listOf(Contestant(BotId("space")), Contestant(BotId("wallhug"))), rounds = 2)
        val batch = Arena(config, ShippedBots, Openings.MIRRORED, threads = 1, keepRecords = true).run()

        val log = MatchLog(directory)
        val header = recordBatch(log, batch, ShippedBots, "MIRRORED", 1, Replays.ALL)

        for (report in batch.reports) {
            val payload = assertNotNull(log.replay(header.id, report.index))
            val decoded = ReplayCodec.decode(payload)

            assertEquals(report.stats.setup, decoded.setup, "match ${report.index}")
            assertTrue(decoded.verify(ShippedBots).matches, "match ${report.index} did not replay")
        }
    }

    @Test
    fun `a torn final line is dropped rather than read as data`() {
        // What a batch killed overnight leaves behind. Repeating a match's columns on every seat is
        // exactly so the rest of the file survives it.
        val directory = Files.createTempDirectory("snakewarz-log")
        val config = configOf(listOf(Contestant(BotId("space")), Contestant(BotId("wallhug"))), rounds = 2)
        val log = MatchLog(directory)
        recordBatch(log, Arena(config, ShippedBots, threads = 1).run(), ShippedBots, "MIRRORED", 1, Replays.NONE)

        val matches = directory.resolve("matches-v2.tsv")
        Files.writeString(matches, matches.readLines().dropLast(1).joinToString("\n", postfix = "\n7\t3\tt"))

        assertEquals(1, log.matches().size, "the intact match survives its neighbour being torn")
    }

    @Test
    fun `the match schema before opening identity remains readable`() {
        val directory = Files.createTempDirectory("snakewarz-log")
        val config = configOf(listOf(Contestant(BotId("space")), Contestant(BotId("wallhug"))), rounds = 2)
        val log = MatchLog(directory)
        recordBatch(log, Arena(config, ShippedBots, threads = 1).run(), ShippedBots, "MIRRORED", 1, Replays.NONE)

        val current = directory.resolve("matches-v2.tsv")
        val legacy = current.readLines().joinToString("\n", postfix = "\n") { line ->
            line.split('\t').dropLast(1).joinToString("\t")
        }
        Files.writeString(directory.resolve("matches.tsv"), legacy)
        Files.delete(current)

        val read = log.matches()
        assertEquals(2, read.size)
        assertTrue(read.all { it.openingIdentity == null })
    }

    @Test
    fun `complete opening identity survives the match log`() {
        val directory = Files.createTempDirectory("snakewarz-log")
        val config = TournamentConfig(
            contestants = listOf(Contestant(BotId("space")), Contestant(BotId("wallhug"))),
            rows = Openings.COMPLETE_ROWS,
            cols = Openings.COMPLETE_COLS,
            rounds = Openings.COMPLETE_ROUNDS_PER_REPLICATION,
            budgetPerTurn = 0,
        )
        val batch = Arena(config, ShippedBots, Openings.COMPLETE, threads = 2).run()
        val log = MatchLog(directory)

        recordBatch(log, batch, ShippedBots, "COMPLETE", 2, Replays.NONE)

        assertEquals(
            batch.reports.map { it.openingIdentity },
            log.matches().map { it.openingIdentity },
        )
    }

    @Test
    fun `a field that would corrupt the file is refused rather than written`() {
        val directory = Files.createTempDirectory("snakewarz-log")
        val log = MatchLog(directory)
        val header = RunHeader.of(
            configOf(listOf(Contestant(BotId("space")), Contestant(BotId("wallhug"))), rounds = 2),
            ShippedBots,
            openings = "MIRR\tORED",
            threads = 1,
        )

        assertFailsWith<IllegalArgumentException> { log.append(header, emptyList(), emptyMap()) }
    }

    @Test
    fun `an empty log reads as nothing rather than failing`() {
        val log = MatchLog(Files.createTempDirectory("snakewarz-log").resolve("never-written"))

        assertEquals(emptyList(), log.runs())
        assertEquals(emptyList(), log.matches())
        assertNull(log.replay("nobody", 0))
    }

    @Test
    fun `the first run schema keeps its original ending rule`() {
        val directory = Files.createTempDirectory("snakewarz-log")
        Files.writeString(
            directory.resolve("runs.tsv"),
            "run\tstartedAt\tbuild\tformat\trows\tcols\tgrowEveryNthMove\tmaxTurns\t" +
                "budgetPerTurn\trounds\tseed\topenings\tthreads\tmap\tcontestants\n" +
                "old\t2026-07-31T00:00:00Z\tdeadbee\tHEAD_TO_HEAD\t9\t9\t2\t4096\t0\t2\t1\t" +
                "MIRRORED\t1\tempty\tspace:budget=0 wallhug:budget=0\n",
        )

        val run = MatchLog(directory).runs().single()

        assertFalse(run.lastSnakeMustBeMoving)
    }

    private fun configOf(contestants: List<Contestant>, rounds: Int): TournamentConfig = TournamentConfig(
        contestants = contestants,
        rows = BOARD,
        cols = BOARD,
        rounds = rounds,
        budgetPerTurn = 0,
    )

    private companion object {
        const val BOARD = 9
    }
}
