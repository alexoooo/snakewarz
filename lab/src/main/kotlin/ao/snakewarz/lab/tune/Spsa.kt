package ao.snakewarz.lab.tune

import ao.snakewarz.core.random.SplitMix64
import kotlin.math.min

/**
 * Simultaneous perturbation stochastic approximation: a gradient from **two** measurements, whatever
 * the dimension.
 *
 * ### Why not coordinate descent
 *
 * `TuneCommand` moves one knob at a time and asks a sequential test whether that was an improvement.
 * That is the right shape at two or three knobs — every step is a decision somebody can read, and a
 * `Choice` needs no special case — and it is the wrong shape at ten, because a pass costs a test per
 * knob per stride and the knobs interact, so the pass has to be repeated until the point stops
 * moving. SPSA costs two measurements per step *regardless of dimension*: it perturbs every
 * coordinate at once along a random sign vector and reads the whole gradient off the one difference.
 * The estimate is terrible on any single iteration and unbiased over many, which is exactly the
 * trade a noisy objective wants.
 *
 * ### The measurement is paired, and that is most of why this works
 *
 * A gap is measured by playing the two arms **against each other** over one set of boards, so a
 * board that is hard for one is hard for the other and the board's own difficulty cancels inside the
 * difference rather than adding to it. Common random numbers are worth more here than any amount of
 * schedule tuning: an unpaired design would have to average away the variance between openings,
 * which dwarfs the difference two knob settings make. See `SpsaTest`, which measures the ratio.
 *
 * ### Nothing here knows what a knob is
 *
 * Coordinates are plain numbers with a [lower] and an [upper], and the caller decides what a unit
 * means. `SpsaCommand` hands it declared steps, so [SpsaSchedule]'s numbers are steps; a test hands
 * it whatever an analytic objective is defined over. That seam is what lets a stochastic optimiser
 * be verified against a function with a known answer instead of against a game, where a broken
 * optimiser and a flat objective look identical.
 *
 * ### At a boundary the pair slides, and the point projects
 *
 * Two different things happen at an edge and conflating them is the classic way to bias a run.
 *
 * A **perturbation** that would leave the box is not clamped: clamping one arm and not the other
 * shortens the chord on one side, and the difference quotient then divides by a separation it does
 * not have, reading a gradient that is systematically wrong in the direction of the wall. Instead
 * the whole pair slides inward as a rigid body, so the two arms stay exactly `2c` apart and the
 * estimate is an honest gradient of a point a little inside the wall. Where the box itself is
 * narrower than the pair, the pair shrinks to fit and the divisor shrinks with it.
 *
 * The **point** is projected — `coerceIn` — which is standard for constrained SPSA and is a real
 * choice with a real consequence: a run whose optimum is outside the declared range parks on the
 * boundary and stays there, so a knob pinned at its own `min` or `max` in the result is a bot asking
 * for a range it was never given rather than a value that was searched for.
 *
 * ### The trajectory always ends somewhere, which is not the same as finding something
 *
 * A search over an objective that is genuinely flat in a coordinate has nothing pulling its iterate
 * back, so the walk drifts and eventually parks against a bound — and that reads exactly like a
 * result. [parked] is what names that shape. What the point is actually worth is the confirming
 * run's job, and nothing short of fresh boards can do it.
 *
 * ### What a run answers with is the tail, not the last step
 *
 * [point] is where the iterate happens to be standing, and on a noisy objective that is a sample of
 * a random walk however long the run was — the gain decays but never reaches zero. [settled] is the
 * mean of the last [TAIL] of the trajectory, which is Polyak–Ruppert averaging: the noise in the
 * individual steps is largely independent and averages out, while the drift the search found does
 * not. It costs nothing, and it is the honest alternative to the two wrong answers — the last
 * iterate, and the best-looking one, which is the maximum of a noise process by construction.
 *
 * The tail is a quarter rather than the textbook half because averaging *lags*: a run still
 * travelling when it stops has a second half spread along the way there, and its mean sits behind
 * the point. A quarter is what leaves the lag smaller than the noise it removes on a run that has
 * roughly arrived, which is the run this is worth doing at all on.
 */
