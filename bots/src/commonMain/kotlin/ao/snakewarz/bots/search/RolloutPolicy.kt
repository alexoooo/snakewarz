package ao.snakewarz.bots.search

import ao.snakewarz.bots.search.puct.MovePrior
import ao.snakewarz.bots.search.puct.PuctBot
import ao.snakewarz.bots.search.uct.UctBot
import ao.snakewarz.bots.search.uct.truncatedPlayout
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId

/**
 * What a snake plays inside a rollout — the one question [randomPlayout] and [truncatedPlayout] both
 * have to answer, asked in one place instead of spelled out in each of them.
 *
 * A rollout is the only part of [UctBot] nobody has ever tuned: the tree is guided by UCB1, the leaf
 * by whatever finishes the game, and in between a hundred-odd moves are drawn out of a hat. A better
 * policy is the standard answer and it is not free — a rollout step here costs tens of nanoseconds,
 * so a reading added to it is paid a hundred times per evaluation and a hundred thousand times per
 * turn, where the same reading at a leaf is paid once.
 *
 * **[EvaluationCost.ROLLOUT] is a flat `1`, so a dearer policy buys no fewer iterations.** All of its
 * cost lands on the wall clock, and none of it on the allowance. That makes a batch at equal
 * allowance *flatter* a policy rather than merely fail to charge it, and it is why nothing here is a
 * default: read a comparison of two of these with the `time` figures beside it, or not at all.
 *
 * ### The three settings, and how far each is from uniform
 *
 * `RolloutPolicyTest` samples rollouts from every position of a played game and reports how often
 * each would differ from the uniform draw, as total variation — the disagreement probability under
 * the best coupling of the two draws, which is the largest claim any implementation could make. Four
 * seeds a board, and a *choice* step is one where the mover has more than one legal move:
 *
 * | policy | 8x8 | 12x12 | 20x20 |
 * |---|---|---|---|
 * | [LIBERTY], share of choice steps it changes anything at | 6.1% | 4.5% | 3.5% |
 * | [LIBERTY], mean divergence per choice step | 0.029 | 0.021 | 0.016 |
 * | [PRIOR], share of choice steps | 73% | 74% | 73% |
 * | [PRIOR], mean divergence per choice step | 0.056 | 0.054 | 0.051 |
 * | choice steps per rollout | 14.7 | 22.1 | 31.0 |
 * | **[LIBERTY], diverging steps per rollout** | **0.42** | **0.46** | **0.48** |
 * | **[PRIOR], diverging steps per rollout** | **0.82** | **1.18** | **1.57** |
 *
 * Read the last two rows rather than the per-step ones, and read them as *trajectories*: one step
 * played differently sends the rest of the rollout down another game, so roughly a third of rollouts
 * under [LIBERTY] and two thirds under [PRIOR] are a different game from the one uniform would have
 * played. Neither mechanism is anywhere near unable to fire, which is what this had to establish
 * before it was worth building.
 *
 * The two disagree about the board, and that is the shape to expect: [LIBERTY] fires on dead ends,
 * which get rarer as the board grows, while [PRIOR] grades every step and is flat in the board and
 * therefore rising per rollout, because a bigger board is a longer rollout.
 *
 * ### And what each costs, which is the half a strength claim is worthless without
 *
 * Same test, timed over `AppraisalTape`'s fixed line at the shipped allowance so that every setting
 * is measured over the same boards in the same order, with the default read twice as the control.
 * Three runs, as a whole-turn ratio against [UNIFORM]:
 *
 * | policy | 8x8 | 12x12 | 20x20 |
 * |---|---|---|---|
 * | [LIBERTY] | 1.38-1.50x | 1.68-1.72x | 1.85-1.92x |
 * | [PRIOR] | 1.41-1.62x | 1.80-1.86x | **1.93-2.45x** |
 *
 * **Read the spread, and read the 20x20 [PRIOR] cell as the instrument's floor rather than as a
 * number.** Five runs, and the wobbliest cell is not the small board it was first assumed to be: at
 * 20x20 the *control* — the default setting timed twice inside one block — swung 11% between runs on
 * its own, and one run put [PRIOR] at 2.45x where the next put it at 1.93x. The 8x8 line is 47 turns
 * against the 20x20's 210, so the short line is not what is driving it either.
 *
 * **What is verified is the allowance, not the ratio, and they are not reciprocals.** Cost per turn is
 * a tree term plus a rollout term and only the second one carries the policy, so halving the budget
 * does not halve the gap: at 20x20 [PRIOR] costs 3819 us/turn at 1000 and 1558 at 500, which is 41% of
 * it rather than 50%. `RolloutPolicyTest.EQUAL_CLOCK` asserts each setting's allowance directly
 * against the default's clock rather than deriving it, and across five runs every one of them landed
 * within 94-113% — which is inside the control's own swing on the same board.
 *
 * Both rows fell by five to seven percent when [pick] stopped reading the board on a forced step, and
 * [PRIOR] fell furthest because it had the most to throw away there. Between a sixth and a third of
 * rollout steps are forced, so a policy that answers them is spending a fifth of its work on
 * positions with nothing to decide.
 *
 * **The prior is far cheaper than it was estimated at** — 2x rather than the 3-6x the work was
 * planned around — and the reason is that at `puct`'s shipped weights the prior is a liberty count
 * and nothing else, so it reads the four orthogonal neighbours rather than the whole ring and needs
 * no exponential. A prior with the pinch reading turned on is a different price and is not measured
 * here.
 *
 * **The ratio rises with the board, and [LIBERTY] is not reliably the cheaper of the two.** A rollout
 * is longer on a bigger board while the tree work above it is not, so the share of a turn a policy
 * can reach grows; and the two policies read a comparable number of squares, so which of them wins on
 * cost is inside the spread on the small board. At 12x12 a uniform step is about 40 ns over the 29
 * steps a rollout takes there, and the two add roughly 37 ns and 45 ns to each of the 22 of those
 * that are a real choice.
 *
 * A separate cross-check with `:lab time`, which runs each entrant in its own process and so cannot
 * be contaminated the way one JVM timing several bots is, agrees on the sign and not on the figure —
 * 1.6x and 2.4x for [LIBERTY] on the two boards. It plays a *different game per entrant* and prints
 * the turn counts that say so: 38 turns for [UNIFORM] against 56 for [LIBERTY] on a 12x12. That
 * column is the confound, which is why the table above is taken over a line no setting chose.
 *
 * ### And a fourth policy, which was priced and never built
 *
 * [PRIOR] samples `MovePrior` at `puct`'s **declared defaults**, and the paragraph above is why that
 * is cheap: at those weights the prior is a liberty count and nothing else. `MovePrior`'s own sweep
 * settled somewhere else entirely — `priorPinch=0.8, priorTail=0.8, priorTemperature=0.9` — and that
 * point is not a value [UctBot.ROLLOUT_POLICY] offers, so no `BotParams` spelling reaches it: a
 * `BotKnob.Choice` coerces what it does not offer back to its default. It was the strongest lead left
 * after the field below, because it diverges from uniform six times harder per rollout than [PRIOR]
 * does, and that is the shape of a policy that could move a search.
 *
 * | at the swept weights | 8x8 | 12x12 | 20x20 |
 * |---|---|---|---|
 * | share of choice steps it changes anything at | 93.4% | 93.5% | 93.9% |
 * | **diverging steps per rollout** | **4.27** | **6.49** | **9.21** |
 * | the same for [PRIOR], from the table above | 0.82 | 1.18 | 1.57 |
 * | **whole-turn cost against [UNIFORM]** | **2.26x** | **2.99x** | **3.25x** |
 * | the same for [PRIOR] | 1.59x | 1.97x | 2.01x |
 *
 * Median of three runs of one block, five passes a cell, every subject a [UctBot] through
 * [UctBot.withRolloutPolicy] so that the timed call site stays monomorphic. The control pair lands
 * within 4% on every board and within 2% at 20x20, and a uniform rollout is carried a **fourth** time
 * as a subject rather than as the control — it reads 97-101% of the control, which is the free check
 * that says the block is a measurement. The [PRIOR] row reproduces this file's own on 8x8 and 20x20
 * and sits 6% above its 12x12 band, which is the derived-ratio spread the paragraph above describes.
 *
 * **And that price cannot be paid.** An e-fold of allowance is worth 80 to 137 Elo to this bot, so a
 * 3.25x turn is 1.18 e-folds and **97 to 110 Elo** — at the conservative end of the band — that the
 * swept prior would have to find per iteration at 20x20 merely to draw level with a uniform draw. The
 * largest per-iteration figure any policy has ever posted here is [LIBERTY]'s **+75** on that board,
 * and [PRIOR] posts **+23**. It would have to be worth four times what the prior it sharpens is worth
 * and half again the best figure on the table, before it broke even — and only then would it start on
 * the 68 to 94 Elo between the best of these and `puct:eval=territory`.
 *
 * **The sign of the evidence is against it as well, not merely silent.** Per iteration [PRIOR] beats
 * [LIBERTY] on both small boards and loses to it at 20x20, so more divergence is not monotone in
 * value, and the board where a policy is worth the most is the board where the *richer* of the two was
 * already worth the less. The swept prior is richer still.
 *
 * So it is not a [VALUES] entry, and that is the finding rather than an omission: a `Choice` value is
 * frozen forever and the bar for one is a setting somebody would want to play. What ships instead is
 * the seam, the table above, and this paragraph. **The mechanism is priced out, not switched off** —
 * at 93% of choice steps it is as far from a structural null as anything measured here.
 *
 * ### And what it is worth, which is the answer: not enough, except on the largest board
 *
 * One field per board, rated in one fit, because a head-to-head between two settings of one bot
 * measures a style match-up and only a common field converts that into strength. Nine rungs, and
 * **every rating below is quotable only beside this field**: the baseline `uct:budget=1000`; each
 * policy at its allowance from the table above; each policy *also* at `budget=1000` as a labelled
 * control, which is the flattery quantified rather than a candidate; `puct:eval=territory`,
 * `alphabeta:eval=territory`, `flat-monte-carlo` — the one rung that deliberately never got this
 * knob and is therefore the sanity check — and `chase`. Two blocks of 200 rounds a board on disjoint
 * seeds, 14,400 matches and 3,200 games an entrant, 94-98% of them distinct games.
 *
 * | against the baseline, in Elo | 8x8 | 12x12 | 20x20 |
 * |---|---|---|---|
 * | [LIBERTY] at equal **clock** | **-25** | **-17** | **+17** |
 * | [PRIOR] at equal **clock** | **-33** | **-38** | **-34** |
 * | [LIBERTY] at equal allowance — *the control* | +18 | +26 | +75 |
 * | [PRIOR] at equal allowance — *the control* | +27 | +31 | +23 |
 *
 * The two rows are one subtraction apart and the subtraction is the whole finding. A policy's value
 * **per iteration** is the control row; what it hands back in allowance is the difference between the
 * rows; and an e-fold of allowance is worth **80 to 137 Elo** to this bot — six estimates, one per
 * policy per board, consistent inside their own bars and agreeing with the 111 an unrelated phase
 * measured on `alphabeta`. So [LIBERTY] has to find 43 / 43 / 58 Elo to break even and finds
 * 18 / 26 / 75.
 *
 * **[PRIOR] is the better policy and the worse setting, which is the opposite of what was expected.**
 * Per iteration it is worth more than [LIBERTY] on both small boards, so its extra divergence is not
 * noise a tree averages out; it simply never pays for itself. Read together the two rows say the
 * mechanism is real and the price is what kills it.
 *
 * **Only the 20x20 sign is in doubt, and it is in doubt in both directions.** The field's intervals
 * there overlap (+30..+53 against the baseline's +12..+36), a paired `ab` of the same pairing came
 * back `UNDECIDED` at +18 +-17 against the 800-board ceiling, and the 20x20 allowance is itself only
 * good to about ten percent of clock, which is another nine Elo. Level is inside all three.
 *
 * What it buys, from `phases` at equal allowance, is **position and not fill**: [LIBERTY] arrives
 * ahead when the board comes apart in 216 of 373 separated matches on a 20x20 against uniform's 151,
 * and in 152 of 304 on an 8x8 against 131 — while converting a lead it already has at 83% and 88%,
 * which is uniform's own rate. The edge is in the room race, and the room race is longer on a bigger
 * board.
 *
 * **The divergence probe predicted visibility and got the trend backwards.** [LIBERTY] fires on
 * *fewer* choice steps as the board grows (6.1% to 3.5%) and its per-rollout figure barely moves
 * (0.42 to 0.48), while its value per iteration **quadruples** (+18 to +75). A firing rate says
 * whether a null would be structural. It does not say how much, and it does not say where.
 *
 * One instance per bot per match, built in the constructor. Nothing here allocates per step.
 */
