package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.snake.SnakeId

/**
 * [SurvivalEval] asking the block decomposition three questions instead of taking one number off it.
 *
 * That leaf reads [FillableSpace]'s single integer — the squares one walk can spend — and compares it
 * between snakes. [ChamberTree] keeps the chambers that integer was summed out of, and this reads
 * them: how much of each chamber's parity really binds, how much of it sits on a boundary somebody
 * else reaches first, and how much of the region the chain never gets to at all. The rest is
 * [SurvivalEval]'s shape unchanged — same sweep, same phase change, same four weights, same scale —
 * so a batch between the two settings is a batch about what a chamber is worth.
 *
 * ### Why the terms are ratios, and why they are knobs
 *
 * [HorizonEval] is the standing warning. It replaced a square count with an oracle-verified count of
 * *moves*, was strictly truer, and lost to the leaf it corrected by 185 Elo — because it was generous
 * in exactly the shapes where a walk cannot really loop, and a leaf is read as a comparison rather
 * than as a quantity. So nothing here is an absolute correction. The parity blend, the frontier
 * discount and the seal penalty are each a **fraction of the shape being compared**, and each carries
 * a weight a sweep can settle rather than a constant somebody argued for: `PuctBot.PARITY_WEIGHT`,
 * `PuctBot.FRONTIER_PENALTY` and `PuctBot.SEAL_PENALTY`.
 *
 * Two of the three have since been swept and moved; the parity blend has been swept and **not**
 * moved. Each knob's own KDoc carries its number, and the table below is where they came from.
 *
 * ### The seal term is the one that is not available anywhere else
 *
 * [SurvivalEval] compares chains between snakes and never asks whether a snake's own region is being
 * shattered. Twenty spendable squares out of twenty-two and twenty out of forty are the same number
 * to it. They are not the same position: the second is a snake that has just cut itself off from half
 * of what the sweep says it owns, and it is the move that does that which this exists to see.
 *
 * ### What it costs, and therefore what it had to beat
 *
 * **1.09x [SurvivalEval] a turn on a 12x12 and 1.07x on a 20x20**, paired seed by seed with
 * `UctBot`'s rollout carried as a control — 3.91 ms against 3.57 at the shipped allowance, and 9.81
 * against 9.13. [EvaluationCost] carries the table and what the control is doing in it. A tenth of a
 * leaf is what these three readings had to buy back, so the bar was *beat [SurvivalEval] by more than
 * a tenth of a leaf of iterations*, roughly 20 Elo — not *beat [TerritoryEval]*, which the two are
 * already level with per unit of time.
 *
 * ### It clears that bar by about four times over, and one of the three terms is the whole of it
 *
 * `spsa puct:eval=chamber --knobs parityWeight,frontierPenalty,sealPenalty --budget 1000
 * --iterations 400 --boards 8` — 6,400 paired matches on a 12x12, no knob finishing on a declared
 * bound. It settled on `parityWeight=0.1, frontierPenalty=0.2, sealPenalty=0.55` and its own
 * confirming run put that **+54 Elo over 400 fresh boards** against the settings above.
 *
 * Then each weight separately, `ab` against `eval=survival` at the shipped allowance with bounds of
 * `0..10`. This is the table the defaults come from, and the last two rows are why only two of the
 * three moved:
 *
 * | parity | frontier | seal | verdict | boards |
 * |---|---|---|---|---|
 * | 0.1 | 0.2 | 0.55 | BETTER, **+69 ±30** | 240 |
 * | **1.0** | **0.2** | **0.55** | BETTER, **+85 ±32** | 220 |
 * | 1.0 | 0.2 | 0.55, on a seed base disjoint from the sweep | BETTER, **+110 ±40** | 160 |
 * | 1.0 | 0.0 | 0.55 — the seal alone | BETTER, **+37 ±20** | 400 |
 * | 1.0 | 0.2 | 0.0 — the frontier discount alone | UNDECIDED, +9 ±9 | 2,000, capped |
 * | 0.1 | 0.0 | 0.0 — the parity relaxation alone | NO BETTER, −37 ±23 | 180 |
 *
 * **The seal term is the finding.** It is the reading no other leaf here has, it is worth `+37` on
 * its own, and it is worth roughly twice that with the frontier discount beside it — while the
 * frontier discount *alone* is `+9 ±9` over two thousand boards, which is a number inside its own
 * error bar. Read the discount as something that sharpens the seal rather than as a term that pays:
 * it marks down the ground a rival can still take, and being cut off from ground worth having is what
 * the seal is measuring.
 *
 * **And the sweep's own parity answer was not adopted, which is the part worth reading twice.** SPSA
 * walked `parityWeight` from the cap to `0.1` — nearly abandoning the chessboard bound in favour of
 * the raw square count, exactly the correction the agenda's first ground-truth finding argues for —
 * and the ablation says that move buys nothing: the same point with the cap left on measures `+85`
 * against the tuned point's `+69`, and the relaxation on its own is `−37` with 141 of 180 boards
 * splitting exactly, so that test mostly never saw it. A three-weight optimum is not three
 * one-weight optima, and a coordinate that drifts while the others are carrying the objective looks
 * like a finding in the journal and is not one. This is the same lesson [HorizonEval] taught from the
 * other end: relaxing the self-avoidance premise is *right about the physics* and does not improve the
 * ranking.
 *
 * Every one of those `ab`s stopped at the sequential test's **upper** bound rather than running out
 * of boards, so each sign is solid and each magnitude is the generous end of its own interval. What
 * was proven in every BETTER row is "at least 10 Elo".
 *
 * ### The field, which says the same thing more weakly and against more opponents
 *
 * 200 rounds over seven entrants, 4,200 matches, **3,441 of them distinct games** and 120 of the 200
 * in the pairing that decides it — contested rather than saturated, which is the check `ab`'s own
 * blind spot makes necessary. No forfeits anywhere in this phase.
 *
 * | | rating | 95% | score |
 * |---|---|---|---|
 * | this, at the adopted weights | **350** | +321..+379 | 81% |
 * | [SurvivalEval] | 303 | +280..+331 | 76% |
 * | [TerritoryEval], the default | 259 | +233..+286 | 72% |
 * | `uct` | 192 | +166..+221 | 65% |
 *
 * Head to head inside that field it beat [SurvivalEval] **127-73**. The intervals overlap by ten
 * points, so the field on its own would be a weaker statement than the `ab`s above — it is here to
 * say the gain is not a pairing artefact, not to size it.
 *
 * `report` says the losses did not change character, only frequency: all 73 are `TRAPPED`, at a
 * median of 96 moves against 102 when it wins, on a board 68% full. It is the same endgame, entered
 * from a better position more often.
 *
 * **A knob tuned at one allowance is tuned at that allowance.** All of the above is budget 1000 on a
 * 12x12 under mirrored openings. Nothing here was measured on a 20x20, where the sweep this sits on
 * costs 2.5x what it costs here, and `PuctBot.SOLVER` is the standing example of a knob on this bot
 * that flips sign with the board.
 */