internal class Spsa(
    start: DoubleArray,
    private val lower: DoubleArray,
    private val upper: DoubleArray,
    private val schedule: SpsaSchedule,
    private val seed: Long,
) {
    private val at: DoubleArray = start.copyOf()

    private val tail: DoubleArray = DoubleArray(start.size)
    private var tailCount: Int = 0

    init {
        require(start.isNotEmpty()) { "a search needs a dimension to search in" }
        require(lower.size == start.size && upper.size == start.size) {
            "a bound per coordinate: ${lower.size} and ${upper.size} against ${start.size}"
        }
        for (i in start.indices) {
            require(upper[i] > lower[i]) { "coordinate $i has nowhere to go: ${lower[i]}..${upper[i]}" }
            require(start[i] in lower[i]..upper[i]) { "coordinate $i starts outside ${lower[i]}..${upper[i]}" }
        }
    }

    val dimensions: Int get() = at.size

    /** Where the iterate stands right now, as a copy — a caller may read it and may not move it. */
    fun point(): DoubleArray = at.copyOf()

    /**
     * What the run answers with: the mean of the last [TAIL] of the trajectory.
     *
     * The whole trajectory is inside the box and so is any average of part of it, so this needs no
     * projection of its own. Before the tail begins it is simply [point].
     */
    fun settled(): DoubleArray =
        if (tailCount == 0) point() else DoubleArray(dimensions) { tail[it] / tailCount }

    /**
     * The two points to measure on [iteration], and everything needed to read the answer.
     *
     * A pure function of the search's position, the schedule and [seed]: the sign vector comes from
     * a stream forked per iteration rather than from one consumed in order, so a run resumed from a
     * journal draws the same directions as the run that wrote it whatever it replayed to get there.
     */
    fun probe(iteration: Int): Probe {
        require(iteration >= 0) { "an iteration is not negative, was $iteration" }

        val rng = SplitMix64(seed).fork(iteration)
        val wanted = schedule.spreadOn(iteration)
        val delta = IntArray(dimensions)
        val half = DoubleArray(dimensions)
        val plus = DoubleArray(dimensions)
        val minus = DoubleArray(dimensions)

        for (i in 0 until dimensions) {
            delta[i] = if (rng.nextInt(RADEMACHER) == 0) -1 else 1
            half[i] = min(wanted, (upper[i] - lower[i]) / 2.0)

            val centre = at[i].coerceIn(lower[i] + half[i], upper[i] - half[i])
            // Rounding can put a slid arm an ulp outside the box it was slid into, and a knob reader
            // range-checks what it is handed. The separation this costs is an ulp, not a policy.
            plus[i] = (centre + half[i] * delta[i]).coerceIn(lower[i], upper[i])
            minus[i] = (centre - half[i] * delta[i]).coerceIn(lower[i], upper[i])
        }

        return Probe(iteration, delta, half, plus, minus)
    }

    /**
     * Moves the point along the gradient [gap] implies, and says how far each coordinate went.
     *
     * [gap] is the score of [Probe.plus] minus the score of [Probe.minus], in `-1.0..1.0`, so this
     * ascends: a positive gap moves toward the plus arm. The reported displacement is what actually
     * happened after projection, which is zero on a coordinate already parked against its bound.
     */
    fun apply(probe: Probe, gap: Double): DoubleArray {
        require(gap.isFinite()) { "a gap is a measured score difference, was $gap" }

        val gain = schedule.gainOn(probe.iteration)
        val moved = DoubleArray(dimensions)

        for (i in 0 until dimensions) {
            val gradient = gap / (2.0 * probe.half[i] * probe.delta[i])
            val next = (at[i] + gain * gradient).coerceIn(lower[i], upper[i])
            moved[i] = next - at[i]
            at[i] = next
        }

        if (probe.iteration + 1 > schedule.iterations * (1.0 - TAIL)) {
            for (i in 0 until dimensions) {
                tail[i] += at[i]
            }
            tailCount++
        }

        return moved
    }

    /**
     * Whether [coordinate] ended close enough to one of its own bounds to be written as that bound.
     *
     * The signature of a coordinate the objective never held. With nothing pulling it back the
     * iterate is a walk, and a walk in a box ends at a wall — so a knob whose recommendation is its
     * own declared `min` or `max` is either a knob that does not matter or a knob whose best value
     * was never in range, and the trajectory cannot tell you which. Either way it is not a search
     * result, and it is worth saying so before the point is read.
     *
     * Exact rather than statistical on purpose. The obvious inferential version — compare the run's
     * cumulative push against a walk of the same steps — is unsound here, because it loses power as
     * the search converges: past the point of arrival every further iteration adds noise variance
     * and no signal, so a long, *successful* run on a real knob reads as nothing there. An
     * instrument that mislabels its own successes is worse than none. What the objective was
     * actually worth is the confirming run's job, and it is a job that needs fresh boards anyway.
     */
    fun parked(coordinate: Int): Boolean {
        val at = settled()[coordinate]
        return at - lower[coordinate] <= AT_THE_WALL || upper[coordinate] - at <= AT_THE_WALL
    }

    override fun toString(): String = "Spsa($dimensions coordinates, $schedule)"

    /** One iteration's two arms, and the geometry the answer has to be divided by. */
    class Probe(
        val iteration: Int,
        /** The sign vector, `+1` or `-1` per coordinate. */
        val delta: IntArray,
        /** How far each arm actually sits from the centre, after any slide or squeeze. */
        val half: DoubleArray,
        val plus: DoubleArray,
        val minus: DoubleArray,
    ) {
        /** The sign vector as something a journal row can hold and a person can compare. */
        val signs: String get() = delta.joinToString("") { if (it > 0) "+" else "-" }

        override fun toString(): String = "Probe($iteration, $signs)"
    }

    companion object {
        /** The fraction of the trajectory [settled] averages — see the class doc. */
        const val TAIL: Double = 0.25

        /** A sign vector is drawn from `{-1, +1}`, which is what makes the estimate unbiased. */
        private const val RADEMACHER = 2

        /**
         * Half a unit, which for a caller counting in declared steps is where rounding lands on the
         * bound — so [parked] means "the recommendation names the wall", not "it is near it".
         */
        private const val AT_THE_WALL = 0.5
    }
}
