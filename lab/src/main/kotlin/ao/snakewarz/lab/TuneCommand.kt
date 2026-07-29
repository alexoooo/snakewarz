package ao.snakewarz.lab

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.strength.SequentialTest
import ao.snakewarz.lab.strength.Sprt
import ao.snakewarz.lab.tune.KnobSpace
import ao.snakewarz.lab.tune.TuneJournal
import ao.snakewarz.match.tournament.Contestant
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Searches a bot's declared knobs for a better setting, one sequential test at a time.
 *
 * ### What it does
 *
 * Coordinate descent: hold everything still, move one knob, and let [Sprt] decide whether that was
 * an improvement. Accept and carry on; reject and try the next. When a whole pass finds nothing, the
 * stride halves and the search looks closer. Chosen over a gradient method because it is sample
 * efficient at this budget, because every step is a decision somebody can read, and because it
 * handles a `Choice` knob — which has no gradient — without a special case.
 *
 * ### What it will not do
 *
 * **It never writes a default into `:bots`.** A tuner with that power is a machine for defeating
 * SW-01: changing a default moves every golden move-stream hash, and a process that could update
 * both would turn "a golden failure is a question" into "a golden failure is a formality". So this
 * prints a recommendation and a person applies it, having read the confirmation below.
 *
 * ### The confirmation, which is not optional
 *
 * A search that tests `k` knobs over several passes runs dozens of sequential tests, each with its
 * own false-positive rate, and every one of them is against the *same* seeds. Both of those push the
 * same way: something will look better eventually. So a winner is re-run against the original
 * incumbent on a **disjoint** set of boards at a stricter bound, and it is that number the
 * recommendation carries. A candidate that cannot survive fresh boards was fitted to the old ones.
 */
