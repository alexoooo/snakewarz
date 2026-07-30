package ao.snakewarz.bots.search

import ao.snakewarz.botapi.scratch.Scratch
import ao.snakewarz.bots.search.learned.LearnedEval
import ao.snakewarz.bots.search.puct.ChamberEval
import ao.snakewarz.bots.search.puct.HorizonEval
import ao.snakewarz.bots.search.puct.MobilityEval
import ao.snakewarz.bots.search.puct.SurvivalEval
import ao.snakewarz.bots.search.puct.TerritoryEval
import ao.snakewarz.bots.search.uct.UctBot

/**
 * What one evaluation of each kind costs against a turn's allowance — the exchange rate, in one
 * place, because it is a set of numbers that only mean anything relative to each other.
 *
 * A budget is counted in evaluations ([ao.snakewarz.core.Budget]), and every search bot pays for one
 * by asking [Scratch.playout] for a playout. That makes an allowance comparable across bots that do
 * completely different things inside an iteration — which is the point, since the win-rate matrix
 * exists to compare them — but it does not make an iteration cost the same wall clock everywhere. A
 * random rollout runs a hundred-odd moves; [MobilityEval] is a handful of array reads.
 *
 * ### They are all 1, and that is a starting point rather than a finding
 *
 * What the ratio actually is has been measured, with `:lab`'s `time` at 1,000 evaluations a turn:
 *
 * | board | [MobilityEval] | [UctBot]'s rollout | [TerritoryEval] | [SurvivalEval] | [HorizonEval] |
 * |---|---|---|---|---|---|
 * | 12x12 | 0.50 ms | 1.68 ms | 1.50 ms | 3.58 ms | 3.81 ms |
 * | 20x20 | 0.46 ms | 1.78 ms | 2.93 ms | 8.89 ms | 9.85 ms |
 *
 * Every figure is the mean of six seeds, each the best of five passes, and each taken beside the
 * other builds it is quoted against in the same session — a wall clock on a desktop drifts by ten
 * percent over an afternoon, which is larger than several of the differences this table is read for.
 *
 * [HorizonEval] walks the same regions as [SurvivalEval] and carries one more integer per cut vertex
 * through the same pop, so it was expected to land beside it, and it does — inside the spread of a
 * `time` run on both boards. That matters more than the absolute number: it makes the batch between
 * those two settings a comparison at an equal clock as well as at an equal allowance, which is the
 * only pairing in this table where that is true for free.
 *
 * **And it moves with the board**, which is the whole difficulty: a sweep is priced by the squares
 * and a rollout is not, so there is no single number that is right on both boards. [TerritoryEval]
 * is **0.9** rollouts on the small board and 1.7 on the large one; [SurvivalEval], which takes every
 * region apart after the sweep and is therefore priced by the squares twice over, is 2.1 and 5.0. A
 * constant here would be right on one board of the two.
 *
 * The first of those is worth reading twice, because it inverts the trade the ladder was settled on:
 * a board-wide ownership sweep is **cheaper than a random rollout** on the shipped 12x12. Any claim
 * of the form "these two are level per millisecond" that was worked out from an older row of this
 * table is a claim about a different exchange rate — [TerritoryEval] and [SurvivalEval] each carry
 * one, and each says which figures it was derived from.
 *
 * The repo's worked instance of that claim has been re-derived off these figures rather than left
 * standing, and the outcome is the useful part: what buys [SurvivalEval] [TerritoryEval]'s
 * millisecond on a 12x12 fell from 470 evaluations to **415**, and at 415 it is still level — `+7
 * Elo ±10` over two thousand boards, against `+81 ±33` at an equal count. **The exchange rate moved
 * and the verdict did not.** Which is the strongest argument yet that the pair really is level per
 * unit of time rather than level at one lucky allowance, and it is why the sweep getting 1.6-2.1x
 * cheaper did not change which evaluation `PuctBot.EVAL` defaults to.
 *
 * So the ratios are recorded and **not** written into the constants below: the calibration this
 * wants is a function of the board rather than a number, and half a calibration would be worse than
 * none — it would look settled. Until then **an equal allowance is an equal number of iterations
 * and not an equal number of milliseconds**, which is a defensible thing for a matrix to mean as
 * long as it is said out loud. Read one with the `time` figures beside it; [TerritoryEval] is what
 * that looks like done properly, and `MatchSetup.DEFAULT_BUDGET_PER_TURN` carries the whole table.
 *
 * ### [ChamberEval] was priced in its own session, and the control is what joins the two
 *
 * The table above is one afternoon's measurements and a desktop drifts, so a figure taken later
 * cannot simply be appended to it. This is the whole of a second session, [UctBot]'s rollout carried
 * through as a control it cannot touch — and the control landing on the row above is what says the
 * two sessions are comparable at all. Six seeds a figure, best of five passes each, entrants
 * interleaved within a seed so a drift would move all four together:
 *
 * | board | [UctBot]'s rollout, control | [TerritoryEval] | [SurvivalEval] | [ChamberEval] |
 * |---|---|---|---|---|
 * | 12x12 | 1.69 ms | 1.62 ms | 3.57 ms | 3.91 ms |
 * | 20x20 | 1.82 ms | 2.49 ms | 9.13 ms | 9.81 ms |
 *
 * **[ChamberEval] is 1.09x [SurvivalEval] on the small board and 1.07x on the large one**, and the
 * ratio is what to quote rather than either figure: those two play games of roughly the same length,
 * where `time` plays a *different* game per entrant, so a seed pairs the board and not the position.
 * That is also why the ratios against [TerritoryEval] here — 2.4x and 3.9x, against 2.2x and 3.7x for
 * [SurvivalEval] in the same session — are worth less than they look: the cheap leaf's matches ran
 * anywhere from 28 to 225 turns and the per-turn mean is over whichever turns happened to be played.
 *
 * [UctBot.ROLLOUT_DEPTH] is the first thing to re-measure when these move, since it compares two
 * kinds of rollout that would stop costing the same. Do not tune one of these down to make a bot
 * look better in a matrix; that is rule SW-07.
 */
