package ao.snakewarz.ui

import ao.snakewarz.core.Board
import ao.snakewarz.core.Cell
import ao.snakewarz.core.Grid
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hit-test, which `docs/UI.md` records having been a pixel out at fractional device-pixel
 * ratios — a class of regression that is invisible on screen and provable by arithmetic.
 *
 * Everything here is expressed as a **round trip** rather than as literal pixels: take the centre of
 * a square, convert it to client coordinates through the box the canvas is actually drawn at, and
 * ask which square that is. That holds at any ratio, which matters because the ratio a test runner
 * reports is not the one a player has.
 *
 * The canvas has to be in the document, because `getBoundingClientRect` on a detached element is all
 * zeros and [BoardRenderer.cellAt] correctly answers [Cell.NONE] for that.
 */
class BoardRendererTest {
    private val canvas = document.createElement("canvas") as HTMLCanvasElement
    private val overlay = document.createElement("canvas") as HTMLCanvasElement

    init {
        document.body?.appendChild(canvas)
        document.body?.appendChild(overlay)
    }

    @AfterTest
    fun detach() {
        canvas.remove()
        overlay.remove()
    }

    @Test
    fun `the centre of every square answers with that square`() {
        for (grid in listOf(Grid(8, 8), Grid(20, 20), Grid(12, 20), Grid(40, 40))) {
            val renderer = fitted(grid)
            val cellSize = (canvas.width - 1) / grid.cols
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
        val cellSize = (canvas.width - 1) / grid.cols
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

    /** Sizes the canvas to [grid] the way a real board would be, and hands back its renderer. */
    private fun fitted(grid: Grid): BoardRenderer {
        val renderer = BoardRenderer(canvas, overlay)
        renderer.fit(Board(grid, intArrayOf(grid.cellAt(0, 0).index)))
        return renderer
    }
}
