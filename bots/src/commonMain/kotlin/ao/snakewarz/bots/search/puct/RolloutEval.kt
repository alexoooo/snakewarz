package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.bots.search.randomPlayout
import ao.snakewarz.bots.search.uct.UctBot
import ao.snakewarz.core.random.Rng

/**
 * The control: play the rest of the game out at random and report who won.
 *
 * Not a hand-written evaluation at all, and that is the point of shipping it. [PuctBot] at this
 * setting judges a leaf exactly as [UctBot] does, so a batch of `eval=expert` against
 * `eval=rollout` changes the value function and nothing else — not the tree, not the prior, not the
 * allowance. Without it, a hand-written evaluation could only be measured against a different bot,
 * and every difference between the two would be a candidate explanation for the result.
 */
internal class RolloutEval(private val slotCount: Int, private val rng: Rng) : LeafEval {
    /** The same rollout [UctBot] runs, so by construction the same price. */
    override val cost: Int get() = EvaluationCost.ROLLOUT

    override fun valuesInto(playout: Playout, into: DoubleArray) {
        outcomeValues(randomPlayout(playout, rng), slotCount, into)
    }

    override fun toString(): String = "RolloutEval"
}
