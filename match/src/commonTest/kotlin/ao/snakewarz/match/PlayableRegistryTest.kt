package ao.snakewarz.match

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotFactory
import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotParams
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.core.Direction
import ao.snakewarz.core.Grid
import ao.snakewarz.core.RulesConfig
import ao.snakewarz.core.SnakeId
import ao.snakewarz.core.SplitMix64
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
