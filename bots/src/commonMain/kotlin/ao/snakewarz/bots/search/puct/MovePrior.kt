package ao.snakewarz.bots.search.puct

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId
import kotlin.math.abs

/**
 * `P(s,a)` — what [PuctBot] believes about a move before it has searched it.
 *
 * AlphaZero takes this from a policy head. Here it is a weighted sum of four readings of the
 * destination square, normalised over the legal set and written into a caller's array by
 * [Direction.ordinal]. Every weight is a knob, so the whole prior is one point in a space a sweep can
 * walk — `PuctBot.PRIOR_LIBERTY` and the four beside it.
 *
 * ### It runs at every expansion, which is what prices it
 *
 * A leaf evaluation happens once per iteration and so does an expansion, so a reading added here
 * costs the same *rate* as one added to [ChamberEval] — and the leaf is the one that gets to sweep
 * the board, because it is answering about one position where this is answering about three. So the
 * true articulation test, "flood the free space from each destination and see whether it shattered",
 * is not affordable at this rate: it is [ChamberEval]'s own work done three times per iteration
 * instead of once. What is affordable is the **local** form of the same question, and [cuts] is it.
 *
 * ### The four readings
 *
 * - **Liberties.** The destination's own free neighbours: the cheapest question that separates a move
 *   into the open from a move into a pocket, and the only reading this prior had before the others
 *   were added. `PuctBot.PRIOR_LIBERTY` weights it.
 * - **Pinch.** Whether the destination's free neighbours are locally *joined* to each other around
 *   it. Two orthogonal neighbours are joined when the diagonal between them is free, since that is a
 *   two-step path avoiding the square itself; when the free neighbours fall into more than one such
 *   group the square is a candidate cut vertex, and taking it is what would separate them. This is
 *   the seal question — `ChamberEval.sealPenalty`'s, the one reading that carried the whole of that
 *   leaf's gain — asked at the prior instead of at the leaf, for eight board reads rather than a
 *   decomposition. `PuctBot.PRIOR_PINCH` weights it.
 * - **Wall.** How many of the destination's blocked neighbours are the wall rather than a
 *   snake — the border ring and the map's own obstacles alike. Liberties count the two the same, and
 *   they are not the same: a body square clears when a tail retracts and a wall never does.
 *   Independent of the liberty count rather than a rescaling of it — `(liberties, walls)` and
 *   `(liberties, bodies)` span the same plane, and liberties alone spans a line in it.
 *   `PuctBot.PRIOR_WALL` weights it.
 * - **Tail following.** Whether the step closes on this snake's own tail. A grid step changes a
 *   Manhattan distance by exactly one, so the reading is a clean `+1` or `-1` rather than a
 *   magnitude. `PuctBot.PRIOR_TAIL` weights it.
 *
 * **A reading whose weight is zero is not computed**, which is what keeps the shipped defaults
 * costing exactly what a one-feature prior costs and playing exactly the move stream it played. The
 * pinch reading is the one that matters there: it is what doubles the board reads per destination,
 * from the four orthogonal neighbours to the whole eight-square ring.
 *
 * ### And all of it is inside the noise, which is the answer to the question the rate asks
 *
 * Timed at `eval=chamber` and the shipped allowance on a 12x12, seed by seed, with `uct` carried as
 * a control. The probe is a weight of `1e-9`: small enough that the score it perturbs is a
 * rounding error and large enough that the reading is computed, so most seeds play the *same game*
 * and the pair is a cost measurement with the position held still. Five of six seeds matched turn
 * for turn; the sixth is dropped rather than averaged in.
 *
 * | reading | paired ratio, 5 seeds |
 * |---|---|
 * | pinch — the whole eight-square ring instead of four | **1.01x** |
 * | wall and tail together | **1.02x** |
 *
 * The wall row prices that reading's arithmetic and not the four board reads it makes; the pinch row
 * above is what four more reads per destination cost, and it is 1.01x.
 *
 * Both are at or under the resolution of a `time` run, and that is what the rate argument above
 * predicts from the other side: a leaf at this setting sweeps a 196-square board and takes every
 * region apart, so thirty-odd extra reads beside it do not show. P3's exchange rate prices a 2% cost
 * at about **5 Elo**, which is the whole of what any of these readings has to buy back.
 *
 * **Both figures are about the rate this runs at and not about the readings, and somewhere else the
 * same readings are ruinous.** Inside a *rollout* — paid per step of a hundred-odd rather than once
 * per expansion — the swept point below costs **1.4x to 1.6x** what the shipped one-feature prior
 * costs there and takes a whole `uct` turn to **2.3x-3.3x** a uniform draw's.
 * [ao.snakewarz.bots.search.RolloutPolicy]'s swept-prior row is that measurement, and it is why
 * nothing was built on it.
 *
 * **Do not read the cost off a field's `us/turn` column instead.** It puts the strongest entrant 13%
 * above the weakest and orders the seven of them almost exactly by rating, which is the confound
 * rather than a measurement: a stronger bot survives longer, a longer game is a fuller board, and a
 * fuller board is where the chamber leaf costs the most per turn.
 *
 * The softmax has no such probe — a temperature above zero changes the distribution materially, so
 * there is no setting that computes it without altering the game. It is at most three [portableExp]
 * calls of fifteen multiply-adds each per expansion, against the same leaf.
 *
 * ### Two ways to normalise, and the default is the one without a temperature
 *
 * At `PuctBot.PRIOR_TEMPERATURE` of zero the score is normalised **proportionally** over
 * `PRIOR_FLOOR` plus itself, which is what this prior has always done: bounded, monotone, and with no
 * constant to tune beyond the weights themselves. Above zero it is a **softmax** at that temperature,
 * which is what a policy head produces and what the proportional form cannot imitate — the difference
 * is not the ranking, which is the same, but how far apart the probabilities are, and PUCT spends its
 * allowance in proportion to exactly that.
 *
 * The proportional form is not temperature-free either, and that is worth knowing before reaching for
 * the softmax: only the *ratio* of the weights to [PRIOR_FLOOR] matters there, so scaling
 * `PuctBot.PRIOR_LIBERTY` sharpens and flattens the prior just as a temperature does. What the
 * softmax adds is exponential rather than linear separation, and a normalisation that stays valid
 * when a penalty drives a score negative — where the proportional form has to clamp at
 * [PRIOR_MINIMUM].
 *
 * The softmax needs an exponential and nothing in `:bots` may call `kotlin.math.exp`, so it takes one
 * from [portableExp], built from `+ - * /` for [ao.snakewarz.bots.search.uct.portableLog]'s reason.
 * That is what keeps this bot in `GoldenMoveStreamTest`'s cross-target set with a temperature in it.
 *
 * ### What the readings are worth — and the softmax is what makes the rest of them work
 *
 * `spsa puct:eval=chamber --knobs priorPinch,priorWall,priorTail,priorTemperature --iterations 300
 * --boards 8` at the shipped allowance on a 12x12 — 4,800 matches, 4,530 of them distinct games. It
 * settled on `priorPinch=0.8, priorTail=0.8, priorTemperature=0.9`, with `priorWall` finishing on
 * its starting `0.0`, and its own confirming run put that point **+65 Elo over 260 fresh boards**.
 *
 * Then every ablation of it, entered **into one field** and rated together — 3,100 matches, 2,234 of
 * them distinct, worst contested pairing 72 of 100 distinct:
 *
 * | entrant | rating | 95% |
 * |---|---|---|
 * | **`priorPinch=0.8, priorTail=0.8, priorTemperature=0.9`** — the swept point | **146** | +124..+168 |
 * | `priorTail=0.8` alone | 113 | +87..+145 |
 * | `priorPinch=0.8` alone | 96 | +70..+125 |
 * | `priorTail=0.4` alone | 88 | +63..+113 |
 * | `priorPinch=0.8, priorTail=0.8` — the pair, no temperature | 86 | +54..+122 |
 * | `priorTemperature=0.9` alone | 49 | +16..+81 |
 * | `eval=chamber` — the baseline | 43 | +23..+64 |
 * | `uct` | −22 | −45..+5 |
 *
 * **The swept point is +103 on the baseline with the intervals disjoint**, and the shape of the rest
 * is the finding:
 *
 * - **The temperature alone is worth nothing** — 49 against 43, one interval inside the other. There
 *   is nothing for it to spread when the score is one feature wide.
 * - **The two rich readings without it are worth *less than either of them alone*** — 86, under
 *   `priorTail`'s 113 and `priorPinch`'s 96. In the proportional form their weights are large
 *   against `PRIOR_FLOOR`, so a move that collects both penalties lands on [PRIOR_MINIMUM] and the
 *   distinctions above it flatten out.
 * - **The three together are the top of the field.** The temperature adds `+60` on top of a pair it
 *   is worth nothing beside on its own.
 *
 * So the answer to "was the missing temperature a real cost of the `exp` ban" is **yes, but only
 * once the prior has something to be a temperature *of*.** A one-feature prior did not want one, and
 * that is why its absence read as a virtue for as long as it did.
 *
 * ### And a warning about how that was nearly missed
 *
 * Every one of those ablations was run as an `ab` first, and the head-to-head table is **intransitive
 * and disagrees with the field on the sign of the coordinate that matters**: `priorTail=0.8` measured
 * `+250 ±78` over 60 boards against the baseline, `priorTail=0.4` measured `−35 ±23` over 280, and
 * `0.4` then beat `0.8` by `66 ±30` over 300. The temperature measured `−85 ±34` alone and `−53 ±28`
 * on top of `priorTail` — and it is the term the field says is carrying the point. `rate` prints the
 * residual: `priorTail=0.8` takes 84% off the baseline where its rating expects 60%.
 *
 * P5a's rule was *ablate a multi-weight point before adopting it*. The correction this adds is that
 * **the ablation is a field, not a stack of `ab`s**: a per-coordinate head-to-head is the "ordering
 * built out of one row" the shared protocol already forbids, and against a knob that changes how a
 * bot plays *itself* it is the least reliable row there is.
 *
 * One instance per bot per match. Nothing here allocates.
 */
