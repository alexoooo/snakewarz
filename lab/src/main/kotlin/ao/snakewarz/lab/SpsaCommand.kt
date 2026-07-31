package ao.snakewarz.lab

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.strength.SequentialTest
import ao.snakewarz.lab.strength.Sprt
import ao.snakewarz.lab.strength.pairScores
import ao.snakewarz.lab.tune.KnobSpace
import ao.snakewarz.lab.tune.Spsa
import ao.snakewarz.lab.tune.SpsaJournal
import ao.snakewarz.lab.tune.SpsaSchedule
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Moves every numeric knob at once, along a gradient estimated from two measurements.
 *
 * ### Why this exists beside `tune`
 *
 * [TuneCommand] is coordinate descent, and coordinate descent is the right shape at two or three
 * knobs: each step is a decision somebody can read, and a `Choice` — which has no gradient at all —
 * needs no special case. It is the wrong shape at ten. A pass costs one sequential test per knob per
 * stride, the knobs interact, so the pass has to be repeated until nothing moves, and the cost grows
 * with the dimension while the evidence per knob does not. An evaluation with eight weights is not
 * something it can settle.
 *
 * SPSA costs **two measurements per step whatever the dimension**. It perturbs every coordinate at
 * once along a random sign vector and reads the whole gradient off the single difference. Each
 * estimate is bad; the average of many is not, and the schedule is what turns the second fact into
 * convergence. It is what chess engines tune with, for exactly this situation.
 *
 * ### Common random numbers, which is where most of the leverage is
 *
 * The two arms are played **against each other** over one set of boards, so every board is common to
 * both and its own difficulty cancels inside the difference instead of adding to it. The spread
 * between openings is far larger than the difference two knob settings make, so an unpaired design
 * would have to average that away first and would need an order of magnitude more games to see the
 * same gradient. `SpsaTest` measures the ratio on a synthetic objective rather than asserting it.
 *
 * The cost of the pairing is that this inherits `AbCommand.blindness` whole: a knob that changes how
 * the bot plays *other* opponents and not how it plays a copy of itself has no gradient here at all,
 * and every board splits down the middle. The run says so rather than reporting a flat objective as
 * a settled question.
 *
 * ### Numbers only
 *
 * A `Choice` and a `Flag` are refused rather than perturbed. There is no direction to move a name
 * in, so a search that treated one as a coordinate would be measuring an arbitrary ordering of the
 * values it happens to be declared in. `tune` enumerates those; this walks the numbers.
 *
 * ### What it will not do
 *
 * It never writes a default into `:bots`, for the reason [TuneCommand] gives, and **it never reports
 * its own best iterate**. A search over a noisy objective visits its best-looking point by
 * construction, so the maximum of the trajectory is a statement about the noise. What it reports is
 * the point it *finished* at, put through a confirming run over boards it has never seen, at a
 * stricter bound — and it is that number, and only that number, a recommendation carries.
 */
