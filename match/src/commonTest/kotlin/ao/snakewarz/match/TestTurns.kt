package ao.snakewarz.match

import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.scratch.BoardScratch
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.snake.SnakeId

/**
 * A one-snake board in the middle of open space, for tests about what a bot decides rather than
 * about what the driver does with it.
 */
internal fun soloBoard(rows: Int = 9, cols: Int = 9): Board {
    val grid = Grid(rows, cols)
    return Board(grid, intArrayOf(grid.cellAt(rows / 2, cols / 2).index))
}

/** The [Turn] the driver would hand [self] on [board] right now, with no search allowance. */
internal fun turnOn(board: Board, self: SnakeId = SnakeId(0)): Turn {
    val budget = Budget(0)
    return Turn(board, self, board.legalMoves(self), budget, BoardScratch(board, budget))
}
