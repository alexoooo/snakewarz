package ao.snakewarz.bots

import ao.snakewarz.botapi.BoardScratch
import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotParams
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Board
import ao.snakewarz.core.Budget
import ao.snakewarz.core.Direction
import ao.snakewarz.core.DirectionSet
import ao.snakewarz.core.EliminationReason
import ao.snakewarz.core.Grid
import ao.snakewarz.core.MatchOutcome
import ao.snakewarz.core.RulesConfig
import ao.snakewarz.core.SnakeId
import ao.snakewarz.core.SplitMix64

/**
 * A minimal turn loop, so `:bots` can test bots without reaching for `:match`.
 *
 * The forty lines duplicated here are the price of the module boundary, and a cheap one. `:bots` may
 * not depend on `:match` — not in production and not in a test either, because a test dependency is
 * still an edge in the resolved graph and would put the driver within a bot's reach. What this loop
 * does *not* have is as telling as what it does: no clock, no recording, no registry.
 */
internal class HeadlessMatch(
    entries: List<BotEntry>,
    rows: Int,
    cols: Int,
    seed: Long,
    budgetPerTurn: Int = 1_000,
    rules: RulesConfig = RulesConfig(),
) {
    private val grid = Grid(rows, cols)
    private val board = Board(grid, cornerSpawns(grid, entries.size), rules)
    private val matchRng = SplitMix64(seed)
    private val budgets = Array(entries.size) { Budget(budgetPerTurn) }
    private val scratches = Array(entries.size) { BoardScratch(board, budgets[it]) }

    private val bots: Array<Bot> = Array(entries.size) { slot ->
        entries[slot].factory.create(
            BotSetup(
                self = SnakeId(slot),
                grid = grid,
                rules = rules,
                opponents = IntArray(entries.size - 1) { if (it < slot) it else it + 1 },
                rng = matchRng.fork(slot),
                params = BotParams.EMPTY,
            ),
        )
    }

    /** Every decision made, in play order, alongside what was legal when it was made. */
    val decisions: MutableList<RecordedDecision> = mutableListOf()

    fun run(): MatchOutcome {
        while (true) {
            val finished = board.outcome
            if (finished != null) {
                return finished
            }

            val id = board.toAct
            val budget = budgets[id.index]
            budget.reset()

            val legal = board.legalMoves(id)
            val decision = bots[id.index].chooseMove(Turn(board, id, legal, budget, scratches[id.index]))
            decisions += RecordedDecision(id, legal, decision, budget.consumed)

            when (decision) {
                is Decision.Move -> board.apply(id, decision.direction)
                else -> board.eliminate(id, EliminationReason.RESIGNED)
            }
        }
    }

    /** The move stream, which is what the golden hashes are taken over. */
    fun moves(): List<Direction> = decisions.mapNotNull { (it.decision as? Decision.Move)?.direction }
}

internal class RecordedDecision(
    val id: SnakeId,
    val legal: DirectionSet,
    val decision: Decision,
    val budgetConsumed: Int,
)

/** Corners, so a test board needs nothing from `:match`'s spawn placement. */
internal fun cornerSpawns(grid: Grid, count: Int): IntArray {
    require(count in 1..4) { "the headless harness seats up to four snakes, was $count" }

    val corners = listOf(
        0 to 0,
        grid.rows - 1 to grid.cols - 1,
        0 to grid.cols - 1,
        grid.rows - 1 to 0,
    )
    return IntArray(count) { grid.cellAt(corners[it].first, corners[it].second).index }
}

/**
 * A fold over a move stream, stable across targets and Kotlin versions.
 *
 * Spelled out rather than borrowed from the standard library on purpose: the numbers it produces are
 * checked into the repository, so what computes them must not quietly change underneath them.
 */
internal fun moveStreamHash(moves: List<Direction>): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (move in moves) {
        hash = (hash xor (move.ordinal + 1).toLong()) * 0x100000001b3L
    }
    return hash
}
