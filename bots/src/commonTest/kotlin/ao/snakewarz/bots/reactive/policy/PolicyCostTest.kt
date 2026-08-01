package ao.snakewarz.bots.reactive.policy

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.HeadlessMatch
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.search.FixedDepthBot
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Browser prices for P2's unreleased no-tree policies and P4's fixed-depth bridge.
 *
 * A Space-v-Space match supplies the line. The timed seat asks a subject for its answer and throws
 * that answer away before asking its independently seeded Space bot for the move actually played.
 * Thus every subject sees the same turns, bodies and walls, none chose a position on the tape, and
 * measuring a policy cannot perturb a later sample. The complete ordered bodies are fingerprinted
 * because [BoardView.hash] deliberately omits their order.
 *
 * Each map is a separate test so a browser run receives a completion heartbeat between timing
 * blocks. The wall fixtures are P1's generated maps captured as playable indices; keeping the
 * generator out of `:bots` preserves the module boundary and the fingerprints detect fixture drift.
 * The no-tree rows use fresh stock Chase controls. The fixed-depth rows use fresh Cartographer
 * controls and call a budgeted subject exactly once per sampled turn; repeating one after its first
 * call would spend the shared turn budget and price fallback instead of search. Read the Chrome
 * figures against P1's envelopes; the cost assertion itself is only a deliberately loose ceiling.
 */
class PolicyCostTest {
    @Test
    fun `policies on empty 8x8`() = price(EMPTY_8)

    @Test
    fun `policies on arena 12x12`() = price(ARENA_12)

    @Test
    fun `policies on cross 12x12`() = price(CROSS_12)

    @Test
    fun `policies on rooms 16x16`() = price(ROOMS_16)

    @Test
    fun `policies on double spiral 16x16`() = price(DOUBLE_SPIRAL_16)

    @Test
    fun `policies on seeded islands 16x16`() = price(ISLANDS_16)

    @Test
    fun `fixed depths on empty 8x8`() = priceFixedDepth(EMPTY_8)

    @Test
    fun `fixed depths on arena 12x12`() = priceFixedDepth(ARENA_12)

    @Test
    fun `fixed depths on cross 12x12`() = priceFixedDepth(CROSS_12)

    @Test
    fun `fixed depths on rooms 16x16`() = priceFixedDepth(ROOMS_16)

    @Test
    fun `fixed depths on double spiral 16x16`() = priceFixedDepth(DOUBLE_SPIRAL_16)

    @Test
    fun `fixed depths on seeded islands 16x16`() = priceFixedDepth(ISLANDS_16)

    private fun price(board: BoardSpec) {
        val paddedWalls = board.paddedWalls()
        val expected = p1Tape(board, paddedWalls)

        println(
            "[policy-cost] board=${board.label} geometry=${board.rows}x${board.cols} " +
                "walls=${board.walls.size} wall-fingerprint=${fingerprint(board.walls)} " +
                "line-seed=$MATCH_SEED line-turns=${expected.lineTurns} stride=${expected.stride} " +
                "samples=${expected.positions.size} position-fingerprint=${fingerprint(expected.positions)}",
        )

        val subjects = PolicyVariant.entries.map { variant ->
            SubjectSpec(variant.key, BotFactory { setup -> PolicyBot(setup, variant) })
        }

        // Heat the shared call site and every implementation before collecting a paired pass.
        time(board, paddedWalls, CONTROL, expected.stride, expected.indices, expected.positions)
        for (subject in subjects) {
            time(board, paddedWalls, subject, expected.stride, expected.indices, expected.positions)
        }

        for (subject in subjects) {
            val passes = List(PASSES) {
                PairedTiming(
                    before = time(board, paddedWalls, CONTROL, expected.stride, expected.indices, expected.positions),
                    subject = time(board, paddedWalls, subject, expected.stride, expected.indices, expected.positions),
                    after = time(board, paddedWalls, CONTROL, expected.stride, expected.indices, expected.positions),
                )
            }
            report(board, subject, passes)
        }
    }

