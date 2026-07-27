package ao.snakewarz.ui

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotRegistry
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Direction
import ao.snakewarz.core.SnakeId
import ao.snakewarz.match.MatchSetup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What each seat of a match is called.
 *
 * Driven through the constructor rather than through the numbering helper, which is private and
 * should stay so: the thing that has to be right is what the scoreboard reads, and that is the whole
 * of name plus suffix plus number rather than any one of the three.
 */
class SlotLabelsTest {
    @Test
    fun `a seat that is the only one of its kind is not numbered`() {
        val labels = labelsFor(listOf("random", "uct"))

        assertEquals("Random", labels[0])
        assertEquals("UCT", labels[1])
    }

    @Test
    fun `identical seats are all numbered, the first included`() {
        // `Random` and `Random ·2` would read as a typo. A list of rows is not a matrix column with
        // a legend under it, which is why TournamentTable deliberately does the other thing.
        val labels = labelsFor(listOf("random", "random", "uct"))

        assertEquals("Random ·1", labels[0])
        assertEquals("Random ·2", labels[1])
        assertEquals("UCT", labels[2], "a unique seat beside repeated ones is still left alone")
    }

    @Test
    fun `an allowance of its own is what tells two seats of the same bot apart`() {
        // The first question this testbed exists to ask, so the label has to be able to express it.
        val setup = MatchSetup.create(
            rows = 10,
            cols = 10,
            slots = listOf(BotId("uct"), BotId("uct")),
            seed = 7,
            budgetPerTurn = 40_000,
            budgets = intArrayOf(40_000, 4_000),
        )

        val labels = SlotLabels(setup, Registry)

        assertEquals("UCT", labels[0], "the seat at the match default is not the odd one out")
        assertEquals("UCT @4k", labels[1])
    }

    @Test
    fun `a slot nobody registered falls back to its slug rather than to nothing`() {
        val labels = labelsFor(listOf("random", "never-shipped"))

        assertEquals("never-shipped", labels[1])
    }

    @Test
    fun `a drawn match is won by nobody, and says so`() {
        val setup = setupFor(listOf("random", "uct"))
        val labels = SlotLabels(setup, Registry)

        assertEquals("nobody", labels.of(SnakeId.NONE))
        assertEquals("Random", labels.of(SnakeId(0)))
    }

    @Test
    fun `a slot off the end of the match is empty, not an exception`() {
        // The scoreboard has a fixed number of rows and a match does not fill them all.
        val labels = SlotLabels(setupFor(listOf("random")), Registry)

        assertEquals("", labels[3])
    }

    private fun labelsFor(slugs: List<String>): SlotLabels = SlotLabels(setupFor(slugs), Registry)

    private fun setupFor(slugs: List<String>): MatchSetup =
        MatchSetup.create(rows = 10, cols = 10, slots = slugs.map(::BotId), seed = 1)

    /** Two entries and a display name apiece, which is all a label needs from a registry. */
    private object Registry : BotRegistry {
        override val entries: List<BotEntry> = listOf(
            BotEntry(BotId("random"), "Random", { Stationary }),
            BotEntry(BotId("uct"), "UCT", { Stationary }),
        )

        override fun get(id: BotId): BotEntry? = entries.firstOrNull { it.id == id }
    }

    private object Stationary : Bot {
        override fun chooseMove(turn: Turn): Decision = Decision.Move(Direction.NORTH)
    }
}
