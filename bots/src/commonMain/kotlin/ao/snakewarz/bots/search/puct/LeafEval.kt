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
 * that is a comparison rather than an assertion. The six answer it at six prices: [MobilityEval]
 * reads sixteen squares, [TerritoryEval] sweeps the board once, [SurvivalEval] sweeps it and then
 * takes each region apart, [HorizonEval] prices the pieces in moves rather than in squares,
 * [ChamberEval] keeps the pieces rather than summing them, and
 * [ao.snakewarz.bots.search.learned.LearnedEval] reads that same decomposition as a feature vector
 * and weights it by a fit. Two entrants differing only in this line is what makes a matrix over them
 * mean something.
 *
 * There used to be one more that played the position out at random, so that one entrant judged a
 * leaf exactly as [UctBot] does. It is gone: `uct` was always in the matrix beside it, and `uct` is
 * that control — a tree with a random rollout at the leaf — without also being a setting nobody
 * would choose to play against.
 *
 * **The obvious answer to that — a *policy* rollout, which `uct` is not the control for — was priced
 * and not built.** [ao.snakewarz.bots.search.RolloutPolicy]'s swept-prior row is the measurement:
 * the richest policy anybody had a reason to try costs 3.25x a uniform rollout on a 20x20 and would
 * have to return 97 to 110 Elo per iteration merely to draw level with one, where the best figure any
 * policy has ever posted on `uct` is +75. A rollout leaf at that price is about two and a half times
 * a whole [TerritoryEval] turn, which is the leaf it would have to beat.
 *
 * ### One value per slot, not one number
 *
 * Because the tree credits **per actor**: [PuctTree.record] reads `values[actorOf(node)]`, exactly
 * as [UctTree.record] judges an outcome against `actorOf(node)`. The reason is the one [UctTree]'s
 * KDoc gives for dropping legacy's negamax — "bad for A" is not "good for B" once there is a C, and
 * a self-relative scalar would have to be complemented on the way up the path to be any use.
 *
 * ### A slot's value is in `0.0..1.0` and the slots do **not** sum to one
 *
 * Stated because it is what a `backup = maxn | paranoid` knob on [PuctBot] would run into, and P7 of
 * the 2026-07-29 agenda went looking for exactly that knob. `values[by]` *is* the max^n backup, and
 * the paranoid one is `values[by] - max(everybody else)`, which is what
 * [ao.snakewarz.bots.search.AlphaBetaBot] scores. Those two differ at three seats and the agenda is
 * right that the difference is real — but swapping them here does not isolate it, because the
 * difference of two of these numbers is not an affine image of one of them.
 *
 * [TerritoryEval] is the worked case and every other implementation is worse, not better.
 * Its value is `0.5 + 0.5 * (ground + mobility) - trapPenalty`, where `ground` is a departure from an
 * even share and so is **antisymmetric** across the live snakes, while `mobility` and `trapPenalty`
 * are each read off one slot alone. Subtracting therefore doubles the territory term's weight
 * against the other two and replaces a level with a difference — the same "about twofold toward
 * territory" shift `AlphaBetaBot`'s own KDoc names — and it rescales the whole quantity that
 * [PuctBot.CPUCT] is balanced against, so exploration moves with it. [MobilityEval] has no
 * antisymmetric term at all. So the knob would move the backup, the effective leaf weighting and the
 * effective exploration constant together, at two seats as well as at three, which is the three-way
 * confound it was proposed to escape.
 *
 * **What it would take first is a leaf whose vector is a distribution over the slots**, where the
 * difference and the level really are the same statement. There is no such implementation here and
 * adding one is a seventh frozen `eval` value that nobody has measured. Until then the backup
 * question is answerable by argument and not by this repository's instruments; P7 declined to freeze
 * a `Choice` value for it and said so.
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
