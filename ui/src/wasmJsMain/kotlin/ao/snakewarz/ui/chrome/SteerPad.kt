package ao.snakewarz.ui.chrome

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.ui.model.UiModel
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.pointerevents.PointerEvent

/**
 * Four arrows for a device with no arrow keys, put in the room the board does not use.
 *
 * A phone has no keyboard and a finger on the board covers the very squares it is aiming at, so the
 * pointer's drawn route cannot be the only way to steer one. These buttons feed [SteerRepeat] — the
 * same clock a held arrow key drives — so a thumb and a keyboard are one control path rather than two
 * that could disagree about how fast a held direction repeats.
 *
 * **The board never gives up a pixel for it.** The canvas is square and its track rarely is, so a
 * fitted board leaves a strip of the container over and under it, or to either side; [place] measures
 * that strip and puts the pad in whichever of the two is deeper. The pad is positioned out of flow,
 * so nothing it does can reach `BoardRenderer.fit` — which measures `.board-wrap` and would otherwise
 * shrink the board to make room for the thing that is only there to steer it. Where the strip is too
 * shallow even for [MIN_SIZE] the pad overlaps the board rather than disappearing, because a control
 * that vanishes on one board size is worse than one that is briefly in the way.
 *
 * A pad *under* the board also writes a class on the container, which is what moves the board to the
 * top of its track and hands the pad the strip whole — height being the thing a portrait phone has
 * least of. Beside it there is no such rule and the board stays centred, because a wide track always
 * leaves more room to one side than the pad wants. Both live behind the same media query as the pad
 * itself, so a page with no finger on it never moves a board for a pad it is not showing.
 */
