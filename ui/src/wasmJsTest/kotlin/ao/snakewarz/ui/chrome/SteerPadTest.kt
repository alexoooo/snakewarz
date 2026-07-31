package ao.snakewarz.ui.chrome

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.ui.model.Screen
import ao.snakewarz.ui.model.SlotLabels
import ao.snakewarz.ui.model.SlotPortraits
import ao.snakewarz.ui.model.UiModel
import ao.snakewarz.ui.model.gauntlet.GauntletProgress
import ao.snakewarz.ui.render.Theme
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One thumb, the several ways a browser can end its press, and the rule that decides which strip of
 * the board's container the pad goes in.
 *
 * Driven against a cut-down skeleton for [ShellTest]'s reason — what is under test is the behaviour
 * rather than which ids the page happens to use — and the placement rule is checked as the
 * arithmetic it is, since a test document lays nothing out. That is also what puts one thing out of
 * reach here and leaves it to a real finger: re-aiming a thumb slid from one arrow onto the next
 * needs four laid-out boxes to hit-test against, and this document has none.
 */
class SteerPadTest {
    private val skeleton: HTMLElement = (document.createElement("div") as HTMLElement).also {
        it.innerHTML = SKELETON
        document.body?.appendChild(it)
    }

    private val moves = mutableListOf<Direction>()
    private val repeat = SteerRepeat { moves += it }
    private val pad = SteerPad(element("board-wrap"), element("board"), repeat)

    @AfterTest
    fun detach() {
        repeat.cancel()
        skeleton.remove()
    }

    @Test
    fun `a press plays one move, and holding is the keyboard's own clock`() {
        press("steer-north")

        assertEquals(listOf(Direction.NORTH), moves, "the press itself is a move")
        assertTrue(element("steer-north").classList.contains("held"), "and it says so under the thumb")

        // The pad hands the repeat to the same clock a held arrow key drives rather than pacing one
        // of its own, so a thumb and a keyboard cannot disagree about how fast a hold repeats.
        repeat.frame(0.0)
        repeat.frame(300.0)
        assertEquals(listOf(Direction.NORTH, Direction.NORTH), moves)
    }

    @Test
    fun `letting go stops it, however the release arrives`() {
        for (event in listOf("pointerup", "pointercancel")) {
            moves.clear()
            press("steer-east")
            fire(event)

            repeat.frame(0.0)
            repeat.frame(1_000.0)
            assertEquals(listOf(Direction.EAST), moves, "$event is a release")
            assertFalse(element("steer-east").classList.contains("held"))
        }
    }

    @Test
    fun `a release that ends nothing ends nothing`() {
        // Every pointer on the page reaches the release, because a thumb that leaves the pad before
        // it lifts reports somewhere else entirely. Nearly all of them are somebody pressing
        // something, and the one that follows must still find the pad free to be taken.
        press("steer-west")
        fire("pointerup")
        fire("pointerup")
        moves.clear()

        press("steer-south")

        assertEquals(listOf(Direction.SOUTH), moves)
    }

    @Test
    fun `a second finger does not take the snake off the first`() {
        press("steer-north")
        moves.clear()

        press("steer-south", pointerId = 2)

        assertEquals(emptyList(), moves.toList(), "there is one snake")
        assertTrue(element("steer-north").classList.contains("held"))
    }

    @Test
    fun `the pad is withdrawn the moment there is nothing to steer, and lets go as it goes`() {
        pad.render(model(steering = true))
        press("steer-north")
        moves.clear()

        pad.render(model(steering = false))

        assertTrue(element("steer-pad").hidden)
        // The release would land on an element the page has hidden, so a hold left standing here is
        // one nothing could ever end.
        repeat.frame(0.0)
        repeat.frame(1_000.0)
        assertEquals(emptyList(), moves.toList())
    }

    @Test
    fun `a board with no pad under it is not moved for one`() {
        pad.render(model(steering = false))
        pad.place()

        assertTrue(element("steer-pad").hidden)
        assertFalse(element("board-wrap").classList.contains("pad-below"), "the board stays centred")
    }

    @Test
    fun `a placed pad is square, and only a pad under the board moves it`() {
        pad.render(model(steering = true))
        pad.place()

        val padded = element("steer-pad").className == "below"
        assertEquals(padded, element("board-wrap").classList.contains("pad-below"))
        assertEquals(element("steer-pad").style.width, element("steer-pad").style.height, "square")
    }

