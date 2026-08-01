package ao.snakewarz.app

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.search.FixedDepthResearch
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Chrome prices for the retained fixed research lanes, over positions none of the subjects chose.
 *
 * The subject is asked for a decision from seat 0 and its answer is discarded. A Space Filler with
 * its own fixed RNG supplies the move that is actually played, against another Space Filler. Thus
 * every configuration sees the same turns at the same fill, including on maps with walls. The
 * complete ordered bodies are fingerprinted at every sample; `BoardView.hash` alone cannot prove
 * that identity because it deliberately omits body ordering.
 *
 * Each board is a separate test so Karma receives a completion heartbeat between the deliberately
 * long timing blocks. The browser suite runs every declared row because filtering a Kotlin/Wasm
 * test is not a dependable research protocol. It prints only `[chrome-cost]` lines so the whole run
 * can be redirected and retained.
 */
class ChromeCostTest {
    @Test
    fun `phase one entrants on empty 8x8`() = price(EMPTY_8)

    @Test
    fun `phase one entrants on arena 12x12`() = price(ARENA_12)

    @Test
    fun `phase one entrants on rooms 16x16`() = price(ROOMS_16)

    @Test
    fun `phase one entrants on seeded islands 16x16`() = price(ISLANDS_16)

    @Test
    fun `phase five empty 8x8 allowance grid`() = pricePhaseFive("exact", PHASE_FIVE_GRID)

    @Test
    fun `phase two level three arena grid`() = pricePhaseTwo(ARENA_12, PHASE_TWO_LEVEL_THREE)

    @Test
    fun `phase two level four scatter grid`() = pricePhaseTwo(SCATTER_12, PHASE_TWO_SEARCH_GRID)

    @Test
    fun `phase two level five islands grid`() = pricePhaseTwo(ISLANDS_12, PHASE_TWO_SEARCH_GRID)

    @Test
    fun `phase two level six pinwheel grid`() = pricePhaseTwo(PINWHEEL_12, PHASE_TWO_SEARCH_GRID)

    @Test
    fun `phase five empty 8x8 allowance curve additions`() =
        pricePhaseFive("allowance-curve-additions", PHASE_FIVE_CURVE_ADDITIONS)

    @Test
    fun `phase seven candidate table`() = pricePhaseSeven()

    private fun price(board: BoardSpec) {
        val walls = generateMap(
            rows = board.rows,
            cols = board.cols,
            shape = board.shape,
            density = board.density,
            seed = board.mapSeed,
        ).walls()
        val probe = play(board, walls, subject = null, stride = 1)
        val stride = maxOf(1, probe.lineTurns / SAMPLES)
        val expectedIndices = probe.sampleIndices.filter { it % stride == 0 }
        val expectedPositions = probe.positions.filterIndexed { index, _ -> index % stride == 0 }

        println(
            "[chrome-cost] board=${board.label} geometry=${board.rows}x${board.cols} " +
                "map=${board.shape.slug} density=${board.densityLabel} map-seed=${board.mapSeed} " +
                "walls=${walls.size} wall-fingerprint=${fingerprint(walls)} line-seed=$MATCH_SEED " +
                "line-turns=${probe.lineTurns} stride=$stride samples=${expectedPositions.size} " +
                "position-fingerprint=${fingerprint(expectedPositions)}",
        )

        val entrants = entrantsFor(board)
        val warmed = (listOf(CONTROL) + entrants).distinctBy { it.label }
        for (subject in warmed) {
            time(board, walls, subject, stride, expectedIndices, expectedPositions)
        }

        for (subject in entrants) {
            val passes = List(PASSES) {
                val before = time(board, walls, CONTROL, stride, expectedIndices, expectedPositions)
                val measured = time(board, walls, subject, stride, expectedIndices, expectedPositions)
                val after = time(board, walls, CONTROL, stride, expectedIndices, expectedPositions)
                PairedTiming(before, measured, after)
            }
            report(board, subject, passes)
        }
    }

