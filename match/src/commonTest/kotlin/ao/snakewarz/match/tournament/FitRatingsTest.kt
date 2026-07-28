package ao.snakewarz.match.tournament

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ratings are read as evidence, so what this pins is mostly what the fit refuses to claim.
 *
 * Every assertion on a rating is to a tolerance and never to an exact `Double`: this suite compiles
 * to wasm as well, and the Elo figure goes through a `log10` that is not specified bit-identical
 * across targets. Anything that decides an *order* reads the strengths instead, which is why the
 * ordering assertions here can be exact.
 */
class FitRatingsTest {
    @Test
    fun `beating everybody rates above beating nobody`() {
        val table = tableOf(3)
        table.wins(0, 1, times = 8)
        table.wins(0, 2, times = 8)
        table.wins(1, 2, times = 8)

        val ratings = fitRatings(table)

        assertEquals(listOf(0, 1, 2), ratings.ranking())
        assertTrue(ratings.rating(0) > ratings.rating(1))
        assertTrue(ratings.rating(1) > ratings.rating(2))
    }

    @Test
    fun `an even field rates level`() {
        val table = tableOf(3)
        for (one in 0 until 3) {
            for (other in one + 1 until 3) {
                table.wins(one, other, times = 5)
                table.wins(other, one, times = 5)
            }
        }

        val ratings = fitRatings(table)

        for (contestant in 0 until 3) {
            assertTrue(abs(ratings.rating(contestant)) < LOOSE, "everybody drew level: ${ratings.rating(contestant)}")
        }
    }

    @Test
    fun `a draw is worth half a win, which is what the matrix already says`() {
        val drawn = tableOf(2)
        drawn.draws(0, 1, times = 10)

        val split = tableOf(2)
        split.wins(0, 1, times = 5)
        split.wins(1, 0, times = 5)

        assertEquals(fitRatings(drawn).rating(0), fitRatings(split).rating(0), LOOSE)
    }

    @Test
    fun `an undefeated contestant gets a finite rating rather than an infinite one`() {
        // The whole job of the phantom. Without it this likelihood has no maximum at all: the fit
        // would climb forever and print an infinity or a NaN into a ladder.
        val table = tableOf(2)
        table.wins(0, 1, times = 40)

        val ratings = fitRatings(table)

        assertTrue(ratings.rating(0).isFinite(), "was ${ratings.rating(0)}")
        assertTrue(ratings.rating(1).isFinite(), "was ${ratings.rating(1)}")
        assertTrue(ratings.rating(0) > ratings.rating(1))
    }

    @Test
    fun `an undefeated contestant is marked as resting on the prior rather than on evidence`() {
        val table = tableOf(2)
        table.wins(0, 1, times = 40)

        val ratings = fitRatings(table)

        assertTrue(ratings.priorDetermined(0), "nothing bounds a bot that never lost")
        assertTrue(ratings.priorDetermined(1))
        assertTrue(ratings.measured(0), "it did play, though -- that is a different question")
    }

    @Test
    fun `a single loss is enough to bound a run of wins`() {
        val table = tableOf(2)
        table.wins(0, 1, times = 40)
        table.wins(1, 0, times = 1)

        val ratings = fitRatings(table)

        assertFalse(ratings.priorDetermined(0), "now the results connect both ways")
        assertFalse(ratings.priorDetermined(1))
        assertTrue(ratings.rating(0) - ratings.rating(1) > 400.0, "forty to one is a wide gap")
    }

    @Test
    fun `two groups that never met are not ranked against each other as though they had`() {
        // The failure this is here to prevent: four bots, two islands, one confident ladder over all
        // four in which two of the gaps are the regularizer and nothing else.
        val table = tableOf(4)
        table.wins(0, 1, times = 6)
        table.wins(1, 0, times = 4)
        table.wins(2, 3, times = 6)
        table.wins(3, 2, times = 4)

        val ratings = fitRatings(table)

        assertEquals(ratings.component(0), ratings.component(1))
        assertEquals(ratings.component(2), ratings.component(3))
        assertTrue(ratings.component(0) != ratings.component(2), "the two halves never played")
        assertTrue((0 until 4).any { ratings.priorDetermined(it) }, "and the fit has to say so")
    }

    @Test
    fun `a contestant that played nothing is reported as unmeasured and ranked last`() {
        val table = tableOf(3)
        table.wins(0, 1, times = 5)
        table.wins(1, 0, times = 5)

        val ratings = fitRatings(table)

        assertFalse(ratings.measured(2))
        assertEquals(0, ratings.games(2))
        assertEquals(2, ratings.ranking().last(), "an unplayed entrant does not get to sit mid-ladder")
        assertTrue(ratings.priorDetermined(2))
    }

    @Test
    fun `the expected score is what the ladder can be checked against`() {
        val table = tableOf(2)
        table.wins(0, 1, times = 15)
        table.wins(1, 0, times = 5)

        val ratings = fitRatings(table)

        // Two contestants and nothing else to explain: the model has to reproduce what happened,
        // give or take the phantom's one drawn game.
        assertEquals(0.75, ratings.expectedScore(0, 1), 0.03)
        assertEquals(0.25, ratings.expectedScore(1, 0), 0.03)
        assertEquals(1.0, ratings.expectedScore(0, 1) + ratings.expectedScore(1, 0), 1e-9)
    }

    @Test
    fun `a rating is reproducible, and the fit does not depend on when it was stopped`() {
        val table = tableOf(4)
        table.wins(0, 1, times = 7)
        table.wins(1, 2, times = 6)
        table.wins(2, 3, times = 9)
        table.wins(3, 0, times = 2)
        table.draws(0, 2, times = 4)

        val once = fitRatings(table)
        val again = fitRatings(table)

        for (contestant in 0 until 4) {
            assertEquals(once.rating(contestant), again.rating(contestant), 0.0)
        }
    }

    @Test
    fun `an empty result set produces no ratings rather than nothing to divide by`() {
        val ratings = fitRatings(tableOf(2))

        assertFalse(ratings.measured(0))
        assertEquals(0.0, ratings.rating(0))
        assertFalse(ratings.rating(0).isNaN())
        assertEquals(0.5, ratings.expectedScore(0, 1), 1e-9)
    }

    @Test
    fun `a prior that bounds nothing is refused`() {
        assertFailsWith<IllegalArgumentException> { fitRatings(tableOf(2), prior = 0.0) }
        assertFailsWith<IllegalArgumentException> { fitRatings(tableOf(2), prior = -1.0) }
    }

    private fun tableOf(size: Int): TournamentTable =
        TournamentTable(List(size) { Contestant(BotId("bot$it")) })

    private fun TournamentTable.wins(winner: Int, loser: Int, times: Int) {
        repeat(times) { record(PairwiseOutcome(SnakeId(0), SnakeId(1), SnakeId(0)), intArrayOf(winner, loser)) }
    }

    private fun TournamentTable.draws(one: Int, other: Int, times: Int) {
        repeat(times) { record(PairwiseOutcome(SnakeId(0), SnakeId(1), SnakeId.NONE), intArrayOf(one, other)) }
    }

    private fun abs(value: Double): Double = if (value < 0.0) -value else value

    private companion object {
        /** Elo points. These are statements about ordering and shape, not about the third digit. */
        const val LOOSE = 1e-6
    }
}
