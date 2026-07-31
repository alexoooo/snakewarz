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
import ao.snakewarz.ui.model.Panel
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
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The screen and panel model, and the focus behaviour that is easiest to ship broken.
 *
 * Driven against a cut-down skeleton rather than the real `index.html`, because what is under test
 * is the rule — one section showing, one overlay at a time, everything behind it inert, Escape
 * closing the top thing and then going back — and not which ids the page happens to use. The ids it
 * does use are the ones [Shell] looks up, so a page that lost one still fails at boot with its name.
 *
 * What is deliberately *not* here: that opening a panel while a batch runs leaves the batch running.
 * That is a property of `GameSession.dispatch`, which needs the whole page and a registry to build,
 * and it is carried instead by `UiIntent.Shell` being a type — an intent filed there cannot reach
 * the guard at all.
 */
class ShellTest {
    private val skeleton: HTMLElement = (document.createElement("div") as HTMLElement).also {
        it.innerHTML = SKELETON
        document.body?.appendChild(it)
    }

    private val intents = mutableListOf<UiIntent>()
    private val shell = Shell { intents += it }

    @AfterTest
    fun detach() {
        skeleton.remove()
    }

    @Test
    fun `exactly one screen is showing, and the rest are hidden rather than off-screen`() {
        for (screen in Screen.entries) {
            shell.render(model(screen = screen))

            val showing = Screen.entries.filter { !section(it).hidden }
            assertEquals(listOf(screen), showing, "on $screen")
        }
    }

    @Test
    fun `navigating takes the focus to the screen that arrived`() {
        // The control that navigated is on the screen that just left. Leave the focus on it and it
        // falls to <body> when that screen is hidden, so the next Tab starts in the browser's own
        // chrome rather than in the page — which is the whole of "keyboard only" being broken.
        shell.render(model(screen = Screen.GAME))
        (element("game-back") as HTMLButtonElement).focus()

        shell.render(model(screen = Screen.HOME))

        assertEquals("screen-home", focused())
    }

    @Test
    fun `a panel covers the board, and the board behind it is inert`() {
        shell.render(model(screen = Screen.GAME))
        assertNull(element("app").getAttribute("inert"), "nothing is over the board yet")
        assertTrue(shell.boardHasKeys, "so the arrow keys are the snake's")

        shell.render(model(screen = Screen.GAME, openPanel = Panel.SETUP))

        assertEquals(listOf(Panel.SETUP), Panel.entries.filter { !panel(it).hidden })
        assertEquals("", element("app").getAttribute("inert"), "the board is unreachable behind it")
        assertTrue(!shell.boardHasKeys, "and the keys belong to whatever is focused in the panel")
    }

    @Test
    fun `a ladder level offers neither Setup nor Tournament`() {
        // A level *is* its configuration, so re-seating it would be playing something else under
        // its name. Gone from the bar rather than greyed on it: a control that can never apply here
        // should not be present, and hiding it is also what takes it out of the tab order.
        shell.render(model(screen = Screen.GAME, level = 3))
        assertEquals(
            listOf(Panel.SHARE, Panel.SETTINGS),
            Panel.entries.filter { !opener(it).hidden },
        )

        shell.render(model(screen = Screen.GAME))
        assertEquals(Panel.entries, Panel.entries.filter { !opener(it).hidden }, "all four in Custom")
    }

    @Test
    fun `a lost level offers a retry and a beaten one offers the rung above it`() {
        // Both are one key: the card marks all three of its actions and the focus lands on the first
        // that is showing, so unlimited lives costs one Enter and so does moving on.
        shell.render(model(screen = Screen.GAME, level = 3, result = "You lose"))
        assertEquals("result-again", focused())
        assertEquals("Retry", element("result-again").textContent)
        // Asked after the focus, because reachable() moves it to find out what would take it.
        assertEquals(listOf("result-again", "result-home"), reachable())

        shell.render(model(screen = Screen.GAME))
        shell.render(model(screen = Screen.GAME, level = 3, levelCleared = true, result = "You win"))
        assertEquals("result-next", focused())
        assertEquals(listOf("result-next", "result-home"), reachable())

        (element("result-next") as HTMLButtonElement).click()
        assertEquals(4, (intents.last() as UiIntent.StartLevel).index)
    }