    private fun p1Tape(board: BoardSpec, paddedWalls: IntArray): TapeExpectation {
        assertEquals(board.wallCount, board.walls.size, "${board.label} wall count")
        assertEquals(board.wallFingerprint, fingerprint(board.walls), "${board.label} wall fingerprint")

        val probe = play(board, paddedWalls, subject = null, stride = 1)
        val stride = maxOf(1, probe.lineTurns / SAMPLE_TARGET)
        val expectedIndices = probe.sampleIndices.filter { it % stride == 0 }
        val expectedPositions = probe.positions.filterIndexed { index, _ -> index % stride == 0 }
        assertEquals(board.lineTurns, probe.lineTurns, "${board.label} P1 line turns")
        assertEquals(board.stride, stride, "${board.label} P1 stride")
        assertEquals(board.samples, expectedPositions.size, "${board.label} P1 sample count")
        assertEquals(
            board.positionFingerprint,
            fingerprint(expectedPositions),
            "${board.label} P1 position fingerprint",
        )
        assertTrue(
            expectedPositions.size >= SAMPLE_TARGET,
            "${board.label} supplied only ${expectedPositions.size} sampled positions",
        )
        return TapeExpectation(probe.lineTurns, stride, expectedIndices, expectedPositions)
    }

    private fun priceFixedDepth(board: BoardSpec) {
        val paddedWalls = board.paddedWalls()
        val expected = p1Tape(board, paddedWalls)

        println(
            "[fixed-depth-cost] board=${board.label} geometry=${board.rows}x${board.cols} " +
                "walls=${board.walls.size} wall-fingerprint=${fingerprint(board.walls)} " +
                "match-seed=$MATCH_SEED line-seed=$LINE_SEED line-turns=${expected.lineTurns} " +
                "stride=${expected.stride} samples=${expected.positions.size} " +
                "position-fingerprint=${fingerprint(expected.positions)}",
        )

        for (subject in FIXED_DEPTH_SUBJECTS) {
            val coverage = fixedPlay(board, paddedWalls, subject, expected.stride)
            assertFixedTape(board, subject, coverage, expected)
            reportCoverage(board, subject, coverage.coverage)
        }

        // Coverage warmed the three search implementations. Heat the distinct control path too.
        fixedTime(board, paddedWalls, FIXED_DEPTH_CONTROL, expected)

        for (subject in FIXED_DEPTH_SUBJECTS) {
            val passes = List(PASSES) {
                PairedTiming(
                    before = fixedTime(board, paddedWalls, FIXED_DEPTH_CONTROL, expected),
                    subject = fixedTime(board, paddedWalls, subject, expected),
                    after = fixedTime(board, paddedWalls, FIXED_DEPTH_CONTROL, expected),
                )
            }
            reportFixedCost(board, subject, passes)
        }
    }

    private fun fixedTime(
        board: BoardSpec,
        paddedWalls: IntArray,
        subject: FixedSubjectSpec,
        expected: TapeExpectation,
    ): Timing {
        val tape = fixedPlay(board, paddedWalls, subject, expected.stride)
        assertFixedTape(board, subject, tape, expected)
        assertTrue(tape.timedDecisions > 0, "${board.label} ${subject.key} was never timed")
        assertTrue(
            tape.maxEvaluations <= subject.allowance,
            "${board.label} ${subject.key} spent ${tape.maxEvaluations}/${subject.allowance}",
        )
        assertTrue(
            tape.totalEvaluations <= subject.allowance * expected.positions.size,
            "${board.label} ${subject.key} overspent ${tape.totalEvaluations} aggregate evaluations",
        )
        assertTrue(
            tape.worst < COST_CEILING_MICROS,
            "${board.label} ${subject.key} took ${tape.worst} us for one decision",
        )
        return Timing(tape.micros, tape.timedDecisions, tape.worst)
    }

