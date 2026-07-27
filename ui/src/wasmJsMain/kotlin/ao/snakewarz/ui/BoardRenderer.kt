package ao.snakewarz.ui

import ao.snakewarz.core.BoardView
import ao.snakewarz.core.Cell
import ao.snakewarz.core.Grid
import ao.snakewarz.core.SnakeId
import ao.snakewarz.core.SnakeView
import ao.snakewarz.match.TurnEvents
import kotlinx.browser.window
import org.w3c.dom.CanvasLineCap
import org.w3c.dom.CanvasLineJoin
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.ROUND
import kotlin.math.PI

/**
 * Paints a [BoardView] onto a 2D canvas, one changed square at a time.
 *
 * A normal turn dirties one or two squares, so painting a whole board per turn would be two orders
 * of magnitude of wasted work at the speeds this thing runs at. [paintMove] repaints what changed;
 * [repaint] exists for the three moments when everything did — start, resize and seek.
 *
 * ### Gridlines are painted once, and survive because nothing else touches them
 *
 * A cell of side `s` owns the pixels `(c*s + 1, r*s + 1)` to `(c*s + s, r*s + s)`: the one-pixel
 * gutter along its top and left edge belongs to the gridline, never to a fill. So the lines are
 * drawn once per resize and every later fill lands strictly inside them. The design doc reached the
 * same end with a second underlay canvas; inset fills get there with one canvas, no stacking context
 * and no second device-pixel-ratio dance, which is worth more than the symmetry.
 *
 * ### Age is read off the position, not remembered
 *
 * A snake's oldest square fades in two steps before it clears — see [tailAlpha]. That is derived
 * from the board every time it is painted rather than kept in a counter here, so seeking a replay,
 * resizing the window and painting a tournament's current match all land on the same colours as
 * playing the match forwards would have.
 *
 * ### The board is painted per square; the overlay is painted whole
 *
 * Two bitmaps and two cadences. Everything above is the board, where a turn dirties two or three
 * squares. The thread through each body and the wash over the hovered one are on a second canvas
 * and are redrawn end to end every time the position moves — because a body that shifted by one
 * square has a thread that shifted along its whole length, so there is no such thing as a dirty
 * square for it. [paintOverlay] is what does that, and it has to follow every paint below.
 *
 * The renderer knows nothing about matches, bots or time. It is handed a read-only projection of the
 * position and a list of dirty cells, and neither contains a pixel or a colour.
 */