internal class RolloutPolicy(
    private val name: String,
    private val grid: Grid,
    /**
     * `puct`'s prior at `puct`'s own declared weights, read off the knobs rather than re-declared —
     * `AlphaBetaBot`'s arrangement, for the same reason: two copies of a default drift.
     *
     * They are parameters rather than constants **only so that an unshipped point can be priced**.
     * [UctBot.ROLLOUT_POLICY] is a `BotKnob.Choice` and `Choice.read` coerces anything it does not
     * offer back to the default, so there is no params spelling that reaches a weight this bot does
     * not declare — and the swept point of the *Prior at its swept weights* row below is exactly such
     * a spelling. `RolloutPolicyTest` reaches it through [UctBot.withRolloutPolicy]; nothing in a
     * shipped path passes any of these.
     */
    priorLiberty: Double = PuctBot.PRIOR_LIBERTY.default,
    priorPinch: Double = PuctBot.PRIOR_PINCH.default,
    priorWall: Double = PuctBot.PRIOR_WALL.default,
    priorTail: Double = PuctBot.PRIOR_TAIL.default,
    priorTemperature: Double = PuctBot.PRIOR_TEMPERATURE.default,
) {
    private val mode = when (name) {
        UNIFORM -> UNIFORM_MODE
        LIBERTY -> LIBERTY_MODE
        PRIOR -> PRIOR_MODE
        else -> error("unknown rollout policy: $name")
    }

    private val prior =
        if (mode != PRIOR_MODE) {
            null
        } else {
            MovePrior(grid, priorLiberty, priorPinch, priorWall, priorTail, priorTemperature)
        }

    /** One step's priors, refilled per draw. Empty unless there is a prior to fill it. */
    private val weights = DoubleArray(if (prior == null) 0 else Direction.entries.size)

    /** The four orthogonal offsets, so [wayOn] walks an `IntArray` rather than an enum's iterator. */
    private val neighbours = IntArray(Direction.entries.size) { grid.offsetOf(Direction.entries[it]) }

    /**
     * What [mover] plays next, given [legal] from its head.
     *
     * A trapped snake plays `NORTH` and **draws nothing**, in every setting: every direction from a
     * trapped position eliminates it and leaves the board identical, so there is nothing to choose
     * between them and drawing for it would only shift the stream.
     *
     * A snake with exactly *one* legal move is the same argument one step weaker, and the branch for
     * it sits deliberately **below** [UNIFORM] rather than above it. Between a sixth and a third of
     * rollout steps are forced, so reading the board for them is real work thrown away — but this
     * project's `uct` has played its whole recorded history drawing on those steps, and
     * `GoldenMoveStreamTest` pins the stream that comes out. The two settings with no history skip
     * the work; the one with a hash on it does not.
     */
    fun pick(board: BoardView, mover: SnakeId, legal: DirectionSet, rng: Rng): Direction = when {
        legal.isEmpty -> Direction.NORTH
        mode == UNIFORM_MODE -> legal.nth(rng.nextInt(legal.size))
        legal.size == 1 -> legal.nth(0)
        mode == LIBERTY_MODE -> escaping(board, mover, legal).let { it.nth(rng.nextInt(it.size)) }
        else -> sampled(board, mover, legal, rng)
    }

    override fun toString(): String = "RolloutPolicy($name)"

    // -- internals

    /**
     * [legal] minus the steps into a square with no way on, or all of [legal] when that leaves none.
     *
     * A destination whose own four neighbours are all blocked is a hole of one square: a snake that
     * enters it is trapped on its next turn unless a tail happens to retract beside it, which is why
     * this is a rollout heuristic and not a legality rule. It is the cheapest reading that separates
     * a step into the open from a step into nothing: the scan stops at the first free neighbour, and
     * most squares have one, so it usually costs a read or two rather than four.
     */
    private fun escaping(board: BoardView, mover: SnakeId, legal: DirectionSet): DirectionSet {
        val head = board.snake(mover).head.index

        var keep = DirectionSet.EMPTY
        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            if (wayOn(board, head + grid.offsetOf(direction))) {
                keep += direction
            }
        }

        return if (keep.isEmpty) legal else keep
    }

    private fun wayOn(board: BoardView, cell: Int): Boolean {
        var at = 0
        while (at < neighbours.size) {
            if (board.isFree(Cell(cell + neighbours[at]))) {
                return true
            }
            at++
        }
        return false
    }

    /**
     * A draw from [MovePrior]'s distribution, by walking its cumulative share.
     *
     * The last legal direction is where the walk lands when nothing else has claimed the draw, which
     * is a rounding outcome rather than a fallback: the shares are normalised to sum to one, and a
     * sum of at most four doubles reproduced by subtraction can finish a hair above or below it.
     */
    private fun sampled(board: BoardView, mover: SnakeId, legal: DirectionSet, rng: Rng): Direction {
        val distribution = prior ?: error("no prior to sample from")
        distribution.into(board, mover, legal, weights)

        var drawn = rng.nextDouble()
        val last = legal.size - 1
        for (i in 0 until last) {
            val direction = legal.nth(i)
            drawn -= weights[direction.ordinal]
            if (drawn < 0.0) {
                return direction
            }
        }
        return legal.nth(last)
    }

    internal companion object {
        /**
         * A uniform draw from the legal set — what this project has always rolled out with, and what
         * every shipped bot still rolls out with.
         */
        const val UNIFORM: String = "uniform"

        /** Refuse a step with no way on, and draw uniformly from what is left. See [escaping]. */
        const val LIBERTY: String = "liberty"

        /** Draw from [MovePrior], the same distribution `puct` opens a node with. */
        const val PRIOR: String = "prior"

        /** In the order a form would offer them, cheapest first. See [UctBot.ROLLOUT_POLICY]. */
        val VALUES: List<String> = listOf(UNIFORM, LIBERTY, PRIOR)

        private const val UNIFORM_MODE = 0
        private const val LIBERTY_MODE = 1
        private const val PRIOR_MODE = 2
    }
}
