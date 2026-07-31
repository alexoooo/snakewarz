package ao.snakewarz.ui.render

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.Board
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hit-test, which `docs/UI.md` records having been a pixel out at fractional device-pixel
 * ratios — a class of regression that is invisible on screen and provable by arithmetic — and the
 * fit that decides how large a square is in the first place.
 *
 * The hit-test is expressed as a **round trip** rather than as literal pixels: take the centre of a
 * square, convert it to client coordinates through the box the canvas is actually drawn at, and ask
 * which square that is. That holds at any ratio, which matters because the ratio a test runner
 * reports is not the one a player has.
 *
 * The canvases live inside a frame of a **stated size**, for the reason `#screen-game` gives its
 * board row a definite one: [BoardRenderer.fit] measures the parent, so a parent sized by the canvas
 * inside it would be exactly the circularity the layout is arranged to avoid, and the board would
 * come out a different size depending on what ran before it.
 */
class BoardRendererTest {
    private val frame = document.createElement("div") as HTMLElement
    private val canvas = document.createElement("canvas") as HTMLCanvasElement
    private val overlay = document.createElement("canvas") as HTMLCanvasElement

    init {
        frame.appendChild(canvas)
        frame.appendChild(overlay)
        document.body?.appendChild(frame)
    }

    @AfterTest
    fun detach() {
        frame.remove()
    }

    @Test
    fun `the centre of every square answers with that square`() {
        for (grid in listOf(Grid(8, 8), Grid(20, 20), Grid(12, 20), Grid(40, 40))) {
            val renderer = fitted(grid)
            val cellSize = cellSize(grid)
            val box = canvas.getBoundingClientRect()

            for (row in 0 until grid.rows) {
                for (col in 0 until grid.cols) {
                    // A cell of side s owns the pixels `c*s + 1` through `c*s + s`, so its centre in
                    // the backing store is half a cell past the gridline that opens it.
                    val backingX = col * cellSize + 1 + cellSize / 2.0
                    val backingY = row * cellSize + 1 + cellSize / 2.0

                    val found = renderer.cellAt(
                        box.left + backingX * box.width / canvas.width,
                        box.top + backingY * box.height / canvas.height,
                    )

                    assertEquals(grid.cellAt(row, col), found, "$grid at ($row, $col)")
                }
            }
        }
    }

    @Test
    fun `a point outside the board is nobody's square`() {
        val grid = Grid(12, 12)
        val renderer = fitted(grid)
        val box = canvas.getBoundingClientRect()

        assertEquals(Cell.NONE, renderer.cellAt(box.left - 4.0, box.top + 4.0), "left of it")
        assertEquals(Cell.NONE, renderer.cellAt(box.left + 4.0, box.top - 4.0), "above it")
        assertEquals(Cell.NONE, renderer.cellAt(box.right + 4.0, box.top + 4.0), "right of it")
        assertEquals(Cell.NONE, renderer.cellAt(box.left + 4.0, box.bottom + 4.0), "below it")
    }

    @Test
    fun `the gridline gutter belongs to the square below and right of it`() {
        // The convention every fill uses: the one-pixel gutter along a cell's top and left edge is
        // the gridline's, so the square the pointer is visibly over is the one this answers with.
        val grid = Grid(10, 10)
        val renderer = fitted(grid)
        val cellSize = cellSize(grid)
        val box = canvas.getBoundingClientRect()

        fun at(backingX: Double, backingY: Double): Cell = renderer.cellAt(
            box.left + backingX * box.width / canvas.width,
            box.top + backingY * box.height / canvas.height,
        )

        // The first pixel a cell owns, and the last.
        assertEquals(grid.cellAt(0, 0), at(1.0, 1.0))
        assertEquals(grid.cellAt(0, 0), at(cellSize.toDouble(), cellSize.toDouble()))
        assertEquals(grid.cellAt(1, 1), at(cellSize + 1.0, cellSize + 1.0))
    }

    /**
     * The board takes the room it is given, up to a cell size that stops a small board becoming a
     * handful of enormous squares.
     *
     * Stated as behaviour rather than against the constant: in a frame no board can exhaust, every
     * board comes out at the *same* cell size and leaves room over — which is what a maximum is —
     * and in a frame smaller than that, the board is as large as the frame allows and no larger.
     */
    @Test
    fun `the cell is capped in a large frame and fills a small one`() {
        val small = Grid(8, 8)
        val large = Grid(40, 40)

        fitted(small, ROOMY_FRAME)
        val cappedCell = cellSize(small)
        fitted(large, ROOMY_FRAME)

        assertEquals(cappedCell, cellSize(large), "a maximum is a maximum whatever the grid is")
        assertTrue(
            cappedCell * small.cols + 1 < ROOMY_FRAME * ratio(),
            "an 8x8 held at the maximum leaves room over",
        )

        // Too narrow for eight squares at that maximum, so here the room is what decides.
        val room = SNUG_FRAME * ratio()
        fitted(small, SNUG_FRAME)
        val snugCell = cellSize(small)

        assertTrue(snugCell < cappedCell, "the room binds below the maximum, not the other way round")
        assertTrue(canvas.width <= room, "the board never claims more room than it measured")
        assertTrue(canvas.width + snugCell > room, "and takes all of it bar the remainder of one cell")
    }

    // -- internals

    private fun cellSize(grid: Grid): Int = (canvas.width - 1) / grid.cols

    private fun ratio(): Double = window.devicePixelRatio.takeIf { it > 0.0 } ?: 1.0

    /** Sizes the canvas to [grid] inside a frame of [side] CSS pixels, and hands back its renderer. */
    private fun fitted(grid: Grid, side: Int = ORDINARY_FRAME): BoardRenderer {
        frame.style.width = "${side}px"
        frame.style.height = "${side}px"

        val renderer = BoardRenderer(canvas, overlay)
        renderer.fit(Board(grid, intArrayOf(grid.cellAt(0, 0).index)))
        return renderer
    }

    private companion object {
        /** A desktop-sized board panel, which is what the hit-test wants to be measured on. */
        const val ORDINARY_FRAME = 640

        /** Larger than forty cells at any plausible maximum, so the maximum is what decides. */
        const val ROOMY_FRAME = 2400

        /** Smaller than eight cells at any plausible maximum, so the room is what decides. */
        const val SNUG_FRAME = 240
    }
}
