package ao.snakewarz.lab.tune

import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.lab.strength.Sprt
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A stochastic optimiser is verified against a function whose answer is known, never against the
 * game it was written for.
 *
 * On the real objective a broken optimiser and a flat objective produce the same picture — a point
 * that wanders and a confirming run that says nothing — and there is no way to tell them apart
 * except by spending hours. An analytic bowl has an optimum somebody can write down, runs in
 * milliseconds, and can be made noisy in a way that models where the noise in a batch of matches
 * actually comes from. Everything about the method that could be wrong is wrong here first.
 *
 * [Bowl] is the objective: strengths in Elo, turned into the paired score gap [SpsaCommand] measures
 * by the same logistic `Sprt` uses, so a gap here means what a gap there means.
 */
class SpsaTest {
    @Test
    fun `it climbs a smooth bowl to the optimum, from the far side of the range`() {
        val bowl = Bowl(doubleArrayOf(70.0, 25.0))
        val search = searchOver(bowl.optimum.size, start = 10.0, iterations = ITERATIONS, stride = FAST)

        for (iteration in 0 until ITERATIONS) {
            val probe = search.probe(iteration)
            search.apply(probe, bowl.gap(probe.plus, probe.minus))
        }

        assertNear(bowl.optimum, search.settled(), TOLERANCE)
    }

    @Test
    fun `the same run costs the same two measurements at eight coordinates as at one`() {
        // The whole reason this exists beside coordinate descent, and the claim a test can settle
        // cheaply: the cost is two evaluations per iteration whatever the dimension, and the search
        // still arrives. A pass of coordinate descent over eight knobs is eight sequential tests,
        // and it has to be repeated because the knobs interact.
        val bowl = Bowl(doubleArrayOf(70.0, 25.0, 55.0, 40.0, 88.0, 12.0, 63.0, 31.0))
        val search = searchOver(bowl.optimum.size, start = 50.0, iterations = ITERATIONS * 2, stride = FAST)
        var measurements = 0

        for (iteration in 0 until ITERATIONS * 2) {
            val probe = search.probe(iteration)
            measurements += 2
            search.apply(probe, bowl.gap(probe.plus, probe.minus))
        }

        assertEquals(ITERATIONS * 4, measurements, "two per iteration, and the dimension does not enter")
        assertNear(bowl.optimum, search.settled(), WIDE_TOLERANCE)
    }

    @Test
    fun `it still arrives when every measurement is a handful of noisy boards`() {
        // The real objective is a win rate over six boards, which is coarse and points the wrong way
        // a third of the time. SPSA needs no single estimate to be right, only to be right on
        // average, and the averaged tail is what turns that into an answer.
        //
        // At the shipped defaults, and over the distance a real knob sits from its optimum -- `cpuct`
        // ships at fifteen of its own steps and the values worth trying are five to thirty.
        val bowl = Bowl(doubleArrayOf(70.0, 25.0))
        var total = 0.0

        for (run in 0 until RUNS) {
            total += distance(bowl.optimum, noisyRun(bowl, run).settled())
        }

        println("[bench] spsa at the defaults -- ${round(total / RUNS)} away after $ITERATIONS iterations")
        assertTrue(
            total / RUNS < NOISY_TOLERANCE,
            "settling ${total / RUNS} from the answer is not a search",
        )
    }

    @Test
    fun `the averaged tail is a better answer than wherever the last step landed`() {
        // A tuner that reports the iterate it happened to stop on is reporting one sample of a random
        // walk, and one that reports the best it ever saw is reporting the maximum of a noise
        // process. Averaging the tail is the answer to both, and this is the evidence for it rather
        // than an appeal to the literature.
        //
        // Over a run long enough to have *arrived*, which is the condition and not a detail:
        // averaging lags, so a trajectory still travelling when it stops has a tail spread along the
        // way there and the last step is the better answer. Measured on this objective the two are
        // level at 200 iterations and the tail wins clearly by 400 -- which is another way of saying
        // that a run where they disagree is a run that wanted more iterations.
        val bowl = Bowl(doubleArrayOf(70.0, 25.0))
        var settled = 0.0
        var last = 0.0

        for (run in 0 until RUNS) {
            val search = noisyRun(bowl, run, ITERATIONS * 2)
            settled += distance(bowl.optimum, search.settled())
            last += distance(bowl.optimum, search.point())
        }

        println("[bench] spsa over $RUNS runs -- settled ${round(settled / RUNS)}, last ${round(last / RUNS)}")
        assertTrue(settled < last, "averaging the tail should beat the last step, was $settled against $last")
    }