    private fun fixedPlay(
        board: BoardSpec,
        paddedWalls: IntArray,
        subject: FixedSubjectSpec,
        stride: Int,
    ): FixedTapeSeat {
        var tape: FixedTapeSeat? = null
        val lineEntry = ShippedBots.entryOf(SPACE_ID)
        val seatEntry = BotEntry(
            id = SEAT_ID,
            displayName = "Fixed-depth cost seat",
            factory = BotFactory { setup ->
                val measured = subject.factory.create(setup)
                val fixedDepth = measured as? FixedDepthBot
                if (subject.depth == null) {
                    check(fixedDepth == null) { "the Cartographer control unexpectedly built a fixed-depth bot" }
                } else {
                    check(fixedDepth?.requestedDepth == subject.depth) {
                        "${subject.key} built depth ${fixedDepth?.requestedDepth}, expected ${subject.depth}"
                    }
                }
                FixedTapeSeat(
                    subject = measured,
                    fixedDepth = fixedDepth,
                    line = lineEntry.factory.create(lineSetup(setup)),
                    allowance = subject.allowance,
                    repetitions = subject.repetitions,
                    stride = stride,
                ).also { tape = it }
            },
        )

        HeadlessMatch(
            entries = listOf(seatEntry, lineEntry),
            rows = board.rows,
            cols = board.cols,
            seed = MATCH_SEED,
            budgetPerTurn = subject.allowance,
            walls = paddedWalls,
            turnOrder = TURN_ORDER,
            recording = false,
            budgetPerSlot = intArrayOf(subject.allowance, 0),
        ).run()

        return checkNotNull(tape) { "the fixed-depth timing seat was never built" }
    }

    private fun assertFixedTape(
        board: BoardSpec,
        subject: FixedSubjectSpec,
        actual: FixedTapeSeat,
        expected: TapeExpectation,
    ) {
        assertEquals(expected.indices, actual.sampleIndices, "${board.label} ${subject.key} sampled different turns")
        assertEquals(expected.positions, actual.positions, "${board.label} ${subject.key} did not see the P1 tape")
    }

    private fun reportCoverage(board: BoardSpec, subject: FixedSubjectSpec, coverage: FixedCoverage) {
        val depth = checkNotNull(subject.depth)
        assertEquals(coverage.sampled, coverage.forced + coverage.searchable, "${board.label} depth $depth roots")
        assertEquals(
            coverage.searchable,
            coverage.completed + coverage.fallbacks,
            "${board.label} depth $depth search outcomes",
        )
        assertTrue(coverage.searchable > 0, "${board.label} depth $depth had no searchable roots")
        assertEquals(0, coverage.fallbacks, "${board.label} depth $depth unexpectedly fell back at its full cap")

        println(
            "[fixed-depth-coverage] board=${board.label} requested-depth=$depth allowance=${subject.allowance} " +
                "sampled=${coverage.sampled} forced-roots=${coverage.forced} searchable-roots=${coverage.searchable} " +
                "completed-depth=0:${coverage.sampled - coverage.completed},$depth:${coverage.completed} " +
                "fallbacks=${coverage.fallbacks}/${coverage.searchable} static-leaves=${coverage.staticLeaves} " +
                "terminal-leaves=${coverage.terminalLeaves} spent-total=${coverage.totalEvaluations} " +
                "spent-max=${coverage.maxEvaluations}",
        )
    }

    private fun reportFixedCost(board: BoardSpec, subject: FixedSubjectSpec, passes: List<PairedTiming>) {
        val depth = checkNotNull(subject.depth)
        val medianMean = passes.map { it.subject.mean }.sorted()[PASSES / 2]
        val largestTurn = passes.maxOf { it.subject.worst }
        val stablePairs = passes.count {
            controlsAreStable(
                it.before.totalMicros,
                it.before.decisions,
                it.after.totalMicros,
                it.after.decisions,
            )
        }
        val raw = passes.joinToString("/") {
            "${it.subject.mean}:${it.subject.worst}|" +
                "${it.before.mean}:${it.before.worst}|${it.after.mean}:${it.after.worst}"
        }

        println(
            "[fixed-depth-cost] board=${board.label} depth=$depth allowance=${subject.allowance} " +
                "control=${FIXED_DEPTH_CONTROL.key} passes=$PASSES median-mean-us=$medianMean " +
                "largest-raw-turn-us=$largestTurn tiny-envelope=${largestTurn <= TINY_ENVELOPE_MICROS} " +
                "standard-envelope=${largestTurn <= STANDARD_ENVELOPE_MICROS} " +
                "stable-control-pairs=$stablePairs/$PASSES stable=${stablePairs >= REQUIRED_STABLE_PAIRS} " +
                "raw=subject-mean:largest|control-before-mean:largest|control-after-mean:largest=$raw",
        )
    }

