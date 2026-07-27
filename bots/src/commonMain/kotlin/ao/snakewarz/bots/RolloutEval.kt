package ao.snakewarz.bots

import ao.snakewarz.botapi.BoardScratch
import ao.snakewarz.botapi.Playout
import ao.snakewarz.core.Rng

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
    /** Zero: [randomPlayout] charges a unit per simulated move, which is the whole allowance model. */
    override val cost: Int get() = 0

    override fun valuesInto(playout: Playout, into: DoubleArray): Boolean {
        val result = randomPlayout(playout, rng)
        if (result === BoardScratch.EXHAUSTED) {
            // Identity rather than equality: `SpaceOwnership`'s judged draw is equal to this by value
            // and is a real reading of a position, whereas this carries no information about one.
            return false
        }

        outcomeValues(result, slotCount, into)
        return true
    }

    override fun toString(): String = "RolloutEval"
}
