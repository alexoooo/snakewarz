package ao.snakewarz.bots

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.random.SplitMix64
import kotlin.time.TimeSource

/**
 * What one turn of one bot costs, timed over a line of positions **that does not depend on the bot
 * being timed**.
 *
 * This exists because the obvious instrument does not measure what it looks like it measures.
 * `:lab`'s `time` — and this module's own `ThroughputTest.timeSearchTurn` — seat the subject in a
 * real match and divide the whole match's wall clock by the subject's own moves. A stronger entrant
 * survives longer, onto a fuller board, where a leaf costs something different; so the numerator and
 * the denominator both move with the entrant, and the ratio between two entrants carries however
 * their two *games* differed as well as however their two *turns* differed. `EvaluationCost`'s own
 * KDoc names the symptom — the cheap leaf's matches "ran anywhere from 28 to 225 turns and the
 * per-turn mean is over whichever turns happened to be played" — and `Research-Process.md` records a
 * µs/turn column ordering entrants by rating in one phase and inversely in another.
 *
 * ### What is held still
 *
 * The line is played by [LINE] in both seats and is fixed by construction:
 *
 * - the subject is **seated at no slot**. It is asked to decide from slot 0's seat at every sampled
 *   turn and its answer is thrown away; slot 0 plays [LINE]'s move instead. So the positions are the
 *   same boards, in the same order, for every entrant this class is handed.
 * - slot 0's line bot draws from a [SplitMix64] of its own rather than from the shared slot stream,
 *   so a subject that draws a different number of random values cannot shift the line under itself.
 * - the sample points are a fixed [stride] over slot 0's own turns, chosen from the line's length so
 *   that the samples spread across the whole game rather than piling into the opening. Every entrant
 *   is timed over the same count of appraisals at the same board fill.
 *
 * What that buys is that a **ratio** between two entrants here is about the two appraisals and
 * nothing else, and that the same ratio taken on the JVM and in Chrome is over identical work — which
 * is what makes a browser tax a number rather than an estimate.
 *
 * What it does **not** buy is realism of the line: two space fillers do not play the game a searcher
 * plays. That is deliberate. A cost figure wants a reproducible sweep of board fills, and the one
 * thing it must not have is a line chosen by the entrant under test.
 */