    private fun time(
        board: BoardSpec,
        paddedWalls: IntArray,
        subject: SubjectSpec,
        stride: Int,
        expectedIndices: List<Int>,
        expectedPositions: List<Long>,
    ): Timing {
        val tape = play(board, paddedWalls, subject, stride)
        assertEquals(expectedIndices, tape.sampleIndices, "${board.label} ${subject.key} sampled different turns")
        assertEquals(expectedPositions, tape.positions, "${board.label} ${subject.key} did not see the fixed line")
        assertEquals(0, tape.consumedEvaluations, "${board.label} ${subject.key} consumed search evaluations")
        assertTrue(tape.appraisals > 0, "${board.label} ${subject.key} was never timed")
        assertTrue(
            tape.worst < COST_CEILING_MICROS,
            "${board.label} ${subject.key} took ${tape.worst} us for one decision",
        )
        return Timing(tape.micros, tape.appraisals, tape.worst)
    }

    private fun play(
        board: BoardSpec,
        paddedWalls: IntArray,
        subject: SubjectSpec?,
        stride: Int,
    ): TapeSeat {
        var tape: TapeSeat? = null
        val lineEntry = ShippedBots.entryOf(SPACE_ID)
        val seatEntry = BotEntry(
            id = SEAT_ID,
            displayName = "Policy cost seat",
            factory = BotFactory { setup ->
                TapeSeat(
                    subject = subject?.factory?.create(setup),
                    line = lineEntry.factory.create(lineSetup(setup)),
                    stride = stride,
                ).also { tape = it }
            },
        )

        HeadlessMatch(
            entries = listOf(seatEntry, lineEntry),
            rows = board.rows,
            cols = board.cols,
            seed = MATCH_SEED,
            budgetPerTurn = 0,
            walls = paddedWalls,
            turnOrder = TURN_ORDER,
            recording = false,
        ).run()

        // HeadlessMatch deliberately has no forfeit recovery: any thrown decision fails this test.
        return checkNotNull(tape) { "the timing seat was never built" }
    }

    private fun report(board: BoardSpec, subject: SubjectSpec, passes: List<PairedTiming>) {
        val medianMean = passes.map { it.subject.mean }.sorted()[PASSES / 2]
        val largestTurn = passes.maxOf { it.subject.worst }
        val stablePairs = passes.count {
            controlsAreStable(
                it.before.totalMicros,
                it.before.decisions,
                it.after.totalMicros,
                it.after.decisions,
            )
        }
        val raw = passes.joinToString("/") {
            "${it.subject.mean}:${it.subject.worst}|" +
                "${it.before.mean}:${it.before.worst}|${it.after.mean}:${it.after.worst}"
        }

        println(
            "[policy-cost] board=${board.label} policy=${subject.key} control=${CONTROL.key} passes=$PASSES " +
                "median-mean-us=$medianMean largest-raw-turn-us=$largestTurn " +
                "stable-control-pairs=$stablePairs/$PASSES stable=${stablePairs >= REQUIRED_STABLE_PAIRS} " +
                "raw=subject-mean:largest|control-before-mean:largest|control-after-mean:largest=$raw",
        )
    }

    private fun lineSetup(setup: BotSetup): BotSetup =
        BotSetup(
            self = setup.self,
            grid = setup.grid,
            rules = setup.rules,
            opponents = setup.opponents.copyOf(),
            rng = SplitMix64(LINE_SEED),
            params = BotParams.EMPTY,
        )