internal class MovePrior(
    private val grid: Grid,
    /** What one free neighbour of the destination is worth. */
    private val libertyWeight: Double,
    /** Taken off per extra group the destination's free neighbours fall into — see [cuts]. */
    private val pinchPenalty: Double,
    /** What one wall square beside the destination is worth. */
    private val wallBonus: Double,
    /** Added to a step that closes on this snake's own tail, and taken off one that does not. */
    private val tailBias: Double,
    /** Softmax temperature, or zero for the proportional prior. */
    private val temperature: Double,
) {
    /**
     * N, NE, E, SE, S, SW, W, NW as index deltas, so the diagonals sit between the orthogonals they
     * join and [cuts] can walk the ring by index.
     */
    private val ring = ringAround(grid)

    private val pinching = pinchPenalty != 0.0
    private val walling = wallBonus != 0.0
    private val biasing = tailBias != 0.0

    /** One when the diagonals are read as well, two when only the orthogonals are wanted. */
    private val ringStep = if (pinching) 1 else 2

    /**
     * Writes `P(s,a)` for every direction in [legal] into [priors], indexed by [Direction.ordinal].
     *
     * Ordinals outside [legal] are left alone: `PuctTree.open` reads only the ones it was offered, so
     * a caller need not clear what it is not asking about. An empty [legal] writes nothing — that
     * node's mover is trapped, and `PuctTree.open` owns the single edge such a node gets.
     */
    fun into(board: BoardView, mover: SnakeId, legal: DirectionSet, priors: DoubleArray) {
        if (legal.isEmpty) {
            return
        }

        val snake = board.snake(mover)
        val head = snake.head
        val headRow = grid.rowOf(head)
        val headCol = grid.colOf(head)

        // A snake of length one is standing on its own tail, so there is nothing to follow and every
        // move would read as moving away -- which is a constant, and a constant is not neutral in a
        // proportional prior.
        val following = biasing && snake.tail != head
        val tailRow = if (following) grid.rowOf(snake.tail) else 0
        val tailCol = if (following) grid.colOf(snake.tail) else 0
        val tailNow = if (following) abs(headRow - tailRow) + abs(headCol - tailCol) else 0

        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val destination = head.index + grid.offsetOf(direction)

            var free = 0
            var at = 0
            while (at < RING) {
                if (board.isFree(Cell(destination + ring[at]))) {
                    free = free or (1 shl at)
                }
                at += ringStep
            }

            var score = libertyWeight * (free and ORTHOGONAL).countOneBits()

            if (pinching) {
                val extra = cuts(free) - 1
                if (extra > 0) {
                    score -= pinchPenalty * extra
                }
            }

            if (walling) {
                // Asked of the board rather than answered from the coordinates: an interior wall of
                // a map is as impassable as the border ring and is invisible to a row and a column.
                var walls = 0
                var side = 0
                while (side < RING) {
                    if (board.isWall(Cell(destination + ring[side]))) {
                        walls++
                    }
                    side += 2
                }
                if (walls != 0) {
                    score += wallBonus * walls
                }
            }

            if (following) {
                val row = headRow + direction.dRow
                val col = headCol + direction.dCol
                val after = abs(row - tailRow) + abs(col - tailCol)
                score += if (after < tailNow) tailBias else -tailBias
            }

            priors[direction.ordinal] = score
        }

        if (temperature > 0.0) {
            softmaxInto(legal, priors)
        } else {
            proportionalInto(legal, priors)
        }
    }

    override fun toString(): String = "MovePrior(t=$temperature)"

    // -- internals

    /**
     * How many groups the destination's free orthogonal neighbours fall into, walking the ring.
     *
     * Two orthogonal neighbours next to each other on the ring are in the same group when the
     * diagonal between them is free, because that is a path from one to the other that does not use
     * the destination square. So more than one group means the square is a **local cut**: taking it
     * separates ground that is joined only through it, as far as its own neighbourhood can see. A
     * longer way round may still exist, which is why this is a prior and not a verdict.
     *
     * Counted as run *starts* around the cycle. A ring with no start is a ring with a single run all
     * the way round, and a destination with no free neighbour at all has no groups — it is a move
     * into a dead end, which the liberty reading already prices at zero and this must not then reward
     * for being uncut.
     */
    private fun cuts(free: Int): Int {
        var starts = 0
        var at = 0
        while (at < RING) {
            if (free and (1 shl at) != 0) {
                val previous = free and (1 shl ((at + RING - 2) and (RING - 1))) != 0
                val between = free and (1 shl ((at + RING - 1) and (RING - 1))) != 0
                if (!previous || !between) {
                    starts++
                }
            }
            at += 2
        }
        return if (starts == 0 && free and ORTHOGONAL != 0) 1 else starts
    }

    /**
     * The prior as a share of the total score, which is what this bot has always used.
     *
     * [PRIOR_FLOOR] is what keeps a move with nothing going for it in the search at all: a prior of
     * exactly zero scores `PuctBot.FIRST_PLAY` forever and is frozen out however the position
     * develops. [PRIOR_MINIMUM] is the same guarantee against a penalty rather than against a zero
     * reading, and it is a floor rather than a shift because a shift would move every other move's
     * share to buy it.
     */
    private fun proportionalInto(legal: DirectionSet, priors: DoubleArray) {
        var total = 0.0

        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            val score = (PRIOR_FLOOR + priors[ordinal]).coerceAtLeast(PRIOR_MINIMUM)
            priors[ordinal] = score
            total += score
        }

        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            priors[ordinal] = priors[ordinal] / total
        }
    }

    /**
     * The prior as a softmax at [temperature], which is what a policy head would hand back.
     *
     * The best score is subtracted before exponentiating, the standard stabilisation — and here it
     * does a second job: the largest exponent is then exactly zero, [portableExp] answers exactly
     * `1.0` there, and the total can neither overflow nor come out as zero however far apart the
     * scores are. [EXPONENT_FLOOR] bounds the other end, which is what lets [portableExp] refuse an
     * argument outside its range instead of quietly saturating.
     *
     * [PRIOR_FLOOR] has no counterpart here and needs none: a constant added to every score cancels
     * in a softmax, and the exponent floor is what keeps the worst move's prior above zero.
     */
    private fun softmaxInto(legal: DirectionSet, priors: DoubleArray) {
        var top = Double.NEGATIVE_INFINITY
        for (i in 0 until legal.size) {
            val score = priors[legal.nth(i).ordinal]
            if (score > top) {
                top = score
            }
        }

        var total = 0.0
        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            val exponent = (priors[ordinal] - top) / temperature
            val weight = portableExp(if (exponent < EXPONENT_FLOOR) EXPONENT_FLOOR else exponent)
            priors[ordinal] = weight
            total += weight
        }

        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            priors[ordinal] = priors[ordinal] / total
        }
    }

    internal companion object {
        /**
         * N, NE, E, SE, S, SW, W, NW as index deltas off [Grid.offsetOf], rather than off the stride
         * directly, so that the one place the row axis points down stays [Direction]'s business.
         */
        private fun ringAround(grid: Grid): IntArray {
            val north = grid.offsetOf(Direction.NORTH)
            val south = grid.offsetOf(Direction.SOUTH)
            val east = grid.offsetOf(Direction.EAST)
            val west = grid.offsetOf(Direction.WEST)

            return intArrayOf(
                north, north + east, east, south + east,
                south, south + west, west, north + west,
            )
        }

        /** Squares around a destination, diagonals included. */
        private const val RING = 8

        /** The four ring positions a snake can actually step to, as a mask over [RING]. */
        private const val ORTHOGONAL = 0b0101_0101

        /** Every score sits on this, so no move's prior is zero. See [proportionalInto]. */
        const val PRIOR_FLOOR: Double = 1.0

        /**
         * And no move's score falls below this, however much the penalties take off it.
         *
         * A twentieth of [PRIOR_FLOOR]: small enough that a heavily penalised move is genuinely last,
         * large enough that the search will still try it once the moves above it stop paying.
         */
        const val PRIOR_MINIMUM: Double = 0.05

        /**
         * The furthest below the best score a softmax will look, which is `1.6e-28` of its weight.
         *
         * Reached only at a temperature near its own floor, and it is a guard on [portableExp]'s
         * declared range rather than a shaping constant: what separates the moves is the temperature.
         */
        const val EXPONENT_FLOOR: Double = -64.0
    }
}