    @Test
    fun `common random numbers are worth an order of magnitude of games`() {
        // The measurement behind the claim in Spsa's KDoc, and the reason the two arms play each
        // other rather than each playing a common reference. A board's own difficulty is far larger
        // than the difference two settings make: shared, it cancels inside the difference; separate,
        // it has to be averaged away before the difference can be seen at all.
        //
        // Measured as a signal-to-noise ratio at one fixed point rather than as convergence, because
        // the two designs produce gaps on *different scales* and the gain rescales a scale away. What
        // survives rescaling is how many standard deviations of measurement the signal is worth, and
        // noise falls as the square root of the games, so the square of the ratio is the price.
        val bowl = Bowl(doubleArrayOf(70.0, 25.0))
        val field = Boards(seed = 99L)
        val probe = searchOver(2, start = 10.0, iterations = 100).probe(0)

        val paired = ratio(REPEATS) { field.paired(bowl, probe, it, BOARDS) }
        val unpaired = ratio(REPEATS) { field.unpaired(bowl, probe, it, BOARDS) }

        // Reported rather than merely asserted: it is the number a run's cost is set from.
        println(
            "[bench] spsa gradient over $BOARDS boards -- signal/noise paired ${round(paired)}, " +
                "unpaired ${round(unpaired)}, so ${round(paired * paired / (unpaired * unpaired))}x the games",
        )
        assertTrue(
            paired > unpaired * 2.0,
            "pairing should be worth about an order of magnitude of games, was $paired against $unpaired",
        )
    }

    @Test
    fun `a flat objective still carries the point a long way, which is why the point is not an answer`() {
        // The failure mode that reads most like a result. With nothing pulling it back the iterate
        // is a walk, and a walk goes somewhere -- so a run over a knob that does not matter finishes
        // holding a confident-looking value produced by nothing at all. Nothing inside the search
        // can tell that from a search that worked; only fresh boards can, which is what makes the
        // confirming run non-optional rather than a courtesy.
        val length = ITERATIONS * 2
        val bowl = Bowl(doubleArrayOf(70.0, 25.0))
        val level = Bowl(doubleArrayOf(70.0, 25.0), peak = 0.0)
        val field = Boards(seed = 5L)
        val real = searchOver(2, start = NEARBY, iterations = length)
        val none = searchOver(2, start = NEARBY, iterations = length)

        for (iteration in 0 until length) {
            val probe = real.probe(iteration)
            real.apply(probe, field.paired(bowl, probe, iteration, BOARDS))

            // The same boards over an objective with nothing under it: pure sampling, every time.
            val blind = none.probe(iteration)
            none.apply(blind, field.paired(level, blind, iteration, BOARDS))
        }

        println("[bench] spsa on a flat objective -- walked to ${none.settled().toList()}")
        assertTrue(
            distance(bowl.optimum, none.settled()) > distance(bowl.optimum, real.settled()) * 3.0,
            "a walk over nothing landed as well as a search: ${none.settled().toList()}",
        )
        assertTrue(
            distance(doubleArrayOf(NEARBY, NEARBY), none.settled()) > NOISY_TOLERANCE,
            "the walk barely moved, so this says nothing about a walk that does",
        )
    }

    @Test
    fun `a run is reproducible from its seed, and a different seed is a different run`() {
        val bowl = Bowl(doubleArrayOf(70.0, 25.0))

        val once = trajectory(bowl, seed = 7L)
        val again = trajectory(bowl, seed = 7L)
        val other = trajectory(bowl, seed = 8L)

        assertEquals(once, again, "the same seed has to walk the same path")
        assertTrue(once != other, "a different seed has to draw different directions")
    }