    private fun pricePhaseFive(gridLabel: String, entrants: List<EntrantSpec>) {
        val board = EMPTY_8
        val tape = fixedTape(board)

        println(
            "[chrome-cost] phase=5 grid=$gridLabel no-interpolation=true board=${board.label} " +
                "geometry=${board.rows}x${board.cols} map=${board.shape.slug} walls=${tape.walls.size} " +
                "wall-fingerprint=${fingerprint(tape.walls)} line-seed=$MATCH_SEED " +
                "line-turns=${tape.lineTurns} stride=${tape.stride} samples=${tape.expectedPositions.size} " +
                "position-fingerprint=${fingerprint(tape.expectedPositions)} entrants=${entrants.size}",
        )

        priceFixedTape(
            phase = 5,
            board = board,
            entrants = entrants,
            tape = tape,
        )
    }

    private fun pricePhaseTwo(board: BoardSpec, entrants: List<EntrantSpec>) {
        val tape = fixedTape(board)
        println(
            "[chrome-cost] phase=2 grid=frozen exact-level-board=true board=${board.label} " +
                "geometry=${board.rows}x${board.cols} map=${board.shape.slug} map-seed=${board.mapSeed} " +
                "entrants=${entrants.size} no-interpolation=true",
        )
        priceFixedTape(phase = 2, board = board, entrants = entrants, tape = tape)
    }

    private fun pricePhaseSeven() {
        println(
            "[chrome-cost] phase=7 table=candidate exact-level-boards=true map-seed=0 " +
                "rows=${PHASE_SEVEN_CANDIDATES.size} control=${CONTROL.expandedLabel} " +
                "qualification=each-row-against-its-frozen-lane",
        )

        for (candidate in PHASE_SEVEN_CANDIDATES) {
            val board = candidate.board
            val tape = fixedTape(board)
            println(
                "[chrome-cost] phase=7 tape=exact board=${board.label} geometry=${board.rows}x${board.cols} " +
                    "map=${board.shape.slug} density=${board.densityLabel} map-seed=${board.mapSeed} " +
                    "walls=${tape.walls.size} wall-fingerprint=${fingerprint(tape.walls)} " +
                    "line-seed=$MATCH_SEED line-turns=${tape.lineTurns} stride=${tape.stride} " +
                    "samples=${tape.expectedPositions.size} " +
                    "position-fingerprint=${fingerprint(tape.expectedPositions)} " +
                    "entrant=${candidate.entrant.expandedLabel}",
            )
            priceFixedTape(
                phase = 7,
                board = board,
                entrants = listOf(candidate.entrant),
                tape = tape,
                rowRole = { "candidate" },
            )
        }
    }

    private fun fixedTape(board: BoardSpec): FixedTape {
        val walls = generateMap(
            rows = board.rows,
            cols = board.cols,
            shape = board.shape,
            density = board.density,
            seed = board.mapSeed,
        ).walls()
        val probe = play(board, walls, subject = null, stride = 1)
        val stride = maxOf(1, probe.lineTurns / SAMPLES)
        return FixedTape(
            walls = walls,
            stride = stride,
            expectedIndices = probe.sampleIndices.filter { it % stride == 0 },
            expectedPositions = probe.positions.filterIndexed { index, _ -> index % stride == 0 },
            lineTurns = probe.lineTurns,
        )
    }

    private fun priceFixedTape(
        phase: Int,
        board: BoardSpec,
        entrants: List<EntrantSpec>,
        tape: FixedTape,
        rowRole: (EntrantSpec) -> String? = { null },
    ) {
        val warmed = (listOf(CONTROL) + entrants).distinctBy { it.expandedLabel }
        for (subject in warmed) {
            time(
                board,
                tape.walls,
                subject,
                tape.stride,
                tape.expectedIndices,
                tape.expectedPositions,
            )
        }

        for (subject in entrants) {
            val passes = List(PASSES) {
                val before = time(
                    board,
                    tape.walls,
                    CONTROL,
                    tape.stride,
                    tape.expectedIndices,
                    tape.expectedPositions,
                )
                val measured = time(
                    board,
                    tape.walls,
                    subject,
                    tape.stride,
                    tape.expectedIndices,
                    tape.expectedPositions,
                )
                val after = time(
                    board,
                    tape.walls,
                    CONTROL,
                    tape.stride,
                    tape.expectedIndices,
                    tape.expectedPositions,
                )
                PairedTiming(before, measured, after)
            }
            reportFixedCost(phase, board, subject, passes, rowRole(subject))
        }
    }

