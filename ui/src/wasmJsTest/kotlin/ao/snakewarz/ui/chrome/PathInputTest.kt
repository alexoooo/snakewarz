package ao.snakewarz.ui.chrome

import ao.snakewarz.ui.model.UiIntent
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The press and the release, and the several ways a browser can end one.
 *
 * Everything about *where* the pointer was — the grace radius around the head, whether a route began
 * at all — belongs to `GameSession` and is not reachable from here; what is under test is that one
 * press produces one [UiIntent.PathBegan] and one release produces one [UiIntent.PathReleased],
 * however the release arrives. That last part is the whole reason this file exists: at least two of
 * the four events fire for every ordinary press, and a release reported twice would stop a snake the
 * player is still holding.
 *
 * Pointer capture is not asserted, and cannot be: a synthesised [PointerEvent] creates no active
 * pointer for the browser to hand over, so this drives the state machine and the real thing is
 * driven with a real mouse and a real finger.
 */
class PathInputTest {
    private val board: HTMLCanvasElement = (document.createElement("canvas") as HTMLCanvasElement).also {
        document.body?.appendChild(it)
    }

    private val intents = mutableListOf<UiIntent>()
    private val path = PathInput(board) { intents += it }

    @AfterTest
    fun detach() {
        board.remove()
    }

    @Test
    fun `a press reports where it landed`() {
        press(clientX = 37, clientY = 91)

        val began = intents.single() as UiIntent.PathBegan
        assertEquals(37.0, began.clientX)
        assertEquals(91.0, began.clientY)
        assertTrue(path.pressing, "so a move over the board now means a route")
    }

    @Test
    fun `a release with no press is harmless`() {
        // A click that began somewhere else, or a stray cancel from the browser. There is nothing to
        // let go of, and reporting one would stop a match nobody was steering.
        fire("pointerup")
        fire("pointercancel")
        fire("lostpointercapture")

        assertEquals(emptyList(), intents.toList())
        assertFalse(path.pressing)
    }

    @Test
    fun `capture taken away is a release`() {
        // The case the guard exists for. A browser that decides the gesture is something else takes
        // capture back without a pointerup, and a snake left walking a route nobody is holding is
        // exactly what release is meant to prevent.
        press()
        intents.clear()

        fire("lostpointercapture")

        assertEquals(listOf(UiIntent.PathReleased), intents.toList())
        assertFalse(path.pressing)
    }

    @Test
    fun `one release is reported once, however many events say so`() {
        // pointerup is followed by an implicit lostpointercapture for every captured press, so both
        // arrive every time. Two PathReleased would be two attempts to stop a snake already stopped.
        press()
        intents.clear()

        fire("pointerup")
        fire("lostpointercapture")

        assertEquals(listOf(UiIntent.PathReleased), intents.toList())
    }

    @Test
    fun `cancelling a press makes its late release harmless and leaves the next press free`() {
        press(pointerId = 1)
        path.cancel()
        intents.clear()

        fire("pointerup")
        press(pointerId = 1, clientX = 19, clientY = 23)

        val began = intents.single() as UiIntent.PathBegan
        assertEquals(19.0, began.clientX)
        assertEquals(23.0, began.clientY)
        assertTrue(path.pressing)
    }

    @Test
    fun `a second finger does not take the route off the first`() {
        press(pointerId = 1)
        intents.clear()

        press(pointerId = 2)

        assertEquals(emptyList(), intents.toList(), "there is one snake and one route")
        assertTrue(path.pressing)
    }

    // -- internals

    private fun press(pointerId: Int = 1, clientX: Int = 0, clientY: Int = 0) {
        board.dispatchEvent(
            PointerEvent(
                "pointerdown",
                PointerEventInit(pointerId = pointerId, clientX = clientX, clientY = clientY, bubbles = true),
            ),
        )
    }

    private fun fire(type: String) {
        board.dispatchEvent(PointerEvent(type, PointerEventInit(pointerId = 1, bubbles = true)))
    }
}
