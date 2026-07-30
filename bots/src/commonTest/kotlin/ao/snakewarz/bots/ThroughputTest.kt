package ao.snakewarz.bots

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * How fast this actually is, in numbers rather than adjectives.
 *
 * It exists to answer one question with data instead of judgement — **what allowance should a match
 * be played at** — and it runs on both targets, because the answer has to hold in a browser and the
 * browser is the slower of the two. Everything it prints is prefixed `[bench]`, so a run shows its
 * working:
 *
 * ```
 * ./gradlew :bots:jvmTest --tests '*ThroughputTest*' -i | grep '\[bench\]'
 * ./gradlew :bots:wasmJsBrowserTest -PbrowserTests=true -i | grep '\[bench\]'
 * ```
 *
 * The assertions are deliberately an order of magnitude looser than the measurements. A benchmark
 * that fails when the machine is busy teaches everyone to ignore it; these fail only on a regression
 * nobody could argue with, and the *printed* numbers are what the tuning decision is made from.
 */
class ThroughputTest {
    @Test
    fun `the engine and the driver run millions of turns a second`() {
        val random = entry("random")

        // Warm up rather than measure the first pass: on the JVM that pass is interpreted, and on
        // wasm the tiering is different again. Neither is the number anybody wants.
        play(listOf(random, random), MEASURED_BOARD, seed = 0, budget = 0, matches = WARMUP_MATCHES)

        val turnsPerSecond = play(
            listOf(random, random),
            MEASURED_BOARD,
            seed = 1,
            budget = 0,
            matches = ENGINE_MATCHES,
        )

        report("engine  ${MEASURED_BOARD}x$MEASURED_BOARD random vs random", turnsPerSecond)
        assertTrue(turnsPerSecond > 100_000, "the engine managed only $turnsPerSecond turns/s")
    }

    @Test
    fun `a search turn at the shipped allowance fits inside a frame`() {
        val uct = entry("uct")
        val space = entry("space")

        // One throwaway match at the smallest allowance, to get the code hot without spending long.
        HeadlessMatch(listOf(uct, space), MEASURED_BOARD, MEASURED_BOARD, seed = 0, budgetPerTurn = BUDGETS[0]).run()

        for (budget in BUDGETS) {
            val microsPerTurn = timeSearchTurn(uct, space, budget)
            report("uct     ${MEASURED_BOARD}x$MEASURED_BOARD budget $budget", microsPerTurn, "us/turn")

            assertTrue(
                microsPerTurn < CEILING_MICROS,
                "a turn at budget $budget took $microsPerTurn us, which no board should",
            )
        }
    }

    /**
     * What each **candidate appraisal** costs a turn, on both boards, over a line neither of them
     * chose — see [AppraisalTape] for why that last clause is the whole instrument.
     *
     * This is the figure `MatchSetup.DEFAULT_BUDGET_PER_TURN`'s KDoc has never had. That table's
     * `puct` column is a JVM measurement multiplied by `uct`'s browser tax, and it says so: *"nobody
     * has timed an appraisal in Chrome."* Here it is timed in Chrome, because this suite recompiles
     * to wasm and runs under Karma, and `uct` is carried across every row as the control that joins
     * these figures to the ones already published.
     *
     * Three allowances rather than one, because the deliverable is not a cost — it is the **allowance
     * that fits the frame slice**, and that is read off the line through these points rather than off
     * any one of them. In Chrome all forty-two cells sit within 8% of a straight line through the
     * origin: a turn carries no fixed cost worth the name, so an allowance ratio is a cost ratio.
     *
     * ### Read the browser run. **A ratio between two entrants on the JVM run of this sweep is not
     * a cost ratio.**
     *
     * One JVM process times seven bots through one `Bot.chooseMove` call site, and it does not
     * survive that. On a 12x12 this sweep puts `eval=learned` at **5.1x** `eval=chamber` and
     * `alphabeta:eval=territory` at **4.7x** `puct` — where Chrome, `:lab time` with a fresh process
     * per entrant, and `EvaluationCost`'s own published figure all agree on 1.1x and 1.0x
     * respectively. Every pass of the contaminated cells agrees with the others, so it reads as a
     * measurement rather than as noise, and the 20x20 half of the same run is clean, which is what
     * makes it invisible to anyone reading one board.
     *
     * Nothing here can fix that from inside one process, so the JVM run is kept for what it is good
     * for — a regression guard on a single entrant, and an answer in seconds instead of the browser
     * suite's ten minutes. The cross-entrant table is the browser's.
     */
    @Test
    fun `an appraisal fits inside the frame slice`() {
        for (board in APPRAISAL_BOARDS) {
            val tape = AppraisalTape(board, board)

            // Every candidate once before any of them is timed, and the sweep rather than the
            // cheapest of them: the first pass is interpreted on the JVM and untiered in wasm, and —
            // the reason this is a loop — one `Bot.chooseMove` call site sees all seven of these, so
            // whichever ran first would otherwise be measured through a call site the rest had not
            // yet made polymorphic. That reads as a browser tax on the entrants that happen to be
            // early in the list, and it is an artefact of the order they are timed in.
            for ((slug, params) in CANDIDATES) {
                tape.time(entry(slug.substringBefore(':')), params, APPRAISAL_BUDGETS[0], passes = 1)
            }

            for ((slug, params) in CANDIDATES) {
                for (budget in APPRAISAL_BUDGETS) {
                    val passes = tape.time(entry(slug.substringBefore(':')), params, budget, APPRAISAL_PASSES)
                    val mean = passes.map { it.mean }.sorted()[APPRAISAL_PASSES / 2]
                    val worst = passes.map { it.worst }.sorted()[APPRAISAL_PASSES / 2]

                    report(
                        "$slug ${board}x$board budget $budget",
                        mean,
                        "us/turn mean, $worst us worst, over ${tape.appraisals} of ${tape.lineTurns} " +
                            "turns, passes ${passes.joinToString("/") { it.mean.toString() }}",
                    )
                    assertTrue(
                        mean < APPRAISAL_CEILING_MICROS,
                        "an appraisal by $slug at budget $budget took $mean us, which no allowance should",
                    )
                }
            }
        }
    }