    private fun time(
        board: BoardSpec,
        walls: IntArray,
        subject: EntrantSpec,
        stride: Int,
        expectedIndices: List<Int>,
        expectedPositions: List<Long>,
    ): Timing {
        val result = play(board, walls, subject, stride)
        assertEquals(
            expectedIndices,
            result.sampleIndices,
            "${board.label} ${subject.label} sampled different turns",
        )
        assertEquals(
            expectedPositions,
            result.positions,
            "${board.label} ${subject.label} did not see the fixed line",
        )
        assertTrue(result.appraisals > 0, "${board.label} ${subject.label} was never appraised")
        return Timing(result.micros / result.appraisals, result.worst)
    }

    private fun play(
        board: BoardSpec,
        walls: IntArray,
        subject: EntrantSpec?,
        stride: Int,
    ): TapeSeat {
        var tape: TapeSeat? = null
        val lineEntry = ShippedBots.entryOf(SPACE_ID)
        val seatEntry = BotEntry(
            id = SEAT_ID,
            displayName = "Chrome cost seat",
            factory = BotFactory { setup ->
                TapeSeat(
                    subject = subject?.entry?.factory?.create(setup),
                    line = lineEntry.factory.create(lineSetup(setup)),
                    allowance = subject?.allowance ?: 0,
                    stride = stride,
                ).also { tape = it }
            },
        )
        val registry = CostRegistry(seatEntry, lineEntry)
        val allowance = subject?.allowance ?: 0
        val setup = MatchSetup.create(
            rows = board.rows,
            cols = board.cols,
            slots = listOf(SEAT_ID, SPACE_ID),
            seed = MATCH_SEED,
            budgetPerTurn = allowance,
            walls = walls,
            budgets = intArrayOf(allowance, 0),
            slotParams = listOf(subject?.params ?: BotParams.EMPTY, BotParams.EMPTY),
        )
        val match = Match(setup, registry)
        match.runToCompletion()

        val forfeits = match.stats().slots.filter { it.fate == EliminationReason.FORFEIT }
        assertTrue(forfeits.isEmpty(), "${board.label} ${subject?.label ?: "line probe"} forfeited: $forfeits")
        return checkNotNull(tape) { "the timing seat was never built" }
    }

    private fun report(board: BoardSpec, subject: EntrantSpec, passes: List<PairedTiming>) {
        val means = passes.map { it.subject.mean }.sorted()
        val worst = passes.map { it.subject.worst }.sorted()
        val ratios = passes.map { ratioThousandths(it.subject.mean, it.before.mean, it.after.mean) }
        val medianRatio = ratios.sorted()[PASSES / 2]
        val raw = passes.joinToString("/") {
            "${it.subject.mean}:${it.subject.worst}" +
                "|${it.before.mean}:${it.before.worst}" +
                "|${it.after.mean}:${it.after.worst}"
        }

        println(
            "[chrome-cost] board=${board.label} entrant=${subject.label} allowance=${subject.allowance} " +
                "control=${CONTROL.label} passes=$PASSES " +
                "median-mean-us=${means[PASSES / 2]} median-worst-us=${worst[PASSES / 2]} " +
                "paired-mean-ratio=${ratioText(medianRatio)} " +
                "ratio-passes=${ratios.joinToString("/") { ratioText(it) }} " +
                "raw=subject-mean:worst|control-before-mean:worst|control-after-mean:worst=$raw",
        )
    }

