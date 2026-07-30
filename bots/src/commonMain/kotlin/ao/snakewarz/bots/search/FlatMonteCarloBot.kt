package ao.snakewarz.bots.search

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.reactive.RandomBot
import ao.snakewarz.bots.reactive.space.SpaceBot
import ao.snakewarz.bots.search.uct.UctBot
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.rules.MatchOutcome

/**
 * Plays each legal move out to the end, many times, at random, and takes the one that wins most. A
 * semantic port of legacy `MonteCarloAi` — Monte Carlo with no tree at all.
 *
 * It is the simplest thing that deserves to be called a search, and it is here for two reasons
 * beyond its own strength: it is the honest baseline [UctBot] has to beat to justify its tree, and
 * it is where the awkward parts of driving [Playout] get worked out on something with no tree
 * bookkeeping on top of them.
 *
 * The whole search is the loop condition. Asking for a playout charges one [EvaluationCost.ROLLOUT],
 * and a playout the allowance would not stretch to comes back with `outcome` already non-null — so
 * this terminates structurally rather than because anybody counted. Handed no allowance at all it
 * runs zero rollouts, spends exactly nothing, and answers with [SpaceBot]'s flood fill, which costs
 * no budget and is a perfectly respectable move.
 *
 * Two departures from legacy, both of which change the answer rather than merely the code:
 *
 * - **The candidates are sampled round-robin, not drained one at a time.** Legacy ran
 *   `numRuns / |legal|` rollouts for the first direction, then the same for the second, and so on.
 *   Under a budget that expires part-way through that gives the first direction a full sample and
 *   the last one none, and the argmax quietly favours whichever direction sorts first. Cycling keeps
 *   the counts within one of each other wherever the budget runs out.
 * - **Rewards are `1.0` / `0.5` / `0.0`**, the same scale [UctBot] uses, rather than legacy's
 *   `+1 / 0 / -1`. Legacy also divided its sum by `numRuns` while having run `numRuns / |legal|`
 *   rollouts, so the number it called an average was wrong by a factor of the branching — harmless
 *   to an argmax, and not worth carrying forward.
 *
 * The rollout is a loop over a [RolloutPolicy] rather than a [RandomBot]: a `Bot` needs a [Turn] per
 * call, and `Turn` is a class, so using one would allocate an object per simulated move across
 * millions of them.
 */
public class FlatMonteCarloBot(setup: BotSetup) : Bot {
    private val self = setup.self
    private val rng = setup.rng
    private val unbudgeted = SpaceBot(setup)

    /**
     * Uniform, and not a setting.
     *
     * This bot is [UctBot]'s ablation control — the same rollout at the same allowance with the tree
     * removed — so its rollout is whatever `uct` ships with rather than whatever `uct` can be asked
     * for. [UctBot.ROLLOUT_POLICY] is where the alternatives are reached, and a control that moved
     * with the subject would stop being one.
     */
    private val policy = RolloutPolicy(RolloutPolicy.UNIFORM, setup.grid)
    private val total = DoubleArray(Direction.entries.size)
    private val rollouts = IntArray(Direction.entries.size)

    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        if (legal.isEmpty) {
            return Decision.Move(Direction.NORTH)
        }

        // Nothing to weigh, and simulating it would spend an allowance a real choice could use.
        legal.singleOrNull()?.let { return Decision.Move(it) }

        total.fill(0.0)
        rollouts.fill(0)

        var next = 0
        while (true) {
            val playout = turn.scratch.playout(EvaluationCost.ROLLOUT)
            if (playout.outcome != null) {
                // The allowance would not stretch to another rollout. Whatever has been sampled so
                // far is what we go on.
                break
            }

            val opening = legal.nth(next % legal.size)
            next++

            playout.advance(opening)
            val result = randomPlayout(playout, rng, policy)

            total[opening.ordinal] += rewardFor(result, playout)
            rollouts[opening.ordinal]++
        }

        return Decision.Move(bestSampled(legal) ?: return unbudgeted.chooseMove(turn))
    }

    /**
     * Win, draw or loss for us, plus a small bonus for having grown.
     *
     * The bonus is legacy's idea and it earns its place: at a few dozen rollouts per candidate, a
     * position where every line loses produces four identical zeroes, and something has to prefer
     * the one that dies latest. It is bounded far below the half-point gap between a loss and a
     * draw, so it can only ever break a tie.
     */
    private fun rewardFor(result: MatchOutcome, playout: Playout): Double {
        val base = when {
            result.winner == self -> 1.0
            result.isDraw -> 0.5
            else -> 0.0
        }
        return base + playout.board.snake(self).length / SURVIVAL_SCALE
    }

    /** The best-averaging opening actually sampled, or `null` if none was. */
    private fun bestSampled(legal: DirectionSet): Direction? {
        var chosen: Direction? = null
        var best = 0.0
        var tied = 0

        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val runs = rollouts[direction.ordinal]
            if (runs == 0) {
                continue
            }

            val average = total[direction.ordinal] / runs
            when {
                chosen == null || average > best -> {
                    chosen = direction
                    best = average
                    tied = 1
                }

                average == best -> {
                    tied++
                    if (rng.nextInt(tied) == 0) {
                        chosen = direction
                    }
                }
            }
        }

        return chosen
    }

    override fun toString(): String = "FlatMonteCarloBot"

    internal companion object {
        /**
         * How much of a turn this may spend. The same range [UctBot] offers, and for the same
         * reason — this is the other bot in the box whose strength is bought by the iteration, and
         * its iteration is the same rollout, priced the same.
         */
        val SEARCH = BotKnob.Search(min = 0, max = 10_000, step = 100)

        val KNOBS: List<BotKnob> = listOf(SEARCH)

        /** Large enough that the survival bonus cannot outweigh a loss becoming a draw. */
        const val SURVIVAL_SCALE = 100_000.0
    }
}
