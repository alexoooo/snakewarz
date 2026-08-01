package ao.snakewarz.lab.policy

import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.reactive.policy.PolicyResearch
import ao.snakewarz.lab.LabCommand
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PolicyCommandTest {
    @Test
    fun `policy requires one log fixed expert and positive sample`() {
        val registry = PolicyLabRegistry(ShippedBots)

        assertContains(
            assertFailsWith<IllegalStateException> {
                LabCommand.of("policy --expert wallhug:budget=0".split(' '), registry)
            }.message.orEmpty(),
            "--log",
        )
        assertContains(
            assertFailsWith<IllegalStateException> {
                LabCommand.of("policy --log somewhere".split(' '), registry)
            }.message.orEmpty(),
            "--expert",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                LabCommand.of("policy --log somewhere --expert wallhug".split(' '), registry)
            }.message.orEmpty(),
            "budget=N",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                LabCommand.of("policy --log somewhere --expert wallhug:budget=0 --positions 0".split(' '), registry)
            }.message.orEmpty(),
            "positive",
        )
    }

    @Test
    fun `policy reports every case in phase order with one denominator per phase`() {
        val directory = Files.createTempDirectory("snakewarz-policy")
        val registry = PolicyLabRegistry(ShippedBots)
        val play = LabCommand.of(
            "play space wallhug --rows 8 --cols 8 --rounds 4 --budget 0 --replays all".split(' ') +
                listOf("--log", directory.toString(), "--threads", "1"),
            registry,
        )
        play.run(registry) {}

        val command = LabCommand.of(
            listOf(
                "policy",
                "--log",
                directory.toString(),
                "--expert",
                "wallhug:budget=0",
                "--positions",
                "2",
                "--seed",
                "72001",
            ),
            registry,
        ) as PolicyCommand
        assertEquals(2, command.positionsPerPhase)
        assertEquals(72_001L, command.seed)

        val lines = mutableListOf<String>()
        command.run(registry, lines::add)

        assertTrue(lines.any { it.contains("matches=4 unreadable=0") }, lines.toString())
        assertTrue(lines.any { it.contains("forced=") && it.contains("median-fill=") }, lines.toString())

        val caseLines = lines.filter { it.startsWith("[policy] phase=") && " case=" in it }
        val expectedOrder = PolicyPhase.entries.flatMap { phase ->
            PolicyResearch.cases.map { candidate -> "${phase.label}:${candidate.key}" }
        }
        val actualOrder = caseLines.map { line ->
            "${line.substringAfter("phase=").substringBefore(' ')}:" +
                line.substringAfter("case=").substringBefore(' ')
        }
        assertEquals(expectedOrder, actualOrder)

        for (phase in PolicyPhase.entries) {
            val phaseLine = lines.single {
                it.startsWith("[policy] phase=${phase.label} ") && " case=" !in it
            }
            val selected = phaseLine.substringAfter("selected=").substringBefore(' ').toInt()
            for (line in caseLines.filter { "phase=${phase.label} " in it }) {
                assertEquals(selected, denominator(line, "tie="), line)
                assertEquals(selected, denominator(line, "top1="), line)
                assertEquals(selected, denominator(line, "ceiling="), line)
            }
        }
    }

    @Test
    fun `policy rejects an accumulated same-board directory`() {
        val directory = Files.createTempDirectory("snakewarz-policy-mixed")
        val registry = PolicyLabRegistry(ShippedBots)
        for (seed in 1L..2L) {
            val play = LabCommand.of(
                "play space wallhug --rows 8 --cols 8 --rounds 2 --budget 0 --replays all".split(' ') +
                    listOf("--seed", seed.toString(), "--log", directory.toString(), "--threads", "1"),
                registry,
            )
            play.run(registry) {}
        }

        val command = LabCommand.of(
            listOf(
                "policy",
                "--log",
                directory.toString(),
                "--expert",
                "wallhug:budget=0",
            ),
            registry,
        )
        val failure = assertFailsWith<IllegalArgumentException> { command.run(registry) {} }

        assertContains(failure.message.orEmpty(), "one dedicated P1 run")
    }

    private fun denominator(line: String, label: String): Int =
        line.substringAfter(label).substringBefore(' ').substringAfter('/').toInt()
}
