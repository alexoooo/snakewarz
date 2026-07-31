package ao.snakewarz.ui.chrome

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.ladder.Ladder
import ao.snakewarz.ui.model.Screen
import ao.snakewarz.ui.model.SlotLabels
import ao.snakewarz.ui.model.SlotPortraits
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import ao.snakewarz.ui.model.ladder.LadderProgress
import ao.snakewarz.ui.render.Theme
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three states a tile can be in, and the one thing a locked one must not be: clickable.
 *
 * Driven against a cut-down skeleton for [ShellTest]'s reason — what is under test is the rule, not
 * which ids the page happens to use — but the tiles are generated from [Ladder] rather than written
 * out, because a test that hard-codes ten of them would stop covering an eleventh.
 */
class LadderScreenTest {
    private val skeleton: HTMLElement = (document.createElement("div") as HTMLElement).also {
        it.innerHTML = skeleton()
        document.body?.appendChild(it)
    }

    private val intents = mutableListOf<UiIntent>()
    private val ladder = LadderScreen(NAMED_OPPONENT, { null }, { intents += it })

    @AfterTest
    fun detach() {
        skeleton.remove()
    }

    @Test
    fun `a browser that has played nothing has one tile open and the rest locked`() {
        ladder.render(model(LadderProgress.NONE))

        assertEquals(listOf(1), open(), "the lowest rung, and only it")
        assertEquals((2..Ladder.size).toList(), locked())
        assertTrue(tile(2).disabled, "so a locked tile cannot be pressed or tabbed to")
    }

    @Test
    fun `beating a level opens the next one and no more`() {
        ladder.render(model(LadderProgress.NONE.withCleared(1)))

        assertEquals(listOf(2), open())
        assertEquals((3..Ladder.size).toList(), locked())
        assertTrue(!tile(1).disabled, "a beaten level stays playable")
    }

    @Test
    fun `beating the last level leaves nothing locked`() {
        val finished = Ladder.levels.fold(LadderProgress.NONE) { progress, level ->
            progress.withCleared(level.index)
        }

        ladder.render(model(finished))

        assertEquals(emptyList(), locked())
        assertEquals(emptyList(), open(), "and nothing left to unlock either")
    }

    @Test
    fun `the open tile is the one arriving on the screen would land on`() {
        // `[data-focus]` is what `Shell` reads on the frame the screen appears, so keyboard-only play
        // starts on the level you would press rather than at the top of a list of ten.
        ladder.render(model(LadderProgress.NONE.withCleared(1).withCleared(2)))

        val marked = Ladder.levels.filter { tile(it.index).hasAttribute("data-focus") }
        assertEquals(listOf(3), marked.map { it.index })
    }

    @Test
    fun `a tile says what the level is, with the opponent named through the registry`() {
        val level = Ladder.levels.first()
        ladder.render(model(LadderProgress.NONE))

        assertEquals(level.title, text(level.index, ".level-title"))
        assertEquals(level.blurb, text(level.index, ".level-blurb"))
        assertTrue(
            text(level.index, ".level-meta").endsWith(OPPONENT_NAME),
            "the display name comes off the BotRegistry interface, by slug: ${text(level.index, ".level-meta")}",
        )
    }

    @Test
    fun `pressing a tile asks for that level`() {
        ladder.render(model(LadderProgress.NONE))

        tile(1).click()

        assertEquals(1, (intents.single() as UiIntent.StartLevel).index)
    }

    // -- internals

    private fun open(): List<Int> = statedAs("open")

    private fun locked(): List<Int> = statedAs("locked")

    private fun statedAs(state: String): List<Int> =
        Ladder.levels.map { it.index }.filter { tile(it).className == "level $state" }

    private fun tile(index: Int): HTMLButtonElement =
        document.getElementById("level-$index") as? HTMLButtonElement
            ?: error("the test skeleton is missing #level-$index")

    private fun text(index: Int, selector: String): String =
        (tile(index).querySelector(selector) as? HTMLElement)?.textContent.orEmpty()

    private fun model(progress: LadderProgress): UiModel = UiModel(
        screen = Screen.LADDER,
        level = null,
        ladder = progress,
        levelCleared = false,
        openPanel = null,
        theme = THEME,
        result = null,
        resultPortrait = null,
        replay = false,
        interactive = false,
        running = false,
        turnCount = 0,
        status = "paused",
        stats = Match(SETUP, NAMED_OPPONENT).stats(),
        labels = SlotLabels(SETUP, NAMED_OPPONENT),
        portraits = SlotPortraits(SETUP, { null }, THEME),
        hover = null,
        canWatchReplay = false,
        shareUrl = null,
        tournament = null,
    )

    private companion object {
        val THEME: Theme = Theme.of(Theme.DEFAULT_ID, dark = false)

        const val OPPONENT_NAME = "The First One"

        /** The opponent of level 1 under a name of this test's own, so the lookup is visible. */
        val NAMED_OPPONENT = object : BotRegistry {
            private val entry = BotEntry(Ladder.levels.first().opponent, OPPONENT_NAME, BotFactory { Quitter })

            override val entries: List<BotEntry> = listOf(entry)

            override fun get(id: BotId): BotEntry? = entry.takeIf { it.id == id }
        }

        object Quitter : Bot {
            override fun chooseMove(turn: Turn): Decision = Decision.Resign
        }

        val SETUP: MatchSetup =
            MatchSetup.create(rows = 8, cols = 8, slots = listOf(Ladder.levels.first().opponent), seed = 1)

        /** One tile per rung, generated so that an eleventh level would still be covered. */
        fun skeleton(): String = Ladder.levels.joinToString(separator = "\n", prefix = "<ol>", postfix = "</ol>") {
            """
            <li><button id="level-${it.index}" class="level locked" disabled>
              <img class="portrait" alt="" aria-hidden="true">
              <span class="level-no"></span>
              <span class="level-title"></span>
              <span class="level-blurb"></span>
              <span class="level-meta"></span>
              <span class="level-state"></span>
            </button></li>
            """.trimIndent()
        }
    }
}