internal class ChamberEval(
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
    parityWeight: Double,
    frontierPenalty: Double,
    /** Taken off in proportion to the share of its own region a snake's best chain cannot reach. */
    private val sealPenalty: Double,
) : LeafEval {
    private val space = TempoOwnership(grid, slotCount)
    private val chambers = ChamberTree(grid, parityWeight, frontierPenalty)

    /** Effective squares each live snake could still spend, refilled every call. */
    private val usable = DoubleArray(slotCount)

    /** And the share of its own ground each one has cut itself off from. */
    private val sealed = DoubleArray(slotCount)

    /** A sweep, and then every region taken apart — and one unit of allowance, like everything else. */
    override val cost: Int get() = EvaluationCost.CHAMBER

    override fun valuesInto(playout: Playout, into: DoubleArray) {
        val board = playout.board
        space.measure(board)

        var live = 0
        var totalUsable = 0.0
        var bestUsable = -1.0
        var secondUsable = -1.0

        for (slot in 0 until slotCount) {
            val snake = board.snake(SnakeId(slot))
            if (!snake.alive) {
                usable[slot] = 0.0
                sealed[slot] = 0.0
                continue
            }
            live++

            // The regions are disjoint, so the whole loop walks the free area once between them --
            // which is what keeps this a constant factor over the sweep rather than a factor of the
            // number of snakes.
            chambers.measure(space, slot, snake.head)
            val held = chambers.chainWorth
            usable[slot] = held
            sealed[slot] = chambers.sealed

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
                // out first. Graded rather than a step, for the reason TerritoryEval records: a step
                // leaves the search no gradient in exactly the phase this is a space-filling puzzle.
                val rival = if (usable[slot] == bestUsable) secondUsable else bestUsable
                val pool = usable[slot] + rival
                val margin = if (pool == 0.0) 0.0 else (usable[slot] - rival) / pool

                separationBonus * margin
            } else {
                // Still a fight. An even split reads even whatever the field size, so this is a
                // departure from fair rather than an absolute quantity.
                val share = if (totalUsable == 0.0) fair else usable[slot] / totalUsable

                territoryWeight * ((share - fair) * live).coerceIn(-1.0, 1.0)
            }

            // Every term is a signed fraction of the half-range, so a weight reads as "how much of
            // the distance from even to decided this term may claim" and the branches are on one
            // scale -- TerritoryEval's convention, kept so that a weight means the same thing at
            // every setting of `eval`. The seal is on that scale too: it is a fraction of the
            // region, and what it multiplies is how much of the reading a shattered region may cost.
            val mobility = mobilityWeight * (liberties / LIBERTIES * 2.0 - 1.0)
            var value = LeafEval.EVEN + LeafEval.EVEN * (ground + mobility - sealPenalty * sealed[slot])

            if (liberties == 0) {
                // A snake hemmed in now does not get to spend what it owns. A penalty rather than an
                // outright loss, because it is not certain -- an adjacent tail retracting can hand a
                // square back before this snake's turn comes.
                value -= trapPenalty
            }

            into[slot] = value.coerceIn(LeafEval.LOSS, LeafEval.WIN)
        }
    }

    override fun toString(): String = "ChamberEval"

    private companion object {
        /** Ways out of a square, so `liberties / LIBERTIES` is a fraction of the most there can be. */
        val LIBERTIES = Direction.entries.size.toDouble()
    }
}
