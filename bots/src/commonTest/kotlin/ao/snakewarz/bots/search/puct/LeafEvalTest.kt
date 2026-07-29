package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.bots.search.learned.LearnedEval
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every [LeafEval], on positions small enough to work out by hand.
 *
 * What every implementation owes the tree whatever it measures — the scale, the reading of a corpse,
 * one unit of allowance — plus the claims [TerritoryEval] makes on its own. [SurvivalEvalTest]
 * carries what only the fillable-space reading can say, and `ChamberEvalTest` what only the chambers
 * can.
 *
 * The weights are passed in rather than taken from [PuctBot]'s declared defaults, because those are
 * a measurement that will move and these are claims about the *shape* of the appraisal, which should
 * not.
 */
class LeafEvalTest {
    @Test
    fun `every reading lands on the scale the tree credits`() {
        val boards = listOf(
            boardOf(1, 1, 0 to 0),
            boardOf(1, 2, 0 to 0, 0 to 1),
            boardOf(1, 6, 0 to 0, 0 to 5),
            boardOf(3, 7, 0 to 0, 2 to 6),
            boardOf(8, 8, 0 to 0, 7 to 7, 0 to 7),
        )

        for (board in boards) {
            val slots = board.snakeCount
            val values = DoubleArray(slots)

            for (eval in evalsFor(board)) {
                eval.valuesInto(playoutOn(board), values)
                for (slot in 0 until slots) {
                    assertTrue(
                        values[slot] in LeafEval.LOSS..LeafEval.WIN,
                        "$eval on ${board.grid.rows}x${board.grid.cols} read ${values[slot]} for slot $slot",
                    )
                }
            }
        }
    }

    @Test
    fun `a dead snake is a loss, whatever is still on the board`() {
        val board = boardOf(4, 4, 0 to 0, 3 to 3)
        board.eliminate(SnakeId(0), EliminationReason.RESIGNED)
        val values = DoubleArray(2)

        for (eval in evalsFor(board)) {
            eval.valuesInto(playoutOn(board), values)
            assertEquals(LeafEval.LOSS, values[0], "$eval kept a corpse in the game")
        }
    }

    @Test
    fun `while the board is contested, more ground reads better than less`() {
        // 1x6 with heads at columns 1 and 5. West owns columns 0 and 2, east owns column 4, and
        // column 3 is a tie that belongs to neither -- so it is two against one, and still a fight.
        val board = boardOf(1, 6, 0 to 1, 0 to 5)
        val values = DoubleArray(2)

        territory(board).valuesInto(playoutOn(board), values)

        assertTrue(values[0] > LeafEval.EVEN, "the snake with the larger share reads above even: ${values[0]}")
        assertTrue(values[1] < LeafEval.EVEN, "and its opponent below it: ${values[1]}")
    }

    @Test
    fun `separation is what the appraisal is for, and it saturates`() {
        val board = sealedBoard()
        val space = SpaceOwnership(board.grid, 2)
        space.measure(board)
        assertTrue(space.isolated(0) && space.isolated(1), "the fixture is only interesting if they are apart")

        val values = DoubleArray(2)
        // Everything but the separation branch is off, so this is about that branch alone: slot 1
        // has nothing legal left as well as nothing to fill, and the readings would otherwise tangle.
        territory(board, separationBonus = 0.9, trapPenalty = 0.0, mobilityWeight = 0.0)
            .valuesInto(playoutOn(board), values)

        assertTrue(values[0] >= 0.9, "fifteen squares against none is decided, not close: ${values[0]}")
        assertTrue(values[1] <= 0.1, "and the shut-in snake is losing it: ${values[1]}")

        val flat = DoubleArray(2)
        territory(board, separationBonus = 0.0, trapPenalty = 0.0, mobilityWeight = 0.0)
            .valuesInto(playoutOn(board), flat)

        assertEquals(LeafEval.EVEN, flat[0], "with the bonus off, a decided game reads as an even one")
        assertEquals(LeafEval.EVEN, flat[1], "which is the reading the branch exists to replace")
    }