internal class TuneCommand(
    val subject: BotId,
    /**
     * The knobs the entrant spec pinned: the configuration this searches *inside*.
     *
     * The incumbent starts here rather than at [BotParams.EMPTY], so every proposal carries it and
     * so does the baseline the confirmation runs against. A weight living under a `Choice` —
     * `ChamberEval`'s three under `eval=chamber` — is otherwise swept at a setting where it does
     * nothing, and the sweep says nothing about it while looking exactly like one that did.
     */
    val fixed: BotParams,
    val knobs: List<BotKnob.Param<*>>,
    val rows: Int,
    val cols: Int,
    val seed: Long,
    val budgetPerTurn: Int,
    val openings: Openings,
    val threads: Int,
    val passes: Int,
    val blockPairs: Int,
    val maxPairs: Int,
    val searchElo1: Double,
    val journalFile: Path,
) : LabCommand {
    /**
     * Boards this run actually played, and how many of them the two entrants shared exactly.
     *
     * Kept because a search that finds nothing has two very different explanations and the printed
     * result cannot tell them apart: the defaults are good, or **this instrument cannot see the knob
     * being turned**. Every step of the descent is the same head-to-head sequential test `ab` runs,
     * so it inherits `AbCommand.blindness` whole — a knob that changes how a bot plays *other*
     * opponents, and not how it plays a copy of itself, scores `0 Elo` on every step of a sweep and
     * reports "leave the defaults alone" with total confidence.
     *
     * `chase --knobs roomShare` is exactly that, and it is worth `+14 Elo` against a field.
     *
     * Replayed decisions contribute nothing here — they were read back from the journal rather than
     * played — so the note is only offered when this run has boards of its own to judge on.
     */
    private var boardsPlayed = 0
    private var boardsShared = 0

    /** A bot that threw, over the whole sweep. Always a defect, and never a result. */
    private var forfeits = 0

    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val journal = TuneJournal(journalFile)
        val history = journal.read().filter { !it.confirming }
        val started = TimeSource.Monotonic.markNow()

        log("[lab] tuning ${entrant(fixed)} over ${knobs.joinToString { it.name }}")
        log("[lab] ${rows}x$cols at $budgetPerTurn evaluations, $openings openings, journal $journalFile")
        if (history.isNotEmpty()) {
            log("[lab] resuming: ${history.size} decisions already taken, ${history.count { it.accepted }} accepted")
        }

        var incumbent = fixed
        var taken = 0
        var stride = COARSE_STRIDE
        var accepted = 0

        for (pass in 0 until passes) {
            var improved = false

            for (knob in knobs) {
                for (value in KnobSpace.neighbours(knob, incumbent, stride)) {
                    // A choice has no stride, so offering it again on a finer pass would replay a
                    // decision that cannot have changed.
                    if (knob is BotKnob.Choice && stride != COARSE_STRIDE) {
                        continue
                    }

                    val proposal = KnobSpace.with(incumbent, knob, value)
                    val boardSeed = seed + taken.toLong() * maxPairs
                    val replayed = history.getOrNull(taken)

                    val decision = replayed ?: decide(
                        registry = registry,
                        pass = pass,
                        stride = stride,
                        knob = knob,
                        incumbent = incumbent,
                        proposal = proposal,
                        seed = boardSeed,
                        bound = searchElo1,
                        cap = maxPairs,
                    ).also(journal::append)
                    taken++

                    log(
                        "[lab] pass $pass stride $stride  ${knob.name}=$value  $decision" +
                            if (decision.boards >= maxPairs && !decision.accepted) "  (ran out of boards)" else "",
                    )
                    if (decision.accepted) {
                        incumbent = proposal
                        accepted++
                        improved = true
                        break
                    }
                }
            }

            if (!improved) {
                stride /= 2
                if (stride < 1) {
                    log("[lab] nothing left to try at any stride")
                    break
                }
                log("[lab] no gain at that stride; looking closer")
            }
        }

        log("")
        if (forfeits > 0) {
            log("[lab] $forfeits FORFEITS -- a bot threw. That is a defect, and this sweep measured it.")
        }
        report(registry, journal, incumbent, accepted, taken, started, log)
    }

    override fun toString(): String = "Tune(${subject.slug}, ${knobs.joinToString { it.name }})"

    /** One experiment: play the proposal against the incumbent until the test settles. */
    private fun decide(
        registry: BotRegistry,
        pass: Int,
        stride: Int,
        knob: BotKnob.Param<*>,
        incumbent: BotParams,
        proposal: BotParams,
        seed: Long,
        bound: Double,
        cap: Int,
    ): TuneJournal.Decision {
        val outcome = SequentialTest(
            baseline = Contestant(subject, params = incumbent),
            candidate = Contestant(subject, params = proposal),
            rows = rows,
            cols = cols,
            seed = seed,
            budgetPerTurn = budgetPerTurn,
            openings = openings,
            threads = threads,
            sprt = Sprt(elo0 = SEARCH_ELO0, elo1 = bound, alpha = ALPHA, beta = BETA),
            blockPairs = blockPairs,
            maxPairs = cap,
        ).run(registry)

        boardsPlayed += outcome.boards
        boardsShared += outcome.splits
        forfeits += outcome.forfeits

        return TuneJournal.Decision(
            pass = pass,
            stride = stride,
            knob = knob.name,
            incumbent = describe(incumbent),
            proposal = describe(proposal),
            seed = seed,
            verdict = outcome.report.verdict.name,
            elo = outcome.report.elo?.roundToInt()?.toString() ?: "unbounded",
            boards = outcome.boards,
        )
    }

    /**
     * Re-runs the whole gain on boards the search never saw, and reports that.
     *
     * The number every recommendation carries, and the only one worth acting on. What the search
     * measured is the best of dozens of tests over one set of boards, which is a quantity with a
     * bias built into it however carefully each test was run.
     */
    private fun report(
        registry: BotRegistry,
        journal: TuneJournal,
        incumbent: BotParams,
        accepted: Int,
        taken: Int,
        started: kotlin.time.TimeSource.Monotonic.ValueTimeMark,
        log: (String) -> Unit,
    ) {
        if (incumbent == fixed) {
            val start = if (fixed.isEmpty) "the shipped settings" else entrant(fixed)
            log("[lab] nothing beat $start over $taken experiments.")
            log("[lab] ${started.elapsedNow().inWholeSeconds}s. That is a result: leave the defaults alone.")
            blindness(log)
            return
        }

        log("[lab] the search settled on ${describe(incumbent)} after $accepted accepted of $taken tried")
        log("[lab] confirming on boards it has never seen, at a stricter bound...")

        val confirmation = decide(
            registry = registry,
            pass = CONFIRMATION,
            stride = 0,
            knob = allKnobs(),
            incumbent = fixed,
            proposal = incumbent,
            seed = seed + CONFIRM_SEED_OFFSET,
            bound = CONFIRM_ELO1,
            // One test rather than dozens, at a finer bound, so it is allowed a great deal more
            // evidence than a step of the search was.
            cap = maxPairs * CONFIRM_BOARDS,
        ).also(journal::append)

        log("")
        when {
            confirmation.accepted -> {
                log("[lab] CONFIRMED at ${confirmation.elo} Elo over ${confirmation.boards} fresh boards.")
                log("[lab] recommended: ${entrant(incumbent)}")
                log("")
                log("[lab] To adopt it, change the declared default and then answer the golden hashes:")
                log("[lab]   1. `play` it against the ladder rungs -- confirm nothing regressed")
                log("[lab]   2. edit the knob's `default` in its declaration")
                log("[lab]   3. GoldenMoveStreamTest will fail. Re-pin it *recording this measurement*")
                log("[lab]      -- a golden failure is a question, and this is the answer to it")
                log("[lab]   4. put the numbers in the KDoc beside the constant, as the others do")
            }

            else -> {
                log(
                    "[lab] NOT CONFIRMED: ${confirmation.verdict} at ${confirmation.elo} Elo over " +
                        "${confirmation.boards} fresh boards.",
                )
                log("[lab] The gain the search found was fitted to the boards it searched on. Do not adopt it.")
                log("[lab] That is a result, and the shipped default is what it argues for. Both runs are")
                log("[lab] in $journalFile -- the confirming one is the row with a negative pass.")
            }
        }
        log("[lab] ${started.elapsedNow().inWholeSeconds}s over $taken experiments")
    }

    /** Says when "nothing beat the defaults" may be this search's blind spot — see [boardsShared]. */
    private fun blindness(log: (String) -> Unit) {
        if (boardsPlayed == 0 || boardsShared * 2 < boardsPlayed) {
            return
        }

        log("")
        log(
            "[lab] NOTE: $boardsShared of $boardsPlayed boards split exactly, so on most of them the " +
                "two settings played the same game. A sweep is head to head, and a knob that only " +
                "changes how ${subject.slug} plays *other* opponents scores zero on every step of " +
                "one. Sweep it against a field instead -- play a few values alongside the rest of " +
                "the ladder and read `rate`, the way ChaseBot.ROOM_SHARE was settled.",
        )
    }

    /** Every setting that differs from stock, as an entrant spec would spell it. */
    private fun describe(params: BotParams): String =
        if (params.isEmpty) "stock" else params.names.joinToString(",") { "$it=${params.string(it, "")}" }

    /** A settings map as something that parses back — which is what makes a printed command pasteable. */
    private fun entrant(params: BotParams): String =
        if (params.isEmpty) subject.slug else "${subject.slug}:${describe(params)}"

    /** A stand-in name for the confirmation, which moves every knob at once rather than one. */
    private fun allKnobs(): BotKnob.Param<*> = knobs.first()

    companion object {
        /**
         * Eight declared steps to start with.
         *
         * A knob's step is the finest change worth *expressing*, which is usually far finer than the
         * smallest change a batch of games can see. Starting wide finds the hill; halving finds the
         * top of it.
         */
        private const val COARSE_STRIDE = 8

        private const val SEARCH_ELO0 = 0.0

        /**
         * The bar for a step of the search, and **the setting that decides what a sweep costs**.
         *
         * A sequential test compares two *hypotheses*, and how fast it decides depends on how far
         * apart they are, not on how large the real effect is. Bounds of `0..3` are what a chess
         * engine uses for a patch worth a fraction of a point, and they cost tens of thousands of
         * games to settle — with a cap in the hundreds every decision would run out of boards and
         * come back undecided, which is a sweep that measures nothing slowly.
         *
         * Twenty points is a difference worth finding in a knob, and it settles in tens of boards.
         * Lower it for a finer search and raise `--max-pairs` to pay for it; the two move together
         * and the run says so when a decision hits the cap instead of settling.
         */
        const val SEARCH_ELO1 = 20.0

        /**
         * The bar the whole change has to clear on boards the search never saw.
         *
         * Lower than the search's, deliberately: a search is allowed to be greedy because a mistake
         * there only costs games, and this is the one number somebody will act on. Fresh boards and
         * a finer bound is what separates a real gain from the best of dozens of tries. Both
         * searches confirm against it — see [SpsaCommand], which is a different way of walking the
         * same space and inherits the same obligation.
         */
        const val CONFIRM_ELO1: Double = 8.0

        private const val ALPHA = 0.05
        private const val BETA = 0.05

        /**
         * Far past anything a search consumed, so the confirmation shares no board with it.
         *
         * Shared with [SpsaCommand], which walks the same space a different way and owes the same
         * disjoint seed base. A confirmation drawn from seeds the search already used is not a
         * confirmation, it is the search reported twice.
         */
        const val CONFIRM_SEED_OFFSET: Long = 1_000_000L

        /** The confirmation gets this many times a search step's boards, and needs them. */
        private const val CONFIRM_BOARDS = 4

        private const val CONFIRMATION = -1
    }
}