internal class SpsaCommand(
    val subject: BotId,
    /**
     * The knobs the entrant spec pinned: the configuration this searches *inside*.
     *
     * Written into both arms of every iteration and into the confirming run's **baseline** as well
     * as its candidate, so the whole run is one variable. A weight can live under a `Choice` —
     * `ChamberEval`'s three do nothing whatever unless `eval=chamber` — and a search that dropped
     * this would perturb them where they are dead code and report a well-formed answer about a bot
     * it was not searching.
     */
    val fixed: BotParams,
    val knobs: List<BotKnob.Param<*>>,
    /** Declared knobs left where they are because they have no gradient — named, never dropped quietly. */
    val ignored: List<String>,
    val rows: Int,
    val cols: Int,
    val seed: Long,
    val budgetPerTurn: Int,
    /** The map the whole search runs on, including its confirming run. */
    val walls: IntArray,
    val openings: Openings,
    val threads: Int,
    val iterations: Int,
    val boardsPerIteration: Int,
    val spread: Double,
    val stride: Double,
    val confirm: Sprt,
    val confirmPairs: Int,
    val journalFile: Path,
) : LabCommand {
    private val spans: List<KnobSpace.Span> = knobs.map {
        KnobSpace.span(it) ?: error("'${it.name}' is not a number, so it has no gradient to walk")
    }

    private var boardsPlayed = 0
    private var boardsShared = 0
    private var matchesPlayed = 0
    private var forfeits = 0
    private val distinctGames = LinkedHashSet<Long>()

    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val journal = SpsaJournal(journalFile)
        val history = journal.read()
        val started = TimeSource.Monotonic.markNow()

        val schedule = SpsaSchedule(iterations, spread, stride)
        val search = Spsa(
            start = DoubleArray(knobs.size) { steps(it, spans[it].default) },
            lower = DoubleArray(knobs.size) { steps(it, spans[it].min) },
            upper = DoubleArray(knobs.size) { steps(it, spans[it].max) },
            schedule = schedule,
            seed = seed,
        )

        log("[lab] spsa ${entrant(fixed)} over ${knobs.joinToString { it.name }}")
        log(
            "[lab] ${rows}x$cols with ${walls.size} walls at $budgetPerTurn evaluations, " +
                "$openings openings, $iterations iterations of $boardsPerIteration boards, " +
                "journal $journalFile",
        )
        log("[lab] $schedule, in each knob's own declared steps")
        if (ignored.isNotEmpty()) {
            log("[lab] ${ignored.joinToString()} have no gradient and stay at their defaults; `tune` walks those")
        }
        if (history.isNotEmpty()) {
            log("[lab] resuming: ${history.size} iterations already measured")
        }

        for (iteration in 0 until iterations) {
            step(registry, search, journal, history.getOrNull(iteration), iteration, log)
        }

        log("")
        val point = search.settled()
        travelled(search, point, log)
        finish(registry, journal, point, started, log)
    }

    /**
     * Where each knob started and where it stopped, and which of them stopped against a wall.
     *
     * Printed above the point rather than beside it, because a knob on its own `min` or `max` is the
     * one shape that reads like a result and is not one: with nothing holding it the iterate is a
     * walk, and a walk in a box ends at a wall. `puct`'s `cpuct` at the shipped allowance is exactly
     * such a knob — a `tune` sweep of it settled on nothing over 800 fresh boards — and a run of
     * this on it walks to the floor of the declared range. Naming that is worth more than a
     * significance test on the trajectory, which cannot be made sound: see [Spsa.parked].
     */
    private fun travelled(search: Spsa, point: DoubleArray, log: (String) -> Unit) {
        val width = knobs.maxOf { it.name.length }
        log("[lab] where each knob went:")
        for (i in knobs.indices) {
            log(
                "[lab]   ${knobs[i].name.padEnd(width)}  ${knobs[i].defaultText} -> " +
                    KnobSpace.text(knobs[i], point[i] * spans[i].step) +
                    if (search.parked(i)) "   at the end of its declared range" else "",
            )
        }

        if (knobs.indices.any { search.parked(it) }) {
            log(
                "[lab] NOTE: a knob that finished on its own bound is either a knob that does not " +
                    "matter -- the point drifted until the wall stopped it -- or one whose best " +
                    "value was never in range, and a trajectory cannot tell those apart. Read the " +
                    "confirmation below and nothing above it.",
            )
        }
        log("")
    }

    override fun toString(): String = "Spsa(${subject.slug}, ${knobs.joinToString { it.name }})"

    /** One iteration: place the two arms, buy or replay the gap, and move the point. */
    private fun step(
        registry: BotRegistry,
        search: Spsa,
        journal: SpsaJournal,
        recorded: SpsaJournal.Iteration?,
        iteration: Int,
        log: (String) -> Unit,
    ) {
        val probe = search.probe(iteration)
        val plus = settings(probe.plus, stock = false)
        val minus = settings(probe.minus, stock = false)
        val plusSpec = describe(plus)
        val minusSpec = describe(minus)
        check(plus != minus) {
            "iteration $iteration placed both arms on '$plusSpec', which is a bot against a copy of " +
                "itself. Raise --spread above ${SpsaSchedule.MINIMUM_SPREAD}."
        }

        val boardSeed = seed + iteration.toLong() * boardsPerIteration
        val gap = if (recorded != null) {
            require(recorded.matches(plusSpec, minusSpec)) {
                "$journalFile records iteration $iteration as '${recorded.plus}' against " +
                    "'${recorded.minus}', and this run would play '$plusSpec' against '$minusSpec'. " +
                    "A resume only replays the same search -- point --journal somewhere else."
            }
            recorded.gap.toDouble()
        } else {
            measure(registry, plus, minus, boardSeed)
        }

        search.apply(probe, gap)
        val point = describe(settings(search.point(), stock = true))

        if (recorded == null) {
            journal.append(
                SpsaJournal.Iteration(
                    iteration = iteration,
                    spread = decimals(probe.half.max()),
                    signs = probe.signs,
                    plus = plusSpec,
                    minus = minusSpec,
                    seed = boardSeed,
                    boards = boardsPerIteration,
                    gap = decimals(gap),
                    verdict = SpsaJournal.NONE,
                    point = point,
                ),
            )
        }

        log(
            "[lab] iter ${(iteration + 1).toString().padStart(ITERATION)}/$iterations  " +
                "${probe.signs}  gap ${signed(gap)}  ${point.ifEmpty { "stock" }}" +
                if (recorded != null) "  (replayed)" else "",
        )
    }

    /**
     * Plays the two arms against each other over one set of boards, and reports the gap.
     *
     * A **fixed, small** number of boards, deliberately unlike every other measurement in this tool.
     * A sequential test exists to reach a verdict about one comparison; an SPSA iteration needs no
     * verdict, only a direction that is right on average, and spending enough boards to settle each
     * one would buy certainty about a step the next iteration overwrites anyway. Boards are better
     * spent on more iterations — that is the whole shape of the method.
     */
    private fun measure(registry: BotRegistry, plus: BotParams, minus: BotParams, boardSeed: Long): Double {
        val batch = Arena(
            config = TournamentConfig(
                contestants = listOf(
                    Contestant(subject, params = minus),
                    Contestant(subject, params = plus),
                ),
                rows = rows,
                cols = cols,
                rounds = boardsPerIteration * MATCHES_PER_BOARD,
                seed = boardSeed,
                budgetPerTurn = budgetPerTurn,
                walls = walls,
            ),
            registry = registry,
            openings = openings,
            threads = threads,
        ).run()

        val scores = pairScores(batch, PLUS)
        check(scores.isNotEmpty()) { "a batch of ${batch.reports.size} matches produced no scored board" }

        boardsPlayed += scores.size
        boardsShared += scores.count { it == EVEN }
        forfeits += batch.forfeits
        matchesPlayed += batch.reports.size
        batch.reports.mapTo(distinctGames) { it.moveStreamHash }

        // Both arms met the same opposition on the same boards, so their scores sum to one and the
        // difference is twice the departure from even.
        return 2.0 * (scores.average() - EVEN)
    }

    /**
     * Confirms where the search finished, on boards it has never seen, and reports that.
     *
     * The only number here worth acting on. What the trajectory did is a record of attempts; this is
     * the finding, and it is one run of one instrument, which is why the command to reproduce it
     * independently is printed rather than described.
     */
    private fun finish(
        registry: BotRegistry,
        journal: SpsaJournal,
        point: DoubleArray,
        started: TimeSource.Monotonic.ValueTimeMark,
        log: (String) -> Unit,
    ) {
        sample(log)
        val proposal = settings(point, stock = true)

        if (proposal == fixed) {
            log("[lab] the search came back to where it started, ${entrant(fixed)} -- nothing to confirm.")
            log("[lab] ${started.elapsedNow().inWholeSeconds}s. That is a result: leave the defaults alone.")
            blindness(log)
            return
        }

        log("[lab] the search settled on ${describe(proposal)} -- an attempt, not yet a finding")
        log("[lab] confirming it on boards it has never seen, at a stricter bound...")

        val outcome = SequentialTest(
            baseline = Contestant(subject, params = fixed),
            candidate = Contestant(subject, params = proposal),
            rows = rows,
            cols = cols,
            seed = seed + TuneCommand.CONFIRM_SEED_OFFSET,
            budgetPerTurn = budgetPerTurn,
            walls = walls,
            openings = openings,
            threads = threads,
            sprt = confirm,
            blockPairs = CONFIRM_BLOCK,
            maxPairs = confirmPairs,
        ).run(registry)

        journal.append(
            SpsaJournal.Iteration(
                iteration = SpsaJournal.CONFIRMATION,
                spread = SpsaJournal.NONE,
                signs = SpsaJournal.NONE,
                plus = describe(proposal),
                minus = describe(fixed).ifEmpty { "stock" },
                seed = seed + TuneCommand.CONFIRM_SEED_OFFSET,
                boards = outcome.boards,
                gap = decimals(2.0 * (outcome.scores.average() - EVEN)),
                verdict = outcome.report.verdict.name,
                point = describe(proposal),
            ),
        )

        log("")
        verdict(outcome, proposal, log)
        log("[lab] ${started.elapsedNow().inWholeSeconds}s over $iterations iterations")
        blindness(log)
    }

    private fun verdict(outcome: SequentialTest.Outcome, proposal: BotParams, log: (String) -> Unit) {
        val elo = outcome.report.elo?.roundToInt()?.toString() ?: "an unbounded"

        if (outcome.better) {
            log("[lab] CONFIRMED at $elo Elo over ${outcome.boards} fresh boards.")
            log("[lab] recommended: ${entrant(proposal)}")
        } else {
            log(
                "[lab] NOT CONFIRMED: ${outcome.report.verdict} at $elo Elo over ${outcome.boards} " +
                    "fresh boards" + if (outcome.cappedOut) ", stopped at the board ceiling." else ".",
            )
            log("[lab] Where the search settled was fitted to the boards it searched. Do not adopt it.")
            log("[lab] That is a result, and the shipped defaults are what it argues for.")
        }

        // Printed either way. A confirmed point is still one run of one instrument against one
        // baseline, and the reader who acts on it should have started it themselves.
        log("")
        log("[lab] $journalFile is a record of attempts, not of findings: no iteration in it is a")
        log("[lab] recommendation, and the point above is worth exactly what this line says it is.")
        log("[lab] Re-run the confirmation on its own before acting on it:")
        log("[lab]   ${rerun(proposal)}")
    }

    /** The `ab` that decides it, spelled out so nobody has to reconstruct the conditions. */
    private fun rerun(proposal: BotParams): String = buildString {
        append("ab ${entrant(fixed)} ${entrant(proposal)}")
        append(" --rows $rows --cols $cols --budget $budgetPerTurn")
        append(" --elo0 ${trim(confirm.elo0)} --elo1 ${trim(confirm.elo1)}")
        if (openings != Openings.MIRRORED) {
            append(" --openings ${openings.name.lowercase()}")
        }
        append(" --log .lab/spsa-${subject.slug}-ab")
    }

    /** The honest sample size, and whether a bot threw — the two lines read before any other. */
    private fun sample(log: (String) -> Unit) {
        if (matchesPlayed == 0) {
            log("[lab] every iteration was replayed from the journal; nothing new was played")
            return
        }

        log("[lab] ${distinctGames.size} of $matchesPlayed matches were distinct games")
        if (forfeits > 0) {
            log("[lab] $forfeits FORFEITS -- a bot threw. That is a defect, and this search measured it.")
        }
    }

    /**
     * Says when a flat result may be this search's blind spot rather than a flat objective.
     *
     * Every gradient here is estimated head to head, so it inherits `AbCommand.blindness`: a knob
     * that only changes how this bot plays *other* opponents leaves every board split down the
     * middle, every gap at zero, and a trajectory that never leaves the defaults it started from.
     * Without this note that reads as a measurement of the knob.
     */
    private fun blindness(log: (String) -> Unit) {
        if (boardsPlayed == 0 || boardsShared * 2 < boardsPlayed) {
            return
        }

        log("")
        log(
            "[lab] NOTE: $boardsShared of $boardsPlayed boards split exactly, so on most of them the " +
                "two arms played the same game and there was no gradient to read. A search is head " +
                "to head, and a knob that only changes how ${subject.slug} plays *other* opponents " +
                "scores zero on every iteration of one. Sweep it against a field instead -- play a " +
                "few values alongside the rest of the ladder and read `rate`.",
        )
    }

    /**
     * A point in declared steps, back as the settings an entrant spec carries.
     *
     * [fixed] goes in first and unconditionally, on an arm and on a proposal alike: it is what the
     * search is being run *inside*, so dropping it from either would compare two configurations
     * rather than two points.
     */
    private fun settings(point: DoubleArray, stock: Boolean): BotParams {
        val values = LinkedHashMap<String, String>()
        for (name in fixed.names) {
            values[name] = fixed.string(name, "")
        }
        for (i in knobs.indices) {
            val text = KnobSpace.text(knobs[i], point[i] * spans[i].step)
            // A proposal drops what the registry already declares, so "back where it started" is
            // visibly empty rather than a spec that repeats the defaults back at the reader. An arm
            // keeps everything, so the two arms differ only where the search moved them.
            if (!stock || !knobs[i].isDefault(text)) {
                values[knobs[i].name] = text
            }
        }
        return BotParams(values)
    }

    /** A knob's value counted in its own declared steps, which is the unit the schedule speaks. */
    private fun steps(knob: Int, value: Double): Double = value / spans[knob].step

    private fun describe(params: BotParams): String =
        params.names.joinToString(",") { "$it=${params.string(it, "")}" }

    /** A settings map as something that parses back — which is what makes a printed command pasteable. */
    private fun entrant(params: BotParams): String =
        if (params.isEmpty) subject.slug else "${subject.slug}:${describe(params)}"

    private fun decimals(value: Double): String = String.format(Locale.ROOT, "%.${PLACES}f", value)

    private fun signed(value: Double): String = if (value < 0) decimals(value) else "+${decimals(value)}"

    private fun trim(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else value.toString()

    private companion object {
        /** The minus arm enters first, so the plus arm is contestant one. */
        const val PLUS = 1

        /** A board is played from both seats, which is what makes it one observation. */
        const val MATCHES_PER_BOARD = 2

        /** A board shared down the middle — what two arms that play alike score on every one. */
        const val EVEN = 0.5

        /** Boards between checks in the confirmation, matching what `ab` uses by default. */
        const val CONFIRM_BLOCK = 20

        /** Enough to read a gap of a hundredth of a board, which is finer than one is ever measured. */
        const val PLACES = 4

        const val ITERATION = 3
    }
}