    /** The timed seat follows [line] and never plays [subject]'s answer. */
    private class TapeSeat(
        private val subject: Bot?,
        private val line: Bot,
        private val stride: Int,
    ) : Bot {
        val sampleIndices: MutableList<Int> = mutableListOf()
        val positions: MutableList<Long> = mutableListOf()

        var micros: Long = 0
            private set

        var worst: Long = 0
            private set

        var consumedEvaluations: Int = 0
            private set

        val appraisals: Int get() = timedDecisions
        val lineTurns: Int get() = ownTurns

        private var ownTurns = 0
        private var timedDecisions = 0

        override fun chooseMove(turn: Turn): Decision {
            if (ownTurns % stride == 0) {
                sampleIndices += ownTurns
                positions += positionFingerprint(turn.board)

                if (subject != null) {
                    check(turn.budget.limit == 0) { "the free policy received allowance ${turn.budget.limit}" }
                    check(turn.budget.consumed == 0) {
                        "the policy began with ${turn.budget.consumed} evaluations already spent"
                    }

                    // Preserve one raw call per sampled position for the free-lane worst-turn gate.
                    // Chase is faster than the browser clock's 100 us tick, however, so a single-call
                    // mean makes paired control stability a coin toss. The separate batch gives the
                    // mean enough duration to resolve without hiding a raw subject outlier.
                    val rawMark = TimeSource.Monotonic.markNow()
                    val rawDecision = subject.chooseMove(turn)
                    val rawElapsed = rawMark.elapsedNow().inWholeMicroseconds
                    check(rawDecision is Decision.Move) { "a no-tree policy returned $rawDecision" }
                    if (rawElapsed > worst) {
                        worst = rawElapsed
                    }

                    val batchMark = TimeSource.Monotonic.markNow()
                    repeat(TIMED_REPETITIONS) {
                        val decision = subject.chooseMove(turn)
                        check(decision is Decision.Move) { "a no-tree policy returned $decision" }
                    }
                    micros += batchMark.elapsedNow().inWholeMicroseconds
                    timedDecisions += TIMED_REPETITIONS
                    consumedEvaluations += turn.budget.consumed
                }
            }

            ownTurns++
            val move = line.chooseMove(turn)
            check(move is Decision.Move) { "the Space line returned $move" }
            check(turn.budget.consumed == 0) { "the Space line consumed ${turn.budget.consumed} evaluations" }
            return move
        }
    }

