package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.bots.search.uct.UctBot
import ao.snakewarz.bots.search.uct.UctTree
import ao.snakewarz.bots.search.uct.truncatedPlayout

/**
 * How a search values a position it has stopped searching — one number per slot, in `0.0..1.0`.
 *
 * This is the thing a neural network would be, in a PUCT that had one. [PuctBot] declares which
 * implementation it wants as a knob rather than picking one, because the interesting question about
 * a hand-written evaluation is not whether it works but whether it is *worth what it costs*, and
 * that is a comparison rather than an assertion. The three answer it at three prices: [MobilityEval]
 * reads sixteen squares, [TerritoryEval] sweeps the board once, [SurvivalEval] sweeps it and then
 * takes each region apart. Two entrants differing only in this line is what makes a matrix over them
 * mean something.
 *
 * There used to be a fourth that played the position out at random, so that one entrant judged a
 * leaf exactly as [UctBot] does. It is gone: `uct` was always in the matrix beside it, and `uct` is
 * that control — a tree with a random rollout at the leaf — without also being a setting nobody
 * would choose to play against.
 *
 * ### One value per slot, not one number
 *
 * Because the tree credits **per actor**: [PuctTree.record] reads `values[actorOf(node)]`, exactly
 * as [UctTree.record] judges an outcome against `actorOf(node)`. The reason is the one [UctTree]'s
 * KDoc gives for dropping legacy's negamax — "bad for A" is not "good for B" once there is a C, and
 * a self-relative scalar would have to be complemented on the way up the path to be any use.
 *
 * ### Only ever asked at a position that has not ended
 *
 * A position that really finished is worth more than a judgement of it, so [PuctBot] reads the
 * outcome directly and calls this only when there is none — the rule [truncatedPlayout] states as "a
 * rollout that finished on its own is worth more than a judgement of it".
 *
 * ### It is what the allowance is counted in
 *
 * One of these *is* one unit of budget. [PuctBot] pays [cost] by asking for the iteration's playout,
 * before descending, so an evaluation that has begun always produces a value and no iteration is
 * ever half-charged. What one costs relative to the others is [EvaluationCost], which is where
 * calibrating them happens.
 */
internal interface LeafEval {
    /** Fills [into] with each slot's value of the position. */
    fun valuesInto(playout: Playout, into: DoubleArray)

    /** What one call costs against the turn's allowance — see [EvaluationCost]. */
    val cost: Int

    companion object {
        /** The scale every implementation answers on, and the one [UctTree.record] already credits. */
        const val WIN: Double = 1.0
        const val EVEN: Double = 0.5
        const val LOSS: Double = 0.0
    }
}
