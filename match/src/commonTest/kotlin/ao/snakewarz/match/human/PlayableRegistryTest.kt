package ao.snakewarz.match.human

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.CyclingBot
import ao.snakewarz.match.Match
import ao.snakewarz.match.TestRegistry
import ao.snakewarz.match.soloBoard
import ao.snakewarz.match.turnOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayableRegistryTest {
    @Test
    fun `the human seat is offered first, then everything the delegate ships`() {
        val registry = PlayableRegistry(delegate("cycle", "south"), InputBuffer())

        assertEquals(
            listOf(PlayableRegistry.HUMAN_ID, BotId("cycle"), BotId("south")),
            registry.entries.map { it.id },
        )
    }

    @Test
    fun `the human seat resolves to an interactive bot and everything else passes through`() {
        val registry = PlayableRegistry(delegate("cycle"), InputBuffer())

        assertTrue(registry.entryOf(PlayableRegistry.HUMAN_ID).seat().interactive)
        assertFalse(registry.entryOf(BotId("cycle")).seat().interactive)
        assertNull(registry[BotId("nobody")])
    }

    @Test
    fun `every seat reads the same keyboard, because there is only one`() {
        // Which is why a match takes at most one human and :ui offers the seat for one slot only.
        // Worth a test rather than only a comment: it is the constraint that would otherwise be
        // discovered by two players sharing one snake's worth of input.
        val buffer = InputBuffer()
        val registry = PlayableRegistry(delegate(), buffer)
        val first = registry.entryOf(PlayableRegistry.HUMAN_ID).seat()
        val second = registry.entryOf(PlayableRegistry.HUMAN_ID).seat()

        assertTrue(first !== second, "a bot is still built per slot")

        buffer.push(Direction.SOUTH)

        assertEquals(Decision.Move(Direction.SOUTH), first.chooseMove(turnOn(soloBoard())))
        assertEquals(Decision.Pending, second.chooseMove(turnOn(soloBoard())), "the queue was already drained")
    }

    @Test
    fun `the seat waits by default, which is what makes a match with a person in it turn-based`() {
        // Load-bearing, not a taste: :ui stops the clock on the strength of Match.interactive and
        // plays one round per keypress, so a seat that coasted on the last heading would run the
        // match with nobody driving it.
        val board = soloBoard()
        board.apply(SnakeId(0), Direction.EAST)
        val seat = PlayableRegistry(delegate(), InputBuffer()).entryOf(PlayableRegistry.HUMAN_ID).seat()

        assertEquals(Decision.Pending, seat.chooseMove(turnOn(board)), "under way, and still waiting")
    }

    @Test
    fun `a delegate cannot quietly claim the reserved slug`() {
        // 'human' goes into the header of every replay somebody played themselves, so it is frozen,
        // and it is not a name a contributed bot may take.
        assertFailsWith<IllegalArgumentException> {
            PlayableRegistry(delegate("human"), InputBuffer())
        }
    }

    private fun delegate(vararg slugs: String) =
        TestRegistry(slugs.map { BotEntry(BotId(it), it, BotFactory { CyclingBot() }) })

    private fun BotEntry.seat(): Bot = factory.create(
        BotSetup(
            self = SnakeId(0),
            grid = Grid(9, 9),
            rules = RulesConfig(),
            opponents = IntArray(0),
            rng = SplitMix64(1),
            params = BotParams.EMPTY,
        ),
    )
}