    /** A budgeted subject gets one call per fresh turn; only the zero-cost control is batched. */
    private class FixedTapeSeat(
        private val subject: Bot,
        private val fixedDepth: FixedDepthBot?,
        private val line: Bot,
        private val allowance: Int,
        private val repetitions: Int,
        private val stride: Int,
    ) : Bot {
        val sampleIndices: MutableList<Int> = mutableListOf()
        val positions: MutableList<Long> = mutableListOf()
        val coverage: FixedCoverage = FixedCoverage()

        var micros: Long = 0
            private set

        var worst: Long = 0
            private set

        var totalEvaluations: Int = 0
            private set

        var maxEvaluations: Int = 0
            private set

        var timedDecisions: Int = 0
            private set

        private var ownTurns = 0

        init {
            require(repetitions > 0) { "timed repetitions must be positive, was $repetitions" }
            require(repetitions == 1 || allowance == 0) {
                "only a zero-cost control may be repeated on one turn, got $repetitions at $allowance"
            }
        }

        override fun chooseMove(turn: Turn): Decision {
            if (ownTurns % stride == 0) {
                sampleIndices += ownTurns
                positions += positionFingerprint(turn.board)
                check(turn.budget.limit == allowance) {
                    "the subject received ${turn.budget.limit}, expected the fixed allowance $allowance"
                }
                check(turn.budget.consumed == 0) {
                    "the subject began with ${turn.budget.consumed} evaluations already spent"
                }

                if (repetitions == 1) {
                    val mark = TimeSource.Monotonic.markNow()
                    val decision = subject.chooseMove(turn)
                    val elapsed = mark.elapsedNow().inWholeMicroseconds
                    check(decision is Decision.Move) { "the fixed-depth subject returned $decision" }
                    micros += elapsed
                    timedDecisions++
                    if (elapsed > worst) {
                        worst = elapsed
                    }
                } else {
                    // Cartographer is below Chrome's timer quantum. Preserve a raw call for its
                    // outlier column, then batch its zero-budget mean for the drift check.
                    val rawMark = TimeSource.Monotonic.markNow()
                    val rawDecision = subject.chooseMove(turn)
                    val rawElapsed = rawMark.elapsedNow().inWholeMicroseconds
                    check(rawDecision is Decision.Move) { "the fixed-depth control returned $rawDecision" }
                    if (rawElapsed > worst) {
                        worst = rawElapsed
                    }

                    val batchMark = TimeSource.Monotonic.markNow()
                    repeat(repetitions) {
                        val decision = subject.chooseMove(turn)
                        check(decision is Decision.Move) { "the fixed-depth control returned $decision" }
                    }
                    micros += batchMark.elapsedNow().inWholeMicroseconds
                    timedDecisions += repetitions
                }

                val spent = turn.budget.consumed
                check(spent <= allowance) { "the subject overspent $spent/$allowance evaluations" }
                totalEvaluations += spent
                if (spent > maxEvaluations) {
                    maxEvaluations = spent
                }
                fixedDepth?.let { coverage.record(it, spent, allowance) }
            }

            ownTurns++
            val spent = turn.budget.consumed
            val move = line.chooseMove(turn)
            check(move is Decision.Move) { "the Space line returned $move" }
            check(turn.budget.consumed == spent) {
                "the Space line changed the budget from $spent to ${turn.budget.consumed}"
            }
            return move
        }
    }

    private class FixedCoverage {
        var sampled: Int = 0
            private set
        var forced: Int = 0
            private set
        var searchable: Int = 0
            private set
        var completed: Int = 0
            private set
        var fallbacks: Int = 0
            private set
        var staticLeaves: Int = 0
            private set
        var terminalLeaves: Int = 0
            private set
        var totalEvaluations: Int = 0
            private set
        var maxEvaluations: Int = 0
            private set

        fun record(bot: FixedDepthBot, spent: Int, allowance: Int) {
            sampled++
            staticLeaves += bot.lastStaticLeaves
            terminalLeaves += bot.lastTerminalLeaves
            totalEvaluations += spent
            if (spent > maxEvaluations) {
                maxEvaluations = spent
            }

            check(spent == bot.lastStaticLeaves) {
                "depth ${bot.requestedDepth} reported ${bot.lastStaticLeaves} paid leaves after spending $spent"
            }
            check(spent <= allowance) { "depth ${bot.requestedDepth} overspent $spent/$allowance" }

            if (bot.lastForced) {
                forced++
                check(bot.lastCompletedDepth == 0 && !bot.lastFallbackUsed) {
                    "a forced depth-${bot.requestedDepth} root was reported as searched or fallback"
                }
                check(spent == 0 && bot.lastTerminalLeaves == 0) {
                    "a forced depth-${bot.requestedDepth} root evaluated leaves"
                }
                return
            }

            searchable++
            when {
                bot.lastCompletedDepth == bot.requestedDepth && !bot.lastFallbackUsed -> completed++
                bot.lastCompletedDepth == 0 && bot.lastFallbackUsed -> fallbacks++
                else -> error(
                    "depth ${bot.requestedDepth} ended at ${bot.lastCompletedDepth}, " +
                        "fallback=${bot.lastFallbackUsed}",
                )
            }
        }
    }

    private class BoardSpec(
        val label: String,
        val rows: Int,
        val cols: Int,
        val walls: IntArray,
        val wallCount: Int,
        val wallFingerprint: Long,
        val lineTurns: Int,
        val stride: Int,
        val samples: Int,
        val positionFingerprint: Long,
    ) {
        fun paddedWalls(): IntArray {
            val grid = Grid(rows, cols)
            return IntArray(walls.size) { index ->
                val playable = walls[index]
                grid.cellAt(playable / cols, playable % cols).index
            }
        }
    }

