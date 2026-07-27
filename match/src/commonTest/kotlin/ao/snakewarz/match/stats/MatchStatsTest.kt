package ao.snakewarz.match.stats

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.Match
import ao.snakewarz.match.matchInOrder
import ao.snakewarz.match.matchOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MatchStatsTest {
    @Test
    fun `an unplayed match reports the opening position`() {
        val stats = matchInOrder(5, 5, "cycle", "south").stats()

        assertEquals(0, stats.turnsPlayed)
        assertEquals(0, stats.rounds)
        assertEquals(2, stats.survivors)
        assertNull(stats.outcome)
        assertNull(stats.winner)
        assertFalse(stats.finished)

        assertEquals(25, stats.playableCells)
        assertEquals(2, stats.occupiedCells)
        assertEquals(2.0 / 25, stats.fillRate)

        for (slot in stats.slots) {
            assertEquals(1, slot.length)
            assertEquals(0, slot.movesMade)
            assertTrue(slot.alive)
            assertNull(slot.fate)
            assertFalse(slot.winner)
        }
    }

    @Test
    fun `a slot carries the bot that is playing it, not just its index`() {
        val stats = matchInOrder(5, 5, "cycle", "south").stats()

        assertEquals(BotId("cycle"), stats.of(SnakeId(0)).bot)
        assertEquals(BotId("south"), stats.of(SnakeId(1)).bot)
        assertSame(stats.slots[1], stats.of(SnakeId(1)))
    }

    @Test
    fun `length follows the half-speed growth rule rather than the move count`() {
        val match = matchInOrder(9, 9, "cycle")

        // 1, 1, 2, 2, 3 -- the tail only retracts on alternating turns, so a snake that has moved
        // four times is three squares long and not five.
        val lengths = ArrayList<Int>()
        lengths.add(match.stats().slots[0].length)
        repeat(4) {
            match.step()
            lengths.add(match.stats().slots[0].length)
        }

        assertEquals(listOf(1, 1, 2, 2, 3), lengths)
        assertEquals(4, match.stats().slots[0].movesMade)
        assertEquals(4, match.stats().rounds)
    }

    @Test
    fun `a resignation shows up as a fate, and the body still holds ground`() {
        val match = matchInOrder(5, 5, "cycle", "quitter")

        match.step() // cycle moves
        match.step() // quitter resigns, which ends the match

        val stats = match.stats()
        assertEquals(2, stats.turnsPlayed)
        assertEquals(1, stats.survivors)
        assertEquals(MatchEnd.LAST_SNAKE_STANDING, stats.outcome?.end)

        val quitter = stats.of(SnakeId(1))
        assertFalse(quitter.alive)
        assertEquals(EliminationReason.RESIGNED, quitter.fate)
        assertEquals(0, quitter.movesMade)
        // Out of the match, still on the board: a corpse is an obstacle.
        assertEquals(1, quitter.length)
        assertEquals(2, stats.occupiedCells)

        assertSame(stats.of(SnakeId(0)), stats.winner)
        assertTrue(stats.of(SnakeId(0)).winner)
        assertFalse(quitter.winner)
    }

    @Test
    fun `a fatal move is not counted as a move survived`() {
        // SOUTH from the spawn in the top-left corner is fine; the bot that plays NORTH walks into
        // the wall on its very first turn.
        val match = matchInOrder(5, 5, "north", "cycle")
        match.step()

        val stats = match.stats()
        val dead = stats.of(SnakeId(0))
        assertEquals(0, dead.movesMade, "the move that killed it never landed")
        assertEquals(EliminationReason.SUICIDE, dead.fate)
    }

    @Test
    fun `a draw has an outcome and no winner`() {
        // Solo, on a board with nowhere to go: the one snake is trapped on its first turn, and with
        // nobody to crown the match ends ALL_ELIMINATED rather than with a survivor.
        val match = matchInOrder(1, 1, "north")
        match.runToCompletion()

        val stats = match.stats()
        assertEquals(MatchEnd.ALL_ELIMINATED, stats.outcome?.end)
        assertEquals(0, stats.survivors)
        assertNull(stats.winner)
    }

    @Test
    fun `the longest snake is reported even when it lost`() {
        val match = matchInOrder(5, 5, "cycle", "quitter")
        repeat(2) { match.step() }

        val stats = match.stats()
        // Both are one square long, and the tie goes to the lower slot -- which here is the winner,
        // so the point of the assertion is that it is answered at all rather than which way.
        assertEquals(1, stats.longest.length)
        assertEquals(SnakeId(0), stats.longest.slot)
    }

    @Test
    fun `stats read the same match a replay does`() {
        val match = matchOf(7, 7, "cycle", "south", seed = 99)
        match.runToCompletion()
        val played = match.stats()

        val replayed = Match.playback(match.record())
        replayed.runToCompletion()
        val watched = replayed.stats()

        assertEquals(played.turnsPlayed, watched.turnsPlayed)
        assertEquals(played.outcome?.winner, watched.outcome?.winner)
        assertEquals(played.occupiedCells, watched.occupiedCells)
        assertEquals(
            played.slots.map { it.length to it.movesMade },
            watched.slots.map { it.length to it.movesMade },
        )
    }
}
