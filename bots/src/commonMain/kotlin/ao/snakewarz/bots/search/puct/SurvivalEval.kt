package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.snake.SnakeId

/**
 * How long each snake can keep moving — which is the question the game actually asks.
 *
 * [TerritoryEval] reads a position as a share of the board, and a share of the board is a proxy. The
 * thing it stands in for is *how many moves does this leave me*, and the two come apart exactly where
 * the game is decided. Two snakes with thirty squares each are not level if one of them holds a room
 * and the other holds a corridor with pockets off it; a snake that has just sealed a neck behind it
 * has not lost a square by that count and has lost half its game. This asks the question directly.
 *
 * Three corrections over [TerritoryEval], each of which was a stated simplification there:
 *
 * 1. **Whose turn it is.** [TempoOwnership] measures in half-steps, so an equidistant square falls to
 *    whoever moves first instead of being contested. Worth one square of frontier on every boundary
 *    on the board.
 * 2. **Which squares are about to clear.** Snakes grow at half speed, so tails retract; a sweep that
 *    treats them as wall under-counts every region late in a game.
 * 3. **How much of a region is reachable in one walk.** [FillableSpace] splits each region at its
 *    articulation points and takes the best chain of blocks, capped by the chessboard parity a walk
 *    on a grid cannot escape. This is the big one, and it is the one no sweep can do.
 *
 * ### It costs what [TerritoryEval] refuses to spend
 *
 * That class states one sweep as a design constraint. This spends the sweep and then walks every
 * region again, so it is several times the price of the thing it improves on — see [EvaluationCost],
 * where the constants are deliberately all `1` and the measured milliseconds are recorded beside
 * them. An allowance here is a count of iterations and makes no claim about the clock, so read a
 * matrix over these two with the `time` figures in hand. Whether the better leaf is worth the smaller
 * tree is the entire question `eval` exists to ask, and it is a measurement rather than an argument.
 *
 * ### What it is worth, measured
 *
 * `:lab`, 12x12, both seatings of every seed. **Per iteration this is the strongest leaf in the
 * box.** Forty rounds a pairing at 1,000 evaluations each, so one sigma is about ±3.2 wins:
 *
 * | | survival | territory | mobility | uct | score |
 * |---|---|---|---|---|---|
 * | survival | — | 31 | 40 | 24 | 79% |
 * | territory | 9 | — | 20 | 25 | 45% |
 * | mobility | 0 | 20 | — | 8 | 23% |
 * | uct | 16 | 15 | 32 | — | 53% |
 *
 * 31 of 40 against the appraisal it refines and **40 of 40** against liberties, with the tree, the
 * prior and the allowance all held still. The whole gap is the three corrections above.
 *
 * **Per unit of time it is level with what it refines**, which is the reading that decides the
 * default. A turn is 3,924 µs at 1,000 evaluations against [TerritoryEval]'s 2,400, so 470 of these
 * buy 2,515 µs and are the fair trade for its 1,000. Over a hundred rounds, one sigma being about ±5:
 *
 * | | survival@470 | territory@1,000 | territory@770 | score |
 * |---|---|---|---|---|
 * | survival@470 | — | 48 | 26 | 37% |
 * | territory@1,000 | 52 | — | 50 | 51% |
 * | territory@770 | 74 | 50 | — | 62% |
 *
 * **A better leaf buys back almost exactly what it costs.** Which is a real finding and not a
 * shrug: an evaluation this much dearer would normally lose badly on the clock, and this one does
 * not — so [EvaluationCost] getting calibrated is what would settle it, rather than another batch.
 *
 * The third row is the warning that comes with the second. `territory@770` is level with
 * `territory@1,000` at 50-50 and beats `survival@470` 74-26 where `territory@1,000` cannot — over a
 * hundred deterministic matches, so it is reproducible rather than noise. **These matchups are not
 * transitive**, both evaluations being static and both bots drawing nothing, so a single pairing is
 * a fact about that pairing. Read the score column against a common field; do not build an ordering
 * out of one row.
 *
 * ### Why `territory` is still the default
 *
 * Level on the clock and dearer, and the clock is what the browser actually enforces: `:ui` gives a
 * frame 8 ms and can only stop between turns, and this is 10.9 ms a turn on a 20x20 at the shipped
 * allowance against [TerritoryEval]'s 4.6. Being level is not a reason to make the expensive one the
 * thing everybody gets — and `PuctBot.EVAL` has a second reason, which is that the default is what
 * an `eval=expert` link from before the rename resolves to. Pick this one when the allowance is a
 * count of iterations, which is what a `:lab` matrix makes it.
 *
 * ### Squares, not moves, and the factor that cancels
 *
 * A snake in a closed room of `n` squares survives about `2n` moves, because the tail frees a square
 * every second turn. The factor is the same for everybody and this reads shares and margins, so it
 * cancels exactly and is not applied. The class is named for the quantity it is *about*; what it
 * counts is squares.
 *
 * ### Shaped like [TerritoryEval] on purpose
 *
 * Same phase change, same four weights, same scale — so that a batch between them changes what is
 * being counted and nothing else, and so that a weight swept against one transfers to the other. The
 * separated branch is where the difference should show up most: once nobody can reach anybody,
 * "whose room outlasts whose" *is* a fillable-space question, and raw area is a poor way to ask it.
 */
