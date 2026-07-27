package ao.snakewarz.bots

import ao.snakewarz.botapi.Playout

/**
 * How a search values a position it has stopped searching — one number per slot, in `0.0..1.0`.
 *
 * This is the thing a neural network would be, in a PUCT that had one. [PuctBot] declares which
 * implementation it wants as a knob rather than picking one, because the interesting question about
 * a hand-written evaluation is not whether it works but whether it is *worth what it costs*, and
 * that is a comparison rather than an assertion. [RolloutEval] is in the list for exactly that
 * reason: it makes the value function the only thing that changes between two entrants.
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
 * ### It charges for itself
 *
 * A rollout spends the allowance a move at a time through `Playout.advance`, so its cost is visible
 * to the engine. A static evaluation's is not: it can sweep the whole board and charge nothing,
 * which would quietly make `budgetPerTurn` mean something different for every bot that declared one.
 * [cost] is what closes that, and [PuctBot] pays it *before* calling [valuesInto].
 */
internal interface LeafEval {
    /**
     * Fills [into] with each slot's value of the position, and says whether there was one.
     *
     * `false` means the allowance ran out with nothing to say, and the iteration that asked must be
     * abandoned rather than credited — the same distinction [PuctBot] draws around
     * `BoardScratch.EXHAUSTED`, and for the same reason. A static evaluation always answers `true`;
     * only one that simulates can run out part-way through an answer.
     */
    fun valuesInto(playout: Playout, into: DoubleArray): Boolean

    /**
     * What one call costs, in the same currency as one simulated move.
     *
     * Zero for an evaluation that spends the budget itself by advancing the playout, since charging
     * it twice would be worse than not charging it at all.
     */
    val cost: Int

    companion object {
        /** The scale every implementation answers on, and the one [UctTree.record] already credits. */
        const val WIN: Double = 1.0
        const val EVEN: Double = 0.5
        const val LOSS: Double = 0.0
    }
}
