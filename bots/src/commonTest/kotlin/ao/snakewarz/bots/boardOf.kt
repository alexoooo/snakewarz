package ao.snakewarz.bots

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.scratch.BoardScratch
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId

/**
 * Hand-built positions, for the tests that are about *one* decision rather than a whole match.
 *
 * [HeadlessMatch] is the other half of the fixture and answers a different question: it plays games,
 * these build boards. A bot's interesting behaviour is usually a single move on a shape you can draw
 * on paper, and this is how that shape gets built.
 */
internal fun boardOf(
    rows: Int,
    cols: Int,
    vararg spawns: Pair<Int, Int>,
    rules: RulesConfig = RulesConfig(),
): Board {
    val grid = Grid(rows, cols)
    return Board(grid, IntArray(spawns.size) { grid.cellAt(spawns[it].first, spawns[it].second).index }, rules)
}

/** The `Turn` the driver would hand [self] on [board], with the allowance a search bot would get. */
internal fun turnOn(board: Board, self: SnakeId = board.toAct, budget: Budget = Budget(0)): Turn =
    Turn(board, self, board.legalMoves(self), budget, BoardScratch(board, budget))

/**
 * Builds [factory]'s bot for the snake to act and asks it for one move.
 *
 * The setup mirrors the driver's exactly — a per-slot stream forked from the match seed, empty
 * params — because a bot that only works under a hand-made `BotSetup` is not the bot that ships.
 */
internal fun moveOn(
    board: Board,
    seed: Long = 1,
    budget: Budget = Budget(0),
    factory: (BotSetup) -> Bot,
): Direction {
    val self = board.toAct
    val bot = factory(setupFor(board, self, seed))
    val decision = bot.chooseMove(turnOn(board, self, budget))
    return (decision as Decision.Move).direction
}

internal fun setupFor(
    board: Board,
    self: SnakeId,
    seed: Long = 1,
    params: BotParams = BotParams.EMPTY,
): BotSetup =
    BotSetup(
        self = self,
        grid = board.grid,
        rules = board.rules,
        opponents = IntArray(board.snakeCount - 1) { if (it < self.index) it else it + 1 },
        rng = SplitMix64(seed).fork(self.index),
        params = params,
    )

/** The square at `(row, col)`, spelled the way the tests talk about the board. */
internal fun Board.at(row: Int, col: Int): Cell = grid.cellAt(row, col)

/** Where [id]'s head is, as `(row, col)` — the form an assertion can read. */
internal fun Board.headOf(id: SnakeId): Pair<Int, Int> =
    snake(id).head.let { grid.rowOf(it) to grid.colOf(it) }
