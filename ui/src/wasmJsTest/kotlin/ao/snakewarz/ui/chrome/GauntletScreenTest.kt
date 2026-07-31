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
import ao.snakewarz.match.gauntlet.Gauntlet
import ao.snakewarz.ui.model.Screen
import ao.snakewarz.ui.model.SlotLabels
import ao.snakewarz.ui.model.SlotPortraits
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import ao.snakewarz.ui.model.gauntlet.GauntletProgress
import ao.snakewarz.ui.render.Theme
import kotlinx.browser.document
import kotlinx.browser.localStorage
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three states a tile can be in, the one thing a locked one must not be — clickable — and the ▷
 * that only a rung with a run kept for it carries.
 *
 * Driven against a cut-down skeleton for [ShellTest]'s reason — what is under test is the rule, not
 * which ids the page happens to use — but the tiles are generated from [Gauntlet] rather than written
 * out, because a test that hard-codes ten of them would stop covering an eleventh.
 */
class GauntletScreenTest {
    private val skeleton: HTMLElement = (document.createElement("div") as HTMLElement).also {
        it.innerHTML = skeleton()
        document.body?.appendChild(it)
    }

    private val intents = mutableListOf<UiIntent>()
    private val gauntlet = GauntletScreen(NAMED_OPPONENT, { null }, { intents += it })

    @AfterTest
    fun detach() {
        skeleton.remove()
        // The store outlives the page this test built, so a run left behind would decide what the
        // next case sees on its tiles.
        for (level in Gauntlet.levels) {
            localStorage.removeItem(replayKey(level.index))
        }
    }

    @Test
    fun `a browser that has played nothing has one tile open and the rest locked`() {
        gauntlet.render(model(GauntletProgress.NONE))

        assertEquals(listOf(1), open(), "the lowest rung, and only it")
        assertEquals((2..Gauntlet.size).toList(), locked())
        assertTrue(tile(2).disabled, "so a locked tile cannot be pressed or tabbed to")
    }

    @Test
    fun `beating a level opens the next one and no more`() {
        gauntlet.render(model(GauntletProgress.NONE.withCleared(1)))

        assertEquals(listOf(2), open())
        assertEquals((3..Gauntlet.size).toList(), locked())
        assertTrue(!tile(1).disabled, "a beaten level stays playable")
    }

    @Test
    fun `beating the last level leaves nothing locked`() {
        val finished = Gauntlet.levels.fold(GauntletProgress.NONE) { progress, level ->
            progress.withCleared(level.index)
        }

        gauntlet.render(model(finished))

        assertEquals(emptyList(), locked())
        assertEquals(emptyList(), open(), "and nothing left to unlock either")
    }

    @Test
    fun `the open tile is the one arriving on the screen would land on`() {
        // `[data-focus]` is what `Shell` reads on the frame the screen appears, so keyboard-only play
        // starts on the level you would press rather than at the top of a list of ten.
        gauntlet.render(model(GauntletProgress.NONE.withCleared(1).withCleared(2)))

        val marked = Gauntlet.levels.filter { tile(it.index).hasAttribute("data-focus") }
        assertEquals(listOf(3), marked.map { it.index })
    }

    @Test
    fun `a tile says what the level is, with the opponent named through the registry`() {
        val level = Gauntlet.levels.first()
        gauntlet.render(model(GauntletProgress.NONE))

        assertEquals(level.title, text(level.index, ".level-title"))
        assertEquals(level.blurb, text(level.index, ".level-blurb"))
        assertTrue(
            text(level.index, ".level-meta").endsWith(OPPONENT_NAME),
            "the display name comes off the BotRegistry interface, by slug: ${text(level.index, ".level-meta")}",
        )
    }

    @Test
    fun `pressing a tile asks for that level`() {
        gauntlet.render(model(GauntletProgress.NONE))

        tile(1).click()

        assertEquals(1, (intents.single() as UiIntent.StartLevel).index)
    }

    @Test
    fun `a level with no run kept for it offers nothing to watch`() {
        gauntlet.render(model(GauntletProgress.NONE.withCleared(1)))

        assertTrue(replay(1).hidden, "a rung beaten on some other browser has nothing to play back here")
    }