    private class SubjectSpec(val key: String, val factory: BotFactory)

    private class FixedSubjectSpec(
        val key: String,
        val depth: Int?,
        val allowance: Int,
        val repetitions: Int,
        val factory: BotFactory,
    )

    private class TapeExpectation(
        val lineTurns: Int,
        val stride: Int,
        val indices: List<Int>,
        val positions: List<Long>,
    )

    private class Timing(
        val totalMicros: Long,
        val decisions: Int,
        val worst: Long,
    ) {
        val mean: Long get() = totalMicros / decisions
    }

    private class PairedTiming(
        val before: Timing,
        val subject: Timing,
        val after: Timing,
    )

    private companion object {
        val SEAT_ID = BotId("policy-cost-seat")
        val SPACE_ID = BotId("space")

        const val MATCH_SEED = 424_242L
        const val LINE_SEED = 0x5EA7L
        const val SAMPLE_TARGET = 24
        const val PASSES = 5
        const val TIMED_REPETITIONS = 100
        const val REQUIRED_STABLE_PAIRS = 4
        const val COST_CEILING_MICROS = 333_000L
        const val TINY_ENVELOPE_MICROS = 3_500L
        const val STANDARD_ENVELOPE_MICROS = 5_500L

        // MatchSetup.create's seeded production order for MATCH_SEED. Keeping this explicit makes
        // the P2 tape begin on the same seat and growth phase as P1's Chrome cost tape.
        val TURN_ORDER = intArrayOf(1, 0)

        val CONTROL = SubjectSpec("chase", ShippedBots.entryOf(BotId("chase")).factory)

        val FIXED_DEPTH_CONTROL = FixedSubjectSpec(
            key = "cartographer",
            depth = null,
            allowance = 0,
            repetitions = TIMED_REPETITIONS,
            factory = ShippedBots.entryOf(BotId("cartographer")).factory,
        )

        val FIXED_DEPTH_SUBJECTS = listOf(
            FixedSubjectSpec(
                key = "fixed-depth-1",
                depth = 1,
                allowance = 4,
                repetitions = 1,
                factory = BotFactory { setup -> FixedDepthBot(setup, 1) },
            ),
            FixedSubjectSpec(
                key = "fixed-depth-2",
                depth = 2,
                allowance = 16,
                repetitions = 1,
                factory = BotFactory { setup -> FixedDepthBot(setup, 2) },
            ),
            FixedSubjectSpec(
                key = "fixed-depth-3",
                depth = 3,
                allowance = 64,
                repetitions = 1,
                factory = BotFactory { setup -> FixedDepthBot(setup, 3) },
            ),
        )

        val EMPTY_8 = BoardSpec(
            label = "empty-8",
            rows = 8,
            cols = 8,
            walls = intArrayOf(),
            wallCount = 0,
            wallFingerprint = FNV_OFFSET,
            lineTurns = 37,
            stride = 1,
            samples = 37,
            positionFingerprint = -9071965372845670272L,
        )
        val ARENA_12 = BoardSpec(
            label = "arena-12",
            rows = 12,
            cols = 12,
            walls = intArrayOf(
                26, 33, 52, 53, 54, 55, 64, 65, 66, 67, 76, 77, 78, 79, 88, 89, 90, 91, 110, 117,
            ),
            wallCount = 20,
            wallFingerprint = -2806513570130820655L,
            lineTurns = 29,
            stride = 1,
            samples = 29,
            positionFingerprint = -4686029232502001735L,
        )
        val CROSS_12 = BoardSpec(
            label = "cross-12",
            rows = 12,
            cols = 12,
            walls = intArrayOf(
                5, 6, 17, 18, 29, 30, 41, 42, 60, 61, 62, 63, 68, 69, 70, 71,
                72, 73, 74, 75, 80, 81, 82, 83, 101, 102, 113, 114, 125, 126, 137, 138,
            ),
            wallCount = 32,
            wallFingerprint = -311692401760026427L,
            lineTurns = 48,
            stride = 2,
            samples = 24,
            positionFingerprint = 8668226761517369966L,
        )
        val ROOMS_16 = BoardSpec(
            label = "rooms-16",
            rows = 16,
            cols = 16,
            walls = intArrayOf(
                4, 11, 52, 59, 64, 67, 68, 69, 70, 73, 74, 75, 76, 79, 84, 91, 100, 107,
                148, 155, 164, 171, 176, 179, 180, 181, 182, 185, 186, 187, 188, 191, 196, 203, 244, 251,
            ),
            wallCount = 36,
            wallFingerprint = 8771100865974249605L,
            lineTurns = 77,
            stride = 3,
            samples = 26,
            positionFingerprint = -4619791786702958675L,
        )
        val DOUBLE_SPIRAL_16 = BoardSpec(
            label = "double-spiral-16",
            rows = 16,
            cols = 16,
            walls = intArrayOf(
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 44, 60, 76, 83, 84, 85,
                86, 87, 88, 92, 99, 108, 115, 124, 131, 140, 147, 156, 163, 167, 168, 169,
                170, 171, 172, 179, 195, 211, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236,
                237, 238,
            ),
            wallCount = 52,
            wallFingerprint = 7564411957312032129L,
            lineTurns = 67,
            stride = 2,
            samples = 34,
            positionFingerprint = -8112187856928568512L,
        )
        val ISLANDS_16 = BoardSpec(
            label = "islands-16-seed-61001",
            rows = 16,
            cols = 16,
            walls = intArrayOf(
                28, 36, 38, 54, 56, 57, 62, 72, 73, 78, 94,
                161, 177, 182, 183, 193, 198, 199, 201, 217, 219, 227,
            ),
            wallCount = 22,
            wallFingerprint = -5944746775832584082L,
            lineTurns = 111,
            stride = 4,
            samples = 28,
            positionFingerprint = -2019814329201616960L,
        )
    }
}

