package ao.snakewarz.ui.model

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.snake.SnakeId
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
        assertEquals("UCT - 1k", labels[1])
    }

    @Test
    fun `identical seats are all numbered, the first included`() {
        // `Random` and `Random ·2` would read as a typo. A list of rows is not a matrix column with
        // a legend under it, which is why TournamentTable deliberately does the other thing.
        val labels = labelsFor(listOf("random", "random", "uct"))

        assertEquals("Random ·1", labels[0])
        assertEquals("Random ·2", labels[1])
        assertEquals("UCT - 1k", labels[2], "a unique seat beside repeated ones is still left alone")
    }

    @Test
    fun `an allowance of its own is what tells two seats of the same bot apart`() {
        // The first question this testbed exists to ask, so the label has to be able to express it.
        val setup = MatchSetup.create(
            rows = 10,
            cols = 10,
            slots = listOf(BotId("uct"), BotId("uct")),
            seed = 7,
            budgetPerTurn = 1_000,
            budgets = intArrayOf(1_000, 4_000),
        )

        val labels = SlotLabels(setup, Registry)

        assertEquals("UCT - 1k", labels[0])
        assertEquals("UCT - 4k", labels[1])
    }

    @Test
    fun `a search bot names its allowance even when nothing about it is unusual`() {
        // It is the setting strength scales on, so hiding it in the match where every seat is at the
        // default hides it in exactly the match where the number is the question.
        val labels = labelsFor(listOf("random", "uct"))

        assertEquals("UCT - 1k", labels[1])
        assertEquals("Random", labels[0], "a bot that spends no allowance is not granted a number to show")
    }

    @Test
    fun `a tuned setting is named beside the allowance rather than starred`() {
        assertEquals("UCT - 1k/exploration=7.5", labelWith("uct", BotParams(mapOf("exploration" to "7.5"))))
    }

    @Test
    fun `a mode is named at its default too, so the seat beside it is readable`() {
        // `PUCT - 1k` next to `PUCT - 1k/rollout` tells you one of them is not `rollout` and leaves
        // you to remember which the other is. Two seats at two evaluations is the experiment.
        assertEquals("PUCT - 1k/expert", labelWith("puct", BotParams.EMPTY))
        assertEquals("PUCT - 1k/rollout", labelWith("puct", BotParams(mapOf("eval" to "rollout"))))
    }

    @Test
    fun `a number is named only when it has been moved, and moved is asked of the knob`() {
        // Every `uct` in existence runs at the same exploration constant, so naming it in every label
        // would spend the width of the panel to say nothing -- and `5.0` arriving spelled out from a
        // replay fragment is that constant rather than a departure from it.
        assertEquals("UCT - 1k", labelWith("uct", BotParams(mapOf("exploration" to "5.0"))))
    }

    private fun labelWith(slug: String, params: BotParams): String {
        val setup = MatchSetup.create(
            rows = 10,
            cols = 10,
            slots = listOf(BotId(slug)),
            seed = 7,
            budgetPerTurn = 1_000,
            slotParams = listOf(params),
        )
        return SlotLabels(setup, Registry)[0]
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

    /**
     * Three entries and a display name apiece, which is nearly all a label needs from a registry.
     *
     * The rest is the knobs, and the three shapes here are the three cases: `random` spends no
     * allowance, so it has none to name; `uct` has one plus a number that is named only when it
     * moves; `puct` adds a mode, which is named always. Shaped after the shipped registry rather
     * than minimised, because which of the three a bot is, is exactly what these labels turn on.
     */
    private object Registry : BotRegistry {
        override val entries: List<BotEntry> = listOf(
            BotEntry(BotId("random"), "Random", { Stationary }),
            BotEntry(
                BotId("uct"),
                "UCT",
                { Stationary },
                listOf(
                    BotKnob.Search(min = 0, max = 10_000, step = 100),
                    BotKnob.Decimal("exploration", "Exploration", "", 5.0, 0.1, 100.0, 0.1, tradeoff = true),
                ),
            ),
            BotEntry(
                BotId("puct"),
                "PUCT",
                { Stationary },
                listOf(
                    BotKnob.Search(min = 0, max = 10_000, step = 100),
                    BotKnob.Choice("eval", "Evaluation", "", "expert", listOf("rollout", "expert"), tradeoff = true),
                ),
            ),
        )

        override fun get(id: BotId): BotEntry? = entries.firstOrNull { it.id == id }
    }

    private object Stationary : Bot {
        override fun chooseMove(turn: Turn): Decision = Decision.Move(Direction.NORTH)
    }
}