    @Test
    fun `a ladder pairing is seconds rather than minutes`() {
        // The unit a tournament is actually sold in: twenty matches of one pairing, which is what
        // the panel offers by default and what BotLadderTest measures every rung over.
        val uct = entry("uct")
        val space = entry("space")

        HeadlessMatch(listOf(uct, space), LADDER_BOARD, LADDER_BOARD, seed = 0, budgetPerTurn = SHIPPED_BUDGET).run()

        val started = TimeSource.Monotonic.markNow()
        for (seed in 1L..(PAIRING_ROUNDS / 2)) {
            HeadlessMatch(listOf(uct, space), LADDER_BOARD, LADDER_BOARD, seed, SHIPPED_BUDGET, recording = false).run()
            HeadlessMatch(listOf(space, uct), LADDER_BOARD, LADDER_BOARD, seed, SHIPPED_BUDGET, recording = false).run()
        }
        val elapsed = started.elapsedNow().inWholeMilliseconds

        report("pairing ${LADDER_BOARD}x$LADDER_BOARD uct vs space, $PAIRING_ROUNDS matches", elapsed, "ms")
        assertTrue(elapsed < PAIRING_CEILING_MILLIS, "a pairing took $elapsed ms")
    }

    // -- internals

    /** Plays [matches] complete matches and returns turns per second. */
    private fun play(entries: List<BotEntry>, size: Int, seed: Long, budget: Int, matches: Int): Long {
        val started = TimeSource.Monotonic.markNow()
        var turns = 0L

        for (i in 0 until matches) {
            val match = HeadlessMatch(entries, size, size, seed + i, budget, recording = false)
            match.run()
            turns += match.turns
        }

        val elapsed = started.elapsedNow().inWholeMicroseconds
        return if (elapsed == 0L) Long.MAX_VALUE else turns * 1_000_000 / elapsed
    }

    /** Microseconds per turn *of the searching bot*, which is half the turns in a two-snake match. */
    private fun timeSearchTurn(searcher: BotEntry, opponent: BotEntry, budget: Int): Long {
        val match = HeadlessMatch(listOf(searcher, opponent), MEASURED_BOARD, MEASURED_BOARD, SEED, budget)

        val started = TimeSource.Monotonic.markNow()
        match.run()
        val elapsed = started.elapsedNow().inWholeMicroseconds

        val searched = match.decisions.count { it.id.index == 0 }
        return elapsed / maxOf(searched, 1)
    }

    private fun report(what: String, value: Long, unit: String = "turns/s") {
        println("[bench] $what: $value $unit")
    }

    private fun entry(slug: String): BotEntry = ShippedBots.entryOf(BotId(slug))