    @Test
    fun `a tall track puts the pad under the board and a wide one puts it beside`() {
        // A phone in portrait: the board is as wide as the room and the strip is under it.
        val portrait = SteerPad.fitInto(wrapWidth = 390.0, wrapHeight = 700.0, boardWidth = 381.0, boardHeight = 381.0)
        assertFalse(portrait.beside)

        // The same phone turned over. The board is now as tall as the room and the strip is to one
        // side of it, which is the whole reason the side is measured rather than assumed.
        val landscape = SteerPad.fitInto(wrapWidth = 800.0, wrapHeight = 260.0, boardWidth = 250.0, boardHeight = 250.0)
        assertTrue(landscape.beside)
    }

    @Test
    fun `the pad fills the strip it is given, between a thumb's floor and a ceiling`() {
        val roomy = SteerPad.fitInto(wrapWidth = 390.0, wrapHeight = 700.0, boardWidth = 381.0, boardHeight = 381.0)
        assertEquals(SteerPad.MAX_SIZE, roomy.size, "a strip larger than the pad wants leaves the board dominant")

        val snug = SteerPad.fitInto(wrapWidth = 420.0, wrapHeight = 560.0, boardWidth = 390.0, boardHeight = 390.0)
        assertEquals(170.0 - 2 * SteerPad.GAP, snug.size, "and a smaller one is filled")

        // Beside the board the pad may have half the strip, because the board stays centred there —
        // so the same room to one side buys half the pad it would buy underneath.
        val wide = SteerPad.fitInto(wrapWidth = 700.0, wrapHeight = 400.0, boardWidth = 390.0, boardHeight = 390.0)
        assertTrue(wide.beside)
        assertEquals(310.0 / 2 - 2 * SteerPad.GAP, wide.size)

        // A board that nearly fills its track. The pad overlaps the outermost squares rather than
        // shrinking into arrows nobody could hit -- which is what its translucency is for.
        val tight = SteerPad.fitInto(wrapWidth = 400.0, wrapHeight = 410.0, boardWidth = 390.0, boardHeight = 390.0)
        assertEquals(SteerPad.MIN_SIZE, tight.size)
    }

    // -- internals

    private fun element(id: String): HTMLElement =
        document.getElementById(id) as? HTMLElement ?: error("the test skeleton is missing #$id")

    private fun press(id: String, pointerId: Int = 1) {
        element(id).dispatchEvent(
            PointerEvent("pointerdown", PointerEventInit(pointerId = pointerId, bubbles = true)),
        )
    }

    /** On the window, which is where the release listens — see [SteerPad]'s note on why it is there. */
    private fun fire(type: String) {
        window.dispatchEvent(PointerEvent(type, PointerEventInit(pointerId = 1, bubbles = true)))
    }

    private fun model(steering: Boolean): UiModel = UiModel(
        screen = Screen.GAME,
        level = null,
        gauntlet = GauntletProgress.NONE,
        levelCleared = false,
        openPanel = null,
        theme = THEME,
        result = null,
        resultPortrait = null,
        replay = false,
        interactive = steering,
        steering = steering,
        running = false,
        turnCount = 0,
        status = "your move",
        stats = Match(SETUP, ONE_SEAT).stats(),
        labels = SlotLabels(SETUP, ONE_SEAT),
        portraits = SlotPortraits(SETUP, { null }, THEME),
        hover = null,
        canWatchReplay = false,
        shareUrl = null,
        tournament = null,
    )

    private companion object {
        val THEME: Theme = Theme.of(Theme.DEFAULT_ID, dark = false)

        /** The board's container, the canvas the pad is measured against, and the pad itself. */
        val SKELETON = """
            <div class="board-wrap" id="board-wrap">
                <canvas id="board"></canvas>
                <div id="steer-pad" hidden>
                    <button id="steer-north" class="steer" type="button"></button>
                    <button id="steer-west" class="steer" type="button"></button>
                    <button id="steer-east" class="steer" type="button"></button>
                    <button id="steer-south" class="steer" type="button"></button>
                </div>
            </div>
        """.trimIndent()

        val SETUP: MatchSetup = MatchSetup.create(rows = 8, cols = 8, slots = listOf(BotId("space")), seed = 1)

        /** One seat, so a real `MatchStats` can be taken off a real board: the model needs one. */
        val ONE_SEAT = object : BotRegistry {
            private val entry = BotEntry(BotId("space"), "Space", BotFactory { Quitter })

            override val entries: List<BotEntry> = listOf(entry)

            override fun get(id: BotId): BotEntry? = entry.takeIf { it.id == id }
        }

        object Quitter : Bot {
            override fun chooseMove(turn: Turn): Decision = Decision.Resign
        }
    }
}
