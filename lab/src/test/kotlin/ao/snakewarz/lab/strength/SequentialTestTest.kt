package ao.snakewarz.lab.strength

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.match.tournament.Contestant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The block loop, and the one thing about it that cannot be checked by reading a result.
 *
 * A sequential test estimates its own variance from the sample that decides it, so a block that
 * replayed the previous block's boards would add matches without adding evidence and there is
 * nothing in the output that would look wrong. It has to be pinned here or not at all.
 */
class SequentialTestTest {
    @Test
    fun `every block moves on to boards the last one did not play`() {
        val seeds = mutableListOf<Long>()
        val outcome = testOf(blockPairs = 20, maxPairs = 60).run(ShippedBots) { batch, _ ->
            seeds += batch.config.seed
        }

        assertTrue(seeds.size >= 2, "a run this even should have needed more than one block")
        assertEquals(seeds, seeds.distinct(), "two blocks drew the same boards")
        for (block in 1 until seeds.size) {
            assertEquals(seeds[block - 1] + 20, seeds[block], "block $block did not start where the last stopped")
        }
        assertEquals(outcome.boards, seeds.size * 20)
    }

    @Test
    fun `it stops at the ceiling and says that is why`() {
        // Two spellings of the same bot never separate, so this can only end at the cap.
        val outcome = testOf(blockPairs = 20, maxPairs = 40).run(ShippedBots)

        assertEquals(40, outcome.boards)
        assertTrue(outcome.cappedOut || outcome.report.verdict == Sprt.Verdict.NO_BETTER)
        assertEquals(outcome.boards, outcome.splits, "identical entrants split every mirrored board")
        assertEquals(0, outcome.forfeits)
        assertTrue(outcome.distinct <= outcome.matches)
    }

    @Test
    fun `a candidate that is the baseline is refused, because that test never settles`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SequentialTest(
                baseline = Contestant(BotId("pressure")),
                candidate = Contestant(BotId("pressure")),
                rows = 8,
                cols = 8,
                seed = 1L,
                budgetPerTurn = 0,
                openings = Openings.MIRRORED,
                threads = 1,
                sprt = Sprt(elo0 = 0.0, elo1 = 5.0, alpha = 0.05, beta = 0.05),
                blockPairs = 20,
                maxPairs = 40,
            )
        }

        assertContains(failure.message.orEmpty(), "same entrant")
    }

    /** A bot against a re-spelling of itself: `adjacencyFloor=0.05` is the declared default. */
    private fun testOf(blockPairs: Int, maxPairs: Int) = SequentialTest(
        baseline = Contestant(BotId("pressure")),
        candidate = Contestant(BotId("pressure"), params = BotParams(mapOf("adjacencyFloor" to "0.05"))),
        rows = 8,
        cols = 8,
        seed = 1L,
        budgetPerTurn = 0,
        openings = Openings.MIRRORED,
        threads = 1,
        sprt = Sprt(elo0 = 0.0, elo1 = 5.0, alpha = 0.05, beta = 0.05),
        blockPairs = blockPairs,
        maxPairs = maxPairs,
    )
}
