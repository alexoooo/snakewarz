package ao.snakewarz.bots.search

import ao.snakewarz.botapi.scratch.Scratch
import ao.snakewarz.bots.search.puct.ExpertEval
import ao.snakewarz.bots.search.puct.MobilityEval
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
 * | board | `eval=rollout` | `eval=expert` | ratio |
 * |---|---|---|---|
 * | 12x12 | 1.98 ms | 2.71 ms | 1.4× |
 * | 20x20 | 1.80 ms | 5.44 ms | 3.0× |
 *
 * **And it moves with the board**, which is the whole difficulty: a sweep is priced by the squares
 * and a rollout is not, so there is no single number that is right on both boards. A constant here
 * would be right on one of them.
 *
 * So the ratios are recorded and **not** written into the constants below: the calibration this
 * wants is a function of the board rather than a number, and half a calibration would be worse than
 * none — it would look settled. Until then **an equal allowance is an equal number of iterations
 * and not an equal number of milliseconds**, which is a defensible thing for a matrix to mean as
 * long as it is said out loud. Read one with the `time` figures beside it; `ExpertEval` is what that
 * looks like done properly, and `MatchSetup.DEFAULT_BUDGET_PER_TURN` carries the whole table.
 *
 * `UctBot.ROLLOUT_DEPTH` is the first thing to re-measure when these move, since it compares two
 * kinds of rollout that would stop costing the same. Do not tune one of these down to make a bot
 * look better in a matrix; that is rule SW-07.
 */
internal object EvaluationCost {
    /** A game played out at random from the leaf, whether to the end or cut short and judged. */
    const val ROLLOUT: Int = 1

    /** [MobilityEval] — a liberty count per snake, and the cheapest thing here by a long way. */
    const val MOBILITY: Int = 1

    /** [ExpertEval] — one board-wide ownership sweep; measured at about three [ROLLOUT]s on a 20x20. */
    const val EXPERT: Int = 1
}
