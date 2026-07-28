package ao.snakewarz.lab.strength

import ao.snakewarz.match.tournament.Ratings
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A sequential test: play until the evidence decides, rather than for a number of games chosen up
 * front.
 *
 * ### Why sequential
 *
 * A fixed batch has to be sized for the smallest difference worth finding, so almost every batch is
 * far larger than the case in front of it needed — a change that is plainly bad is plainly bad after
 * forty pairs, and one that is plainly good is too. A sequential test stops as soon as either
 * hypothesis is enough ahead, which for a tuning loop is the difference between a dozen experiments a
 * session and two.
 *
 * ### What it is testing
 *
 * Two hypotheses, both of which have to be *stated*: `H0`, that the candidate is no better than
 * [elo0], against `H1`, that it is at least [elo1]. "Is it better than zero" is not a testable
 * hypothesis on its own — every change is different from zero at some sample size — so the interval
 * between the two bounds is where the test is allowed to say "not worth the games".
 *
 * The likelihood ratio is the normal approximation over pair scores, using the sample's **own**
 * variance:
 * ```
 * LLR = pairs * (s1 - s0) * (mean - (s0 + s1) / 2) / variance
 * ```
 * where `s0` and `s1` are the Elo bounds expressed as scores. Accept `H1` above
 * `ln((1 - beta) / alpha)`, accept `H0` below `ln(beta / (1 - alpha))`, keep playing between them.
 *
 * ### Two things it will get wrong if allowed to
 *
 * The variance is estimated from the same sample that decides, so a lucky first handful can look
 * conclusive twice over — once in the mean and once by understating the spread. That is what
 * [Sprt.MINIMUM_PAIRS] is for, and it is not optional.
 *
 * And the bounds are in **raw** Elo, so the same bound is a different amount of evidence at different
 * draw rates — and drawishness varies enormously here with the board and the pairing. [Report.effect]
 * is the sample's standardized effect size, which is the draw-rate-free version of the same
 * statement, and it is printed beside every verdict so the cost of a bound is visible rather than
 * assumed.
 */
internal class Sprt(
    val elo0: Double,
    val elo1: Double,
    val alpha: Double,
    val beta: Double,
) {
    init {
        require(elo0 < elo1) { "the bounds have to leave room to be undecided, was $elo0..$elo1" }
        require(alpha > 0.0 && alpha < 1.0) { "alpha is a probability, was $alpha" }
        require(beta > 0.0 && beta < 1.0) { "beta is a probability, was $beta" }
    }

    /** Below this the candidate is no better than [elo0]. */
    val lower: Double = ln(beta / (1.0 - alpha))

    /** Above this the candidate is at least [elo1]. */
    val upper: Double = ln((1.0 - beta) / alpha)

    fun test(scores: List<Double>): Report {
        if (scores.isEmpty()) {
            return Report(0, 0.0, 0.5, 0.0, 0.0, lower, upper, Verdict.UNDECIDED)
        }

        val mean = scores.sum() / scores.size
        var squares = 0.0
        for (score in scores) {
            squares += (score - mean) * (score - mean)
        }
        // The population variance of the sample, floored so a run of identical pairs -- which is
        // overwhelming evidence, not a division by zero -- produces a very large ratio and not a NaN.
        val variance = (squares / scores.size).coerceAtLeast(MINIMUM_VARIANCE)

        val s0 = scoreOf(elo0)
        val s1 = scoreOf(elo1)
        val llr = scores.size * (s1 - s0) * (mean - (s0 + s1) / 2.0) / variance

        return Report(
            pairs = scores.size,
            llr = llr,
            mean = mean,
            variance = variance,
            effect = (mean - EVEN) / sqrt(variance),
            lower = lower,
            upper = upper,
            verdict = when {
                scores.size < MINIMUM_PAIRS -> Verdict.UNDECIDED
                llr >= upper -> Verdict.BETTER
                llr <= lower -> Verdict.NO_BETTER
                else -> Verdict.UNDECIDED
            },
        )
    }

    override fun toString(): String = "Sprt($elo0..$elo1 Elo, alpha=$alpha, beta=$beta)"

    /** What the evidence so far says, and what it is made of. */
    class Report(
        val pairs: Int,
        val llr: Double,
        val mean: Double,
        val variance: Double,
        /**
         * The sample's standardized effect: how many standard deviations of a board the mean sits
         * above even.
         *
         * The draw-rate-free statement of the same result. Two pairings can show the same Elo and
         * need wildly different numbers of games to prove it, and this is the number that says which
         * one you are in.
         */
        val effect: Double,
        val lower: Double,
        val upper: Double,
        val verdict: Verdict,
    ) {
        /** The observed difference in Elo, or `null` where the sample is all wins or all losses. */
        val elo: Double? = eloOf(mean)

        /** Half-width of a 95% interval on [elo], by the delta method. `null` where [elo] is. */
        val eloMargin: Double? =
            if (elo == null || pairs == 0) {
                null
            } else {
                // The logistic's slope at the observed mean, times the standard error of that mean.
                CONFIDENCE_Z * eloSlope(mean) * sqrt(variance / pairs)
            }

        override fun toString(): String = "$verdict after $pairs pairs, LLR ${llr.toInt()}"
    }

    enum class Verdict {
        /** The candidate is at least `elo1` better. */
        BETTER,

        /** The candidate is no better than `elo0`. */
        NO_BETTER,

        /** Neither, yet. */
        UNDECIDED,
    }

    companion object {
        /**
         * Pairs that have to be played before a verdict is allowed, however clear it looks.
         *
         * The test estimates its own variance, so a first handful that happens to agree with itself
         * understates the spread and overstates the evidence at the same time. Forty boards is cheap
         * — seconds for reactive bots, a minute or so for searchers — and it is the difference
         * between a tuner that converges and one that ratchets on noise.
         */
        const val MINIMUM_PAIRS: Int = 40

        /** A sample this uniform is conclusive; the floor only stops it being a division by zero. */
        private const val MINIMUM_VARIANCE = 1e-6

        private const val EVEN = 0.5
        private const val CONFIDENCE_Z = 1.96

        /** An Elo difference as an expected score, in `0.0..1.0`. */
        fun scoreOf(elo: Double): Double = 1.0 / (1.0 + exp(-elo / Ratings.SCALE * LN_TEN))

        /** An expected score back as Elo, or `null` at the ends where it is unbounded. */
        fun eloOf(score: Double): Double? =
            if (score <= 0.0 || score >= 1.0) null else -Ratings.SCALE * log10(1.0 / score - 1.0)

        /** `d(elo)/d(score)` at [score], for putting an interval on an Elo read off a mean. */
        private fun eloSlope(score: Double): Double = Ratings.SCALE / LN_TEN / (score * (1.0 - score))

        private val LN_TEN = ln(10.0)
    }
}