internal class SteerPad(
    private val boardWrap: HTMLElement,
    private val board: HTMLElement,
    private val repeat: SteerRepeat,
) {
    private val root: HTMLElement = elementById("steer-pad")

    private val buttons: List<Pair<HTMLButtonElement, Direction>> = listOf(
        elementById<HTMLButtonElement>("steer-north") to Direction.NORTH,
        elementById<HTMLButtonElement>("steer-south") to Direction.SOUTH,
        elementById<HTMLButtonElement>("steer-west") to Direction.WEST,
        elementById<HTMLButtonElement>("steer-east") to Direction.EAST,
    )

    /** The direction under the finger, or `null` when nothing is being held. */
    private var held: Direction? = null

    /** Which strip the pad was last measured into, and whether the player has a snake to steer. */
    private var beside: Boolean = false
    private var offered: Boolean = false

    init {
        for ((button, direction) in buttons) {
            // The press is the button's and the rest of the gesture is the pad's, so that a thumb
            // rolled from one arrow onto the next is re-aimed rather than stopping dead on the edge
            // of the one it started on.
            button.addEventListener("pointerdown") { event -> press(event as PointerEvent, direction) }

            // Enter, because that is what activates every other control on this page. Space is the
            // match's on the game screen — `Chrome.onKeyDown` cancels it — so a pad button focused by
            // Tab answers the one key a person would try next.
            button.addEventListener("keydown") { event ->
                val key = event as KeyboardEvent
                if (key.key == "Enter" && !key.repeat) {
                    hold(direction)
                }
            }
            button.addEventListener("keyup") { event ->
                if ((event as KeyboardEvent).key == "Enter") {
                    release()
                }
            }
        }

        root.addEventListener("pointermove") { event -> aim(event as PointerEvent) }

        // The release is the **window's**, and that is the one thing here that is not local. A thumb
        // that leaves the pad before it lifts sends its `pointerup` to whatever it is over instead,
        // and a hold nothing ends is a snake walking with nobody holding it. `setPointerCapture`
        // would route it back, and is not worth what it costs: it throws outright on a pointer the
        // browser has stopped tracking, taking the press down with it, and everything it would buy
        // for the aiming is already had from the moves that arrive while the thumb is on the pad.
        window.addEventListener("pointerup") { release() }
        window.addEventListener("pointercancel") { release() }
    }

    /**
     * Shows the pad exactly while there is a snake to steer with it.
     *
     * [UiModel.steering] is the predicate a press on the *board* answers to as well, so what the pad
     * offers and what the board would accept cannot come apart. Absent rather than greyed, for the
     * reason `Mode.offers` hides a panel opener: watching two bots fight, this could never apply.
     */
    fun render(model: UiModel) {
        offered = model.steering
        // A pad withdrawn under a thumb that is still down — the player has just been eliminated —
        // has to let go of it here: the release lands on an element the page has hidden, and a hold
        // nobody can end would leave the next match unsteerable by the pad it is holding for.
        if (!offered) {
            release()
        }
        write()
    }

    /**
     * Puts the pad in the deeper of the two strips the fitted board leaves, and sizes it to fit.
     *
     * Follows every fit, exactly as the overlay does, and for a sharper reason than tidiness: the pad
     * hangs off the board's own edges, so a board that has just changed size or shape leaves it over
     * the squares or stranded in the middle of the page.
     */
    fun place() {
        val wrap = boardWrap.getBoundingClientRect()
        val canvas = board.getBoundingClientRect()
        val fit = fitInto(wrap.width, wrap.height, canvas.width, canvas.height)

        beside = fit.beside
        root.style.width = "${fit.size}px"
        root.style.height = "${fit.size}px"
        // The arrows are drawn in text, so the pad's own font is how they follow its size — a glyph
        // left at a fixed size is a dot on a pad with room and a smudge on one without.
        root.style.fontSize = "${fit.size * GLYPH_FRACTION}px"

        write()
    }

    override fun toString(): String = "SteerPad(${held ?: "up"})"

    // -- internals

    /**
     * Where the pad is, and whether it is there at all.
     *
     * One writer for both, because the container's alignment is only right while the pad is actually
     * showing: left behind, it would hold a board with nothing under it against the top of its track.
     */
    private fun write() {
        root.hidden = !offered
        root.className = if (beside) "beside" else "below"
        boardWrap.classList.toggle("pad-below", offered && !beside)
    }

    /**
     * Takes hold of one arrow with one pointer.
     *
     * A second finger arriving while the first is down is ignored rather than taking over: there is
     * one snake, and two thumbs disagreeing about where it goes is not something anybody asked for.
     * The default is cancelled so that a tap neither focuses the button nor comes back as a
     * synthesised click a moment later — one press must be one move.
     */
    private fun press(event: PointerEvent, direction: Direction) {
        event.preventDefault()
        if (held != null) {
            return
        }
        hold(direction)
    }

    /**
     * Re-aims a thumb that has slid onto another arrow.
     *
     * Sliding *off* the pad entirely keeps the direction it left with rather than stopping, because
     * the gap between two arrows is somewhere a thumb crosses on its way round a corner and not a
     * place anybody means to let go at. Letting go is [release], and it is the only thing that stops.
     */
    private fun aim(event: PointerEvent) {
        if (held == null) {
            return
        }
        val direction = directionAt(event.clientX.toDouble(), event.clientY.toDouble())
        if (direction != null && direction != held) {
            hold(direction)
        }
    }

    private fun directionAt(clientX: Double, clientY: Double): Direction? =
        buttons.firstOrNull { (button, _) ->
            val box = button.getBoundingClientRect()
            clientX >= box.left && clientX < box.right && clientY >= box.top && clientY < box.bottom
        }?.second

    /**
     * Takes the arrow, lights it up, and plays the move.
     *
     * The class is written rather than left to `:active` because [press] cancels the pointer's
     * default action, and a browser that skips the state it would otherwise have applied leaves a
     * pad that does not answer a thumb — which reads as a press that missed.
     */
    private fun hold(direction: Direction) {
        for ((button, of) in buttons) {
            button.classList.toggle("held", of == direction)
        }
        held = direction
        repeat.press(direction)
    }

    /**
     * Lets go, once.
     *
     * Every pointer the page sees comes through here, so the guard is doing real work: most of them
     * end nothing, and a release that stops a hold nobody has costs nothing either.
     */
    private fun release() {
        val direction = held ?: return
        held = null
        for ((button, _) in buttons) {
            button.classList.remove("held")
        }
        repeat.release(direction)
    }

    /** Where the pad goes and how large it is there — the whole of [place] that is not a measurement. */
    class Fit(val beside: Boolean, val size: Double)

    internal companion object {
        /** How far the pad keeps from the board and from the edges of the room it shares with it. */
        const val GAP = 8.0

        /**
         * A third of this is one arrow, so the floor is a target a thumb can hit and the ceiling is
         * what stops a pad on a tablet growing into the largest thing on the screen. The board is
         * what somebody came to look at.
         */
        const val MIN_SIZE = 120.0
        const val MAX_SIZE = 200.0

        /** An arrow, as a share of the whole pad. Roughly half the button it is centred in. */
        const val GLYPH_FRACTION = 0.15

        /**
         * Which strip of the container the fitted board leaves more of, and the largest square that
         * fits in it.
         *
         * The side is chosen by comparing the two whole strips, which is the orientation question:
         * a board in a tall track leaves its room above and below, and one in a wide track to either
         * side of it.
         *
         * **What each side may then claim differs, and the phone is why.** Height is what a portrait
         * phone has least of, so the board goes to the top of its track and the pad takes the strip
         * *whole* — the arrangement every game on that shape of screen has. Width is never that tight:
         * a landscape phone leaves more to one side of its board than the pad would ever want, so the
         * board stays centred where it belongs and the pad takes half, against the edge a thumb is
         * already holding.
         *
         * Separated from the measuring for the reason `BoardRenderer.cellAt` is arithmetic over a
         * bounding box — a rule about two rectangles needs no page to be checked against.
         */
        fun fitInto(wrapWidth: Double, wrapHeight: Double, boardWidth: Double, boardHeight: Double): Fit {
            val under = wrapHeight - boardHeight
            val alongside = wrapWidth - boardWidth
            val beside = alongside > under

            val strip = if (beside) alongside / 2 else under
            val across = if (beside) wrapHeight else wrapWidth
            // Clamped low as well as high: where the board very nearly fills its track there is no
            // strip to speak of, and a pad shrunk to fit one would be arrows nobody can hit. It
            // overlaps the outermost squares instead, which is what its translucency is for.
            return Fit(beside, minOf(strip - 2 * GAP, across - 2 * GAP).coerceIn(MIN_SIZE, MAX_SIZE))
        }
    }
}