    @Test
    fun `an iteration is a pure function of the seed, not of what was replayed to reach it`() {
        // What a resume depends on. The sign vector comes from a stream forked per iteration rather
        // than one consumed in order, so a search that read its first forty gaps back from a journal
        // proposes the same forty-first arms as the run that wrote them.
        val fresh = searchOver(2, start = 10.0, iterations = 100)
        val walked = searchOver(2, start = 10.0, iterations = 100)
        for (iteration in 0 until 40) {
            walked.apply(walked.probe(iteration), 0.0)
        }

        assertEquals(fresh.probe(40).signs, walked.probe(40).signs)
    }

    @Test
    fun `both arms stay inside the box, and stay exactly the same distance apart`() {
        // Clamping one arm and not the other shortens the chord on one side, and the difference
        // quotient then divides by a separation it does not have. The pair slides instead.
        val search = Spsa(
            start = doubleArrayOf(0.0, 100.0, 50.0),
            lower = doubleArrayOf(0.0, 0.0, 0.0),
            upper = doubleArrayOf(100.0, 100.0, 100.0),
            schedule = SpsaSchedule(iterations = 40, spread = 8.0, stride = 2.0),
            seed = 3L,
        )

        for (iteration in 0 until 40) {
            val probe = search.probe(iteration)
            for (i in 0 until 3) {
                assertTrue(probe.plus[i] in 0.0..100.0, "plus arm left the box at $iteration: ${probe.plus[i]}")
                assertTrue(probe.minus[i] in 0.0..100.0, "minus arm left the box at $iteration: ${probe.minus[i]}")
                assertEquals(2.0 * probe.half[i], abs(probe.plus[i] - probe.minus[i]), 1e-9)
            }
            search.apply(probe, 0.05)
        }
    }

    @Test
    fun `an optimum outside the declared range parks the point on the boundary, and says so`() {
        // The consequence of projecting rather than clamping, and it is a result rather than a
        // failure: a knob sitting on its own min or max in a recommendation is a bot asking for a
        // range it was never given -- or one the objective never held. Which of the two is a
        // question for fresh boards, and `parked` is what puts it.
        val bowl = Bowl(doubleArrayOf(180.0))
        val search = searchOver(1, start = 50.0, iterations = ITERATIONS, stride = FAST)

        for (iteration in 0 until ITERATIONS) {
            val probe = search.probe(iteration)
            search.apply(probe, bowl.gap(probe.plus, probe.minus))
        }

        assertEquals(UPPER, search.point()[0], 1e-9)
        assertTrue(search.parked(0), "settled at ${search.settled()[0]} of $UPPER")
    }