    private fun reportFixedCost(
        phase: Int,
        board: BoardSpec,
        subject: EntrantSpec,
        passes: List<PairedTiming>,
        rowRole: String?,
    ) {
        val means = passes.map { it.subject.mean }.sorted()
        val largestRawTurn = passes.maxOf { it.subject.worst }
        val ratios = passes.map { ratioThousandths(it.subject.mean, it.before.mean, it.after.mean) }
        val medianRatio = ratios.sorted()[PASSES / 2]
        val stableControls = passes.count { stableWithinFifteenPercent(it.before.mean, it.after.mean) }
        val raw = passes.joinToString("/") {
            "${it.subject.mean}:${it.subject.worst}" +
                "|${it.before.mean}:${it.before.worst}" +
                "|${it.after.mean}:${it.after.worst}"
        }

        val role = if (rowRole == null) "" else " row-role=$rowRole"
        println(
            "[chrome-cost] phase=$phase board=${board.label} entrant=${subject.expandedLabel} " +
                "control=${CONTROL.expandedLabel} passes=$PASSES median-mean-us=${means[PASSES / 2]} " +
                "largest-raw-subject-turn-us=$largestRawTurn paired-mean-ratio=${ratioText(medianRatio)} " +
                "control-stability=$stableControls/$PASSES requirement=>=4/5 threshold=15% " +
                "stable=${stableControls >= REQUIRED_STABLE_CONTROLS}$role " +
                "ratio-passes=${ratios.joinToString("/") { ratioText(it) }} " +
                "raw=subject-mean:worst|control-before-mean:worst|control-after-mean:worst=$raw",
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

    private fun entrantsFor(board: BoardSpec): List<EntrantSpec> =
        if (board.shape == MapShape.EMPTY && board.rows == 8 && board.cols == 8) {
            BASELINE + BOSS
        } else {
            BASELINE
        }

    /** The timed seat follows this line and never plays the subject's answer. */
    private class TapeSeat(
        private val subject: Bot?,
        private val line: Bot,
        private val allowance: Int,
        private val stride: Int,
    ) : Bot {
        val sampleIndices: MutableList<Int> = mutableListOf()
        val positions: MutableList<Long> = mutableListOf()

        var micros: Long = 0
            private set

        var worst: Long = 0
            private set

        val appraisals: Int get() = sampleIndices.size
        val lineTurns: Int get() = ownTurns

        private var ownTurns = 0

        override fun chooseMove(turn: Turn): Decision {
            if (ownTurns % stride == 0) {
                sampleIndices += ownTurns
                positions += positionFingerprint(turn.board)

                if (subject != null) {
                    check(turn.budget.limit == allowance) {
                        "the subject received ${turn.budget.limit}, expected the fixed allowance $allowance"
                    }
                    check(turn.budget.consumed == 0) {
                        "the subject began a turn with ${turn.budget.consumed} evaluations already spent"
                    }

                    val mark = TimeSource.Monotonic.markNow()
                    subject.chooseMove(turn)
                    val elapsed = mark.elapsedNow().inWholeMicroseconds
                    micros += elapsed
                    if (elapsed > worst) {
                        worst = elapsed
                    }
                }
            }

            ownTurns++
            return line.chooseMove(turn)
        }

        override fun onEliminated() {
            subject?.onEliminated()
            line.onEliminated()
        }
    }

    private class CostRegistry(
        private val seat: BotEntry,
        private val line: BotEntry,
    ) : BotRegistry {
        override val entries: List<BotEntry> = listOf(seat, line)

        override fun get(id: BotId): BotEntry? =
            when (id) {
                seat.id -> seat
                line.id -> line
                else -> null
            }
    }

    private class BoardSpec(
        val label: String,
        val rows: Int,
        val cols: Int,
        val shape: MapShape,
        val density: Double = 0.0,
        val mapSeed: Long = 0L,
    ) {
        val densityLabel: String get() = if (density == 0.0) "default" else density.toString()
    }

    private class EntrantSpec(
        val label: String,
        slug: String,
        val allowance: Int,
        val params: BotParams = BotParams.EMPTY,
        entryOverride: BotEntry? = null,
    ) {
        val entry: BotEntry = entryOverride ?: ShippedBots.entryOf(BotId(slug))

        val expandedLabel: String = buildString {
            append(entry.id.slug).append(":budget=").append(allowance)
            for (knob in entry.params) {
                append(',').append(knob.name).append('=').append(knob.read(params).toString())
            }
        }
    }

    private class Timing(val mean: Long, val worst: Long)

    private class FixedTape(
        val walls: IntArray,
        val stride: Int,
        val expectedIndices: List<Int>,
        val expectedPositions: List<Long>,
        val lineTurns: Int,
    )

    private class PhaseSevenSpec(
        val board: BoardSpec,
        val entrant: EntrantSpec,
    )

    private class PairedTiming(
        val before: Timing,
        val subject: Timing,
        val after: Timing,
    )

    private companion object {
        val SEAT_ID = BotId("chrome-cost-seat")
        val SPACE_ID = BotId("space")

        const val MATCH_SEED = 424_242L
        const val LINE_SEED = 0x5EA7L
        const val SAMPLES = 24
        const val PASSES = 5
        const val REQUIRED_STABLE_CONTROLS = 4

        val CONTROL = EntrantSpec("uct@600-control", "uct", 600)

        val BASELINE = listOf(
            EntrantSpec("chase@0", "chase", 0),
            EntrantSpec("flat-monte-carlo@400", "flat-monte-carlo", 400),
            EntrantSpec("uct@600", "uct", 600),
            EntrantSpec("uct@1000", "uct", 1_000),
            EntrantSpec("puct@1000", "puct", 1_000),
            EntrantSpec(
                "alphabeta:eval=territory@1000",
                "alphabeta",
                1_000,
                BotParams(mapOf("eval" to "territory")),
            ),
        )

        val BOSS = EntrantSpec(
            "alphabeta:eval=chamber@1000",
            "alphabeta",
            1_000,
            BotParams(mapOf("eval" to "chamber")),
        )

        val PHASE_FIVE_GRID =
            grid("uct", intArrayOf(600, 800, 1_000, 1_200, 1_400, 1_600)) +
                grid(
                    "puct",
                    intArrayOf(1_000, 1_200, 1_400, 1_600, 1_800, 2_000),
                    mapOf("eval" to "territory"),
                ) +
                grid(
                    "puct",
                    intArrayOf(300, 400, 500, 600, 800, 1_000),
                    mapOf("eval" to "chamber"),
                ) +
                grid(
                    "puct",
                    intArrayOf(600, 800, 1_000),
                    mapOf(
                        "eval" to "chamber",
                        "priorPinch" to "0.8",
                        "priorTail" to "0.8",
                        "priorTemperature" to "0.9",
                    ),
                ) +
                grid(
                    "puct",
                    intArrayOf(600, 800, 1_000),
                    mapOf("eval" to "learned"),
                ) +
                grid(
                    "alphabeta",
                    intArrayOf(1_000, 1_200, 1_800, 2_000, 2_200, 2_400),
                    mapOf("eval" to "territory"),
                ) +
                grid(
                    "alphabeta",
                    intArrayOf(300, 400, 500, 600, 800, 1_000),
                    mapOf("eval" to "chamber"),
                )

        val PHASE_TWO_LEVEL_THREE = (2..5).map { depth ->
            val research = checkNotNull(FixedDepthResearch.case("depth-$depth"))
            EntrantSpec(
                label = "lookahead-research-depth-$depth",
                slug = "lookahead",
                allowance = 1 shl (depth * 2),
                entryOverride = BotEntry(
                    id = BotId("chrome-depth-$depth"),
                    displayName = "Chrome depth $depth",
                    factory = research.botFactory,
                ),
            )
        }

        val PHASE_TWO_SEARCH_GRID =
            grid("uct", intArrayOf(400, 600, 800)) +
                grid("puct", intArrayOf(400, 600, 800), mapOf("eval" to "territory")) +
                grid("puct", intArrayOf(300, 400, 600), mapOf("eval" to "chamber")) +
                grid("alphabeta", intArrayOf(400, 600, 800), mapOf("eval" to "territory")) +
                grid("alphabeta", intArrayOf(300, 400, 600), mapOf("eval" to "chamber"))

        val PHASE_FIVE_CURVE_ADDITIONS =
            grid(
                "puct",
                intArrayOf(1_500),
                mapOf("eval" to "territory"),
            ) +
                grid(
                    "puct",
                    intArrayOf(400),
                    mapOf(
                        "eval" to "chamber",
                        "priorPinch" to "0.8",
                        "priorTail" to "0.8",
                        "priorTemperature" to "0.9",
                    ),
                ) +
                grid(
                    "puct",
                    intArrayOf(300, 500),
                    mapOf("eval" to "learned"),
                ) +
                grid(
                    "alphabeta",
                    intArrayOf(1_100, 1_700),
                    mapOf("eval" to "territory"),
                ) +
                grid(
                    "lookahead",
                    intArrayOf(32, 48),
                    mapOf("depth" to "3"),
                )

        val EMPTY_8 = BoardSpec("empty-8", 8, 8, MapShape.EMPTY)
        val ARENA_12 = BoardSpec("arena-12", 12, 12, MapShape.ARENA)
        val PILLARS_12 = BoardSpec("pillars-12", 12, 12, MapShape.PILLARS)
        val SCATTER_12 = BoardSpec("scatter-12-seed-0", 12, 12, MapShape.SCATTER)
        val ISLANDS_12 = BoardSpec("islands-12-seed-0", 12, 12, MapShape.ISLANDS)
        val PINWHEEL_12 = BoardSpec("pinwheel-12", 12, 12, MapShape.PINWHEEL)
        val ROOMS_16 = BoardSpec("rooms-16", 16, 16, MapShape.ROOMS)
        val ISLANDS_16 = BoardSpec("islands-16-seed-61001", 16, 16, MapShape.ISLANDS, mapSeed = 61_001L)

        val PHASE_SEVEN_CANDIDATES = listOf(
            PhaseSevenSpec(PILLARS_12, EntrantSpec("chase@0", "chase", 0)),
            PhaseSevenSpec(ROOMS_16, EntrantSpec("cartographer@0", "cartographer", 0)),
            PhaseSevenSpec(
                ARENA_12,
                EntrantSpec("lookahead:depth=1@4", "lookahead", 4, BotParams(mapOf("depth" to "1"))),
            ),
            PhaseSevenSpec(SCATTER_12, EntrantSpec("flat-monte-carlo@400", "flat-monte-carlo", 400)),
            PhaseSevenSpec(ISLANDS_12, EntrantSpec("uct@600", "uct", 600)),
            PhaseSevenSpec(
                PINWHEEL_12,
                EntrantSpec("puct:eval=territory@600", "puct", 600, BotParams(mapOf("eval" to "territory"))),
            ),
            PhaseSevenSpec(
                EMPTY_8,
                EntrantSpec(
                    "alphabeta:eval=territory@1700",
                    "alphabeta",
                    1_700,
                    BotParams(mapOf("eval" to "territory")),
                ),
            ),
        )

        private fun grid(
            slug: String,
            allowances: IntArray,
            params: Map<String, String> = emptyMap(),
        ): List<EntrantSpec> = allowances.map { allowance ->
            EntrantSpec(
                label = "$slug@$allowance/${params.entries.joinToString { "${it.key}=${it.value}" }}",
                slug = slug,
                allowance = allowance,
                params = BotParams(params),
            )
        }
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

private fun ratioThousandths(subject: Long, before: Long, after: Long): Long =
    subject * 2_000L / maxOf(1L, before + after)

private fun ratioText(thousandths: Long): String =
    "${thousandths / 1_000}.${(thousandths % 1_000).toString().padStart(3, '0')}x"

private fun stableWithinFifteenPercent(before: Long, after: Long): Boolean {
    val smaller = minOf(before, after)
    val larger = maxOf(before, after)
    return larger * 100L <= maxOf(1L, smaller) * 115L
}

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
