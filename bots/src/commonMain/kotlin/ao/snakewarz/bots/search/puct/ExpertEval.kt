package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.snake.SnakeId

/**
 * The subject: a position appraised the way somebody who plays this game would appraise it.
 *
 * Not a weighted sum of features but a small expert system, because the game has a **phase change**
 * in it and a linear reading cannot express one. While the snakes can still reach each other, the
 * position is a fight and a share of the board is worth roughly what its size says. The moment they
 * cannot, it stops being a fight at all: each snake will fill its own room and die when it runs out,
 * so the larger room outlasts the smaller one and the result is arithmetic rather than a matter of
 * degree. A reading that stayed proportional across that boundary would keep telling the search a
 * decided game was close.
 *
 * The whole appraisal costs **one** [SpaceOwnership] sweep. That is the design constraint rather
 * than an optimisation: a sweep is already about a hundred rollout moves' worth of work — see
 * `UctBot.ROLLOUT_DEPTH`'s measured table — so a second one, or a per-snake flood fill, would price
 * the evaluation out of any allowance a browser could grant. Everything else here is array reads.
 * The separation test comes off the same sweep, which is why [SpaceOwnership.isolated] exists.
 *
 * ### What it is worth, measured
 *
 * All figures from `:lab`, 12x12, forty rounds a pairing, both seatings of every seed — one sigma is
 * about ±3.2 wins, so anything inside 17-23 is the same number. At an **equal allowance**:
 *
 * | | expert | rollout | mobility | uct | score |
 * |---|---|---|---|---|---|
 * | expert | — | 18 | 31 | 15 | 44% |
 * | rollout | 22 | — | 28 | 19 | 53% |
 * | mobility | 9 | 12 | — | 13 | 28% |
 * | uct | 25 | 21 | 27 | — | 61% |
 *
 * So at equal allowance this loses to a random rollout. It is not being given an equal *turn*,
 * though: [cost] overcharges it, and measured at the shipped allowance a turn here is 1,104 µs
 * against `eval=rollout`'s 1,892 and `uct`'s 2,006. Handed the 68,000 that buys the same
 * millisecond, the three are level:
 *
 * | | expert@68k | rollout | uct | score |
 * |---|---|---|---|---|
 * | expert@68k | — | 18 | 21 | 49% |
 * | rollout | 22 | — | 19 | 51% |
 * | uct | 19 | 21 | — | 50% |
 *
 * **A hand-written appraisal is worth about what a random rollout is worth, per unit of time.** That
 * is the honest reading, and it is why `puct` is registered as experimental rather than as a ladder
 * rung. [cost] is deliberately left overcharging rather than tuned down to close the gap — the
 * accounting should not be adjusted to favour the thing it is accounting for, and the allowance knob
 * is there for anybody who wants the other comparison.
 *
 * ### Two things the ablation found
 *
 * [territoryWeight] belongs at its maximum: at 1.0 against 0.7, everything else equal, 18 of 20.
 * That is the whole of the contested phase, and halving it halves the only signal there is.
 *
 * The separation branch has to be **graded, not a verdict**, and that was found the hard way. It
 * first shipped as a step — ahead reads 0.95, behind reads 0.05 — and scored **0 of 40** against
 * [MobilityEval], which does nothing but count liberties. A step gives the search no gradient at all
 * once the snakes have parted, which is precisely the phase where this game becomes a space-filling
 * puzzle and every move either preserves your room or seals a pocket off it. Grading the same branch
 * turned that 0-40 into 31-9 and took the bot from 18% to 49% of a four-way field. [separationBonus]
 * at 0.9 against 1.0 is 20-20, so the lower one stands: a judged win should read below a proven one.
 *
 * With everything but [territoryWeight] at zero this is a plain proportional-territory evaluation,
 * which is why there is no separate slug for one — the ablation is a configuration, not a bot.
 */
