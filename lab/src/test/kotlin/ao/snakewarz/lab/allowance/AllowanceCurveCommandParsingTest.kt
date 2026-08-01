package ao.snakewarz.lab.allowance

import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.LabCommand
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AllowanceCurveCommandParsingTest {
    @Test
    fun `allowance accepts fixed variants and a semicolon separated panel`() {
        val command = LabCommand.of(
            listOf(
                "allowance",
                "uct:budget=800",
                "uct:budget=1600",
                "--panel",
                "puct:budget=2000,eval=territory;alphabeta:budget=1000,eval=chamber",
                "--replications",
                "3",
                "--seed",
                "91001",
                "--threads",
                "2",
                "--log",
                ".lab/p5-curve-uct",
            ),
            ShippedBots,
        )

        assertIs<AllowanceCurveCommand>(command)
    }

    @Test
    fun `allowance report accepts the same plan without replaying it`() {
        val command = LabCommand.of(
            listOf(
                "allowance-report",
                "uct:budget=800",
                "uct:budget=1600",
                "--panel",
                "puct:budget=2000,eval=territory",
                "--replications",
                "3",
                "--seed",
                "91001",
                "--log",
                ".lab/p5-curve-uct",
            ),
            ShippedBots,
        )

        assertIs<AllowanceCurveReadCommand>(command)
    }

    @Test
    fun `allowance requires explicit disjoint fixed budgets and retained output`() {
        assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                listOf(
                    "allowance",
                    "uct:budget=800",
                    "uct:budget=1600",
                    "--panel",
                    "puct:budget=2000;uct:budget=800",
                ),
                ShippedBots,
            )
        }
        assertFailsWith<IllegalStateException> {
            LabCommand.of(
                listOf(
                    "allowance",
                    "uct:budget=800",
                    "uct:budget=1600",
                    "--panel",
                    "puct:budget=2000",
                    "--log",
                    "none",
                ),
                ShippedBots,
            )
        }
    }
}
