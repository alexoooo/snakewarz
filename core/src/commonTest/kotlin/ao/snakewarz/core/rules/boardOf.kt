package ao.snakewarz.core.rules

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.grid.Occupancy
import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.core.snake.SnakeView

/** Builds a board from `(row, col)` pairs, which read far better in a test than cell indices. */
internal fun boardOf(
    rows: Int,
    cols: Int,
    vararg spawns: Pair<Int, Int>,
    rules: RulesConfig = RulesConfig(),
    turnOrder: IntArray = IntArray(spawns.size) { it },
    walls: List<Pair<Int, Int>> = emptyList(),
): Board {
    val grid = Grid(rows, cols)
    val cells = IntArray(spawns.size) { grid.cellAt(spawns[it].first, spawns[it].second).index }
    val wallCells = IntArray(walls.size) { grid.cellAt(walls[it].first, walls[it].second).index }
    return Board(grid, cells, rules, turnOrder, wallCells)
}

/**
 * A legal move for whoever is to act, and an arbitrary fatal one where none exists.
 *
 * Enough of a policy to play out real games, which is what the property tests want: a hand-written
 * sequence exercises the positions you thought of, and these exercise the ones you did not.
 */
internal fun chosenMove(board: Board, rng: Rng): Direction =
    rng.pick(board.legalMoves(board.toAct)) ?: Direction.entries[rng.nextInt(Direction.entries.size)]

/** The body as `(row, col)` pairs, tail first — readable enough to assert on directly. */
internal fun SnakeView.bodyIn(grid: Grid): List<Pair<Int, Int>> =
    (0 until length).map { grid.rowOf(cellAt(it)) to grid.colOf(cellAt(it)) }

/**
 * Everything about a board that a correct [Board.undo] has to restore: the clock, the turn, every
 * snake's metadata and body, the whole occupancy array, and the hash.
 *
 * Comparing rendered signatures rather than field-by-field assertions means a field added later is
 * covered by the undo tests the moment it appears in here.
 */
internal fun Board.signature(): String = buildString {
    append("turn=").append(turnIndex)
    append(" toAct=").append(toAct.index)
    append(" alive=").append(aliveCount)
    append(" outcome=").append(outcome)
    append(" hash=").append(hash)
    append(" occupancyHash=").append(occupancyHash)

    for (slot in 0 until snakeCount) {
        val snake = snake(SnakeId(slot))
        append("\n  ").append(slot)
        append(" alive=").append(snake.alive)
        append(" reason=").append(snake.eliminationReason)
        append(" moves=").append(snake.movesMade)
        append(" last=").append(snake.lastDirection)
        append(" grows=").append(snake.growsOnNextMove)
        append(" body=")
        for (i in 0 until snake.length) {
            append(snake.cellAt(i).index).append(',')
        }
    }

    append("\n  owners=")
    for (index in 0 until grid.cellCount) {
        val cell = Cell(index)
        if (grid.isPlayable(cell)) {
            append(ownerOf(cell).index).append(',')
        }
    }
}

/** The occupancy this board *should* have, rebuilt from nothing but the bodies it reports. */
internal fun Board.rebuiltOccupancy(): Occupancy {
    val rebuilt = Occupancy(grid)
    for (slot in 0 until snakeCount) {
        val snake = snake(SnakeId(slot))
        for (i in 0 until snake.length) {
            rebuilt.occupy(snake.cellAt(i), SnakeId(slot))
        }
    }
    return rebuilt
}