    @Test
    fun `a level with a run kept for it offers it, and pressing it asks for that one`() {
        // Any string at all: what is stored is the replay codec's business and this screen only ever
        // asks whether there is something, which is why a tile costs one lookup rather than a decode.
        localStorage.setItem(replayKey(1), "a payload only the codec has to understand")

        gauntlet.render(model(GauntletProgress.NONE.withCleared(1)))
        assertTrue(!replay(1).hidden)

        replay(1).click()

        assertEquals(1, (intents.single() as UiIntent.WatchLevelReplay).index, "and it is a level intent")
    }

    @Test
    fun `a locked level never offers one, whatever is in storage`() {
        // A value left by a version whose table was numbered differently, or one somebody typed into
        // a console. Either way the ▷ and the Cleared badge have to go on meaning the same thing.
        localStorage.setItem(replayKey(Gauntlet.size), "a payload from some other gauntlet")

        gauntlet.render(model(GauntletProgress.NONE))

        assertEquals(listOf(Gauntlet.size), locked().takeLast(1), "the top rung is out of reach")
        assertTrue(replay(Gauntlet.size).hidden)
    }

    // -- internals

    private fun open(): List<Int> = statedAs("open")

    private fun locked(): List<Int> = statedAs("locked")

    private fun statedAs(state: String): List<Int> =
        Gauntlet.levels.map { it.index }.filter { tile(it).className == "level $state" }

    private fun tile(index: Int): HTMLButtonElement =
        document.getElementById("level-$index") as? HTMLButtonElement
            ?: error("the test skeleton is missing #level-$index")

    private fun replay(index: Int): HTMLButtonElement =
        document.getElementById("level-replay-$index") as? HTMLButtonElement
            ?: error("the test skeleton is missing #level-replay-$index")

    private fun text(index: Int, selector: String): String =
        (tile(index).querySelector(selector) as? HTMLElement)?.textContent.orEmpty()

    private fun model(progress: GauntletProgress): UiModel = UiModel(
        screen = Screen.GAUNTLET,
        level = null,
        gauntlet = progress,
        levelCleared = false,
        openPanel = null,
        theme = THEME,
        result = null,
        resultPortrait = null,
        replay = false,
        interactive = false,
        steering = false,
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

        /** `Preferences`' per-rung key, written out here for the reason `PreferencesTest` writes it out. */
        fun replayKey(level: Int): String = "snakewarz.gauntlet.replay.$level.v1"

        const val OPPONENT_NAME = "The First One"

        /** The opponent of level 1 under a name of this test's own, so the lookup is visible. */
        val NAMED_OPPONENT = object : BotRegistry {
            private val entry = BotEntry(Gauntlet.levels.first().opponent, OPPONENT_NAME, BotFactory { Quitter })

            override val entries: List<BotEntry> = listOf(entry)

            override fun get(id: BotId): BotEntry? = entry.takeIf { it.id == id }
        }

        object Quitter : Bot {
            override fun chooseMove(turn: Turn): Decision = Decision.Resign
        }

        val SETUP: MatchSetup =
            MatchSetup.create(rows = 8, cols = 8, slots = listOf(Gauntlet.levels.first().opponent), seed = 1)

        /**
         * One tile per rung, generated so that an eleventh level would still be covered.
         *
         * The ▷ is a **sibling** of the tile here for the reason it is one in `index.html`: a tile is
         * a `<button>`, so a nested one would be invalid markup and would never receive its click.
         */
        fun skeleton(): String = Gauntlet.levels.joinToString(separator = "\n", prefix = "<ol>", postfix = "</ol>") {
            """
            <li><button id="level-${it.index}" class="level locked" disabled>
              <img class="portrait" alt="" aria-hidden="true">
              <span class="level-no"></span>
              <span class="level-title"></span>
              <span class="level-blurb"></span>
              <span class="level-meta"></span>
              <span class="level-state"></span>
            </button>
            <button id="level-replay-${it.index}" class="level-replay" hidden>&#9655;</button></li>
            """.trimIndent()
        }
    }
}
