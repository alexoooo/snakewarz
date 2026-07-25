package ao.snakewarz.app

import ao.snakewarz.core.Cell
import ao.snakewarz.core.Grid
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

/**
 * Phase 0 entry point. This exists to prove the toolchain end to end: Kotlin/Wasm boots in a real
 * browser, can reach `:core`, and can paint a canvas at the right device pixel ratio.
 *
 * There is deliberately no game logic, no abstraction and no scheduler here. The real renderer
 * arrives in Phase 3 in the `:ui` module, at which point this file shrinks to wiring.
 */

private const val ROWS = 20
private const val COLS = 20

public fun main() {
    val canvas = document.getElementById("board") as? HTMLCanvasElement
        ?: error("index.html is missing the #board canvas")

    val grid = Grid(rows = ROWS, cols = COLS)

    setText("status-board", "${grid.rows} x ${grid.cols}, ${grid.cellCount} padded cells")
    setText("status-engine", ":core reachable, canvas painting")

    // Reveal the app *before* the first paint. #app starts `display: none`, and a hidden element
    // reports clientWidth 0, so measuring the board container first would size every board to the
    // minimum cell size. This must stay ahead of render().
    //
    // It also tells the boot watchdog in index.html that wasm started; without it the page would
    // show the unsupported panel once the timeout elapsed.
    document.body?.classList?.add("booted")

    render(canvas, grid)
    window.addEventListener("resize") { render(canvas, grid) }
}

private fun setText(id: String, value: String) {
    document.getElementById(id)?.textContent = value
}

private fun render(canvas: HTMLCanvasElement, grid: Grid) {
    val context = canvas.getContext("2d") as? CanvasRenderingContext2D
        ?: error("2d canvas context unavailable")

    val dark = window.matchMedia("(prefers-color-scheme: dark)").matches
    val background = if (dark) "#1c2024" else "#ffffff"
    val gridline = if (dark) "#2c3238" else "#e6e9ed"
    val spawnTint = if (dark) "#26352e" else "#edf4ef"

    // Integer cell size, so gridlines land on whole pixels and no seams appear.
    val available = (canvas.parentElement as? HTMLElement)?.clientWidth ?: 640
    val cellSize = ((available - 1) / grid.cols).coerceIn(8, 28)
    val width = cellSize * grid.cols + 1
    val height = cellSize * grid.rows + 1
    val ratio = window.devicePixelRatio

    // Resizing the backing store resets the context, so scale afterwards, never before.
    canvas.width = (width * ratio).toInt()
    canvas.height = (height * ratio).toInt()
    canvas.style.width = "${width}px"
    canvas.style.height = "${height}px"
    context.scale(ratio, ratio)

    context.fillStyle = background.toJsString()
    context.fillRect(0.0, 0.0, width.toDouble(), height.toDouble())

    // Legacy seeds the first snake at (0, 0) and the second at (rows-1, cols-1) -- the reason the
    // old README says "you always start in the bottom right". Shading both proves Grid addressing
    // actually works, rather than merely that a canvas exists.
    context.fillStyle = spawnTint.toJsString()
    fillCell(context, grid, grid.cellAt(0, 0), cellSize)
    fillCell(context, grid, grid.cellAt(grid.rows - 1, grid.cols - 1), cellSize)

    context.strokeStyle = gridline.toJsString()
    context.lineWidth = 1.0
    context.beginPath()
    for (col in 0..grid.cols) {
        val x = col * cellSize + 0.5
        context.moveTo(x, 0.0)
        context.lineTo(x, height.toDouble())
    }
    for (row in 0..grid.rows) {
        val y = row * cellSize + 0.5
        context.moveTo(0.0, y)
        context.lineTo(width.toDouble(), y)
    }
    context.stroke()
}

private fun fillCell(context: CanvasRenderingContext2D, grid: Grid, cell: Cell, cellSize: Int) {
    context.fillRect(
        (grid.colOf(cell) * cellSize + 1).toDouble(),
        (grid.rowOf(cell) * cellSize + 1).toDouble(),
        (cellSize - 1).toDouble(),
        (cellSize - 1).toDouble(),
    )
}
