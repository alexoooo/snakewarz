package ao.snakewarz.bots.search

import ao.snakewarz.botapi.scratch.Scratch
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
 * | board | [MobilityEval] | [UctBot]'s rollout | [TerritoryEval] | [SurvivalEval] |
 * |---|---|---|---|---|
 * | 12x12 | 0.37 ms | 1.85 ms | 2.40 ms | 3.92 ms |
 * | 20x20 | — | 2.13 ms | 4.65 ms | 10.9 ms |
 *
 * **And it moves with the board**, which is the whole difficulty: a sweep is priced by the squares
 * and a rollout is not, so there is no single number that is right on both boards. [TerritoryEval]
 * is 1.3 rollouts on the small board and 2.2 on the large one; [SurvivalEval], which takes every
 * region apart after the sweep and is therefore priced by the squares twice over, is 2.1 and 5.1. A
 * constant here would be right on one board of the two.
 *
 * So the ratios are recorded and **not** written into the constants below: the calibration this
 * wants is a function of the board rather than a number, and half a calibration would be worse than
 * none — it would look settled. Until then **an equal allowance is an equal number of iterations
 * and not an equal number of milliseconds**, which is a defensible thing for a matrix to mean as
 * long as it is said out loud. Read one with the `time` figures beside it; [TerritoryEval] is what
 * that looks like done properly, and `MatchSetup.DEFAULT_BUDGET_PER_TURN` carries the whole table.
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

    /** [TerritoryEval] — one board-wide ownership sweep; measured at about three [ROLLOUT]s on a 20x20. */
    const val TERRITORY: Int = 1

    /** [SurvivalEval] — a sweep, and then every region taken apart. The dearest of them. */
    const val SURVIVAL: Int = 1
}