    @Test
    fun `a separated snake reads a margin rather than a verdict`() {
        // The measured correction. A step function made every move in a separated position read the
        // same number, leaving the search nothing to prefer in exactly the phase where this game is
        // a space-filling puzzle -- and `:lab` scored it 0 of 40 against a bot that only counts
        // liberties. Two rooms apart by one square must not read the same as two rooms apart by ten.
        val narrow = roomsOf(mine = 6, theirs = 5)
        val wide = roomsOf(mine = 10, theirs = 1)

        assertTrue(narrow < wide, "a bigger margin has to read better: $narrow against $wide")
        assertTrue(narrow > LeafEval.EVEN, "and both are still ahead: $narrow")
    }

    @Test
    fun `a snake with nothing legal left is marked down for it`() {
        val board = sealedBoard()
        assertTrue(board.legalMoves(SnakeId(1)).isEmpty, "the fixture is only interesting if it is stuck")

        val values = DoubleArray(2)
        // Separation off, so the only thing left to move slot 1 off even is the penalty.
        territory(board, separationBonus = 0.0, trapPenalty = 0.35, mobilityWeight = 0.0)
            .valuesInto(playoutOn(board), values)

        assertEquals(LeafEval.EVEN, values[0], "the snake with room to move is not penalised")
        assertTrue(values[1] < LeafEval.EVEN, "and the one with none is: ${values[1]}")
    }

    @Test
    fun `mobility reads the same one-sided position the same way round`() {
        val board = sealedBoard()
        val values = DoubleArray(2)

        MobilityEval(2).valuesInto(playoutOn(board), values)

        assertTrue(values[0] > values[1], "the snake that can still move is ahead of the one that cannot")
        assertEquals(LeafEval.LOSS, values[1], "no liberties out of all of them is none of the share")
    }

    @Test
    fun `mobility calls a symmetric position even, and costs almost nothing to say so`() {
        val board = boardOf(1, 5, 0 to 0, 0 to 4)
        val values = DoubleArray(2)

        val eval = MobilityEval(2)
        eval.valuesInto(playoutOn(board), values)

        assertEquals(LeafEval.EVEN, values[0])
        assertEquals(LeafEval.EVEN, values[1])
    }

    @Test
    fun `every evaluation is priced the same, so an allowance is a count of them`() {
        // Uncalibrated on purpose -- EvaluationCost says so out loud. What has to hold is that the
        // four are one currency, because a matrix comparing them at "the same allowance" is
        // otherwise comparing nothing.
        val board = boardOf(5, 5, 0 to 0, 4 to 4)

        assertEquals(1, MobilityEval(2).cost)
        assertEquals(1, territory(board).cost)
        assertEquals(1, survival(board).cost)
        assertEquals(1, horizon(board).cost)
        assertEquals(1, chamber(board).cost)
    }

    @Test
    fun `an evaluation is paid for by the playout it is handed, whatever it then does`() {
        // The dearest of the three, on a board big enough for it to walk: one unit buys the whole
        // appraisal however much of the board that turns out to be.
        val board = boardOf(6, 6, 0 to 0, 5 to 5)
        val values = DoubleArray(2)

        val budget = Budget(1)
        survival(board).valuesInto(playoutOn(board, budget), values)

        assertEquals(1, budget.consumed)
    }