internal object EvaluationCost {
    /** A game played out at random from the leaf, whether to the end or cut short and judged. */
    const val ROLLOUT: Int = 1

    /** [MobilityEval] — a liberty count per snake, and the cheapest thing here by a long way. */
    const val MOBILITY: Int = 1

    /** [TerritoryEval] — one board-wide ownership sweep; measured at 0.9 [ROLLOUT]s on a 12x12 and 1.7 on a 20x20. */
    const val TERRITORY: Int = 1

    /** [SurvivalEval] — a sweep, and then every region taken apart. The dearest of them. */
    const val SURVIVAL: Int = 1

    /**
     * [HorizonEval] — [SURVIVAL]'s work with a second answer carried through the pop.
     *
     * Measured at [SURVIVAL]'s price on both boards, which is the one thing about this evaluation
     * that came out where it was predicted to.
     */
    const val HORIZON: Int = 1

    /**
     * [ChamberEval] — [SURVIVAL]'s decomposition kept rather than summed, and priced as it pops.
     *
     * **1.09x [SURVIVAL] on a 12x12 and 1.07x on a 20x20**, paired seed by seed against it — the same
     * depth-first pass over the same regions, with one more number settled per chamber and one more
     * board read on the edges the pass already rejects. So a batch between those two settings is very
     * nearly a comparison at an equal clock as well as at an equal allowance, and the tenth of a leaf
     * this costs is the whole of what the chambers had to buy back.
     *
     * **They bought it back several times over once the weights were swept** — `+85 Elo ±32` against
     * [SurvivalEval] at the shipped allowance on a 12x12, and `+110 ±40` on a disjoint seed base, for
     * that same 9%. [ChamberEval] carries the table and which of its three readings the gain is in.
     */
    const val CHAMBER: Int = 1

    /**
     * [LearnedEval] — [CHAMBER]'s sweep and decomposition, read as a feature vector into a fit.
     *
     * The same two passes over the board, then about 500 multiply-adds and thirty divisions per live
     * snake. The 12x12 figure was **1.11-1.16x [ChamberEval]** at twenty-five readings; re-measured in
     * Chrome at twenty-nine, on three boards at once:
     *
     * | | 8x8 | 12x12 | 20x20 |
     * |---|---|---|---|
     * | this, against [ChamberEval] | 1.28x | 1.19x | 1.09x |
     * | this, against a bare [TerritoryEval] search | 3.00x | 4.21x | 5.24x |
     *
     * **The ratio against [ChamberEval] falls as the board grows, and that is the shape to expect**:
     * the fit is a fixed cost per live snake while the sweep under it grows with the board, so the
     * dearer the shared work the less the arithmetic on top of it shows. Quoting the 12x12 figure
     * alone reads as a constant and it is not one.
     *
     * What the four readings added in P4 cost on their own is **not** isolated here: two sessions and
     * two instruments separate 1.16x from 1.19x, and P1 measured browser ratios moving 3% between runs
     * on one machine. The three-board row is a measurement; the difference from the old band is not.
     * `LearnedEval` carries the table and what the readings buy back for it.
     */
    const val LEARNED: Int = 1
}