/** Full line identity, including the body ordering that [BoardView.hash] deliberately omits. */
private fun positionFingerprint(board: BoardView): Long {
    var hash = FNV_OFFSET
    hash = mix(hash, board.turnIndex)
    hash = mix(hash, board.toAct.index)
    hash = mix(hash, board.aliveCount)
    hash = mix(hash, board.openCount)
    for (slot in 0 until board.snakeCount) {
        val snake = board.snake(SnakeId(slot))
        hash = mix(hash, slot)
        hash = mix(hash, if (snake.alive) 1 else 0)
        hash = mix(hash, snake.eliminationReason?.ordinal ?: -1)
        hash = mix(hash, snake.movesMade)
        hash = mix(hash, if (snake.growsOnNextMove) 1 else 0)
        hash = mix(hash, snake.lastDirection?.ordinal ?: -1)
        hash = mix(hash, snake.length)
        for (part in 0 until snake.length) {
            hash = mix(hash, snake.cellAt(part).index)
        }
    }
    return hash
}

private fun fingerprint(values: IntArray): Long {
    var hash = FNV_OFFSET
    for (value in values) {
        hash = mix(hash, value)
    }
    return hash
}

private fun fingerprint(values: List<Long>): Long {
    var hash = FNV_OFFSET
    for (value in values) {
        hash = (hash xor value) * FNV_PRIME
    }
    return hash
}

private fun mix(hash: Long, value: Int): Long = (hash xor value.toLong()) * FNV_PRIME

/** Difference no greater than 15% of the before/after pair mean, without floating-point drift. */
private fun controlsAreStable(
    beforeMicros: Long,
    beforeDecisions: Int,
    afterMicros: Long,
    afterDecisions: Int,
): Boolean {
    // Compare the full batch rates without truncating either to integer microseconds per decision.
    // Cross multiplication also keeps this portable: no floating point enters the stability gate.
    val left = beforeMicros * afterDecisions
    val right = afterMicros * beforeDecisions
    val difference = if (left >= right) left - right else right - left
    return difference * 200L <= (left + right) * 15L
}

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
