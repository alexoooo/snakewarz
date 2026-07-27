package ao.snakewarz.ui

import ao.snakewarz.core.BoardView
import ao.snakewarz.core.Cell
import ao.snakewarz.core.Grid
import ao.snakewarz.core.SnakeId
import ao.snakewarz.core.SnakeView
import ao.snakewarz.match.TurnEvents
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

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
 * The renderer knows nothing about matches, bots or time. It is handed a read-only projection of the
 * position and a list of dirty cells, and neither contains a pixel or a colour.
 */
internal class BoardRenderer(private val canvas: HTMLCanvasElement) {
    private val context: CanvasRenderingContext2D = canvas.getContext("2d") as? CanvasRenderingContext2D
        ?: error("this browser has a canvas but no 2d context")

    private var palette: Palette = Palette.of(prefersDark())
    private var grid: Grid = Grid(1, 1)
    private var cellSize: Int = MIN_CELL

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
     * Re-measures the container, resizes the backing store and repaints. Start, resize and seek.
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

        val ratio = window.devicePixelRatio
        val available = ((canvas.parentElement as? HTMLElement)?.clientWidth ?: FALLBACK_WIDTH) * ratio
        val byWidth = (available - 1) / grid.cols
        val byHeight = (window.innerHeight * ratio * HEIGHT_SHARE - 1) / grid.rows
        cellSize = minOf(byWidth, byHeight).toInt()
            .coerceIn((MIN_CELL * ratio).toInt().coerceAtLeast(1), (MAX_CELL * ratio).toInt().coerceAtLeast(1))

        val width = cellSize * grid.cols + 1
        val height = cellSize * grid.rows + 1

        canvas.width = width
        canvas.height = height
        canvas.style.width = "${width / ratio}px"
        canvas.style.height = "${height / ratio}px"

        repaint(view)
    }

    /** Switches themes. The caller follows with [fit], because the gridlines change colour too. */
    fun applyScheme(dark: Boolean) {
        palette = Palette.of(dark)
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
        val colour = palette.body(id.index)
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
        fill(cell, palette.body(owner.index), alpha)
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
        view.rules.growEveryNthMove < 2 -> 1.0
        // A one-square snake is all head, and the head is painted over this anyway.
        snake.length < 2 -> 1.0
        snake.growsOnNextMove -> Palette.AGING_ALPHA
        else -> Palette.DYING_ALPHA
    }

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

    private companion object {
        /**
         * Bounds on the cell size, in CSS pixels — [fit] converts them to device pixels, so a board
         * comes out the same physical size whatever the display's ratio. Below the minimum a 40x40
         * board is unreadable; above the maximum a 12x12 one is absurd.
         */
        const val MIN_CELL = 6
        const val MAX_CELL = 30

        /** How much of the viewport height the board may claim, leaving room for the transport. */
        const val HEIGHT_SHARE = 0.68

        /** Only reached if the canvas has no element parent, which the static skeleton guarantees. */
        const val FALLBACK_WIDTH = 640
    }
}

internal fun prefersDark(): Boolean = window.matchMedia("(prefers-color-scheme: dark)").matches
