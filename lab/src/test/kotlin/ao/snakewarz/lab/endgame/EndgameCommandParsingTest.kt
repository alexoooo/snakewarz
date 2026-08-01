package ao.snakewarz.lab.endgame

import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.LabCommand
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class EndgameCommandParsingTest {
    @Test
    fun `solve endgame accepts its bounded default program`() {
        val command = LabCommand.of(
            listOf("solve-endgame", "--log", ".lab/finalists", "--champion", "chase"),
            ShippedBots,
        )

        assertIs<EndgameCommand>(command)
    }

    @Test
    fun `solve endgame accepts explicit thresholds and wholesale caps`() {
        val command = LabCommand.of(
            listOf(
                "solve-endgame",
                "--log",
                ".lab/finalists",
                "--champion",
                "chase",
                "--thresholds",
                "2,4,8",
                "--positions-per-threshold",
                "3",
                "--seed",
                "17",
                "--max-nodes-per-position",
                "100",
                "--max-total-nodes",
                "1000",
                "--memory-mib",
                "16",
                "--max-seconds",
                "20",
            ),
            ShippedBots,
        )

        assertIs<EndgameCommand>(command)
    }

    @Test
    fun `solve endgame refuses ambiguous corpus and threshold inputs`() {
        assertFailsWith<IllegalStateException> {
            LabCommand.of(listOf("solve-endgame", "--log", ".lab/finalists"), ShippedBots)
        }
        assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                listOf("solve-endgame", "--log", "none", "--champion", "chase"),
                ShippedBots,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                listOf(
                    "solve-endgame",
                    "--log",
                    ".lab/finalists",
                    "--champion",
                    "chase",
                    "--thresholds",
                    "4,4",
                ),
                ShippedBots,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                listOf(
                    "solve-endgame",
                    "--log",
                    ".lab/finalists",
                    "--champion",
                    "chase",
                    "--max-total-nodes",
                    "0",
                ),
                ShippedBots,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                listOf(
                    "solve-endgame",
                    "--log",
                    ".lab/finalists",
                    "--champion",
                    "chase",
                    "--thresholds",
                    "63",
                ),
                ShippedBots,
            )
        }
    }
}
