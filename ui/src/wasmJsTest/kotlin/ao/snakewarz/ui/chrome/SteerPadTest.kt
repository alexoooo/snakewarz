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
import org.w3c.dom.HTMLButtonElement
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
    private val pad = SteerPad(repeat)

    @AfterTest
    fun detach() {
        repeat.cancel()
        skeleton.remove()
    }

    @Test
    fun `a press plays one move, and holding is the keyboard's own clock`() {
        pad.render(model(steering = true))
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
            pad.render(model(steering = true))
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
        pad.render(model(steering = true))
        press("steer-west")
        fire("pointerup")
        fire("pointerup")
        moves.clear()

        press("steer-south")

        assertEquals(listOf(Direction.SOUTH), moves)
    }

    @Test
    fun `a second finger does not take the snake off the first`() {
        pad.render(model(steering = true))
        press("steer-north")
        moves.clear()

        press("steer-south", pointerId = 2)

        assertEquals(emptyList(), moves.toList(), "there is one snake")
        assertTrue(element("steer-north").classList.contains("held"))
    }

    @Test
    fun `the pad is disabled the moment there is nothing to steer, and lets go as it goes`() {
        pad.render(model(steering = true))
        press("steer-north")
        moves.clear()

        pad.render(model(steering = false))

        assertFalse(element("steer-pad").hidden)
        assertTrue((element("steer-north") as HTMLButtonElement).disabled)
        // A disabled pad cannot receive the release that would ordinarily end its hold, so it must
        // let go while it is being disabled.
        repeat.frame(0.0)
        repeat.frame(1_000.0)
        assertEquals(emptyList(), moves.toList())
    }

    @Test
    fun `all four desktop controls use the two-row WASD mapping`() {
        pad.render(model(steering = true))

        for ((id, direction) in listOf(
            "steer-north" to Direction.NORTH,
            "steer-west" to Direction.WEST,
            "steer-south" to Direction.SOUTH,
            "steer-east" to Direction.EAST,
        )) {
            moves.clear()
            press(id)
            fire("pointerup")
            assertEquals(listOf(direction), moves, id)
        }
        assertFalse(element("steer-pad").hidden, "the pad is offered without a coarse-pointer query")
    }

    @Test
    fun `a bot-only board does not reserve room for a pad`() {
        pad.render(model(steering = false, steeringPad = false))

        assertTrue(element("steer-pad").hidden)
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

    private fun model(steering: Boolean, steeringPad: Boolean = true): UiModel = UiModel(
        screen = Screen.GAME,
        level = null,
        gauntlet = GauntletProgress.NONE,
        levelCleared = false,
        openPanel = null,
        theme = THEME,
        result = null,
        resultPortrait = null,
        replay = false,
        canTryAgain = false,
        replayNextLevel = null,
        interactive = steering,
        steering = steering,
        steeringPad = steeringPad,
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
