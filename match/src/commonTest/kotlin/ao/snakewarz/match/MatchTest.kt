package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.Direction
import ao.snakewarz.core.EliminationReason
import ao.snakewarz.core.MatchEnd
import ao.snakewarz.core.MoveOutcome
import ao.snakewarz.core.RulesConfig
import ao.snakewarz.core.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchTest {
    @Test
    fun `a step plays one turn and reports what it did`() {
        val match = matchOf(5, 5, "south", "north")

        val first = assertIs<StepResult.Advanced>(match.step())
        assertEquals(MoveOutcome.MOVED, first.outcome)
        assertEquals(1, match.turnIndex, "one step is one turn, never two")

        val second = assertIs<StepResult.Advanced>(match.step())
        assertTrue(first.id != second.id, "and the turn passes")
    }

    @Test
    fun `a fatal move is still a move, because the replay needs the direction`() {
        // The engine keeps "played a losing move" and "left without moving" apart, and so does this.
        // A suicide costs the replay format nothing at all: it is a recorded direction that turns
        // out to be illegal on playback, so it describes itself and needs no symbol.
        val match = matchInOrder(3, 3, "north", "cycle")

        val result = assertIs<StepResult.Advanced>(match.step())

        assertEquals(Direction.NORTH, result.direction, "walking into the wall is a recorded direction")
        assertEquals(MoveOutcome.SUICIDE, result.outcome, "south and east were free, so this one is on the bot")
        assertTrue(result.fatal)
        assertTrue(match.record().terminals.isEmpty(), "and it takes no room in the side table")
    }

    @Test
    fun `a snake with nowhere left to go is trapped rather than blamed`() {
        // The other half of the pair above. Same shape of event, opposite attribution: this snake
        // had no free square to move to, so the board killed it rather than the bot.
        val match = matchInOrder(1, 2, "east", "cycle")

        val result = assertIs<StepResult.Advanced>(match.step())

        assertEquals(MoveOutcome.TRAPPED, result.outcome)
        assertEquals(EliminationReason.TRAPPED, match.view.snake(SnakeId(0)).eliminationReason)
        assertEquals(MatchEnd.LAST_SNAKE_STANDING, match.outcome?.end, "the survivor wins even boxed in itself")
    }

    @Test
    fun `a resignation leaves without moving`() {
        val match = matchInOrder(5, 5, "quitter", "cycle")
        val head = match.view.snake(SnakeId(0)).head

        val result = assertIs<StepResult.Eliminated>(match.step())

        assertEquals(EliminationReason.RESIGNED, result.reason)
        assertEquals(head, match.view.snake(SnakeId(0)).head)
        assertEquals(SnakeId(0), match.view.ownerOf(head), "the body stays on the board as an obstacle")
    }

    @Test
    fun `a bot that throws forfeits, and the match carries on without it`() {
        // This is what makes a contributed bot safe to accept: it can lose, but it cannot take the
        // page down with it.
        val match = matchInOrder(5, 5, "thrower", "cycle")

        val result = assertIs<StepResult.Eliminated>(match.step())

        assertEquals(EliminationReason.FORFEIT, result.reason)
        assertEquals(MatchEnd.LAST_SNAKE_STANDING, match.outcome?.end)
        assertEquals(SnakeId(1), match.outcome?.winner)
    }

    @Test
    fun `a non-interactive slot that stalls forfeits, rather than hanging the match`() {
        val match = matchInOrder(5, 5, "staller", "cycle")

        assertEquals(EliminationReason.FORFEIT, assertIs<StepResult.Eliminated>(match.step()).reason)
    }

    @Test
    fun `an interactive slot that stalls parks, consuming no turn`() {
        val match = matchInOrder(5, 5, "human", "cycle")

        repeat(3) {
            assertEquals(StepResult.AwaitingInput, match.step())
            assertEquals(0, match.turnIndex, "waiting for a human is not a turn")
            assertNull(match.outcome)
        }
    }

    @Test
    fun `a finished match reports itself and calls no bot`() {
        val match = matchInOrder(1, 2, "east", "cycle")

        assertIs<StepResult.Advanced>(match.step())
        val finished = assertIs<StepResult.Finished>(match.step())

        assertEquals(MatchEnd.LAST_SNAKE_STANDING, finished.outcome.end)
        assertEquals(finished.outcome, assertIs<StepResult.Finished>(match.step()).outcome, "and stays finished")
    }

    @Test
    fun `running to completion terminates on every board`() {
        for ((rows, cols) in listOf(1 to 1, 1 to 4, 2 to 2, 5 to 9, 20 to 20)) {
            val slots = if (rows * cols >= 2) listOf("cycle", "cycle") else listOf("cycle")
            val match = matchOf(rows, cols, *slots.toTypedArray())

            match.runToCompletion()

            assertTrue(match.turnIndex <= match.setup.rules.maxTurns, "${rows}x$cols overran the turn limit")
        }
    }

    @Test
    fun `the turn limit ends a match nobody can lose`() {
        // Two snakes on a wide, empty board that only ever go straight would otherwise run forever;
        // the cap is what stops a browser tab doing that.
        val setup = MatchSetup.create(4, 4, listOf(BotId("cycle"), BotId("cycle")), seed = 1, rules = RulesConfig(maxTurns = 6))
        val match = Match(setup, TestRegistry.ALL)

        val outcome = match.runToCompletion()

        assertEquals(MatchEnd.TURN_LIMIT, outcome.end)
        assertEquals(6, match.turnIndex)
        assertTrue(outcome.isDraw)
    }

    @Test
    fun `dirty cells are the new head and the square the tail left`() {
        // Which alternates, because snakes grow at half speed: the tail only retracts every other
        // turn. A renderer that assumes two dirty cells every turn leaves a trail of stale squares
        // behind on exactly half of them.
        val match = matchInOrder(6, 6, "south")
        val id = SnakeId(0)

        match.step()
        assertEquals(2, match.events().size, "a dragging move clears the old tail and paints the new head")
        assertEquals(match.view.snake(id).head, match.events().cellAt(0), "the head is always first")

        match.step()
        assertEquals(1, match.events().size, "a growing move only paints the head")
        assertEquals(match.view.snake(id).head, match.events().cellAt(0))
    }

    @Test
    fun `dirty cells are empty when nothing on the board changed`() {
        val match = matchInOrder(5, 5, "quitter", "cycle")

        match.step()

        assertTrue(match.events().isEmpty, "a death recolours a whole snake, which is a repaint and not a cell update")
    }

    @Test
    fun `a solo match can be recorded even when its only snake leaves`() {
        // The one place "at most slots - 1 snakes can be eliminated" is wrong. A contested match
        // ends the moment one survivor is left, so nobody is ever the last to go; a solo match has
        // no survivor to crown and ends with ALL_ELIMINATED, so its single snake really can be.
        // Getting this wrong made a lone player resigning unrecordable.
        val match = matchInOrder(5, 5, "quitter")

        val outcome = match.runToCompletion()
        val record = match.record()

        assertEquals(MatchEnd.ALL_ELIMINATED, outcome.end)
        assertEquals(1, record.terminals.size)
        assertEquals(EliminationReason.RESIGNED, record.terminals.single().reason)
        assertEquals(record, ReplayCodec.decode(ReplayCodec.encode(record)), "and it survives a round trip")
    }

    @Test
    fun `a solo match that plays itself into a wall is recorded with no terminal at all`() {
        val match = matchInOrder(1, 3, "east")

        assertEquals(MatchEnd.ALL_ELIMINATED, match.runToCompletion().end)
        assertTrue(match.record().terminals.isEmpty(), "a suicide describes itself")
    }

    @Test
    fun `the same setup plays the same match every time`() {
        val setup = MatchSetup.create(12, 12, listOf(BotId("cycle"), BotId("south")), seed = 909)

        val first = Match(setup, TestRegistry.ALL).also { it.runToCompletion() }.record()
        val second = Match(setup, TestRegistry.ALL).also { it.runToCompletion() }.record()

        assertEquals(first, second)
    }

    @Test
    fun `every slot gets its own stream, forked so one bot cannot shift another`() {
        // Two matches differing only in slot 1's bot must leave slot 0's stream untouched. This is
        // the property that lets a bot be tuned without invalidating every recorded match it is not
        // playing in.
        val shared = MatchSetup.create(12, 12, listOf(BotId("cycle"), BotId("south")), seed = 5)
        val other = MatchSetup(
            seed = shared.seed,
            rows = shared.rows,
            cols = shared.cols,
            rules = shared.rules,
            budgetPerTurn = shared.budgetPerTurn,
            slots = listOf(BotId("cycle"), BotId("east")),
            turnOrder = shared.turnOrder(),
            spawns = shared.spawns(),
        )

        val firstOpening = Match(shared, TestRegistry.ALL).step()
        val secondOpening = Match(other, TestRegistry.ALL).step()

        assertEquals(
            assertIs<StepResult.Advanced>(firstOpening).direction,
            assertIs<StepResult.Advanced>(secondOpening).direction,
        )
    }
}