    @Test
    fun `beating the last rung offers nothing above it`() {
        // There is no level eleven, so the card is the verdict and the way out — and the focus falls
        // through to Home rather than staying on a button nobody can press.
        shell.render(model(screen = Screen.GAME, level = 10, levelCleared = true, result = "Ladder complete"))

        assertEquals("result-home", focused())
        assertEquals(listOf("result-home"), reachable())
    }

    @Test
    fun `opening an overlay takes the focus and closing it hands the focus back`() {
        shell.render(model(screen = Screen.GAME))
        (element("open-setup") as HTMLButtonElement).focus()

        shell.render(model(screen = Screen.GAME, openPanel = Panel.SETUP))
        assertEquals("panel-setup", focused(), "the panel itself, which names no default action")

        shell.render(model(screen = Screen.GAME))
        assertEquals("open-setup", focused(), "back to the button that opened it")
    }

    @Test
    fun `the result dialog is over the panels and takes the focus to its default action`() {
        shell.render(model(screen = Screen.GAME, openPanel = Panel.SETUP))

        shell.render(model(screen = Screen.GAME, openPanel = Panel.SETUP, result = "You win"))

        assertTrue(!element("dialog-result").hidden)
        assertEquals("You win", element("result-title").textContent)
        assertEquals("result-again", focused(), "its [data-focus] control")
    }

    @Test
    fun `enter on the verdict plays again, because what it focuses is a real button`() {
        // Enter activating a focused <button> is the platform's doing rather than ours, which is
        // the whole reason nothing on this page is a custom widget. What has to hold here is that
        // the control the card hands the focus to *is* one, and that pressing it means play again
        // — together, that is "a lost match costs one key".
        shell.render(model(screen = Screen.GAME))
        shell.render(model(screen = Screen.GAME, result = "You lose"))

        val landed = document.activeElement as? HTMLButtonElement
            ?: error("the verdict focused ${focused()}, which Enter would do nothing to")
        landed.click()

        assertEquals(UiIntent.Restart, intents.single())
    }

    @Test
    fun `while the verdict is up, the only controls Tab can reach are its own`() {
        // A panel open when the match ends is the case this is here for: it is a *sibling* of #app,
        // so marking the board inert leaves the panel beside the card — visible, and still a tab
        // stop — while the card claims aria-modal.
        shell.render(model(screen = Screen.GAME, openPanel = Panel.SETUP))
        shell.render(model(screen = Screen.GAME, openPanel = Panel.SETUP, result = "You win"))

        assertEquals(listOf("result-again", "result-home"), reachable())
    }

    @Test
    fun `escape puts the verdict away before it goes back a screen`() {
        shell.render(model(screen = Screen.GAME, result = "You lose"))
        escape()

        assertEquals(UiIntent.ClosePanel, intents.single(), "the card in front, not the screen behind it")
    }

    @Test
    fun `escape closes the top overlay, and then goes back a screen`() {
        shell.render(model(screen = Screen.GAME, openPanel = Panel.SETUP))
        escape()
        assertEquals(UiIntent.ClosePanel, intents.single())

        intents.clear()
        shell.render(model(screen = Screen.GAME))
        escape()
        assertEquals(Screen.HOME, (intents.single() as UiIntent.Navigate).screen)
    }

    @Test
    fun `escape on the home screen has nowhere to go`() {
        shell.render(model(screen = Screen.HOME))
        escape()

        assertEquals(emptyList(), intents.toList())
    }

    // -- internals

    private fun focused(): String? = document.activeElement?.id

    /**
     * Every control Tab could land on, in document order.
     *
     * Asked by focusing each one and seeing whether it took, because what puts the rest of the page
     * out of reach — `inert` and `hidden` — is answered by the browser and not by an attribute a
     * walk of the DOM could read off an element. `tabindex="-1"` is excluded by the selector: a
     * screen and a panel are focused programmatically and are not tab stops.
     */
    private fun reachable(): List<String> {
        val candidates = skeleton.querySelectorAll(TABBABLE)
        val ids = mutableListOf<String>()
        for (index in 0 until candidates.length) {
            val element = candidates.item(index) as? HTMLElement ?: continue
            element.focus()
            if (document.activeElement === element) {
                ids += element.id
            }
        }
        return ids
    }

