package ao.snakewarz.lab.tune

import kotlin.math.pow

/**
 * How far [Spsa] looks on each side of where it stands, and how far it moves on what it sees.
 *
 * Two decaying sequences, which is the whole of SPSA's convergence argument: the perturbation has to
 * shrink so the difference quotient stops being a chord across a hill and starts being a gradient,
 * and the gain has to shrink faster so the accumulated noise is summable. The exponents are Spall's
 * asymptotically optimal pair — [DECAY] on the perturbation, [FADE] on the gain — and [SETTLING] is
 * his rule of thumb for keeping the first few steps from bolting, expressed as a fraction of the run.
 *
 * ### Everything here is counted in the knob's own declared steps
 *
 * A knob says its own step, and that step is the finest change worth *expressing* — the unit the
 * whole tuning loop already speaks, and what `KnobSpace`'s stride is measured in. So [spread] and
 * [stride] are numbers of declared steps and mean the same thing on a `cpuct` that moves in tenths
 * as on a weight that moves in twentieths.
 *
 * The floor at one step is not a detail. An entrant spec carries a knob at the precision its step
 * implies, so two arms less than a step apart snap to the *same* spec, and a batch of a bot against
 * a bit-identical copy of itself measures the seating and reports a gradient of zero.
 *
 * ### What [stride] means, exactly
 *
 * SPSA's raw gain has no interpretable units — it multiplies a difference of win rates divided by a
 * perturbation — so it is stated here as a **bound**, which is the thing a person can both picture
 * and reason about: [stride] is the most a coordinate moves on the first iteration, reached only
 * when one arm won every board. A gap of a tenth moves a tenth of it. The gain that produces that
 * is solved for once, in [gain], and decays from there.
 *
 * A bound rather than a typical move, because on six boards the *typical* gap is mostly sampling
 * noise: two arms of equal strength differ by about `0.3` over twelve games. Anchoring the gain on
 * anything smaller than the noise floor makes a run's step size a property of how noisy its batches
 * were, and the trajectory then random-walks across the whole range and calls it a search.
 */
internal class SpsaSchedule(
    /** How many gradient steps the run will take. [SETTLING] is a fraction of this. */
    val iterations: Int,
    /** Declared steps between the centre and each arm on the first iteration. */
    val spread: Double,
    /** The most a coordinate moves on the first iteration, in declared steps. */
    val stride: Double,
) {
    init {
        require(iterations > 0) { "a search takes at least one iteration, was $iterations" }
        require(spread >= MINIMUM_SPREAD) {
            "the two arms have to differ by a declared step or they are the same entrant, was $spread"
        }
        require(stride > 0.0) { "a search that cannot move measures nothing, was $stride" }
    }

    private val settling: Double = SETTLING * iterations

    /** The raw SPSA gain, solved so that [stride] means what its documentation says it means. */
    private val gain: Double = stride * 2.0 * spread * (1.0 + settling).pow(FADE) / WHOLE_GAP

    /** How far each arm sits from the centre on [iteration], never below a declared step. */
    fun spreadOn(iteration: Int): Double =
        (spread / (iteration + 1.0).pow(DECAY)).coerceAtLeast(MINIMUM_SPREAD)

    /** What the gradient estimate is multiplied by on [iteration]. */
    fun gainOn(iteration: Int): Double = gain / (iteration + 1.0 + settling).pow(FADE)

    override fun toString(): String =
        "SpsaSchedule($iterations iterations, spread $spread, stride $stride)"

    companion object {
        /**
         * The gap that buys a full [stride]: one arm won every board of the iteration.
         *
         * The largest a gap can be, so a stride is a ceiling on the movement rather than a typical
         * movement, and a run's step size cannot be set by how noisy its batches happened to be.
         */
        const val WHOLE_GAP: Double = 1.0

        /**
         * Below one declared step the two arms write the same entrant spec — see the class doc.
         *
         * Not merely a floor on precision: a batch of an entrant against a copy of itself is refused
         * outright by `TournamentConfig`, so this is what stops a long run ending in a throw.
         */
        const val MINIMUM_SPREAD: Double = 1.0

        /** Spall's perturbation exponent. Slow, because a gradient needs a chord it can still see. */
        private const val DECAY = 0.101

        /** And his gain exponent, faster, so the steps are summable and the noise is not. */
        private const val FADE = 0.602

        /**
         * A tenth of the run spent settling, which is what stops the first estimate deciding the run.
         *
         * The gain is largest at iteration zero and the gradient estimate is worst there — one pair
         * of eight-board measurements — so without this a single unlucky opening block throws the
         * point across its range and every later iteration is spent walking back.
         */
        private const val SETTLING = 0.1
    }
}