    private companion object {
        /**
         * The board `MatchSetup.DEFAULT_BUDGET_PER_TURN` was timed on, so a `[bench]` line here can
         * be read against that table rather than merely near it.
         *
         * Not the board the page opens on — `index.html` selects 8 — and deliberately not. A turn's
         * cost grows with the squares, so the question this test exists to answer, *does a turn at
         * the shipped allowance fit inside a frame*, is only worth asking near the dear end of what
         * a player can pick. `:ui` offers up to 40; a turn that fits here fits the opening board
         * several times over.
         */
        const val MEASURED_BOARD = 20

        /** The board the ladder and the shipped tournament defaults use. */
        const val LADDER_BOARD = 12

        /**
         * The board `index.html` opens on, which is the geometry most matches ever played are on.
         *
         * The dear end of the range is where the *frame* question lives, and that is
         * [MEASURED_BOARD]'s job. This is the other end, and it is here for the ratio rather than
         * for the ceiling: the cost ratio between two appraisals moves with the board — `chamber`
         * is 3.45x `puct` at [LADDER_BOARD] and 4.58x at [MEASURED_BOARD], and `uct` changes sides
         * entirely — so an allowance table with two rows in it cannot be interpolated and a field
         * run at a third size needs a third row measured.
         */
        const val OPENING_BOARD = 8

        const val SEED = 424_242L

        val BUDGETS = intArrayOf(250, 1_000, 2_000, 10_000)

        /**
         * The board the page opens on, the ladder's, and the one the budget table was taken on.
         *
         * Three rather than two because the ratio moves between them and cannot be interpolated —
         * [OPENING_BOARD] carries the measurement that says so. The small board is nearly free: its
         * line is a third of the ladder board's, so it adds well under a fifth to a browser sweep.
         */
        val APPRAISAL_BOARDS = intArrayOf(OPENING_BOARD, LADDER_BOARD, MEASURED_BOARD)

        /**
         * Enough points to fit the line a frame slice is solved on, and no dearer than it has to be.
         *
         * 10,000 is deliberately absent, where [BUDGETS] carries it: it is six times past the slice
         * on the cheapest candidate here and forty on the dearest, so it would add minutes of Karma
         * to establish a point every reader already knows the sign of.
         */
        val APPRAISAL_BUDGETS = intArrayOf(250, 1_000, 2_000)

        /**
         * `LabCommand.DEFAULT_PASSES`, and for its reason — *enough passes for the fastest of them
         * to be about the code rather than about the machine*.
         *
         * Three is not enough here and that was measured rather than assumed: at three, one cell of
         * the sweep came back with **every** pass five times its neighbours', so the minimum carried
         * it and the cell read ten times its own line through the other allowances.
         */
        const val APPRAISAL_PASSES = 5

        /**
         * A crash guard on the appraisal sweep, and deliberately **not** the frame criterion.
         *
         * [CEILING_MICROS] cannot serve here. The dearest cell of this sweep is a leaf several times
         * the shipped default's, at twice the shipped allowance, on the largest board offered — a
         * setting that is *meant* to overrun a frame, since saying by how much is the whole point of
         * the table. In a browser that cell reads a few hundred milliseconds, so a ceiling written
         * for `uct` at the shipped allowance fires on a measurement working exactly as intended.
         *
         * The frame criterion is the `worst` figure each line prints, read against `:ui`'s 8 ms
         * slice; this is an order of magnitude above the dearest cell measured and catches only a
         * regression nobody could argue with.
         */
        const val APPRAISAL_CEILING_MICROS = 2_000_000L

        /** Carried across every row of the appraisal table, and the one figure already published. */
        const val CONTROL = "uct"

        /**
         * The appraisals a field is choosing between, named exactly as `:lab` spells an entrant so a
         * `[bench]` line can be pasted back into a batch.
         *
         * `alphabeta` appears three times because which leaf it should be seated at is a costing
         * question and not a preference — it borrows `puct`'s leaves wholesale, and since P3 moved
         * its default the two bots default to the same one.
         *
         * **The order is the leaf-pair gate, and it is load-bearing.** [AppraisalTape] documents the
         * free consistency check this sweep carries: two entrants on one leaf must cost the same per
         * turn, so `puct` against `alphabeta:eval=territory`, `puct:eval=chamber` against
         * `alphabeta:eval=chamber` and `puct:eval=learned` against `alphabeta:eval=learned` are three
         * ratios that read ~1 on a usable run. This list is **interleaved so that the two halves of
         * every pair are adjacent**. They used to sit four entrants apart — three `puct` rows then
         * three `alphabeta` rows — which is precisely the arrangement in which a clock step *between*
         * entrant blocks lands entirely inside the ratio the gate exists to protect, and P3a
         * diagnosed two browser runs failing that way. Adjacency costs nothing and is the fix.
         *
         * The second pair is spelled `alphabeta:eval=chamber` rather than `alphabeta`, and that is
         * not cosmetic: at the old default the bare row *was* the chamber row, and after the move it
         * would have paired territory against chamber — a "gate" that can never pass and so is no
         * gate at all, silently disabling the one check that says whether a sweep is usable.
         */
        val CANDIDATES: List<Pair<String, BotParams>> = listOf(
            CONTROL to BotParams.EMPTY,
            "puct" to BotParams.EMPTY,
            "alphabeta:eval=territory" to BotParams(mapOf("eval" to "territory")),
            "puct:eval=chamber" to BotParams(mapOf("eval" to "chamber")),
            "alphabeta:eval=chamber" to BotParams(mapOf("eval" to "chamber")),
            "puct:eval=learned" to BotParams(mapOf("eval" to "learned")),
            "alphabeta:eval=learned" to BotParams(mapOf("eval" to "learned")),
        )

        const val WARMUP_MATCHES = 200
        const val ENGINE_MATCHES = 2_000

        const val PAIRING_ROUNDS = 20

        /**
         * Twenty times a 60 Hz frame. A turn that takes longer than this has stopped being a
         * *slower* match and started being an unresponsive page.
         */
        const val CEILING_MICROS = 333_000L

        const val PAIRING_CEILING_MILLIS = 120_000L
    }
}
