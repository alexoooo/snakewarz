package ao.snakewarz.ui.render

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.core.snake.SnakeView
import ao.snakewarz.match.human.PathPlanner
import ao.snakewarz.ui.schedule.Ticker
import ao.snakewarz.ui.schedule.TurnScheduler
import kotlinx.browser.window
import org.w3c.dom.BUTT
import org.w3c.dom.CanvasLineCap
import org.w3c.dom.CanvasLineJoin
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.ROUND
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.toJsNumber
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paints a [BoardView] onto two canvases: a board that is the ground, and an overlay that carries
 * everything alive.
 *
 * ### The board is laid down once, and only when the ground itself changes
 *
 * Background, walls and gridlines, and nothing else — under whatever treatment [TexturePack] gives
 * the first two. None of the three moves while a match is played,
 * so [fit] is the only thing that paints them — a resize, a theme, a different board. A turn costs
 * this bitmap nothing at all, which is also why the gridlines can be stroked once and left: a cell of
 * side `s` owns the pixels `(c*s + 1, r*s + 1)` to `(c*s + s, r*s + s)`, the one-pixel gutter along
 * its top and left edge belongs to the gridline, and every fill here lands strictly inside it.
 *
 * ### The snakes are on the overlay, and that is what lets a body be one animal
 *
 * That gutter is why. A body painted as *squares* on the board stops one pixel short of the next
 * square of the same snake, so a grid line runs down the middle of the animal — and a connected snake
 * has to cross the gutter. A stroke on the overlay is under no such rule, because nothing beneath it
 * is ever partially repainted: the whole bitmap is cleared and redrawn from the position every time
 * that position moves, so a joint may span whatever it likes.
 *
 * [paintOverlay] is what does that, and **it has to follow every turn played and every paint of the
 * board.** It is the only thing that draws a snake at all, so a missed call does not lose a
 * decoration — it leaves a board with no game on it.
 *
 * The cost is `O(sum of body lengths)` a turn rather than the `O(rows * cols)` a whole-board repaint
 * would be, and it is bounded above it by [TurnScheduler], which spends eight milliseconds of a frame
 * on turns and then stops whether or not the match is keeping up.
 *
 * ### Age is read off the position, not remembered
 *
 * A snake's oldest square fades in two steps before it clears — see [tailAlpha]. That is derived from
 * the board every time it is painted rather than kept in a counter here, so seeking a replay, resizing
 * the window and painting a tournament's current match all land on the same colours as playing the
 * match forwards would have.
 *
 * The renderer knows nothing about matches, bots or time. It is handed a read-only projection of the
 * position, and that projection carries neither a pixel nor a colour.
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

    /**
     * The colours in force.
     *
     * The scheme is the one thing about it a renderer can know for itself; which *theme* the player
     * chose is remembered a layer up, so this opens on the default and is handed the real one by
     * [applyTheme] before the first fit.
     */
    private var theme: Theme = Theme.of(Theme.DEFAULT_ID, prefersDark())

    /**
     * How the ground is drawn under those colours.
     *
     * Handed over by whoever starts the match, exactly as [theme] is handed over by whoever picked
     * one, and for the same reason in both cases: a renderer cannot work either out for itself. A
     * board carries wall *squares* and never the shape they were drawn from, so this opens on the
     * plain pack — which is also the honest answer for every board that arrives out of a replay.
     */
    private var pack: TexturePack = TexturePack.PLAIN
    private var grid: Grid = Grid(1, 1)
    private var cellSize: Int = MIN_CELL

    /** The square the wash is drawn for, so a turn can redraw it without re-deciding. */
    private var hovered: Cell = Cell.NONE

    /**
     * The route being walked, and the route a press on the hovered square would commit to.
     *
     * Kept for the same reason [hovered] is: [fit] repaints the overlay from inside itself, so every
     * decoration has to be readable without the caller handing it over again. Empty planners on a
     * one-square grid stand in until the first real ones arrive, exactly as [grid] does.
     */
    private var plan: PathPlanner = PathPlanner(Grid(1, 1))
    private var preview: PathPlanner = PathPlanner(Grid(1, 1))

    /**
     * The motion clock, as [Ticker] last reported it, and what is happening on it.
     *
     * The only time this class knows about, and it is a *drawing* clock: [drawOverlay] reads it to
     * decide how far through a death a body is and where the dashes on a route have got to, and
     * nothing it decides can reach a match. Painting outside a tick — a turn, a pointer move — uses
     * the last reading, which is exactly right because the clock only advances while something is
     * moving.
     *
     * [aliveWhenLastDrawn] is what turns a *position* into an *event*: the board says which snakes
     * are alive and never that one has just died, so the transition is the difference between two
     * paints. It is dropped by [fit], which is the only thing that ever puts a different board under
     * this — otherwise closing a setup preview over a finished match would flash a corpse that died
     * a minute ago.
     */
    private var motion: Double = 0.0
    private var moving: Boolean = false
    private var aliveWhenLastDrawn: BooleanArray = BooleanArray(0)
    private var diedAt: DoubleArray = DoubleArray(0)
    private var cellsWhenLastDrawn: Array<IntArray> = emptyArray()
    private var transitions: Array<MoveTransition?> = emptyArray()
    private val reducedMotion: Boolean = window.matchMedia("(prefers-reduced-motion: reduce)").matches

    /**
     * The dash pattern the marching route is stroked with, and the empty one that puts it back.
     *
     * Held rather than built per call: `setLineDash` takes a JavaScript array, and a route is
     * re-stroked on every frame a pointer is down. The lengths change with the cell size, so the
     * two entries are written each time and the array itself is not.
     */
    private val dashes: JsArray<JsNumber> = JsArray()
    private val solid: JsArray<JsNumber> = JsArray()

    /**
     * Resizes both backing stores to the room the board has, and lays the ground down. Start, resize,
     * theme, and a match on a different board.
     *
     * **The board fills its container**, so an 8x8 and a 28x28 occupy the same frame at different
     * magnifications and the grid decides only how finely that frame is divided. The container is
     * the input to the size rather than a clamp on it: a phone in portrait and a 4K monitor are the
     * same board at two magnifications, which is the whole of "snakes centre stage".
     *
     * One clamp survives in each direction and neither is a frame size. [MAX_CELL] stops a small
     * board turning into a handful of enormous squares in a large window, and [MIN_CELL] keeps a
     * 28x28 legible on a phone. Both are in CSS pixels at [bootRatio], so zooming the page moves the
     * text around a board that stays where it is — the room is measured in device pixels too, where
     * zoom cancels out exactly, because the box loses CSS pixels at the same rate a CSS pixel gains
     * device ones.
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
        val box = canvas.parentElement as? HTMLElement
        val room = minOf(
            (box?.clientWidth?.takeIf { it > 0 } ?: FALLBACK_WIDTH) * ratio,
            (box?.clientHeight?.takeIf { it > 0 } ?: FALLBACK_HEIGHT) * ratio,
        )
        // Fitted by the longer side, so a 12x20 board sits inside the square a 20x20 fills, and
        // floored so that a whole number of device pixels a cell still leaves the room it was
        // measured against.
        val span = maxOf(grid.rows, grid.cols)
        val fits = (room - 1) / span
        cellSize = fits.toInt()
            .coerceAtMost((MAX_CELL * bootRatio).toInt())
            .coerceAtLeast((MIN_CELL * bootRatio).toInt().coerceAtLeast(1))

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

        // A different board, and this is the only route onto one — so whatever was dying on the last
        // one is not dying on this one. A resize pays the same price, which is a death flash cut
        // short by the one gesture nobody makes mid-death.
        aliveWhenLastDrawn = BooleanArray(0)
        rememberPosition(view)

        repaint(view)
        drawOverlay(view)
    }

    /** Switches themes. The caller follows with [fit], which is the only thing that paints a board. */
    fun applyTheme(theme: Theme) {
        this.theme = theme
    }

    /**
     * Switches texture packs, on the same terms as [applyTheme]: the caller follows with [fit].
     *
     * Both are set rather than passed because both belong to the *board on the arena* rather than to
     * one paint of it — a resize has to redraw the same ground it drew a moment ago, and neither the
     * window nor the theme knows which board that is.
     */
    fun applyPack(pack: TexturePack) {
        this.pack = pack
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

        // A cell of side s owns the pixels `c*s + 1` to `c*s + s` — the convention the board's own
        // fills use, so the square the pointer is visibly over is the square this answers with.
        val col = (x.toInt() - 1) / cellSize
        val row = (y.toInt() - 1) / cellSize
        return if (grid.contains(row, col)) grid.cellAt(row, col) else Cell.NONE
    }

    /**
     * Repaints the overlay: whoever holds [cell], the route in [preview], the route in [plan], and
     * every snake on the board.
     *
     * **This is the only thing that draws a snake anywhere**, so it has to follow every turn played
     * and every paint of the board — not merely every pointer move. A missed call does not lose a
     * decoration; it leaves a board with no game on it.
     *
     * A *square* is what is remembered here, never a snake — so a restart, a seek and a tournament
     * moving on to its next match all resolve to whoever holds that square now. That is the same
     * rule every colour on this board already follows: read it off the position, do not keep it.
     */
    fun paintOverlay(view: BoardView, cell: Cell, plan: PathPlanner, preview: PathPlanner): Boolean {
        hovered = cell
        this.plan = plan
        this.preview = preview
        drawOverlay(view)
        return moving
    }

    /**
     * Repaints the overlay at [motionMillis] of the motion clock, and answers whether anything on it
     * is still moving.
     *
     * [Ticker]'s half of [paintOverlay]: the same drawing, from the same position, at a different
     * instant. What changes between two of these is a body settling after a death, the dashes along
     * a held route and the head that route is anchored on — nothing that could change what the
     * position *is*, which is what makes it safe for a clock to drive it.
     */
    fun animate(view: BoardView, motionMillis: Double): Boolean {
        motion = motionMillis
        drawOverlay(view)
        return moving
    }

    // -- the board

    /**
     * The ground: the board's own colour, the figure [pack] stipples it with, the map drawn on it,
     * and the lines that divide it.
     *
     * Walls go between the background and the gridlines, so a line reads across a wall exactly as it
     * reads across open board. Nothing here moves while a match is played, which is why [fit] is the
     * only caller — and why the gridlines survive a whole match having been stroked once.
     *
     * The ground figure goes under the walls in the same sweep rather than in a pass of its own: a
     * mark on a square a block covers is a mark nobody sees, and skipping the wall squares would cost
     * a `isWall` per cell to save a fill that is already inside one.
     */
    private fun repaint(view: BoardView) {
        val width = (cellSize * grid.cols + 1).toDouble()
        val height = (cellSize * grid.rows + 1).toDouble()

        context.fillStyle = theme.background.toJsString()
        context.fillRect(0.0, 0.0, width, height)

        val textured = cellSize >= TEXTURE_MIN_CELL
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val cell = grid.cellAt(row, col)
                when {
                    view.isWall(cell) -> fillWall(row, col, textured)
                    textured -> shadeGround(row, col)
                }
            }
        }
        context.globalAlpha = 1.0

        context.strokeStyle = theme.gridline.toJsString()
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
    }

    /**
     * One wall square: the block, the line that gives it a face, and whatever [pack] adds to it.
     *
     * The edge is what stops a room's wall reading as one undifferentiated slab, and it is drawn in
     * the same sweep as the block rather than in a second pass over the map — a wall square is one
     * thing to paint. It is dropped below [WALL_EDGE_MIN_CELL], where a one-pixel perimeter is most
     * of the square: a 28x28 board on a phone is walls a handful of device pixels across, and there
     * is no relief to give them at that size.
     *
     * A pack's inset is dropped a size earlier still — [textured] is false below [TEXTURE_MIN_CELL],
     * and every pack comes out plain there. That is [WALL_EDGE_MIN_CELL]'s decision taken again for
     * the same reason and at a size further up: a groove is a *pair* of gaps between two blocks, so
     * it needs the room the edge needs and then the room to be seen across.
     *
     * The inset is whole device pixels, so a run of blocks lines up exactly however the share divides
     * — the same arithmetic the cell size itself is chosen by, and for the same reason.
     */
    private fun fillWall(row: Int, col: Int, textured: Boolean) {
        val inset = if (textured) (cellSize * pack.wallInset(row, col)).toInt() else 0
        val x = (col * cellSize + 1 + inset).toDouble()
        val y = (row * cellSize + 1 + inset).toDouble()
        val side = (cellSize - 1 - 2 * inset).toDouble()

        context.globalAlpha = 1.0
        context.fillStyle = theme.wall.toJsString()
        context.fillRect(x, y, side, side)

        if (textured && pack.studded) {
            // The wall's own edge colour, which is the one shade the theme already keeps for relief
            // on a block — a stud is that line's job done at a point instead of round a perimeter.
            context.fillStyle = theme.wallEdge.toJsString()
            centreMark(row, col)
        }

        if (cellSize < WALL_EDGE_MIN_CELL) {
            return
        }

        // The cell owns `c*s + 1` through `c*s + s`, so a 1px stroke centred half a pixel inside
        // each of those bounds lands exactly on the outermost row and column the *block* holds.
        context.strokeStyle = theme.wallEdge.toJsString()
        context.lineWidth = 1.0
        context.strokeRect(x + 0.5, y + 0.5, side - 1, side - 1)
    }

    /**
     * One bare square's share of the pack's figure, or nothing where the pack asks for none.
     *
     * The wall colour at a whisper of itself, which is [Theme.background] shaded towards the one
     * other colour the board already has — so a figured ground follows every theme and both schemes
     * without a pack knowing any of them, and can never be mistaken for a wall.
     */
    private fun shadeGround(row: Int, col: Int) {
        val shade = pack.groundShade(row, col)
        if (shade <= 0.0) {
            return
        }
        context.globalAlpha = shade
        context.fillStyle = theme.wall.toJsString()
        centreMark(row, col)
    }

    /**
     * A small square at the centre of a cell, in whatever the board context is currently filling
     * with: a stud on a block, or a mark on the bare ground.
     *
     * One helper for both, because they are one shape at two colours — and because the figure has to
     * sit at the same place in a cell either way, or a stippled board and a studded block would read
     * as two different griddings of the same squares.
     */
    private fun centreMark(row: Int, col: Int) {
        val side = (cellSize * MARK_SHARE).toInt().coerceAtLeast(1).toDouble()
        val x = col * cellSize + 1 + (cellSize - 1 - side) / 2.0
        val y = row * cellSize + 1 + (cellSize - 1 - side) / 2.0
        context.fillRect(x, y, side, side)
    }

    // -- the overlay

    /**
     * Everything that moves: a wash over the hovered snake, the previewed route, the drawn route,
     * every body, every head.
     *
     * The split is what each cue is *for*. A body is what a snake **is**, so it is drawn for every
     * snake every turn — there is no such thing as a dirty square for a shape that shifted along its
     * whole length. The wash picks *one* snake out of the others, which is a question only a pointer
     * asks, so it stays with the pointer. The route is a statement about squares nothing has happened
     * on yet, which is a third thing again — and the preview is that statement in the conditional:
     * what a press *would* commit to, rather than what one did.
     *
     * Order is `wash -> preview -> route -> bodies -> heads`, and every step of it is load-bearing.
     * The wash goes down first, so hovering lays a tint under everything rather than rearranging it.
     * The preview goes under the route because a committed route is the stronger claim of the two and
     * must win wherever they overlap. Both go under the bodies, so a snake reads on top of its own
     * plan rather than being hidden by it. And the heads come after **every** body rather than each
     * with its own, so the one square that says where a snake is about to go is never buried under
     * the animal it is about to meet.
     *
     * Nothing here says anything about the tail being about to clear beyond the fade [drawBody] draws
     * — the label already says it in words, and a third telling of one fact is noise.
     */
    private fun drawOverlay(view: BoardView) {
        trackDeaths(view)
        trackMoves(view)
        moving = stillMoving(view)
        overlayContext.clearRect(0.0, 0.0, overlay.width.toDouble(), overlay.height.toDouble())

        val hoveredOwner = if (grid.isPlayable(hovered)) view.ownerOf(hovered) else SnakeId.NONE
        if (!hoveredOwner.isNone) {
            // The head colour, which is the theme's answer to "this snake, but readable against
            // itself" — darker than the trail on a light page and lighter on a dark one, either way.
            wash(view.snake(hoveredOwner), theme.head(hoveredOwner.index))
        }

        drawRoute(preview, PREVIEW_ALPHA, PREVIEW_TARGET_ALPHA)
        drawRoute(plan, PLAN_ALPHA, PLAN_TARGET_ALPHA)

        for (slot in 0 until view.snakeCount) {
            drawBody(view, SnakeId(slot))
        }
        for (slot in 0 until view.snakeCount) {
            drawHead(view, SnakeId(slot))
        }

        overlayContext.globalAlpha = 1.0
    }

    /**
     * One snake's body: the taper on its oldest square, the ribbon along the rest, and the spine down
     * the middle of both.
     *
     * The ribbon is a polyline through the cell centres with round joins and caps, which is what
     * turns a right-angle corner into a bend and a run of squares into an animal. **One path and one
     * `stroke`** for the whole of it rather than a segment at a time: a corpse's ribbon is
     * translucent, and a segment drawn over its neighbour's round cap would deepen at every joint,
     * where a single stroke composites once however often it crosses itself.
     *
     * **The oldest square is lifted out of that path exactly when it is fading**, which is also the
     * only case in which two alphas meet on one body. [tailAlpha]'s two carve-outs mean a corpse and
     * a never-retracting trail both give the tail the body's own alpha, so the seam exists only on a
     * *living* snake — whose ribbon is opaque, and therefore cannot deepen where the taper runs under
     * its cap.
     *
     * A corpse loses the spine as well: a snake that is out is scenery, and scenery is one flat
     * obstacle rather than something with a highlight down its back.
     */
    private fun drawBody(view: BoardView, id: SnakeId) {
        val snake = view.snake(id)
        // A one-square snake is all head, and the head is drawn on its own below.
        if (snake.length < 2) {
            return
        }

        val colour = theme.body(id.index)
        val corpse = corpseAlpha(id.index)
        val alpha = if (snake.alive) 1.0 else corpse
        val tail = tailAlpha(view, snake, corpse)
        val fading = tail != alpha
        if (fading) {
            drawTaper(id.index, snake, colour, tail)
        }

        val first = if (fading) 1 else 0
        if (snake.length - first >= 2) {
            overlayContext.lineCap = CanvasLineCap.ROUND
            overlayContext.lineJoin = CanvasLineJoin.ROUND
            overlayContext.strokeStyle = colour.toJsString()
            overlayContext.globalAlpha = alpha
            overlayContext.lineWidth = bodyWidth()
            overlayContext.beginPath()
            overlayContext.moveTo(bodyX(id.index, snake, first), bodyY(id.index, snake, first))
            for (i in first + 1 until bodyPointCount(id.index, snake)) {
                overlayContext.lineTo(bodyX(id.index, snake, i), bodyY(id.index, snake, i))
            }
            overlayContext.stroke()
        }

        // The spine goes out with the snake rather than the instant it dies: it is the one part of
        // the drawing that says *alive*, so fading it over the flash is the light going out, where
        // dropping it on the turn of the death is the snake becoming scenery between two frames.
        val spine = if (snake.alive) 1.0 else deathFlash(id.index)
        if (spine > 0.0) {
            drawSpine(id.index, snake, theme.head(id.index), spine)
        }
    }

    /**
     * The oldest square's share of the body, narrowing towards a tip at its centre.
     *
     * A filled quadrilateral rather than a stroke, because a stroke has one width and the tail is the
     * one place the ribbon changes width along a single segment. What it buys is which end of a coil
     * is the old one, answered at a glance and from any distance.
     *
     * Adjacent squares are exactly one cell apart on one axis, so the perpendicular is that step
     * turned a quarter turn and divided by the cell — no length to measure and no zero to guard.
     */
    private fun drawTaper(slot: Int, snake: SnakeView, colour: String, alpha: Double) {
        val x0 = bodyX(slot, snake, 0)
        val y0 = bodyY(slot, snake, 0)
        val x1 = bodyX(slot, snake, 1)
        val y1 = bodyY(slot, snake, 1)
        val acrossX = (y0 - y1) / cellSize
        val acrossY = (x1 - x0) / cellSize

        val base = bodyWidth() / 2
        val tip = base * TAIL_TIP_SHARE

        overlayContext.fillStyle = colour.toJsString()
        overlayContext.globalAlpha = alpha
        overlayContext.beginPath()
        overlayContext.moveTo(x0 + acrossX * tip, y0 + acrossY * tip)
        overlayContext.lineTo(x1 + acrossX * base, y1 + acrossY * base)
        overlayContext.lineTo(x1 - acrossX * base, y1 - acrossY * base)
        overlayContext.lineTo(x0 - acrossX * tip, y0 - acrossY * tip)
        overlayContext.closePath()
        overlayContext.fill()
    }

    /**
     * The line down the middle of a body, from a whisker at the tail to nearly full colour at the
     * head.
     *
     * [Theme.head] over [Theme.body] — the pair the theme already keeps for "this snake, but readable
     * against itself" — so the spine runs into the head marker as one bright line rather than as a
     * second decoration, and drawing a highlight costs no third palette.
     *
     * A segment at a time, because both the alpha and the width ramp along the length and neither can
     * vary inside one `stroke`. The ramp is what makes a coil readable: the squares nearest the head
     * are the ones a reader is trying to trace, and they are the brightest and the widest.
     *
     * [weight] is the whole line's share of itself, which is one for a living snake and a share
     * running to nothing across a death — see [drawBody].
     */
    private fun drawSpine(slot: Int, snake: SnakeView, colour: String, weight: Double) {
        val width = (cellSize * SPINE_WIDTH).coerceAtLeast(MIN_MARK)
        val points = bodyPointCount(slot, snake)

        overlayContext.lineCap = CanvasLineCap.ROUND
        overlayContext.lineJoin = CanvasLineJoin.ROUND
        overlayContext.strokeStyle = colour.toJsString()

        // cellAt(0) is the tail, so `i` runs the body in the order it was laid down.
        for (i in 0 until points - 1) {
            overlayContext.globalAlpha = weight * ramp(i, points - 1, SPINE_TAIL_ALPHA, SPINE_HEAD_ALPHA)
            overlayContext.lineWidth = ramp(i, points - 1, MIN_MARK, width)
            overlayContext.beginPath()
            overlayContext.moveTo(bodyX(slot, snake, i), bodyY(slot, snake, i))
            overlayContext.lineTo(bodyX(slot, snake, i + 1), bodyY(slot, snake, i + 1))
            overlayContext.stroke()
        }
    }

    /**
     * One snake's head: the marker, and the two eyes that say which way it is about to go.
     *
     * A corpse gets neither, which is the argument [drawBody] makes for dropping its spine — a snake
     * that is out is an obstacle, and an obstacle has nothing to be watching with.
     *
     * Facing is [SnakeView.lastDirection], and a snake that has not moved has none. That is only ever
     * the opening position, the one moment every snake is a single square, so the eyes open on
     * [OPENING_FACING] rather than arriving a move later — which would read as the drawing catching
     * up rather than as a snake deciding.
     *
     * Eyes are **dropped** below [EYE_MIN_CELL] rather than scaled down to it, which is
     * [WALL_EDGE_MIN_CELL]'s decision taken again: two dots and the gap between them are three
     * features across a fraction of a square, and shrunk far enough they merge into one smudge that
     * says less than the bare marker already does. That cut-off is what stands in for a floor here.
     */
    private fun drawHead(view: BoardView, id: SnakeId) {
        val snake = view.snake(id)
        if (!snake.alive) {
            return
        }

        val x = headX(id.index, snake)
        val y = headY(id.index, snake)

        overlayContext.globalAlpha = 1.0
        overlayContext.fillStyle = theme.head(id.index).toJsString()
        dot(x, y, (cellSize * HEAD_RADIUS * pulse(snake.head)).coerceAtLeast(MIN_MARK))

        if (cellSize < EYE_MIN_CELL) {
            return
        }

        val facing = snake.lastDirection ?: OPENING_FACING
        val aheadX = x + facing.dCol * cellSize * EYE_AHEAD
        val aheadY = y + facing.dRow * cellSize * EYE_AHEAD
        val acrossX = -facing.dRow * cellSize * EYE_SPREAD
        val acrossY = facing.dCol * cellSize * EYE_SPREAD
        val eye = cellSize * EYE_RADIUS

        // The board's own colour, so a dot reads against all six head hues of every theme without a
        // seventh entry in any of them: a head is the readable-against-the-page end of a trail, and
        // the page's own board is the far end of that same contrast.
        overlayContext.fillStyle = theme.background.toJsString()
        dot(aheadX + acrossX, aheadY + acrossY, eye)
        dot(aheadX - acrossX, aheadY - acrossY, eye)
    }

    /**
     * One route: the squares it would walk at [alpha], and a mark on the one it ends on at
     * [targetAlpha].
     *
     * The committed route and the preview are the same drawing at two weights, which is why they are
     * one function called twice — change how a route is drawn and both change together, because
     * there is nothing else to change.
     *
     * Drawn in the accent, which is the one colour on the page that is already the player's — theirs
     * rather than any snake's, so a route reads as an intention laid over the position instead of as
     * a seventh trail somebody might mistake for a body.
     *
     * The anchor is skipped. `cellAt(0)` is the square the head is already standing on, and what a
     * route says is where the snake is *going*; tinting the square it is on would put a translucent
     * wash under the one marker on the board that has to stay unambiguous.
     *
     * The mark on the end exists for the head marker's reason: a run of tinted squares across a busy
     * board does not say at a glance which end of it the pointer is at.
     */
    private fun drawRoute(planner: PathPlanner, alpha: Double, targetAlpha: Double) {
        if (planner.isEmpty) {
            return
        }

        overlayContext.fillStyle = theme.accent.toJsString()
        overlayContext.globalAlpha = alpha
        for (i in 1 until planner.cellCount) {
            val cell = planner.cellAt(i)
            overlayContext.fillRect(
                (grid.colOf(cell) * cellSize + 1).toDouble(),
                (grid.rowOf(cell) * cellSize + 1).toDouble(),
                (cellSize - 1).toDouble(),
                (cellSize - 1).toDouble(),
            )
        }

        march(planner, alpha)

        val target = planner.cellAt(planner.cellCount - 1)
        overlayContext.globalAlpha = targetAlpha
        dot(centreX(target), centreY(target), (cellSize * PLAN_TARGET_RADIUS).coerceAtLeast(MIN_MARK))
    }

    /**
     * Dashes travelling along a route, from the snake's own head towards the far end of it.
     *
     * The one thing on this board that says a route is *being* walked rather than merely drawn, and
     * it is the reason the motion clock runs at all while a pointer is down. Which way they travel
     * is the whole of it — a dash pattern with no offset is a dotted line and says nothing — so the
     * offset runs negative, which is the direction the snake is about to go.
     *
     * A share of the fill's own alpha rather than a weight of its own, so the preview stays the
     * ghost of the committed route here exactly as it is everywhere else.
     *
     * Dashing is context state and the next stroke on this bitmap is a snake, so it is put back
     * before this returns. Butt caps for the same reason a solid body has round ones: a rounded dash
     * on a short route closes the gaps it exists to open.
     */
    private fun march(planner: PathPlanner, alpha: Double) {
        val dash = (cellSize * DASH_SHARE).coerceAtLeast(MIN_MARK)
        dashes[0] = dash.toJsNumber()
        dashes[1] = dash.toJsNumber()

        overlayContext.strokeStyle = theme.accent.toJsString()
        overlayContext.globalAlpha = (alpha * DASH_LIFT).coerceAtMost(1.0)
        overlayContext.lineWidth = (cellSize * DASH_WIDTH).coerceAtLeast(MIN_MARK)
        overlayContext.lineCap = CanvasLineCap.BUTT
        overlayContext.lineJoin = CanvasLineJoin.ROUND
        overlayContext.setLineDash(dashes)
        overlayContext.lineDashOffset = -cellSize * motion / DASH_MILLIS_PER_CELL

        overlayContext.beginPath()
        overlayContext.moveTo(centreX(planner.cellAt(0)), centreY(planner.cellAt(0)))
        for (i in 1 until planner.cellCount) {
            overlayContext.lineTo(centreX(planner.cellAt(i)), centreY(planner.cellAt(i)))
        }
        overlayContext.stroke()

        overlayContext.setLineDash(solid)
        overlayContext.lineDashOffset = 0.0
        overlayContext.lineCap = CanvasLineCap.ROUND
    }

    /**
     * A translucent tint over one whole body, brightening toward the head.
     *
     * Squares, and *under* the ribbon rather than over it — so what a hover leaves showing is a halo
     * in the corners the body does not fill. That is not a compromise. A stroked body says where a
     * snake ran and blurs which squares it holds; the halo is the one place the drawing says that
     * outright, on the one snake somebody is asking about.
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

    // -- motion

    /**
     * Notices the deaths, by comparing this position against the one drawn before it.
     *
     * A board says which snakes are alive and never that one has *just* died — the same reason the
     * head handoff used to be tracked here before the bodies moved to the overlay. A flash is an
     * event, so the event has to be recovered from the difference between two paints, and this is
     * the only thing on this bitmap that remembers anything at all about the last one.
     *
     * A board of a different width resets both arrays, and so does [fit]: a snake that is dead the
     * first time this sees a position has not died in front of anybody.
     */
    private fun trackDeaths(view: BoardView) {
        if (aliveWhenLastDrawn.size != view.snakeCount) {
            aliveWhenLastDrawn = BooleanArray(view.snakeCount) { view.snake(SnakeId(it)).alive }
            diedAt = DoubleArray(view.snakeCount) { NEVER }
            return
        }

        for (slot in 0 until view.snakeCount) {
            val living = view.snake(SnakeId(slot)).alive
            if (aliveWhenLastDrawn[slot] && !living) {
                diedAt[slot] = motion
            }
            aliveWhenLastDrawn[slot] = living
        }
    }

    /** Recovers a visible one-cell move from consecutive paints without retaining engine state. */
    private fun trackMoves(view: BoardView) {
        if (cellsWhenLastDrawn.size != view.snakeCount) {
            rememberPosition(view)
            return
        }

        for (slot in 0 until view.snakeCount) {
            val snake = view.snake(SnakeId(slot))
            val current = IntArray(snake.length) { snake.cellAt(it).index }
            val previous = cellsWhenLastDrawn[slot]
            val existing = transitions[slot]
            if (existing != null && transitionProgress(existing) >= 1.0) {
                transitions[slot] = null
            }
            if (!current.contentEquals(previous)) {
                transitions[slot] = when {
                    reducedMotion || !snake.alive -> null
                    existing != null && transitionProgress(existing) < 1.0 -> null
                    isVisibleMove(previous, current) -> MoveTransition(previous, current.size == previous.size, motion)
                    else -> null
                }
                cellsWhenLastDrawn[slot] = current
            }
        }
    }

    private fun rememberPosition(view: BoardView) {
        cellsWhenLastDrawn = Array(view.snakeCount) { slot ->
            val snake = view.snake(SnakeId(slot))
            IntArray(snake.length) { snake.cellAt(it).index }
        }
        transitions = arrayOfNulls(view.snakeCount)
    }

    private fun isVisibleMove(previous: IntArray, current: IntArray): Boolean {
        if (previous.isEmpty() || current.isEmpty() || current.size !in previous.size..previous.size + 1) {
            return false
        }
        val oldHead = Cell(previous.last())
        val newHead = Cell(current.last())
        val adjacent = kotlin.math.abs(grid.rowOf(oldHead) - grid.rowOf(newHead)) +
            kotlin.math.abs(grid.colOf(oldHead) - grid.colOf(newHead)) == 1
        if (!adjacent) {
            return false
        }
        return if (current.size == previous.size + 1) {
            previous.indices.all { current[it] == previous[it] }
        } else {
            (0 until current.lastIndex).all { current[it] == previous[it + 1] }
        }
    }

    private fun transitionProgress(transition: MoveTransition): Double {
        val linear = ((motion - transition.startedAt) / MOVE_MILLIS).coerceIn(0.0, 1.0)
        val remaining = 1.0 - linear
        return 1.0 - remaining * remaining * remaining
    }

    /**
     * Whether anything on this bitmap still has frames to run, which is what keeps [Ticker] going.
     *
     * Two things ever do: a death that has not settled yet, and a route somebody is holding or
     * hovering. Everything else on the overlay is a function of the position alone and changes only
     * when the position does, so the loop stops the moment the last flash finishes or the pointer
     * comes up — which is what stops this being a page that repaints sixty times a second forever.
     */
    private fun stillMoving(view: BoardView): Boolean {
        if (steering()) {
            return true
        }
        for (slot in 0 until view.snakeCount) {
            if (transitions.getOrNull(slot) != null) {
                return true
            }
            if (deathFlash(slot) > 0.0) {
                return true
            }
        }
        return false
    }

    /** Whether a route is on the board at all: the two live decorations both hang off one. */
    private fun steering(): Boolean = !plan.isEmpty || !preview.isEmpty

    /**
     * How much of a death is still to play, from one at the moment of it to nothing once it has
     * settled.
     *
     * Zero for a snake that never died, for one whose flash has finished, and for a stamp taken on
     * a board this is no longer looking at — all three fall out of the arithmetic rather than needing
     * a case, because [NEVER] is a stamp far enough back that no clock reading can be inside it.
     */
    private fun deathFlash(slot: Int): Double {
        if (slot >= diedAt.size) {
            return 0.0
        }
        val since = motion - diedAt[slot]
        return if (since < 0.0 || since >= DEATH_MILLIS) 0.0 else 1.0 - since / DEATH_MILLIS
    }

    /**
     * What a dead snake's ribbon is worth this frame: full colour at the death, [Theme.CORPSE_ALPHA]
     * once it has settled.
     *
     * A square root rather than a straight ramp, so the colour *holds* for a moment and then goes —
     * which is what makes it read as a snake dying rather than as one fading out. [Theme.CORPSE_ALPHA]
     * is where it lands and stays: how visible a corpse is is a rule about the game, not a moment.
     */
    private fun corpseAlpha(slot: Int): Double {
        val flash = deathFlash(slot)
        return if (flash <= 0.0) Theme.CORPSE_ALPHA else Theme.CORPSE_ALPHA + (1.0 - Theme.CORPSE_ALPHA) * sqrt(flash)
    }

    /**
     * How much larger than itself a head marker is drawn this frame.
     *
     * Only the head a live route is anchored on, which is the snake somebody is steering — the
     * planner's own first square says which that is, so the renderer needs to know nothing about
     * seats to answer it. A pulse on every head would be six things breathing on a board where the
     * point is to watch one; a pulse that never stopped would be a clock that never stopped.
     */
    private fun pulse(head: Cell): Double {
        val anchored = (!plan.isEmpty && plan.cellAt(0) == head) ||
            (!preview.isEmpty && preview.cellAt(0) == head)
        return if (!anchored) 1.0 else 1.0 + HEAD_PULSE * sin(2 * PI * motion / HEAD_PULSE_MILLIS)
    }

    private fun bodyPointCount(slot: Int, snake: SnakeView): Int =
        snake.length + if (transitions.getOrNull(slot)?.retracts == true) 1 else 0

    private fun bodyX(slot: Int, snake: SnakeView, point: Int): Double = bodyCoordinate(slot, snake, point, true)

    private fun bodyY(slot: Int, snake: SnakeView, point: Int): Double = bodyCoordinate(slot, snake, point, false)

    private fun bodyCoordinate(slot: Int, snake: SnakeView, point: Int, horizontal: Boolean): Double {
        val transition = transitions.getOrNull(slot) ?: return centre(snake.cellAt(point), horizontal)
        val progress = transitionProgress(transition)
        val old = transition.oldCells
        if (transition.retracts) {
            return when (point) {
                0 -> interpolate(Cell(old[0]), Cell(old[1]), progress, horizontal)
                old.size -> interpolate(Cell(old.last()), snake.head, progress, horizontal)
                else -> centre(Cell(old[point]), horizontal)
            }
        }
        return if (point == snake.length - 1) {
            interpolate(Cell(old.last()), snake.head, progress, horizontal)
        } else {
            centre(Cell(old[point]), horizontal)
        }
    }

    private fun headX(slot: Int, snake: SnakeView): Double = headCoordinate(slot, snake, true)

    private fun headY(slot: Int, snake: SnakeView): Double = headCoordinate(slot, snake, false)

    private fun headCoordinate(slot: Int, snake: SnakeView, horizontal: Boolean): Double {
        val transition = transitions.getOrNull(slot) ?: return centre(snake.head, horizontal)
        return interpolate(Cell(transition.oldCells.last()), snake.head, transitionProgress(transition), horizontal)
    }

    private fun interpolate(from: Cell, to: Cell, progress: Double, horizontal: Boolean): Double {
        val start = centre(from, horizontal)
        return start + (centre(to, horizontal) - start) * progress
    }

    private fun centre(cell: Cell, horizontal: Boolean): Double = if (horizontal) centreX(cell) else centreY(cell)

    // -- internals

    /**
     * How much colour a snake's oldest square keeps: full, then [Theme.AGING_ALPHA], then
     * [Theme.DYING_ALPHA], and then the square is empty board.
     *
     * The board already knows this and nothing has to be remembered between turns to read it:
     * `growsOnNextMove` is false exactly when the next move drags the body instead of extending it,
     * which is the move that gives this square back. So a snake's tail spends one of its own moves
     * aging and the next one dying, and a player can see where space is about to open up instead of
     * counting growth turns.
     *
     * Two rules out. A dead snake is a permanent obstacle, so a corpse keeps [corpse] — whatever the
     * flash has it at this frame — all the way to its tail, which is also what keeps the body one
     * alpha and therefore one path. And a trail that never retracts — `growEveryNthMove = 1`,
     * classic Tron — has no square about to clear, so fading one would be a lie about the rules in
     * play.
     */
    private fun tailAlpha(view: BoardView, snake: SnakeView, corpse: Double): Double = when {
        !snake.alive -> corpse
        tailClearsNext(view, snake) -> Theme.DYING_ALPHA
        // A trail that never retracts has no square about to open, and a one-square snake is all
        // head — which is drawn over this anyway.
        view.rules.growEveryNthMove < 2 || snake.length < 2 -> 1.0
        else -> Theme.AGING_ALPHA
    }

    /** [i] of [count] steps from the tail, as a value from [tail] at the oldest square to [head]. */
    private fun ramp(i: Int, count: Int, tail: Double, head: Double): Double =
        if (count < 2) head else tail + (head - tail) * i / (count - 1)

    private fun bodyWidth(): Double = (cellSize * BODY_WIDTH).coerceAtLeast(MIN_MARK)

    /** A filled circle in the current fill style: the head marker, an eye, a route's target. */
    private fun dot(x: Double, y: Double, radius: Double) {
        overlayContext.beginPath()
        overlayContext.arc(x, y, radius, 0.0, 2 * PI, false)
        overlayContext.fill()
    }

    private fun centreX(cell: Cell): Double = grid.colOf(cell) * cellSize + 1 + (cellSize - 1) / 2.0

    private fun centreY(cell: Cell): Double = grid.rowOf(cell) * cellSize + 1 + (cellSize - 1) / 2.0

    private class MoveTransition(
        val oldCells: IntArray,
        val retracts: Boolean,
        val startedAt: Double,
    )

    /** Guarded, because a ratio of zero would divide the CSS size by nothing. */
    private fun ratioNow(): Double = window.devicePixelRatio.takeIf { it > 0.0 } ?: 1.0

    private companion object {
        /**
         * The largest a single square may get, in CSS pixels at the ratio the page opened on — so,
         * in millimetres.
         *
         * It binds only on the small end of the picker: forty-four is a comfortable finger target
         * and an 8x8 drawn at it is a board rather than eight rows of tiles, while a 20x20 has to be
         * given nearly nine hundred pixels of frame before this is reached at all. Bigger boards
         * therefore take the whole frame and this figure never enters into it.
         */
        const val MAX_CELL = 44

        /** Below this a 28x28 board is unreadable. Also at CSS scale, and only a floor. */
        const val MIN_CELL = 6

        /**
         * Reached whenever `.board-wrap` measures zero — before the first layout, and on a screen
         * that is not showing the board. Both are followed by a `fit` that can measure for real.
         *
         * Both are asked of `.board-wrap` rather than of the window: the page is a viewport-height
         * column, so the board's own track already *is* "whatever is left after the two bars",
         * measured rather than guessed at as a share of the viewport. `#screen-game`'s
         * `minmax(0, 1fr)` row is what makes that reading definite — without it the track would size
         * to the canvas and the canvas to the track.
         */
        const val FALLBACK_WIDTH = 640
        const val FALLBACK_HEIGHT = 640

        /**
         * The smallest square that has room for a wall's edge, in device pixels.
         *
         * Below it the one-pixel perimeter is most of the square and the block is legible without
         * relief anyway — which is the 28x28-on-a-phone end of `MIN_CELL`, where a cell is a
         * handful of pixels across.
         */
        const val WALL_EDGE_MIN_CELL = 8

        /**
         * The smallest square a [TexturePack] is drawn on at all, in device pixels.
         *
         * [WALL_EDGE_MIN_CELL]'s decision one size up. An edge needs a pixel it can have; a groove
         * needs two blocks either side of it and a gap wide enough to be seen between them, so below
         * this every pack collapses to [TexturePack.PLAIN] rather than to a smudge that differs from
         * the plain board only in being dimmer.
         */
        const val TEXTURE_MIN_CELL = 12

        /**
         * How much of a cell a pack's figure takes: a stud on a block, a mark on the bare ground.
         *
         * The renderer's number rather than the pack's, because it is a *size* — a pack picks which
         * squares carry a figure and how much colour it keeps, and everything drawn on this bitmap
         * is measured here.
         */
        const val MARK_SHARE = 0.18

        /**
         * The floor under every width and radius drawn on the overlay, in device pixels.
         *
         * [MIN_CELL] is stated in CSS pixels at the ratio the page opened at, and a display can
         * report less than one — a browser zoomed out when it loaded — so a share of a cell can round
         * away to nothing. Two device pixels is the narrowest a mark can be and still be a mark, and
         * it is also what the spine's tail end ramps *down* to, so nothing on this bitmap is ever
         * drawn thinner than this.
         */
        const val MIN_MARK = 2.0

        /**
         * How much of a square the body fills.
         *
         * Deliberately not all of it: the margin either side is what lets two snakes lying alongside
         * each other read as two rather than as one slab, and it is where the hover wash shows
         * through.
         */
        const val BODY_WIDTH = 0.78

        /**
         * How much of the body's width the tail keeps at its tip.
         *
         * A share rather than a size of its own, so it inherits the body's floor and cannot sharpen
         * into a whisker on a small board. Not a point, either: the oldest square is still a square a
         * snake dies in, and it has to look like one.
         */
        const val TAIL_TIP_SHARE = 0.30

        /**
         * The spine at its widest, at the head end; [MIN_MARK] is what it ramps down to at the tail.
         *
         * Under a fifth of the cell against the body's [BODY_WIDTH], because the two are one drawing
         * at two widths — a highlight down the middle of the animal rather than a second animal.
         */
        const val SPINE_WIDTH = 0.16

        /** How much of the head colour the spine keeps, at the oldest square and at the newest. */
        const val SPINE_TAIL_ALPHA = 0.30
        const val SPINE_HEAD_ALPHA = 0.95

        /**
         * The head marker, as a share of the cell.
         *
         * A shade wider than half [BODY_WIDTH], so the ribbon's round cap ends *inside* the head and
         * a snake finishes in a bulge rather than in a stub the same width as the rest of it.
         */
        const val HEAD_RADIUS = 0.42

        /**
         * The eyes: how far forward of the head's centre the pair sits, how far either side of the
         * facing each one sits, and how large each dot is.
         *
         * Forward, because eyes centred on the square would say a snake is looking at itself; the
         * whole value of drawing them is that the animal is pointed the way it is about to move.
         */
        const val EYE_AHEAD = 0.10
        const val EYE_SPREAD = 0.13
        const val EYE_RADIUS = 0.085

        /**
         * The smallest square that gets eyes, in device pixels.
         *
         * A cut-off rather than a floor — see [drawHead]. Twelve is where a dot at [EYE_RADIUS] still
         * has a whole device pixel of radius, and below that a pair of them is one smudge.
         */
        const val EYE_MIN_CELL = 12

        /**
         * Which way a snake that has never moved is looking.
         *
         * Arbitrary, and that is the honest description: the alternative is a head that grows eyes on
         * its first move, and a drawing that arrives a move late reads as broken where an arbitrary
         * facing on a stationary snake reads as a snake waiting.
         */
        val OPENING_FACING = Direction.NORTH

        /** The overlay: how much of the head colour a hovered square takes, oldest to newest. */
        const val WASH_TAIL = 0.10
        const val WASH_HEAD = 0.62

        /**
         * The drawn route: how much of the accent a square it will walk takes, and the mark on the
         * square it ends on.
         *
         * Quiet, because a route has to be legible without competing with the snakes — it is what
         * *may* happen and they are what has. The mark is nearly opaque against that, since the one
         * thing a player checks mid-drag is where the far end of it has got to.
         */
        const val PLAN_ALPHA = 0.30
        const val PLAN_TARGET_ALPHA = 0.85
        const val PLAN_TARGET_RADIUS = 0.20

        /**
         * The previewed route, at well under half the committed one's weight in both figures.
         *
         * A preview is what a press *would* do; a route is what a press did, and on this board that
         * costs a move. So the two must not be mistakable at a glance — and the gap has to survive
         * the moment they are compared in, which is a hover sliding straight into a press on the
         * same squares. Under half rather than a step down is what makes 0.14 read as a ghost of the
         * route rather than as a fainter version of the same statement: against a 0.30 fill it is
         * the difference between "the board is tinted here" and "these squares are spoken for". The
         * mark keeps proportionally more, 0.40 against 0.85, because the far end of a route across a
         * busy board is the one thing a preview exists to answer and a wash that quiet loses it.
         */
        const val PREVIEW_ALPHA = 0.14
        const val PREVIEW_TARGET_ALPHA = 0.40

        /**
         * How long a snake takes to go out, in milliseconds of the motion clock.
         *
         * Long enough to see from the other side of the board and short enough that it is over
         * before the next turn on any speed a person watches at — a flash still playing while the
         * survivors have moved twice would read as lag rather than as a death.
         */
        const val DEATH_MILLIS = 300.0

        /** A visible turn's ease-out glide; engine state has already reached the destination. */
        const val MOVE_MILLIS = 120.0

        /** A stamp no reading of a clock that starts at zero and only grows can ever be inside. */
        const val NEVER = -DEATH_MILLIS

        /** How much larger a steered head swells, and how long one breath takes. */
        const val HEAD_PULSE = 0.12
        const val HEAD_PULSE_MILLIS = 1_400.0

        /**
         * The dashes on a route: how long each is, how thick, and how long one takes to travel a
         * square.
         *
         * A dash a third of a cell puts three along every square of the route, which is enough to
         * read as movement on a route one square long — the case a quick press produces. Slower than
         * the snake itself at every speed on the slider, deliberately: dashes that outran the animal
         * would say the route was the thing moving.
         */
        const val DASH_SHARE = 0.16
        const val DASH_WIDTH = 0.10
        const val DASH_MILLIS_PER_CELL = 260.0

        /**
         * How much brighter the dashes are than the tint they run over.
         *
         * A multiple rather than a weight of its own, so the preview stays the ghost of the
         * committed route here as it is everywhere else, and moving either alpha moves both.
         */
        const val DASH_LIFT = 2.0
    }
}
