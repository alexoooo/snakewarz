package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.snake.SnakeId

/**
 * A share of the board, read the way somebody who plays this game would read it.
 *
 * Not a weighted sum of features but a rule with a branch in it, because the game has a **phase
 * change** and a linear reading cannot express one. While the snakes can still reach each other, the
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
 * [SurvivalEval] is what happens when that constraint is deliberately spent rather than kept: it
 * asks how much of a region a snake can actually *use*, which no single sweep can answer. This one
 * stays the cheap reading of the same phase change, and the two are the tradeoff `eval` offers.
 *
 * ### What it is worth, measured
 *
 * All figures from `:lab`, 12x12, forty rounds a pairing, both seatings of every seed — one sigma is
 * about ±3.2 wins, so anything inside 17-23 is the same number. At an **equal allowance**, which is
 * now an equal number of iterations:
 *
 * | | territory | rollout | mobility | uct | score |
 * |---|---|---|---|---|---|
 * | territory | — | 33 | 20 | 25 | 65% |
 * | rollout | 7 | — | 31 | 22 | 50% |
 * | mobility | 20 | 9 | — | 8 | 31% |
 * | uct | 15 | 18 | 32 | — | 54% |
 *
 * `rollout` was an `eval` setting that played the position out at random, and it is **gone**. It
 * existed as a control, and `uct` is a better one: a tree with exactly that rollout at its leaf, and
 * a bot somebody might actually pick. The column stands as the record of what was measured, and
 * re-running this table means reading the `uct` column in its place.
 *
 * **Per iteration, the hand-written appraisal is decisively the better leaf** — 33 of 40 against the
 * random rollout it replaces, with the tree, the prior and the allowance all held still. That is a
 * reversal of what this table said when an allowance was counted in simulated moves and [cost] was
 * `grid.playableCount`: the appraisal read 44% there and lost to the rollout, because it was being
 * charged a hundred and forty-four units for a leaf the rollout got for sixty.
 *
 * It is not free, and the second table is the one that keeps the first honest. The allowances in it
 * were worked out from a sweep costing about 1.4 rollouts on this board — 2,709 µs a turn at 1,000
 * evaluations against the random rollout's 1,984 and `uct`'s 2,096. **That exchange rate no longer
 * holds**: [ao.snakewarz.bots.search.EvaluationCost] carries the live one, where the sweep is
 * *cheaper* than a rollout here, so the equal-clock allowance below is smaller than the one this bot
 * should be handed today and the row is a record of a measurement rather than a live comparison.
 * Handed the 730 that bought the same millisecond then:
 *
 * | | territory@730 | rollout | uct | score |
 * |---|---|---|---|---|
 * | territory@730 | — | 21 | 24 | 56% |
 * | rollout | 19 | — | 22 | 51% |
 * | uct | 16 | 18 | — | 43% |
 *
 * **A hand-written appraisal is worth about what a random rollout is worth, per unit of time**, and
 * is comfortably ahead of one per unit of search. Both readings are inside two sigma of each other,
 * which is why `puct` is still registered as experimental rather than as a ladder rung — and why
 * [ao.snakewarz.bots.search.EvaluationCost] leaving every evaluation at `1` is a thing to know about
 * before quoting the first table: it is a count of iterations and makes no claim about the clock.
 *
 * ### It is no longer the best leaf here, and is still the default
 *
 * [SurvivalEval] beats this **31 of 40** at an equal allowance, which is a wider margin than
 * anything in either table above. At an equal *clock* the two are level. Its KDoc carries both
 * matrices and the non-transitivity warning that comes with them. Level and cheaper is why `eval`
 * still defaults here.
 *
 * ### The exchange rate moved and the verdict did not — re-derived after the bitboard sweep
 *
 * `CellBits` made this evaluation **1.59x** cheaper a turn on this board and [SurvivalEval] only
 * **1.18x**, because both sweep with the same primitive and only that one then walks every region
 * with a depth-first pass no bitmap helps. So the allowance that buys [SurvivalEval] this one's
 * millisecond fell from the **470** its KDoc records to **415**, and the honest equal-clock batch is
 * a different batch than it was. Both numbers below are `ab` on 12x12, this bot as the baseline:
 *
 * | question | entrants | verdict |
 * |---|---|---|
 * | per iteration | `survival@1,000` against `territory@1,000` | **+81 Elo ±33**, 240 boards |
 * | per millisecond | `survival@415` against `territory@1,000` | **+7 Elo ±10**, 2,000 boards |
 *
 * **The worked example survives being re-derived**, and that is the finding: the exchange rate moved
 * against [SurvivalEval] and the outcome did not move with it. What changed is the width — the old
 * *48-52 over a hundred rounds* was ±35 Elo of nothing, and ±10 over two thousand boards is a real
 * null. Read the second row as level rather than as a win for either: it stopped at the `--max-pairs`
 * ceiling rather than because the evidence settled, so ±10 is where the interval sat and not where it
 * converged.
 *
 * The first row is the one to be careful with. It stopped at the sequential test's upper bound, so
 * **the sign is solid and 81 is the generous end** — "at least 10 Elo" is what was actually proven.
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
internal class TerritoryEval(
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
     * One board-wide ownership sweep, and one unit of allowance like everything else.
     *
     * A *constant* rather than the squares a sweep actually reached, because two evaluations
     * compared at one nominal allowance have to be paying for the same thing. That it is the same
     * constant [SurvivalEval] pays for several times the work is [EvaluationCost]'s open question
     * rather than a claim: they are equal iterations rather than equal milliseconds until somebody
     * measures the ratio.
     */
    override val cost: Int get() = EvaluationCost.TERRITORY

    override fun valuesInto(playout: Playout, into: DoubleArray) {
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
    }

    override fun toString(): String = "TerritoryEval"

    private companion object {
        /** Ways out of a square, so `liberties / LIBERTIES` is a fraction of the most there can be. */
        val LIBERTIES = Direction.entries.size.toDouble()
    }
}
