package ao.snakewarz.lab.allowance

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.match.tournament.Contestant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AllowanceCurveReadCommandTest {
    @Test
    fun `a retained curve renders identically without replaying or appending`() {
        val directory = Files.createTempDirectory("snakewarz-allowance-read-")
        try {
            val plan = plan()
            val liveLines = mutableListOf<String>()
            AllowanceCurveCommand(plan, directory).run(ShippedBots, liveLines::add)
            val reportStart = liveLines.indexOfFirst { it.startsWith("[allowance] empty 8x8 complete population") }
            val reportEnd = liveLines.indexOfFirst { it.startsWith("[allowance] match summaries retained") }
            assertTrue(reportStart >= 0 && reportEnd > reportStart, liveLines.joinToString("\n"))
            val expected = liveLines.subList(reportStart, reportEnd)
            val before = snapshot(directory)

            val reread = mutableListOf<String>()
            AllowanceCurveReadCommand(plan, directory).run(ShippedBots, reread::add)

            assertEquals(expected, reread)
            assertEquals(before, snapshot(directory), "a read command must not append to its evidence")

            val matchLog = MatchLog(directory)
            matchLog.append(extraRun(matchLog.runs().first()), emptyList(), emptyMap())
            val error = assertFailsWith<IllegalArgumentException> {
                AllowanceCurveReadCommand(plan, directory).run(ShippedBots) {}
            }
            assertTrue(error.message.orEmpty().contains("exactly the variant-by-panel runs"), error.message)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun plan(): AllowanceCurvePlan = AllowanceCurvePlan(
        variants = listOf(
            Contestant(BotId("uct"), 0),
            Contestant(BotId("uct"), 1),
        ),
        panel = listOf(Contestant(BotId("chase"), 0)),
        replications = 1,
        seed = 91_001L,
        threads = 2,
    )

    private fun snapshot(directory: Path): Map<String, List<Byte>> =
        Files.newDirectoryStream(directory).use { files ->
            files.map { file -> file.fileName.toString() to Files.readAllBytes(file).toList() }
                .sortedBy { it.first }
                .toMap()
        }

    private fun extraRun(source: RunHeader): RunHeader = RunHeader(
        id = "zz-extra-run",
        startedAt = source.startedAt,
        build = source.build,
        format = source.format,
        rows = source.rows,
        cols = source.cols,
        growEveryNthMove = source.growEveryNthMove,
        maxTurns = source.maxTurns,
        lastSnakeMustBeMoving = source.lastSnakeMustBeMoving,
        budgetPerTurn = source.budgetPerTurn,
        rounds = source.rounds,
        seed = source.seed,
        openings = source.openings,
        threads = source.threads,
        map = source.map,
        contestants = source.contestants,
    )
}