    @Test
    fun `a finished position and a judged one arrive in the same shape`() {
        val values = DoubleArray(3)

        outcomeValues(MatchOutcome(SnakeId(1), MatchEnd.LAST_SNAKE_STANDING), 3, values)
        assertEquals(listOf(LeafEval.LOSS, LeafEval.WIN, LeafEval.LOSS), values.toList())

        outcomeValues(MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT), 3, values)
        assertEquals(listOf(LeafEval.EVEN, LeafEval.EVEN, LeafEval.EVEN), values.toList())
    }

    // -- internals

    /**
     * Two snakes walked south in step until each has filled a column, sealing the east one into the
     * corner: slot 0 has the fifteen squares of columns 0 to 2 and slot 1 has nothing at all.
     */
    private fun sealedBoard(): Board {
        val board = boardOf(5, 5, 0 to 3, 0 to 4, rules = RulesConfig(growEveryNthMove = 1))
        repeat(4) {
            board.apply(SnakeId(0), Direction.SOUTH)
            board.apply(SnakeId(1), Direction.SOUTH)
        }
        return board
    }

    private fun playoutOn(board: Board, budget: Budget = Budget(1_000)): Playout =
        turnOn(board, board.toAct, budget).scratch.playout()

    /**
     * A [TerritoryEval] sized for [board].
     *
     * The grid comes off the board rather than being defaulted, because the sweep inside steps
     * through *its* grid's padded address space — a 5x5 has a stride of seven and a 1x6 has eight,
     * so an evaluation built for one would read a different board than the one it was handed.
     */
    private fun territory(
        board: Board,
        separationBonus: Double = 0.9,
        trapPenalty: Double = 0.35,
        territoryWeight: Double = 0.7,
        mobilityWeight: Double = 0.2,
    ): TerritoryEval =
        TerritoryEval(board.grid, board.snakeCount, territoryWeight, mobilityWeight, trapPenalty, separationBonus)

    /** A [HorizonEval] sized for [board], on the same weights and for the same reason. */
    private fun horizon(
        board: Board,
        separationBonus: Double = 0.9,
        trapPenalty: Double = 0.35,
        territoryWeight: Double = 0.7,
        mobilityWeight: Double = 0.2,
    ): HorizonEval =
        HorizonEval(board.grid, board.snakeCount, territoryWeight, mobilityWeight, trapPenalty, separationBonus)

    /** A [SurvivalEval] sized for [board], on the same weights and for the same reason. */
    private fun survival(
        board: Board,
        separationBonus: Double = 0.9,
        trapPenalty: Double = 0.35,
        territoryWeight: Double = 0.7,
        mobilityWeight: Double = 0.2,
    ): SurvivalEval =
        SurvivalEval(board.grid, board.snakeCount, territoryWeight, mobilityWeight, trapPenalty, separationBonus)

    /**
     * Slot 0's reading of a corridor divided into two rooms of the sizes asked for.
     *
     * Two heads side by side in a row: each can only expand away from the other, so they are
     * separated with no bodies to build, and the room sizes are the two stretches of corridor left.
     * Both heads have exactly one liberty either way round, so varying the sizes varies the margin
     * and nothing else — which is why the other terms are switched off here.
     */
    private fun roomsOf(mine: Int, theirs: Int): Double {
        val board = boardOf(1, mine + theirs + 2, 0 to mine, 0 to (mine + 1))

        val space = SpaceOwnership(board.grid, 2)
        val owned = space.measure(board)
        assertEquals(listOf(mine, theirs), owned.toList(), "the fixture did not divide as intended")
        assertTrue(space.isolated(0) && space.isolated(1), "the fixture is only interesting if they are apart")

        val values = DoubleArray(2)
        territory(board, mobilityWeight = 0.0, trapPenalty = 0.0).valuesInto(playoutOn(board), values)
        return values[0]
    }

    /**
     * A [ChamberEval] sized for [board], on the same four weights and its own three.
     *
     * The three chamber weights are its shipped defaults rather than a fixture's, because unlike the
     * four above they have no meaning outside this evaluation and no second implementation to be held
     * still against. `ChamberEvalTest` is where they are varied.
     */
    private fun chamber(
        board: Board,
        separationBonus: Double = 0.9,
        trapPenalty: Double = 0.35,
        territoryWeight: Double = 0.7,
        mobilityWeight: Double = 0.2,
    ): ChamberEval =
        ChamberEval(
            board.grid,
            board.snakeCount,
            territoryWeight,
            mobilityWeight,
            trapPenalty,
            separationBonus,
            PuctBot.PARITY_WEIGHT.default,
            PuctBot.FRONTIER_PENALTY.default,
            PuctBot.SEAL_PENALTY.default,
        )

    private fun evalsFor(board: Board): List<LeafEval> = listOf(
        territory(board),
        MobilityEval(board.snakeCount),
        survival(board),
        horizon(board),
        chamber(board),
        // At the shipped literal, because what the tests above assert of every leaf — the scale, the
        // reading of a corpse — is exactly what a fit could get wrong without failing anywhere else.
        LearnedEval(board.grid, board.snakeCount),
    )
}