internal class BoardRenderer(
    private val canvas: HTMLCanvasElement,
    private val overlay: HTMLCanvasElement,
) {
    private val context: CanvasRenderingContext2D = canvas.getContext("2d") as? CanvasRenderingContext2D
        ?: error("this browser has a canvas but no 2d context")

    private val overlayContext: CanvasRenderingContext2D = overlay.getContext("2d") as? CanvasRenderingContext2D
        ?: error("this browser has a canvas but no 2d context")

    /**
     * The ratio the board's size is anchored to: whatever the display reported when the page loaded.
     *
     * This is the whole of "zooming moves the text and leaves the board alone". A CSS pixel covers
     * `devicePixelRatio` device pixels, so a board of a constant CSS size grows on the glass as you
     * zoom in and a board of a constant *device* size does not. Reading the ratio once carries the
     * display's own scale factor — 1.25, 1.5 and 1.75 are all ordinary on Windows — so the board
     * comes out the same number of millimetres on every machine, and everything the player zooms
     * afterwards is HTML doing what HTML should.
     *
     * Once, rather than per fit, because a single reading cannot tell a 150% zoom from a 1.5x
     * display. Anchoring on the ratio the page opened at is the honest limit of what the platform
     * reports; the cost is that reloading at a different zoom re-anchors.
     */
    private val bootRatio: Double = ratioNow()

    private var palette: Palette = Palette.of(prefersDark())
    private var grid: Grid = Grid(1, 1)
    private var cellSize: Int = MIN_CELL

    /** The square the wash is drawn for, so a turn can redraw it without re-deciding. */
    private var hovered: Cell = Cell.NONE

    /**
     * The square each snake's head was last painted on.
     *
     * `TurnEvents` reports the two squares the *engine* changed — the new head and the vacated tail
     * — and a head that became ordinary body is not one of them, because as far as the engine is
     * concerned nothing about that square changed. Drawing heads differently is a renderer's idea,
     * so tracking the handoff is a renderer's job.
     */
    private var heads: IntArray = IntArray(0)

    /**
     * Resizes the backing store to the fixed board and repaints. Start, resize and seek.
     *
     * The board is **a fixed rectangle of device pixels**, so the grid decides only how finely that
     * rectangle is divided: an 8x8 and a 40x40 occupy the same frame at different magnifications,
     * and zooming the page moves the text around a board that stays where it is. Only a window with
     * no room for it can make it smaller, and both of those clamps are measured in device pixels
     * too — where zoom cancels out exactly, because the viewport loses CSS pixels at the same rate a
     * CSS pixel gains device ones.
     *
     * Everything below the CSS size is in **device** pixels, and the context is never scaled. The
     * obvious alternative — a backing store of `size * devicePixelRatio` and a matching
     * `context.scale` — puts every coordinate between two device pixels the moment the ratio is
     * fractional, and 1.25, 1.35 and 1.5 are all ordinary on Windows. A gridline then antialiases
     * into a soft two-pixel smear instead of the hairline it is meant to be. Choosing a whole number
     * of device pixels per cell makes every fill and every line land exactly, at any ratio.
     *
     * If you ever do re-introduce `context.scale`, note that setting `canvas.width` resets the
     * context transform, so it has to come after the resize and not with the rest of the setup.
     */
    fun fit(view: BoardView) {
        grid = view.grid

        val ratio = ratioNow()
        val room = minOf(
            ((canvas.parentElement as? HTMLElement)?.clientWidth?.takeIf { it > 0 } ?: FALLBACK_WIDTH) * ratio,
            window.innerHeight * ratio * HEIGHT_SHARE,
        )
        // Fitted by the longer side, so a 12x20 board sits inside the square a 20x20 fills. Rounded
        // against the target and floored against the room: half a pixel a cell either way is how a
        // 40x40 comes out the size of an 8x8, and how a board that has to shrink still fits.
        val span = maxOf(grid.rows, grid.cols)
        val wanted = (BOARD_EXTENT * bootRatio - 1) / span + 0.5
        val fits = (room - 1) / span
        cellSize = minOf(wanted, fits).toInt().coerceAtLeast((MIN_CELL * bootRatio).toInt().coerceAtLeast(1))

        val width = cellSize * grid.cols + 1
        val height = cellSize * grid.rows + 1

        canvas.width = width
        canvas.height = height
        canvas.style.width = "${width / ratio}px"
        canvas.style.height = "${height / ratio}px"

        // Sized off the same integers rather than measured, so the two bitmaps line up exactly
        // whatever the CSS around them does. Setting `width` also clears it, which is why the
        // overlay is redrawn below rather than left to the next turn.
        overlay.width = width
        overlay.height = height
        overlay.style.width = canvas.style.width
        overlay.style.height = canvas.style.height

        repaint(view)
        drawOverlay(view)
    }

    /** Switches themes. The caller follows with [fit], because the gridlines change colour too. */
    fun applyScheme(dark: Boolean) {
        palette = Palette.of(dark)
    }

    /**
     * The square under a point in client coordinates, or [Cell.NONE] if that is not on the board.
     *
     * Measured rather than read off `offsetX`: the canvas's CSS size is deliberately fractional — a
     * whole number of device pixels divided by the ratio — so the only honest scale from a client
     * coordinate to a backing-store one is the box the element is actually drawn at. That box is
     * the *border* box, which is why `#board` carries an outline instead of a border.
     */
    fun cellAt(clientX: Double, clientY: Double): Cell {
        val box = canvas.getBoundingClientRect()
        if (box.width <= 0.0 || box.height <= 0.0) {
            return Cell.NONE
        }

        val x = (clientX - box.left) * canvas.width / box.width
        val y = (clientY - box.top) * canvas.height / box.height
        if (x < 0.0 || y < 0.0 || x >= canvas.width || y >= canvas.height) {
            return Cell.NONE
        }

        // A cell of side s owns the pixels `c*s + 1` to `c*s + s` — the convention every fill uses,
        // so the square the pointer is visibly over is the square this answers with.
        val col = (x.toInt() - 1) / cellSize
        val row = (y.toInt() - 1) / cellSize
        return if (grid.contains(row, col)) grid.cellAt(row, col) else Cell.NONE
    }

    /**
     * Repaints the overlay, picking out whoever holds [cell] and nobody when that is nobody.
     *
     * The whole overlay, because the threads on it move with the snakes: this is the one call that
     * has to follow every [paintMove], [paintSnake] and [repaint], not merely every pointer move.
     *
     * A *square* is what is remembered here, never a snake — so a restart, a seek and a tournament
     * moving on to its next match all resolve to whoever holds that square now. That is the same
     * rule every colour on this board already follows: read it off the position, do not keep it.
     */
    fun paintOverlay(view: BoardView, cell: Cell) {
        hovered = cell
        drawOverlay(view)
    }

    fun repaint(view: BoardView) {
        val width = (cellSize * grid.cols + 1).toDouble()
        val height = (cellSize * grid.rows + 1).toDouble()

        context.fillStyle = palette.background.toJsString()
        context.fillRect(0.0, 0.0, width, height)

        context.strokeStyle = palette.gridline.toJsString()
        context.lineWidth = 1.0
        context.beginPath()
        for (col in 0..grid.cols) {
            val x = col * cellSize + 0.5
            context.moveTo(x, 0.0)
            context.lineTo(x, height)
        }
        for (row in 0..grid.rows) {
            val y = row * cellSize + 0.5
            context.moveTo(0.0, y)
            context.lineTo(width, y)
        }
        context.stroke()

        if (heads.size != view.snakeCount) {
            heads = IntArray(view.snakeCount)
        }
        for (slot in 0 until view.snakeCount) {
            paintSnake(view, SnakeId(slot))
        }
    }

    /**
     * Repaints one snake end to end.
     *
     * This is how a death is drawn. A death dirties no cells — the loser does not move, it changes
     * colour — so `TurnEvents` correctly reports nothing and the whole body needs recolouring.
     */
    fun paintSnake(view: BoardView, id: SnakeId) {
        val snake = view.snake(id)
        val colour = Palette.bodyColour(id.index)
        val alpha = if (snake.alive) 1.0 else Palette.CORPSE_ALPHA

        for (i in 0 until snake.length) {
            // cellAt(0) is the tail, and the tail is the one square whose colour is not the body's.
            fill(snake.cellAt(i), colour, if (i == 0) tailAlpha(view, snake) else alpha)
        }
        if (snake.alive) {
            fill(snake.head, palette.head(id.index), 1.0)
        }
        heads[id.index] = snake.head.index
    }

    /** The one or two squares a surviving move changed, plus the head handoff and the fading tail. */
    fun paintMove(view: BoardView, mover: SnakeId, events: TurnEvents) {
        for (i in 0 until events.size) {
            paintOwner(view, events.cellAt(i))
        }
        paintOwner(view, Cell(heads[mover.index]))

        val snake = view.snake(mover)
        // The tail dims where it stands, so on a growing turn the engine reports no dirty cell for
        // it — the square did not change hands, only how much longer the mover will hold it. The
        // square the fade came *from* is either this same one or the one the engine just vacated,
        // and that one is in `events`, so there is no third square to chase and nothing to track.
        paintOwner(view, snake.tail)
        fill(snake.head, palette.head(mover.index), 1.0)
        heads[mover.index] = snake.head.index
    }

    /** Repaints [cell] as whoever holds it now, or as empty board if nobody does. */
    private fun paintOwner(view: BoardView, cell: Cell) {
        val owner = view.ownerOf(cell)
        if (owner.isNone) {
            fill(cell, palette.background, 1.0)
            return
        }

        val snake = view.snake(owner)
        val alpha = when {
            !snake.alive -> Palette.CORPSE_ALPHA
            cell == snake.tail -> tailAlpha(view, snake)
            else -> 1.0
        }
        fill(cell, Palette.bodyColour(owner.index), alpha)
    }

    /**
     * How much colour a snake's oldest square keeps: full, then [Palette.AGING_ALPHA], then
     * [Palette.DYING_ALPHA], and then the square is empty board.
     *
     * The board already knows this and nothing has to be remembered between turns to read it:
     * `growsOnNextMove` is false exactly when the next move drags the body instead of extending it,
     * which is the move that gives this square back. So a snake's tail spends one of its own moves
     * aging and the next one dying, and a player can see where space is about to open up instead of
     * counting growth turns.
     *
     * Two rules out. A dead snake is a permanent obstacle, so a corpse keeps the corpse colour all
     * the way to its tail. And a trail that never retracts — `growEveryNthMove = 1`, classic Tron —
     * has no square about to clear, so fading one would be a lie about the rules in play.
     */
    private fun tailAlpha(view: BoardView, snake: SnakeView): Double = when {
        !snake.alive -> Palette.CORPSE_ALPHA
        tailClearsNext(view, snake) -> Palette.DYING_ALPHA
        // A trail that never retracts has no square about to open, and a one-square snake is all
        // head — which is painted over this anyway.
        view.rules.growEveryNthMove < 2 || snake.length < 2 -> 1.0
        else -> Palette.AGING_ALPHA
    }

    /**
     * Repaints the overlay: a thread through every snake, and a wash over the hovered one.
     *
     * The split is what each cue is *for*. The thread traces where a body ran, which a coil doubled
     * back beside itself makes genuinely hard to read off the squares alone — and that is true of
     * every snake on the board, every turn, whether or not a pointer is anywhere near it. So the
     * thread is drawn always. The wash picks *one* snake out of the others, which is a question only
     * a pointer asks, so it stays with the pointer.
     *
     * Order matters exactly once: the wash goes down first, so hovering lays a tint under the
     * threads rather than rearranging them, and the picked-out snake's own thread stays on top of
     * its own wash.
     *
     * Nothing here says anything about the tail being about to clear. The board already fades that
     * square and the label already says it in words; a third telling of one fact is noise.
     *
     * The cost is a redraw proportional to how much of the board is occupied, once per turn rather
     * than once per changed square — which is the same order the hovered snake already cost, times
     * the number of snakes. It is bounded by the clock above it: [TurnScheduler] tops out at eighty
     * turns a second and a batch repaints once a frame.
     */
    private fun drawOverlay(view: BoardView) {
        overlayContext.clearRect(0.0, 0.0, overlay.width.toDouble(), overlay.height.toDouble())

        val hoveredOwner = if (grid.isPlayable(hovered)) view.ownerOf(hovered) else SnakeId.NONE
        if (!hoveredOwner.isNone) {
            // The head colour, which is the palette's answer to "this snake, but readable against
            // itself" — darker than the trail on a light page and lighter on a dark one, either way.
            wash(view.snake(hoveredOwner), palette.head(hoveredOwner.index))
        }

        for (slot in 0 until view.snakeCount) {
            drawThread(view, SnakeId(slot))
        }

        overlayContext.globalAlpha = 1.0
    }

    /**
     * The thread down one snake's body, and the marker where it ends.
     *
     * A corpse keeps the share of its colour that [Palette.CORPSE_ALPHA] gives it on the board, and
     * loses the head marker entirely — both for the reason [paintSnake] paints it that way. A snake
     * that is out is an obstacle, and an obstacle has no head to be watching.
     */
    private fun drawThread(view: BoardView, id: SnakeId) {
        val snake = view.snake(id)
        val tint = palette.head(id.index)
        val thread = palette.background
        val fade = if (snake.alive) 1.0 else Palette.CORPSE_ALPHA

        if (snake.length > 1) {
            overlayContext.lineCap = CanvasLineCap.ROUND
            overlayContext.lineJoin = CanvasLineJoin.ROUND
            overlayContext.strokeStyle = thread.toJsString()
            overlayContext.lineWidth = (cellSize * THREAD_WIDTH).coerceAtLeast(THREAD_MIN_WIDTH)

            // cellAt(0) is the tail, so `i` runs the body in the order it was laid down.
            for (i in 0 until snake.length - 1) {
                overlayContext.globalAlpha = fade * ramp(i, snake.length - 1, THREAD_TAIL, THREAD_HEAD)
                overlayContext.beginPath()
                overlayContext.moveTo(centreX(snake.cellAt(i)), centreY(snake.cellAt(i)))
                overlayContext.lineTo(centreX(snake.cellAt(i + 1)), centreY(snake.cellAt(i + 1)))
                overlayContext.stroke()
            }
        }

        if (!snake.alive) {
            return
        }

        // Where the thread ends, said outright: a snake coiled back on itself is ambiguous at a
        // glance, and a glance is all this gets.
        overlayContext.globalAlpha = 1.0
        overlayContext.fillStyle = thread.toJsString()
        overlayContext.strokeStyle = tint.toJsString()
        overlayContext.lineWidth = 1.0
        overlayContext.beginPath()
        overlayContext.arc(
            centreX(snake.head),
            centreY(snake.head),
            (cellSize * HEAD_RADIUS).coerceAtLeast(THREAD_MIN_WIDTH),
            0.0,
            2 * PI,
            false,
        )
        overlayContext.fill()
        overlayContext.stroke()
    }

    /** [i] of [count] steps from the tail, as a value from [tail] at the oldest square to [head]. */
    private fun ramp(i: Int, count: Int, tail: Double, head: Double): Double =
        if (count < 2) head else tail + (head - tail) * i / (count - 1)

    /**
     * A translucent tint over one whole body, brightening toward the head.
     *
     * Flat squares straight onto the overlay — unlike [fill] there is nothing underneath them to
     * bleed through, because the overlay was cleared a moment ago and the board is a separate bitmap.
     */
    private fun wash(snake: SnakeView, colour: String) {
        overlayContext.fillStyle = colour.toJsString()
        for (i in 0 until snake.length) {
            val cell = snake.cellAt(i)
            overlayContext.globalAlpha = ramp(i, snake.length, WASH_TAIL, WASH_HEAD)
            overlayContext.fillRect(
                (grid.colOf(cell) * cellSize + 1).toDouble(),
                (grid.rowOf(cell) * cellSize + 1).toDouble(),
                (cellSize - 1).toDouble(),
                (cellSize - 1).toDouble(),
            )
        }
    }

    private fun centreX(cell: Cell): Double = grid.colOf(cell) * cellSize + 1 + (cellSize - 1) / 2.0

    private fun centreY(cell: Cell): Double = grid.rowOf(cell) * cellSize + 1 + (cellSize - 1) / 2.0

    private fun fill(cell: Cell, colour: String, alpha: Double) {
        val x = (grid.colOf(cell) * cellSize + 1).toDouble()
        val y = (grid.rowOf(cell) * cellSize + 1).toDouble()
        val side = (cellSize - 1).toDouble()

        if (alpha < 1.0) {
            // Composite over the board rather than over whatever used to be here: a translucent fill
            // on top of a previous translucent fill would deepen every time the cell was repainted.
            context.fillStyle = palette.background.toJsString()
            context.fillRect(x, y, side, side)
            context.globalAlpha = alpha
        }

        context.fillStyle = colour.toJsString()
        context.fillRect(x, y, side, side)

        if (alpha < 1.0) {
            context.globalAlpha = 1.0
        }
    }

    /** Guarded, because a ratio of zero would divide the CSS size by nothing. */
    private fun ratioNow(): Double = window.devicePixelRatio.takeIf { it > 0.0 } ?: 1.0

    private companion object {
        /**
         * How wide and tall the board is, in CSS pixels at the ratio the page opened on — so, in
         * millimetres. The grid chooses only how many squares fit inside it.
         *
         * There is deliberately no maximum cell size to go with the minimum. A cap on the cell is
         * what used to make an 8x8 board a third of a 20x20 one, and it would fight this figure for
         * every size the picker offers.
         */
        const val BOARD_EXTENT = 640.0

        /** Below this a 40x40 board is unreadable. Also at CSS scale, and only a floor. */
        const val MIN_CELL = 6

        /**
         * How much of the viewport height the board may claim when [BOARD_EXTENT] will not fit.
         *
         * A fallback for a short window and nothing more, which is why it is close to all of it: at
         * two thirds an ordinary desktop viewport would clamp the board and its size would quietly
         * depend on window height again, which is the thing being fixed.
         */
        const val HEIGHT_SHARE = 0.9

        /** Only reached before the first layout, which `booted` ahead of `start()` already prevents. */
        const val FALLBACK_WIDTH = 640

        /**
         * The overlay: how much of the head colour a hovered square takes, oldest to newest, and
         * the thread that runs down the middle of every body.
         *
         * The thread is drawn in the board's own colour, so it reads against all six trail hues on
         * either theme without a seventh entry in the palette.
         */
        const val WASH_TAIL = 0.10
        const val WASH_HEAD = 0.62
        const val THREAD_TAIL = 0.30
        const val THREAD_HEAD = 0.95
        const val THREAD_WIDTH = 0.16
        const val HEAD_RADIUS = 0.24

        /** So the thread survives a 40x40 board, where a cell is about fifteen device pixels. */
        const val THREAD_MIN_WIDTH = 2.0
    }
}