internal class ExpertEval(
    grid: Grid,
    private val slotCount: Int,
    /** How far a share of the board moves the reading away from even, while it is still contested. */
    private val territoryWeight: Double,
    /** How far having more ways out than the average moves it, on the same board. */
    private val mobilityWeight: Double,
    /** Taken off a snake with nothing legal left, which is a move from dead however much it owns. */
    private val trapPenalty: Double,
    /** How far toward decided a snake that nobody can reach any more is pushed. */
    private val separationBonus: Double,
) : LeafEval {
    private val space = SpaceOwnership(grid, slotCount)

    /**
     * One board-wide ownership sweep, priced at the playable squares.
     *
     * Measured rather than guessed: `UctBot.ROLLOUT_DEPTH` records that a hundred rollout moves cost
     * about what one sweep costs on a 12x12, where `playableCount` is 144 — so this overcharges by
     * roughly forty per cent, which is the direction to be wrong in. It is a *constant* rather than
     * the squares a sweep actually reached, because two evaluations compared at one nominal
     * allowance have to be paying for the same thing.
     */
    override val cost: Int = grid.playableCount

    override fun valuesInto(playout: Playout, into: DoubleArray): Boolean {
        val board = playout.board
        val owned = space.measure(board)

        var live = 0
        var totalOwned = 0
        var bestOwned = -1
        var secondOwned = -1

        for (slot in 0 until slotCount) {
            if (!board.snake(SnakeId(slot)).alive) {
                continue
            }
            live++

            val held = owned[slot]
            totalOwned += held
            if (held > bestOwned) {
                secondOwned = bestOwned
                bestOwned = held
            } else if (held > secondOwned) {
                secondOwned = held
            }
        }

        if (live <= 1) {
            // PuctBot never asks -- the board's own outcome is non-null the moment one snake is left,
            // and a real result is read directly. A unit test can call this, and an answer beats
            // dividing by the number of survivors.
            for (slot in 0 until slotCount) {
                into[slot] = if (board.snake(SnakeId(slot)).alive) LeafEval.WIN else LeafEval.LOSS
            }
            return true
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
                // out first. The margin is against the *best* of the others, because outlasting the
                // field means outlasting whoever lasts longest.
                //
                // Graded rather than a step, and that was measured the hard way: a step made every
                // move in a separated position read the same number, so the search had no gradient
                // left in exactly the phase where this game is a space-filling puzzle, and it
                // wandered. Graded, the reading falls whenever a move seals off a pocket, which is
                // the one thing a separated snake must not do.
                val rival = if (owned[slot] == bestOwned) secondOwned else bestOwned
                val pool = owned[slot] + rival
                val margin = if (pool == 0) 0.0 else (owned[slot] - rival).toDouble() / pool

                separationBonus * margin
            } else {
                // Still a fight. An even split reads even whatever the field size, so this is a
                // departure from fair rather than an absolute quantity.
                val share = if (totalOwned == 0) fair else owned[slot].toDouble() / totalOwned

                territoryWeight * ((share - fair) * live).coerceIn(-1.0, 1.0)
            }

            // Every term is a signed fraction of the half-range, so a weight reads as "how much of
            // the distance from even to decided this term may claim" and the two branches are on one
            // scale. Whatever else is true, a separated advantage is worth more than the same
            // advantage while somebody can still take it away -- which is separationBonus above
            // territoryWeight, and is the whole claim this evaluation makes.
            val mobility = mobilityWeight * (liberties / LIBERTIES * 2.0 - 1.0)
            var value = LeafEval.EVEN + LeafEval.EVEN * (ground + mobility)

            if (liberties == 0) {
                // Applied in both branches: a snake hemmed in now does not get to spend the room it
                // owns. A penalty rather than an outright loss, because it is not certain -- an
                // adjacent tail retracting can hand a square back before this snake's turn comes.
                value -= trapPenalty
            }

            into[slot] = value.coerceIn(LeafEval.LOSS, LeafEval.WIN)
        }

        return true
    }

    override fun toString(): String = "ExpertEval"

    private companion object {
        /** Ways out of a square, so `liberties / LIBERTIES` is a fraction of the most there can be. */
        val LIBERTIES = Direction.entries.size.toDouble()
    }
}