    private fun escape() {
        val event = KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true))
        document.body?.dispatchEvent(event)
    }

    private fun section(screen: Screen): HTMLElement = element(
        when (screen) {
            Screen.HOME -> "screen-home"
            Screen.LADDER -> "screen-ladder"
            Screen.GAME -> "screen-game"
        },
    )

    private fun panel(panel: Panel): HTMLElement = element(
        when (panel) {
            Panel.SETUP -> "panel-setup"
            Panel.TOURNAMENT -> "panel-tournament"
            Panel.SHARE -> "panel-share"
            Panel.SETTINGS -> "panel-settings"
        },
    )

    private fun opener(panel: Panel): HTMLElement = element("open-" + panel(panel).id.removePrefix("panel-"))

    private fun element(id: String): HTMLElement =
        document.getElementById(id) as? HTMLElement ?: error("the test skeleton is missing #$id")

    private fun model(
        screen: Screen,
        level: Int? = null,
        levelCleared: Boolean = false,
        openPanel: Panel? = null,
        result: String? = null,
    ): UiModel = UiModel(
        screen = screen,
        level = level,
        ladder = LadderProgress.NONE,
        levelCleared = levelCleared,
        openPanel = openPanel,
        theme = Theme.of(Theme.DEFAULT_ID, dark = false),
        result = result,
        resultPortrait = null,
        replay = false,
        interactive = false,
        running = false,
        turnCount = 0,
        status = "paused",
        stats = Match(SETUP, ONE_SEAT).stats(),
        labels = SlotLabels(SETUP, ONE_SEAT),
        portraits = SlotPortraits(SETUP, { null }, Theme.of(Theme.DEFAULT_ID, dark = false)),
        hover = null,
        canWatchReplay = false,
        shareUrl = null,
        tournament = null,
    )

    private companion object {
        /** What the browser offers Tab, which is every native control minus the ones opted out. */
        const val TABBABLE = "button, input, select, summary, [tabindex]:not([tabindex='-1'])"

        val SETUP: MatchSetup = MatchSetup.create(rows = 8, cols = 8, slots = listOf(BotId("space")), seed = 1)

        /** One seat, so a real [MatchStats] can be taken off a real board: the model needs one. */
        val ONE_SEAT = object : BotRegistry {
            private val entry = BotEntry(BotId("space"), "Space", BotFactory { Quitter })

            override val entries: List<BotEntry> = listOf(entry)

            override fun get(id: BotId): BotEntry? = entry.takeIf { it.id == id }
        }

        object Quitter : Bot {
            override fun chooseMove(turn: Turn): Decision = Decision.Resign
        }

        /**
         * The shape of `index.html`, cut down to what [Shell] looks up.
         *
         * Written as markup here for the reason it is markup there: the structure is the fixed part
         * and only what a test asserts about it is interesting. Production code never builds any of
         * this.
         */
        val SKELETON = """
            <main id="app">
              <section id="screen-home" tabindex="-1"></section>
              <section id="screen-ladder" tabindex="-1"><button id="ladder-back"></button></section>
              <section id="screen-game" tabindex="-1">
                <button id="game-back"></button>
                <button id="open-setup"></button>
                <button id="open-tournament"></button>
                <button id="open-share"></button>
                <button id="open-settings"></button>
              </section>
            </main>
            <div id="panel-scrim" hidden></div>
            <aside id="panel-setup" tabindex="-1" hidden><button id="close-setup"></button></aside>
            <aside id="panel-tournament" tabindex="-1" hidden><button id="close-tournament"></button></aside>
            <aside id="panel-share" tabindex="-1" hidden><button id="close-share"></button></aside>
            <aside id="panel-settings" tabindex="-1" hidden><button id="close-settings"></button></aside>
            <div id="dialog-result" hidden>
              <img id="result-portrait" alt="" aria-hidden="true" hidden>
              <h2 id="result-title"></h2>
              <p id="result-detail"></p>
              <button id="result-again" data-focus></button>
              <button id="result-next" data-focus hidden></button>
              <button id="result-home" data-focus></button>
            </div>
        """.trimIndent()
    }
}