internal class AppraisalTape(
    private val rows: Int,
    private val cols: Int,
    private val seed: Long = DEFAULT_SEED,
    private val samples: Int = DEFAULT_SAMPLES,
) {
    private val lineEntry: BotEntry = ShippedBots.entryOf(BotId(LINE))

    /**
     * Turns slot 0 takes on the reference line, measured once by playing it with the subject seat
     * asked for nothing at all. Fixed for a given board and seed, and independent of the subject.
     *
     * Worth printing beside a figure taken here: two space fillers growing at half speed consume
     * about one square a turn between them, so a line this long is a board this full, and a figure
     * over a line that ended early would be a figure about an opening.
     */
    val lineTurns: Int = play(lineEntry, BotParams.EMPTY, budget = 0, stride = NEVER).appraisals

    /** Every `stride`-th own turn is sampled, so the samples spread over the whole line. */
    private val stride: Int = maxOf(1, lineTurns / samples)

    /** How many appraisals a figure from [time] is averaged over. */
    val appraisals: Int get() = (lineTurns + stride - 1) / stride

    /**
     * One entry per pass, in the order the passes were played.
     *
     * The caller takes the **median** and reports the passes as the spread. That is deliberately not
     * `TimeCommand`'s minimum, and the reason is measured rather than argued: on a machine whose
     * clock rate steps, a pass occasionally lands in a fast mode, and the minimum then reports that
     * mode for whichever cell happened to catch it. Two cells of one sweep came back bimodal — three
     * passes near 49,000 µs and two near 33,000 — and the minimum put that entrant 30% off its own
     * line through the other two allowances while every other entrant stayed on its. Under a median
     * every one of the forty-two cells sits within 8% of a straight line through the origin.
     *
     * The minimum's argument — that noise only ever adds time — holds for a pass that is slow. It
     * does not hold for a pass that is fast, because that one is not noise.
     *
     * ### A median protects a cell. Nothing here protects a **sweep**, and the check for that is free
     *
     * A machine whose clock steps can step *between* entrants, and then every pass of everything
     * after the step agrees with every other — so the cell looks clean and the ratio across the step
     * is wrong by whatever the two modes differ by. It is the same shape as the JVM contamination
     * [ThroughputTest] documents, and it is invisible for the same reason.
     *
     * What catches it costs nothing, because the sweep already contains the control: **two entrants
     * on one leaf must cost the same per turn.** `puct` against `alphabeta:eval=territory`,
     * `puct:eval=chamber` against `alphabeta:eval=chamber`, and `puct:eval=learned` against
     * `alphabeta:eval=learned` differ in their search and not in their appraisal, and on a clean run
     * they land within a few percent. One browser run of the three-board sweep had two of them 30%
     * and 62% apart on the two small boards while the 20x20 half stayed within 2% — a clock step,
     * mid-sweep, in a run whose every pass list was tight. A sweep failing that check is not
     * comparable across entrants and the honest answer is another run, not an average with one.
     *
     * **Spell the pairs from the leaf, never from a default.** Both halves of the second pair named
     * `alphabeta` bare until P3 moved that bot's default from `chamber` to `territory` — at which
     * point the pair would have been territory-against-chamber, a ratio that can never read 1 and a
     * gate that can therefore never pass or fail meaningfully. `ThroughputTest.CANDIDATES` now names
     * every leaf explicitly and lists the two halves of each pair **adjacently**, so a clock step
     * between entrant blocks cannot land inside a gate ratio, which is how two of P3a's four browser
     * runs failed.
     *
     * A third thing this gate cannot do, and it is worth knowing before trusting one reading: it
     * certifies a run, not a number. Using a pair the subject under test is *in* is circular — it
     * would certify a run precisely when the run agrees with the answer — so a phase measuring one
     * of these entrants reads the gate off the pairs that entrant is not in.
     */
    fun time(subject: BotEntry, params: BotParams, budget: Int, passes: Int): List<Timing> =
        List(passes) {
            val timed = play(subject, params, budget, stride)
            Timing(timed.micros / maxOf(timed.appraisals, 1), timed.worst)
        }

    /**
     * One pass: what an appraisal cost on average, and what the dearest of them cost.
     *
     * Both, because the two answer different questions and only one of them is the frame criterion.
     * `:ui` can stop stepping *between* turns and not inside one, so what overruns a frame is a
     * single turn and not a mean — and an appraisal is not flat across a game. [worst] is the one to
     * read an allowance off; [mean] is the one to take a ratio between two entrants with, since it
     * averages the machine over every sample rather than reading the sample the machine was busiest
     * on.
     */
    class Timing(val mean: Long, val worst: Long)

    private fun play(subject: BotEntry, params: BotParams, budget: Int, stride: Int): Appraiser {
        var appraiser: Appraiser? = null

        val seat = BotEntry(
            BotId(SEAT),
            "Appraisal seat",
            BotFactory { setup ->
                Appraiser(
                    subject = subject.factory.create(setup),
                    line = lineEntry.factory.create(
                        BotSetup(
                            self = setup.self,
                            grid = setup.grid,
                            rules = setup.rules,
                            opponents = setup.opponents,
                            rng = SplitMix64(LINE_SEED),
                            params = BotParams.EMPTY,
                        ),
                    ),
                    stride = stride,
                ).also { appraiser = it }
            },
        )

        HeadlessMatch(
            entries = listOf(seat, lineEntry),
            rows = rows,
            cols = cols,
            seed = seed,
            budgetPerTurn = budget,
            recording = false,
            budgetPerSlot = intArrayOf(budget, 0),
            paramsPerSlot = listOf(params, BotParams.EMPTY),
        ).run()

        return checkNotNull(appraiser) { "the seat was never built, so nothing was timed" }
    }

    /**
     * Slot 0: asks [subject] and throws the answer away, then plays [line]'s move.
     *
     * The subject spends the whole turn allowance before [line] is asked, which is why [LINE] has to
     * be a bot that consumes none — `SpaceBot` answers with a flood fill and charges nothing.
     */
    private class Appraiser(
        private val subject: Bot,
        private val line: Bot,
        private val stride: Int,
    ) : Bot {
        var micros: Long = 0
            private set

        /** The dearest single appraisal of the pass — the frame criterion, which a mean is not. */
        var worst: Long = 0
            private set

        /** Own turns sampled, or — when [stride] is [NEVER] — own turns taken, which is the length. */
        var appraisals: Int = 0
            private set

        private var own = 0

        override fun chooseMove(turn: Turn): Decision {
            if (stride == NEVER) {
                appraisals++
            } else if (own % stride == 0) {
                val mark = TimeSource.Monotonic.markNow()
                subject.chooseMove(turn)
                val elapsed = mark.elapsedNow().inWholeMicroseconds

                micros += elapsed
                if (elapsed > worst) {
                    worst = elapsed
                }
                appraisals++
            }

            own++
            return line.chooseMove(turn)
        }

        override fun toString(): String = "Appraiser($subject)"
    }

    companion object {
        /**
         * Plays the line in both seats, and the reason is the one `TimeCommand` gives for using it as
         * a sparring partner: it is the strongest thing in the registry that spends no allowance, so
         * it walks a real game onto a realistically full board and adds nothing to the clock.
         */
        const val LINE = "space"

        /** Not a registered bot and never will be — [BotEntry] only wants a well-formed slug. */
        private const val SEAT = "appraisal-seat"

        /** Slot 0's line bot draws from this rather than from the slot stream the subject shares. */
        private const val LINE_SEED = 0x5EA7L

        private const val DEFAULT_SEED = 424_242L

        /**
         * Sample points per line.
         *
         * Enough that a figure is about the board's whole fill sweep rather than about its opening,
         * and few enough that the dearest entrant on the largest board still answers in a second per
         * pass — which is what keeps the browser suite, where every one of these is 2-3x slower,
         * inside a Karma timeout.
         */
        private const val DEFAULT_SAMPLES = 24

        /** Asks the subject for nothing, so a pass counts the line's length instead of timing it. */
        private const val NEVER = -1
    }
}
