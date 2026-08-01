package ao.snakewarz.lab.championship

import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.LabCommand
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ChampionshipCommandParsingTest {
    @Test
    fun `championship accepts finalists incumbent costs and one retained log`() {
        val command = LabCommand.of(
            listOf(
                "championship",
                "uct:budget=1600",
                "alphabeta:budget=1000,eval=chamber",
                "--incumbent",
                "alphabeta:budget=1000,eval=chamber",
                "--costs",
                "9.2,10.8",
                "--log",
                ".lab/p5-finalists",
            ),
            ShippedBots,
        )

        assertIs<ChampionshipCommand>(command)
    }

    @Test
    fun `championship refuses incomplete or invalid independent costs`() {
        assertFailsWith<IllegalStateException> {
            LabCommand.of(
                listOf("championship", "uct", "alphabeta", "--costs", "1.0,2.0"),
                ShippedBots,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                listOf(
                    "championship",
                    "uct",
                    "alphabeta",
                    "--incumbent",
                    "alphabeta",
                    "--costs",
                    "1.0",
                ),
                ShippedBots,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LabCommand.of(
                listOf(
                    "championship",
                    "uct",
                    "alphabeta",
                    "--incumbent",
                    "alphabeta",
                    "--costs",
                    "1.0,NaN",
                ),
                ShippedBots,
            )
        }
    }
}
