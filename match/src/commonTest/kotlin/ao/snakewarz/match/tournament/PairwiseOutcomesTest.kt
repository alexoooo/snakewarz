package ao.snakewarz.match.tournament

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.stats.MatchStats
import ao.snakewarz.match.stats.SlotStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairwiseOutcomesTest {
    @Test
    fun `head to head asks the engine who won`() {
        val settled = pairwiseOutcomes(
            TournamentFormat.HEAD_TO_HEAD,
            statsOf(
                MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING),
                slot(0, alive = false, movesMade = 40, fate = EliminationReason.TRAPPED),
                slot(1, alive = true, movesMade = 12),
            ),
        )

        assertEquals(1, settled.size)
        assertEquals(SnakeId(1), settled[0].winner)
        assertEquals(SnakeId(0), settled[0].loser)
    }

    @Test
    fun `head to head trusts the outcome over the move counts`() {
        // Slot 0 made more moves and still lost, which is exactly what happens when the loser acts
        // first: it survives one more of its own moves than the winner does before dying. Scoring
        // this by movesMade would hand the match to the wrong contestant.
        val settled = pairwiseOutcomes(
            TournamentFormat.HEAD_TO_HEAD,
            statsOf(
                MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING),
                slot(0, alive = false, movesMade = 31, fate = EliminationReason.SUICIDE),
                slot(1, alive = true, movesMade = 30),
            ),
        )

        assertEquals(SnakeId(1), settled.single().winner)
    }

    @Test
    fun `a drawn match is a draw for both`() {
        val settled = pairwiseOutcomes(
            TournamentFormat.HEAD_TO_HEAD,
            statsOf(
                MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT),
                slot(0, alive = true, movesMade = 50),
                slot(1, alive = true, movesMade = 50),
            ),
        )

        assertTrue(settled.single().isDraw)
        assertEquals(SnakeId.NONE, settled.single().loser)
    }

    @Test
    fun `head to head is a statement about a pair`() {
        assertFailsWith<IllegalArgumentException> {
            pairwiseOutcomes(
                TournamentFormat.HEAD_TO_HEAD,
                statsOf(
                    MatchOutcome(SnakeId(0), MatchEnd.LAST_SNAKE_STANDING),
                    slot(0, alive = true, movesMade = 9),
                    slot(1, alive = false, movesMade = 4, fate = EliminationReason.TRAPPED),
                    slot(2, alive = false, movesMade = 4, fate = EliminationReason.TRAPPED),
                ),
            )
        }
    }

    @Test
    fun `a free-for-all compares every pair by who outlasted whom`() {
        val settled = pairwiseOutcomes(
            TournamentFormat.FREE_FOR_ALL,
            statsOf(
                MatchOutcome(SnakeId(0), MatchEnd.LAST_SNAKE_STANDING),
                slot(0, alive = true, movesMade = 20),
                slot(1, alive = false, movesMade = 14, fate = EliminationReason.TRAPPED),
                slot(2, alive = false, movesMade = 6, fate = EliminationReason.SUICIDE),
            ),
        )

        assertEquals(3, settled.size, "three snakes settle three comparisons")
        assertEquals(SnakeId(0), settled[0].winner, "the survivor outlasted the field")
        assertEquals(SnakeId(0), settled[1].winner)
        assertEquals(SnakeId(1), settled[2].winner, "and the two dead are ranked by how long they lasted")
    }

    @Test
    fun `snakes that went out together drew, and survivors drew among themselves`() {
        val settled = pairwiseOutcomes(
            TournamentFormat.FREE_FOR_ALL,
            statsOf(
                MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT),
                slot(0, alive = true, movesMade = 30),
                slot(1, alive = true, movesMade = 30),
                slot(2, alive = false, movesMade = 11, fate = EliminationReason.TRAPPED),
                slot(3, alive = false, movesMade = 11, fate = EliminationReason.TRAPPED),
            ),
        )

        assertEquals(6, settled.size)
        assertTrue(between(settled, 0, 1).isDraw, "both survived")
        assertTrue(between(settled, 2, 3).isDraw, "both went out on the same move")
        assertEquals(SnakeId(0), between(settled, 0, 2).winner)
    }

    @Test
    fun `a match still being played has settled nothing`() {
        assertFailsWith<IllegalArgumentException> {
            pairwiseOutcomes(
                TournamentFormat.HEAD_TO_HEAD,
                statsOf(null, slot(0, alive = true, movesMade = 3), slot(1, alive = true, movesMade = 3)),
            )
        }
    }

    @Test
    fun `a comparison names two different slots`() {
        assertFailsWith<IllegalArgumentException> { PairwiseOutcome(SnakeId(0), SnakeId(0), SnakeId(0)) }
        assertFailsWith<IllegalArgumentException> { PairwiseOutcome(SnakeId(0), SnakeId.NONE, SnakeId(0)) }
        assertFailsWith<IllegalArgumentException>("a slot that was not compared cannot have won") {
            PairwiseOutcome(SnakeId(0), SnakeId(1), SnakeId(2))
        }
    }

    private fun between(settled: List<PairwiseOutcome>, one: Int, other: Int): PairwiseOutcome =
        settled.first { it.one == SnakeId(one) && it.other == SnakeId(other) }

    private fun slot(
        index: Int,
        alive: Boolean,
        movesMade: Int,
        fate: EliminationReason? = null,
    ): SlotStats = SlotStats(
        slot = SnakeId(index),
        bot = BotId("cycle"),
        length = movesMade / 2 + 1,
        movesMade = movesMade,
        alive = alive,
        fate = fate,
        winner = false,
    )

    private fun statsOf(outcome: MatchOutcome?, vararg slots: SlotStats): MatchStats = MatchStats(
        setup = MatchSetup.create(
            rows = 9,
            cols = 9,
            slots = slots.map { it.bot },
            seed = 1,
            rules = RulesConfig(),
            budgetPerTurn = 0,
        ),
        turnsPlayed = slots.sumOf { it.movesMade },
        outcome = outcome,
        slots = slots.toList(),
    )
}