internal class SurvivalEval(
    grid: Grid,
    private val slotCount: Int,
    /** How far a share of what can be filled moves the reading away from even, while contested. */
    private val territoryWeight: Double,
    /** How far having more ways out than the average moves it, on the same board. */
    private val mobilityWeight: Double,
    /** Taken off a snake with nothing legal left, which is a move from dead however much it owns. */
    private val trapPenalty: Double,
    /** How far toward decided a snake that nobody can reach any more is pushed. */
    private val separationBonus: Double,
) : LeafEval {
    private val space = TempoOwnership(grid, slotCount)
    private val fillable = FillableSpace(grid)

    /** Squares each live snake could still spend, refilled every call. */
    private val usable = IntArray(slotCount)

    /** A sweep, and then every region taken apart — and one unit of allowance, like everything else. */
    override val cost: Int get() = EvaluationCost.SURVIVAL

    override fun valuesInto(playout: Playout, into: DoubleArray) {
        val board = playout.board
        space.measure(board)

        var live = 0
        var totalUsable = 0
        var bestUsable = -1
        var secondUsable = -1

        for (slot in 0 until slotCount) {
            val snake = board.snake(SnakeId(slot))
            if (!snake.alive) {
                usable[slot] = 0
                continue
            }
            live++

            // The regions are disjoint, so the whole loop walks the free area once between them --
            // which is what keeps this a constant factor over the sweep rather than a factor of the
            // number of snakes.
            val held = fillable.measure(space, slot, snake.head)
            usable[slot] = held

            totalUsable += held
            if (held > bestUsable) {
                secondUsable = bestUsable
                bestUsable = held
            } else if (held > secondUsable) {
                secondUsable = held
            }
        }

        if (live <= 1) {
            // PuctBot never asks -- the board's own outcome is non-null the moment one snake is left,
            // and a real result is read directly. A unit test can call this, and an answer beats
            // dividing by the number of survivors.
            for (slot in 0 until slotCount) {
                into[slot] = if (board.snake(SnakeId(slot)).alive) LeafEval.WIN else LeafEval.LOSS
            }
            return
        }

        val fair = 1.0 / live

        for (slot in 0 until slotCount) {
            val id = SnakeId(slot)
            if (!board.snake(id).alive) {
                into[slot] = LeafEval.LOSS
                continue
            }

            val liberties = board.legalMoves(id).size

            val ground = if (space.isolated(slot)) {
                // Nobody can reach this snake any more, so the only question left is whose room runs
                // out first -- and here that is asked of the moves each room is worth rather than of
                // its area, which is the one place the two readings routinely disagree by a factor.
                // Graded rather than a step, for the reason TerritoryEval records: a step leaves the
                // search no gradient in exactly the phase this is a space-filling puzzle.
                val rival = if (usable[slot] == bestUsable) secondUsable else bestUsable
                val pool = usable[slot] + rival
                val margin = if (pool == 0) 0.0 else (usable[slot] - rival).toDouble() / pool

                separationBonus * margin
            } else {
                // Still a fight. An even split reads even whatever the field size, so this is a
                // departure from fair rather than an absolute quantity.
                val share = if (totalUsable == 0) fair else usable[slot].toDouble() / totalUsable

                territoryWeight * ((share - fair) * live).coerceIn(-1.0, 1.0)
            }

            // Every term is a signed fraction of the half-range, so a weight reads as "how much of
            // the distance from even to decided this term may claim" and the two branches are on one
            // scale -- TerritoryEval's convention, kept so that a weight means the same thing at
            // either setting of `eval`.
            val mobility = mobilityWeight * (liberties / LIBERTIES * 2.0 - 1.0)
            var value = LeafEval.EVEN + LeafEval.EVEN * (ground + mobility)

            if (liberties == 0) {
                // A snake hemmed in now does not get to spend what it owns. A penalty rather than an
                // outright loss, because it is not certain -- an adjacent tail retracting can hand a
                // square back before this snake's turn comes.
                value -= trapPenalty
            }

            into[slot] = value.coerceIn(LeafEval.LOSS, LeafEval.WIN)
        }
    }

    override fun toString(): String = "SurvivalEval"

    private companion object {
        /** Ways out of a square, so `liberties / LIBERTIES` is a fraction of the most there can be. */
        val LIBERTIES = Direction.entries.size.toDouble()
    }
}