    @Test
    fun `a coordinate with nowhere to go, or a start outside the box, is refused`() {
        val schedule = SpsaSchedule(iterations = 10, spread = 8.0, stride = 2.0)

        assertFailsWith<IllegalArgumentException> {
            Spsa(doubleArrayOf(1.0), doubleArrayOf(1.0), doubleArrayOf(1.0), schedule, seed = 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            Spsa(doubleArrayOf(5.0), doubleArrayOf(0.0), doubleArrayOf(1.0), schedule, seed = 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            Spsa(doubleArrayOf(), doubleArrayOf(), doubleArrayOf(), schedule, seed = 1L)
        }
    }

    // -- the objective

    /**
     * A smooth peak in Elo, read as the paired score gap a batch of matches would report.
     *
     * Quadratic in the distance from [optimum], which is what a well-behaved tuning surface looks
     * like near its top, and scaled so a coordinate at the far end of its range is [PEAK] Elo weaker
     * than a coordinate at the optimum — the order of magnitude a real knob is worth.
     */
    private class Bowl(val optimum: DoubleArray, val peak: Double = PEAK) {
        fun strength(at: DoubleArray): Double {
            var sum = 0.0
            for (i in at.indices) {
                val away = (at[i] - optimum[i]) / (UPPER - LOWER)
                sum += away * away
            }
            return -peak * sum
        }

        /** What one arm scores against the other, as `SpsaCommand` computes it from a batch. */
        fun gap(plus: DoubleArray, minus: DoubleArray): Double =
            2.0 * (Sprt.scoreOf(strength(plus) - strength(minus)) - EVEN)
    }

    /**
     * A field of boards, each with a bias of its own that whoever plays on it inherits.
     *
     * The mechanism common random numbers exist to defeat, modelled rather than parameterised: the
     * bias is a property of the **board**, so two arms sharing a board both carry it and it cancels
     * in the difference, while two arms on different boards each carry a different one and it does
     * not. Boards are drawn from their own seeds, so both designs below see the same field.
     */
    private class Boards(private val seed: Long) {
        /** How much this board favours whoever is on it, in Elo. */
        fun bias(board: Long): Double = gaussian(SplitMix64(seed).fork(board.toInt())) * BOARD_SPREAD

        /**
         * Both arms on the same boards, playing each other — what the arena actually does.
         *
         * Two settings only differ on the boards where they actually choose differently, and on the
         * rest they play each other move for move and split exactly. That is not a modelling
         * flourish: it is the signature `AbCommand.blindness` looks for, and it is where most of the
         * variance reduction comes from, because a board that splits by construction contributes
         * nothing to the spread at all.
         */
        fun paired(bowl: Bowl, probe: Spsa.Probe, iteration: Int, boards: Int): Double {
            val expected = Sprt.scoreOf(bowl.strength(probe.plus) - bowl.strength(probe.minus))
            // Concentrated onto the boards that diverge, so the mean is unchanged and the boards
            // that do not diverge are exact splits rather than coin flips.
            val whenDiverged = (EVEN + (expected - EVEN) / DIVERGENCE).coerceIn(0.0, 1.0)
            val rng = SplitMix64(seed).fork(PLAY_STREAM + iteration)

            var scored = 0.0
            for (board in 0 until boards) {
                scored += if (rng.nextDouble() < DIVERGENCE) played(whenDiverged, rng) else EVEN
            }
            return 2.0 * (scored / boards - EVEN)
        }

        /** Each arm against a common reference, on a board set of its own. */
        fun unpaired(bowl: Bowl, probe: Spsa.Probe, iteration: Int, boards: Int): Double {
            val rng = SplitMix64(seed).fork(PLAY_STREAM + iteration)
            val first = iteration.toLong() * boards * 2
            return against(bowl, probe.plus, first, boards, rng) -
                against(bowl, probe.minus, first + boards, boards, rng)
        }

        private fun against(bowl: Bowl, arm: DoubleArray, from: Long, boards: Int, rng: Rng): Double {
            var scored = 0.0
            for (board in 0 until boards) {
                scored += played(Sprt.scoreOf(bowl.strength(arm) + bias(from + board) - REFERENCE), rng)
            }
            return scored / boards
        }

        /** One board, played from both seats — so it scores `0`, a half or `1`, as a real one does. */
        private fun played(expected: Double, rng: Rng): Double {
            var won = 0
            for (seat in 0 until SEATS) {
                if (rng.nextDouble() < expected) {
                    won++
                }
            }
            return won.toDouble() / SEATS
        }
    }

    // -- scaffolding

    private fun searchOver(dimensions: Int, start: Double, iterations: Int, stride: Double = STRIDE): Spsa =
        Spsa(
            start = DoubleArray(dimensions) { start },
            lower = DoubleArray(dimensions) { LOWER },
            upper = DoubleArray(dimensions) { UPPER },
            schedule = SpsaSchedule(iterations, spread = 8.0, stride = stride),
            seed = 11L,
        )

    /** One run at the shipped defaults, over the distance a real knob sits from its optimum. */
    private fun noisyRun(bowl: Bowl, run: Int, iterations: Int = ITERATIONS): Spsa {
        val field = Boards(seed = 1_000L + run)
        val search = Spsa(
            start = DoubleArray(bowl.optimum.size) { NEARBY },
            lower = DoubleArray(bowl.optimum.size) { LOWER },
            upper = DoubleArray(bowl.optimum.size) { UPPER },
            schedule = SpsaSchedule(iterations, spread = 8.0, stride = STRIDE),
            seed = 11L + run,
        )

        for (iteration in 0 until iterations) {
            val probe = search.probe(iteration)
            search.apply(probe, field.paired(bowl, probe, iteration, BOARDS))
        }
        return search
    }

    /** How many standard deviations of one measurement the signal in it is worth. */
    private fun ratio(repeats: Int, measure: (Int) -> Double): Double {
        val gaps = DoubleArray(repeats) { measure(it) }
        val mean = gaps.average()
        var squares = 0.0
        for (gap in gaps) {
            squares += (gap - mean) * (gap - mean)
        }
        return abs(mean) / sqrt(squares / repeats)
    }

    private fun round(value: Double): String = "${(value * 100).toInt() / 100.0}"

    private fun trajectory(bowl: Bowl, seed: Long): List<String> {
        val search = Spsa(
            start = DoubleArray(bowl.optimum.size) { 10.0 },
            lower = DoubleArray(bowl.optimum.size) { LOWER },
            upper = DoubleArray(bowl.optimum.size) { UPPER },
            schedule = SpsaSchedule(iterations = 30, spread = 8.0, stride = 2.0),
            seed = seed,
        )

        return buildList {
            for (iteration in 0 until 30) {
                val probe = search.probe(iteration)
                search.apply(probe, bowl.gap(probe.plus, probe.minus))
                add(probe.signs + " " + search.point().joinToString(",") { it.toString() })
            }
        }
    }

    private fun distance(optimum: DoubleArray, at: DoubleArray): Double {
        var sum = 0.0
        for (i in optimum.indices) {
            sum += (at[i] - optimum[i]) * (at[i] - optimum[i])
        }
        return sqrt(sum)
    }

    private fun assertNear(optimum: DoubleArray, at: DoubleArray, tolerance: Double) {
        assertTrue(
            distance(optimum, at) < tolerance,
            "settled at ${at.toList()} rather than ${optimum.toList()}",
        )
    }

    private companion object {
        const val LOWER = 0.0
        const val UPPER = 100.0

        /** Elo between the optimum and the far end of one coordinate's range. */
        const val PEAK = 400.0

        /** How much a board favours whoever plays on it, in Elo. Larger than any knob is worth. */
        const val BOARD_SPREAD = 150.0

        /**
         * How often two settings actually play a different game on a board.
         *
         * `AbCommand.blindness` fires at a half, and it fires often, so under half is the everyday
         * case rather than the pathological one. Everything a knob is worth lives on this fraction
         * of the boards, in either design; only the paired one gets the other fraction for free.
         */
        const val DIVERGENCE = 0.4

        /** The reference an unpaired arm is measured against, near enough that scores stay contested. */
        const val REFERENCE = -50.0

        const val EVEN = 0.5
        const val SEATS = 2

        /** Boards behind one gradient estimate — `LabCommand`'s own default. */
        const val BOARDS = 6

        /** Repetitions of one measurement, enough that its own spread is measured rather than met. */
        const val REPEATS = 4_000

        /** `LabCommand`'s own defaults, so the numbers printed here price a real run. */
        const val ITERATIONS = 200
        const val STRIDE = 6.0

        /** For the deterministic bowls, where there is no reason to walk the range slowly. */
        const val FAST = 16.0

        /** Where a real knob starts from its optimum: `cpuct` ships fifteen of its own steps up. */
        const val NEARBY = 45.0

        /** Averaged over several seeds, so a comparison is not one lucky field. */
        const val RUNS = 12

        /** A stream for the games, so drawing one cannot shift which boards were drawn. */
        const val PLAY_STREAM = 100_000

        const val TOLERANCE = 3.0
        const val WIDE_TOLERANCE = 12.0
        const val NOISY_TOLERANCE = 9.0
    }
}

/**
 * A standard normal from [rng], by the Irwin-Hall sum.
 *
 * Twelve uniforms less six, which is within a percent of a normal over the range this uses and needs
 * no transcendental — so the field a comparison is drawn from is the same on any target that runs
 * this suite.
 */
private fun gaussian(rng: Rng): Double {
    var sum = 0.0
    repeat(12) { sum += rng.nextDouble() }
    return sum - 6.0
}
