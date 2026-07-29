package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.Budget
import ao.snakewarz.core.rules.Board
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [ChamberEval] can say that a chain of chambers summed to one number cannot.
 *
 * Three weights and therefore three claims, and the first of them is that turning all three off is
 * not a new evaluation at all: at the cap, with nothing discounted and nothing penalised, this has to
 * read exactly what [SurvivalEval] reads. That is what makes a batch between the two settings a batch
 * about the three weights rather than about two implementations that drifted.
 *
 * The contract every [LeafEval] shares — the scale, a corpse, the price — is in [LeafEvalTest].
 */
class ChamberEvalTest {
    @Test
    fun `with all three weights neutral it is the fillable-space reading, to the bit`() {
        // Not "close to": the same integer chain, divided the same way, on the same scale. Anything
        // less makes every later comparison between the two settings a comparison of two things.
        val boards = listOf(
            boardOf(1, 6, 0 to 1, 0 to 5),
            boardOf(1, 7, 0 to 3, 0 to 6),
            boardOf(3, 7, 0 to 0, 2 to 6),
            boardOf(5, 5, 0 to 0, 4 to 4),
            boardOf(8, 8, 0 to 0, 7 to 7, 0 to 7),
        )

        for (board in boards) {
            val slots = board.snakeCount
            val fromChambers = DoubleArray(slots)
            val fromSquares = DoubleArray(slots)

            chamber(board, parityWeight = 1.0, frontierPenalty = 0.0, sealPenalty = 0.0)
                .valuesInto(playoutOn(board), fromChambers)
            SurvivalEval(board.grid, slots, 0.7, 0.2, 0.35, 0.9)
                .valuesInto(playoutOn(board), fromSquares)

            assertEquals(
                fromSquares.toList(),
                fromChambers.toList(),
                "on ${board.grid.rows}x${board.grid.cols} the neutral chamber reading is not the square one",
            )
        }
    }

    @Test
    fun `a snake that has cut itself off from half its room is marked down for it`() {
        // 1x7, heads at columns 3 and 6. The sweep gives the west snake four squares -- three on one
        // side of its head and one on the other -- and a walk gets three of them. To SurvivalEval
        // that is a three and nothing else; here the fourth square is a quarter of the region the
        // chain never reaches, and the penalty is that quarter.
        val forked = boardOf(1, 7, 0 to 3, 0 to 6)
        val whole = boardOf(1, 7, 0 to 0, 0 to 6)

        // Everything but the seal is off, so this is about that term alone.
        assertEquals(LeafEval.EVEN, sealOnly(whole, sealPenalty = 0.4), "a corridor seals nothing")

        val forkedValue = sealOnly(forked, sealPenalty = 0.4)
        assertTrue(forkedValue < LeafEval.EVEN, "a quarter of the region cut off has to cost something")
        assertEquals(
            LeafEval.EVEN,
            sealOnly(forked, sealPenalty = 0.0),
            "and with the weight at zero it has to cost nothing, or the knob is not the whole of it",
        )
    }

    @Test
    fun `ground the other snake can still take is worth less than ground it cannot`() {
        // 1x6, heads at columns 1 and 5. Moving first, west reaches columns 0, 2 and 3 and east only
        // column 4, so the boundary runs between 3 and 4 and each snake has exactly one square on it.
        // That one square is east's whole chain and half of west's, so discounting the two costs them
        // unequally — which is the asymmetry a count of held squares cannot express at all.
        val board = boardOf(1, 6, 0 to 1, 0 to 5)

        val level = territoryOnly(board, frontierPenalty = 0.0)
        val discounted = territoryOnly(board, frontierPenalty = 0.5)

        assertTrue(level > LeafEval.EVEN, "the snake with more ground is ahead before anything is discounted")
        assertTrue(discounted > level, "and further ahead once what its rival could still take is: $discounted")
    }

    // -- internals

    /** Slot 0's reading with every term but the seal switched off. */
    private fun sealOnly(board: Board, sealPenalty: Double): Double {
        val values = DoubleArray(board.snakeCount)
        chamber(
            board,
            territoryWeight = 0.0,
            mobilityWeight = 0.0,
            trapPenalty = 0.0,
            separationBonus = 0.0,
            sealPenalty = sealPenalty,
        ).valuesInto(playoutOn(board), values)
        return values[0]
    }

    /** Slot 0's reading with nothing but the contested share moving it. */
    private fun territoryOnly(board: Board, frontierPenalty: Double): Double {
        val values = DoubleArray(board.snakeCount)
        chamber(
            board,
            territoryWeight = 1.0,
            mobilityWeight = 0.0,
            trapPenalty = 0.0,
            separationBonus = 0.0,
            frontierPenalty = frontierPenalty,
            sealPenalty = 0.0,
        ).valuesInto(playoutOn(board), values)
        return values[0]
    }

    private fun playoutOn(board: Board, budget: Budget = Budget(1_000)): Playout =
        turnOn(board, board.toAct, budget).scratch.playout()

    /**
     * A [ChamberEval] sized for [board].
     *
     * The grid comes off the board rather than being defaulted, because the sweep inside steps
     * through *its* grid's padded address space — a 5x5 has a stride of seven and a 1x6 has eight,
     * so an evaluation built for one would read a different board than the one it was handed.
     */
    private fun chamber(
        board: Board,
        separationBonus: Double = 0.9,
        trapPenalty: Double = 0.35,
        territoryWeight: Double = 0.7,
        mobilityWeight: Double = 0.2,
        parityWeight: Double = 1.0,
        frontierPenalty: Double = 0.0,
        sealPenalty: Double = 0.0,
    ): ChamberEval =
        ChamberEval(
            board.grid,
            board.snakeCount,
            territoryWeight,
            mobilityWeight,
            trapPenalty,
            separationBonus,
            parityWeight,
            frontierPenalty,
            sealPenalty,
        )
}
