package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [SurvivalEval] can say that counting squares cannot.
 *
 * Mostly [FillableSpace], because that is where the difference lives — the sweep hands over an area
 * and this asks what a walk could actually spend of it. The shapes are small enough to work out on
 * paper, and each is chosen so that the two readings *disagree*: a fixture where they agree tests
 * nothing this class does.
 *
 * The contract every [LeafEval] shares — the scale, a corpse, the price — is in [LeafEvalTest].
 */
class SurvivalEvalTest {
    @Test
    fun `a corridor entered at its end is worth all of it`() {
        // The baseline. Nothing branches and nothing is stranded, so the honest answer is the area,
        // and an estimate that came in under it here would be pessimistic everywhere.
        val board = boardOf(1, 4, 0 to 0)

        assertEquals(3, fillableFor(board, slot = 0), "three squares ahead, three squares taken")
    }

    @Test
    fun `a fork is worth its better arm, not both`() {
        // 1x7, heads at columns 3 and 6. The sweep gives the west snake columns 0, 1, 2 and 4 -- four
        // squares, on both sides of its own head. A snake is a walk, so it commits to one side and
        // the other is gone the moment it turns. This is the whole claim of the class in one board.
        val board = boardOf(1, 7, 0 to 3, 0 to 6)

        val space = TempoOwnership(board.grid, 2)
        val owned = space.measure(board)
        assertEquals(4, owned[0], "the sweep hands it four squares")

        val usable = FillableSpace(board.grid).measure(space, 0, board.snake(SnakeId(0)).head)
        assertEquals(3, usable, "but a walk out of the middle can only have one arm")
    }

    @Test
    fun `a room entered at a corner gives up nothing`() {
        // A 3x3 has five squares of one colour and four of the other. Entering at a corner -- the
        // majority colour -- the alternation works out exactly, and every square is reachable in one
        // walk. So parity must not cost anything here.
        val board = boardOf(3, 3, 0 to 0)

        assertEquals(8, fillableFor(board, slot = 0), "a walk from a corner of a 3x3 takes the lot")
    }

    @Test
    fun `the same room entered at an edge loses a square to parity`() {
        // Same eight squares, one step round the edge. A walk alternates colours, and from an edge
        // square it would need five of the four squares its colour has -- so one square of the room
        // is unreachable no matter how the walk is drawn. Counting squares cannot see this at all.
        val board = boardOf(3, 3, 0 to 1)

        assertEquals(7, fillableFor(board, slot = 0), "eight squares of room, seven squares of walk")
    }

    @Test
    fun `a snake with nowhere to go can spend nothing`() {
        val board = boardOf(1, 2, 0 to 0, 0 to 1)

        assertEquals(0, fillableFor(board, slot = 0))
    }

    @Test
    fun `a separated snake is judged on what it can spend rather than what it holds`() {
        // Two columns walked south in step until each is sealed off: slot 0 has the fifteen squares
        // of columns 0 to 2 and slot 1 has none. The room is a plain rectangle entered at its corner,
        // so all fifteen are spendable -- which is what makes this the right fixture for the wiring
        // rather than for the estimate. The reading has to saturate the same way TerritoryEval's does.
        val board = sealedBoard()
        val space = TempoOwnership(board.grid, 2)
        space.measure(board)
        assertTrue(space.isolated(0) && space.isolated(1), "the fixture is only interesting if they are apart")

        assertEquals(15, fillableFor(board, slot = 0), "a 5x3 rectangle entered at a corner is all walk")

        val values = DoubleArray(2)
        eval(board, trapPenalty = 0.0, mobilityWeight = 0.0).valuesInto(playoutOn(board), values)

        assertTrue(values[0] >= 0.9, "fifteen moves against none is decided, not close: ${values[0]}")
        assertTrue(values[1] <= 0.1, "and the shut-in snake is losing it: ${values[1]}")
    }

    @Test
    fun `while the board is contested, more room to move reads better than less`() {
        val board = boardOf(1, 6, 0 to 1, 0 to 5)
        val values = DoubleArray(2)

        eval(board).valuesInto(playoutOn(board), values)

        assertTrue(values[0] > LeafEval.EVEN, "the snake with more of the corridor reads above even: ${values[0]}")
        assertTrue(values[1] < LeafEval.EVEN, "and its opponent below it: ${values[1]}")
    }

    @Test
    fun `the buffers survive being reused, which is the only way they are ever used`() {
        // One instance per bot per match, thousands of leaves a turn. A generation stamp that failed
        // to reset would make the second reading of a position depend on the first.
        val board = boardOf(3, 3, 0 to 0)
        val fillable = FillableSpace(board.grid)
        val space = TempoOwnership(board.grid, 1)
        space.measure(board)

        val head = board.snake(SnakeId(0)).head
        val first = fillable.measure(space, 0, head)

        assertEquals(first, fillable.measure(space, 0, head), "the same question, twice, one answer")
    }

    // -- internals

    private fun sealedBoard(): Board {
        val board = boardOf(5, 5, 0 to 3, 0 to 4, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) {
            board.apply(SnakeId(0), Direction.SOUTH)
            board.apply(SnakeId(1), Direction.SOUTH)
        }
        return board
    }

    /** The squares [slot] could still spend on [board], sweep and all, as [SurvivalEval] asks it. */
    private fun fillableFor(board: Board, slot: Int): Int {
        val space = TempoOwnership(board.grid, board.snakeCount)
        space.measure(board)
        return FillableSpace(board.grid).measure(space, slot, board.snake(SnakeId(slot)).head)
    }

    private fun playoutOn(board: Board, budget: Budget = Budget(1_000)): Playout =
        turnOn(board, board.toAct, budget).scratch.playout()

    private fun eval(
        board: Board,
        separationBonus: Double = 0.9,
        trapPenalty: Double = 0.35,
        territoryWeight: Double = 0.7,
        mobilityWeight: Double = 0.2,
    ): SurvivalEval =
        SurvivalEval(board.grid, board.snakeCount, territoryWeight, mobilityWeight, trapPenalty, separationBonus)
}
