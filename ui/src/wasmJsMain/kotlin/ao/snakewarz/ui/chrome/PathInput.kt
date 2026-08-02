package ao.snakewarz.ui.chrome

import ao.snakewarz.ui.model.UiIntent
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.pointerevents.PointerEvent

/**
 * A finger or a mouse held down on the board, reported as the two moments a drawn route has.
 *
 * A press and a release, and nothing in between: the moves that happen while one is held are the
 * board's own `pointermove`, which [Chrome] forks on [pressing] because the same event means a route
 * while a pointer is down and a question about what is under it while one is not.
 *
 * Nothing here knows where the snake is or whether a route began. A press within reach of the head
 * takes hold of the snake and a press anywhere else falls through to hover, and both of those are
 * `GameSession`'s to decide — this reports what the pointer did.
 *
 * **The pointer is captured for the length of the press.** `.board-wrap` clips its overflow and the
 * board is one element on a page with no room to spare, so a finger sliding off the edge of it
 * mid-drag is ordinary rather than exceptional; without capture the events stop arriving and the
 * snake runs on with nobody holding it. `lostpointercapture` is therefore a release and is handled
 * as one, because a browser that takes capture back — a gesture it decides is something else, a
 * device that disappears — leaves exactly that.
 */
internal class PathInput(
    private val board: HTMLCanvasElement,
    private val dispatch: (UiIntent) -> Unit,
) {
    /** Whether a pointer is down on the board, and so whether a move over it is drawing a route. */
    var pressing: Boolean = false
        private set

    private var pointerId: Int = 0

    init {
        board.addEventListener("pointerdown") { event -> press(event as PointerEvent) }
        board.addEventListener("pointerup") { release() }
        board.addEventListener("pointercancel") { release() }
        board.addEventListener("lostpointercapture") { release() }
    }

    /**
     * Forgets a press whose board is no longer steerable.
     *
     * Match replacement is synchronous, but the browser owns the matching pointer release and may
     * deliver it after the replacement. Marking the press ended before releasing capture makes that
     * late event harmless instead of carrying the old gesture into the new match.
     */
    fun cancel() {
        if (!pressing) {
            return
        }
        pressing = false
        if (board.hasPointerCapture(pointerId)) {
            board.releasePointerCapture(pointerId)
        }
    }

    override fun toString(): String = "PathInput(pressing=$pressing)"

    /**
     * Takes hold of the board with one pointer.
     *
     * A second finger arriving while the first is still down is ignored rather than taking over.
     * There is one snake being steered and one route being drawn, and a route that changes hands
     * part-way through is not something anybody asked for.
     */
    private fun press(event: PointerEvent) {
        if (pressing) {
            return
        }
        pressing = true
        pointerId = event.pointerId
        board.setPointerCapture(pointerId)
        dispatch(UiIntent.PathBegan(event.clientX.toDouble(), event.clientY.toDouble()))
    }

    /**
     * Lets go, once.
     *
     * Four events mean this and at least two of them arrive for every press — `pointerup` is
     * followed by an implicit `lostpointercapture` — so the guard is what keeps one release from
     * being reported twice. Releasing the capture we took also raises `lostpointercapture`, and
     * lands back here on the same guard.
     */
    private fun release() {
        if (!pressing) {
            return
        }
        pressing = false
        if (board.hasPointerCapture(pointerId)) {
            board.releasePointerCapture(pointerId)
        }
        dispatch(UiIntent.PathReleased)
    }
}
