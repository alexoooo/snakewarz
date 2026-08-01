package ao.snakewarz.lab.endgame

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.LabCommand
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.expandedSpec
import ao.snakewarz.match.tournament.Contestant
import java.nio.file.Path
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/** Measures how far exact terminal search reaches into P5's complete empty-8x8 population. */
internal class EndgameCommand(
    private val logDirectory: Path,
    private val champion: Contestant,
    private val thresholds: IntArray,
    private val positionsPerThreshold: Int,
    private val seed: Long,
    private val maxNodesPerPosition: Int,
    private val maxTotalNodes: Long,
    private val memoryMiB: Int,
    private val maxSeconds: Long,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val runs = MatchLog(logDirectory).runs()
        require(runs.size == 1) { "$logDirectory has ${runs.size} runs; solve-endgame needs one P5 finalist run" }
        val championSpec = expandedSpec(champion, registry, runs.single().budgetPerTurn)
        val corpus = loadEndgameCorpus(logDirectory, championSpec, registry)
        val selection = selectEndgamePositions(corpus.replays, thresholds, positionsPerThreshold, seed)

        log("[solve-endgame] champion $championSpec")
        log("[solve-endgame] ${corpus.replays.size} complete replays, ${distinctBlocks(corpus.replays)} opening blocks")
        log(
            "[solve-endgame] thresholds ${selection.thresholds.joinToString()}, " +
                "$positionsPerThreshold positions each, " +
                "$maxNodesPerPosition search nodes/position, $maxTotalNodes total exact visits, " +
                "$memoryMiB MiB, ${maxSeconds}s",
        )

        // Validation and sampling run before the table allocation: a bad corpus must not ask the JVM
        // for hundreds of megabytes merely to explain that its board is wrong.
        val deadlineNanos = secondsToNanos(maxSeconds)
        val solver = ExactEndgameSolver(maxNodesPerPosition, memoryMiB)
        val verifier = ExactProofVerifier()
        val started = System.nanoTime()
        val shouldStop = { System.nanoTime() - started >= deadlineNanos }
        log(
            "[solve-endgame] exact table arrays ${formatMib(solver.table.allocatedBytes)} MiB; " +
                "per-position path is added and checked against the same cap",
        )
        var totalNodes = 0L
        var stop = false
        val projectionPoints = mutableListOf<ProjectionPoint>()

        for (threshold in selection.thresholds) {
            val samples = selection.at(threshold)
            val candidateCount = selection.candidates[selection.thresholds.indexOf(threshold)]
            log(
                "[solve-endgame] threshold $threshold: $candidateCount replay candidates, " +
                    "${samples.size} retained blocks",
            )
            val readings = mutableListOf<SolveReading>()
            for (sample in samples) {
                if (totalNodes >= maxTotalNodes || System.nanoTime() - started >= deadlineNanos) {
                    stop = true
                    break
                }

                val remainingGlobal = maxTotalNodes - totalNodes
                val nodeLimit = minOf(maxNodesPerPosition.toLong(), remainingGlobal).toInt()
                if (nodeLimit == 0) {
                    stop = true
                    break
                }

                val board = replayBoardAt(sample.replay.record, sample.turnIndex)
                val rebuilt = LongArray(ExactStateCodec.WORDS)
                ExactStateCodec.encode(board, rebuilt, 0)
                check(sample.state.sameAs(rebuilt)) {
                    "${sample.replay.key} turn ${sample.turnIndex} did not reconstruct structurally"
                }

                val solveStarted = System.nanoTime()
                val solved = solver.solve(
                    board = board,
                    self = sample.replay.championSeat,
                    turnOrder = sample.replay.record.setup.turnOrder(),
                    nodeLimit = nodeLimit,
                    shouldStop = shouldStop,
                )
                val solveNanos = System.nanoTime() - solveStarted
                totalNodes += solved.stats.calls
                if (!solved.solved) {
                    readings += SolveReading(
                        sample = sample,
                        result = solved,
                        proof = null,
                        optimal = false,
                        elapsedNanos = solveNanos,
                    )
                    if (solved.abortReason == ExactAbortReason.STOP_REQUESTED) {
                        stop = true
                        break
                    }
                    continue
                }

                val proofAllowance = maxTotalNodes - totalNodes
                if (proofAllowance == 0L) {
                    readings += SolveReading(sample, solved, proof = null, optimal = false, elapsedNanos = solveNanos)
                    stop = true
                    break
                }
                val fresh = replayBoardAt(sample.replay.record, sample.turnIndex)
                val proofStarted = System.nanoTime()
                val proof = verifier.verify(
                    board = fresh,
                    self = sample.replay.championSeat,
                    turnOrder = sample.replay.record.setup.turnOrder(),
                    solved = solved,
                    table = solver.table,
                    maxVisits = proofAllowance,
                    shouldStop = shouldStop,
                )
                val proofNanos = System.nanoTime() - proofStarted
                totalNodes += proof.visits
                val optimal = solved.optimalMask and (1 shl sample.recordedMove.ordinal) != 0
                readings += SolveReading(sample, solved, proof, optimal, solveNanos + proofNanos)
                if (!proof.complete) {
                    stop = true
                    break
                }
            }

            reportThreshold(threshold, samples.size, readings, log)
            val completed = readings.filter { it.proven }
            if (readings.size == samples.size && completed.size == samples.size && completed.isNotEmpty()) {
                projectionPoints += ProjectionPoint(
                    remainingCells = median(completed.map { it.sample.remaining.toLong() }).toInt(),
                    medianCalls = median(completed.map { it.result.stats.calls }),
                    worstCalls = completed.maxOf { it.result.stats.calls },
                    solverCalls = completed.sumOf { it.result.stats.calls },
                    visits = completed.sumOf { it.totalVisits },
                    elapsedNanos = completed.sumOf { it.elapsedNanos },
                )
                reportProjection(projectionPoints, corpus.run.growEveryNthMove, log)
            } else if (samples.isNotEmpty()) {
                stop = true
            }

            if (stop) {
                val reason = when {
                    totalNodes >= maxTotalNodes -> "the $maxTotalNodes total-node cap"
                    System.nanoTime() - started >= deadlineNanos -> "the ${maxSeconds}s command deadline"
                    else -> "an incomplete threshold"
                }
                log("[solve-endgame] stopping before a larger threshold after $reason")
                break
            }
        }
        log("[solve-endgame] total exact visits $totalNodes")
    }

    private fun reportThreshold(
        threshold: Int,
        selected: Int,
        readings: List<SolveReading>,
        log: (String) -> Unit,
    ) {
        val solved = readings.filter { it.result.solved }
        val proven = readings.filter { it.proven }
        val outside = proven.count { !it.optimal }
        val capped = readings.count { !it.result.solved }
        val proofAborted = solved.count { !it.proven }
        log(
            "[solve-endgame] threshold $threshold coverage: ${readings.size}/$selected attempted, " +
                "${solved.size} solved, ${proven.size} replay-verified, $capped search-capped, " +
                "$proofAborted proof-capped, $outside champion misses",
        )
        if (readings.isEmpty()) {
            return
        }

        val calls = readings.map { it.result.stats.calls }
        val depths = readings.map { it.result.stats.maxDepth.toLong() }
        val lookups = readings.sumOf { it.result.stats.lookups }
        val hits = readings.sumOf { it.result.stats.transpositions }
        val collisions = readings.sumOf { it.result.stats.structuralCollisions }
        val proofEdges = readings.sumOf { it.proof?.verifiedEdges ?: 0L }
        val proofVisits = readings.sumOf { it.proof?.visits ?: 0L }
        val elapsedNanos = readings.sumOf { it.elapsedNanos }
        val totalVisits = readings.sumOf { it.totalVisits }
        val remaining = readings.map { it.sample.remaining }
        val allocated = readings.maxOf { it.result.stats.allocatedBytes }
        val rate = if (lookups == 0L) 0.0 else hits.toDouble() / lookups
        log(
            "[solve-endgame] threshold $threshold calls median ${median(calls)}, worst ${calls.max()}, " +
                "depth median ${median(depths)}, worst ${depths.max()}",
        )
        log(
            "[solve-endgame] threshold $threshold transpositions $hits/$lookups (${percent(rate)}), " +
                "$collisions structural hash collisions, $proofVisits proof visits, $proofEdges verified edges",
        )
        log(
            "[solve-endgame] threshold $threshold actual remaining ${remaining.min()}..${remaining.max()}, " +
                "arrays peak ${formatMib(allocated)} MiB, ${millis(elapsedNanos)} ms, " +
                "${visitsPerSecond(totalVisits, elapsedNanos)} visits/s",
        )
    }

    private fun reportProjection(points: List<ProjectionPoint>, growEveryNthMove: Int, log: (String) -> Unit) {
        if (points.size < 2) {
            log("[solve-endgame] initial-opening projection needs two completed thresholds")
            return
        }
        val previous = points[points.lastIndex - 1]
        val current = points.last()
        if (
            current.remainingCells <= previous.remainingCells ||
            current.medianCalls <= previous.medianCalls ||
            previous.medianCalls <= 0L
        ) {
            log("[solve-endgame] initial-opening extrapolation unsupported: observed curve is non-monotone")
            return
        }
        val slope = (ln(current.medianCalls.toDouble()) - ln(previous.medianCalls.toDouble())) /
            (current.remainingCells - previous.remainingCells)
        val logCalls = ln(current.medianCalls.toDouble()) +
            slope * (INITIAL_FREE_CELLS - current.remainingCells)
        val perPosition = boundedExp(logCalls)
        val population = boundedMultiply(perPosition, Openings.COMPLETE_ROUNDS_PER_REPLICATION.toLong())
        val throughput =
            if (current.elapsedNanos == 0L) {
                0.0
            } else {
                current.visits.toDouble() * NANOS_PER_SECOND / current.elapsedNanos
            }
        val projectedVisits = population.toDouble() * current.visits / current.solverCalls
        val projectedSeconds = if (throughput == 0.0) Double.POSITIVE_INFINITY else projectedVisits / throughput
        log(
            "[solve-endgame] node-only empirical extrapolation (not a bound) from actual remaining " +
                "${previous.remainingCells}/${current.remainingCells}: $perPosition calls per initial state, " +
                "$population over 80 states, ${formatSeconds(projectedSeconds)} at observed throughput",
        )

        val extraPlies = growEveryNthMove.toLong() *
            (INITIAL_FREE_CELLS - current.remainingCells + DUEL_SNAKES)
        val envelopePerPosition = boundedMultiply(current.worstCalls, boundedPower(MAX_BRANCHING, extraPlies))
        val envelopePopulation = boundedMultiply(
            envelopePerPosition,
            Openings.COMPLETE_ROUNDS_PER_REPLICATION.toLong(),
        )
        log(
            "[solve-endgame] conservative observed-worst envelope (not a proof bound): " +
                "$envelopePerPosition calls/state, $envelopePopulation calls/80 states",
        )
    }

    private fun distinctBlocks(replays: List<EndgameReplay>): Int = replays.mapTo(LinkedHashSet()) { it.block }.size

    private fun median(values: List<Long>): Long {
        check(values.isNotEmpty()) { "a median needs at least one value" }
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            val low = sorted[sorted.size / 2 - 1]
            val high = sorted[sorted.size / 2]
            low + (high - low) / 2
        }
    }

    private fun percent(rate: Double): String = String.format(Locale.ROOT, "%.2f%%", rate * 100.0)

    private fun formatMib(bytes: Long): String = String.format(Locale.ROOT, "%.2f", bytes / BYTES_PER_MIB)

    private fun millis(nanos: Long): String = String.format(Locale.ROOT, "%.2f", nanos / NANOS_PER_MILLI)

    private fun visitsPerSecond(visits: Long, nanos: Long): String =
        if (nanos == 0L) {
            "infinite"
        } else {
            String.format(Locale.ROOT, "%.0f", visits.toDouble() * NANOS_PER_SECOND / nanos)
        }

    private fun formatSeconds(seconds: Double): String =
        if (!seconds.isFinite()) "unknown seconds" else String.format(Locale.ROOT, "%.0f seconds", seconds)

    private fun secondsToNanos(seconds: Long): Long {
        require(seconds > 0 && seconds <= Long.MAX_VALUE / NANOS_PER_SECOND) {
            "max-seconds is outside the measurable range: $seconds"
        }
        return seconds * NANOS_PER_SECOND
    }

    private fun boundedExp(value: Double): Long =
        if (!value.isFinite() || value >= ln(Long.MAX_VALUE.toDouble())) {
            Long.MAX_VALUE
        } else {
            exp(value).toLong().coerceAtLeast(1L)
        }

    private fun boundedMultiply(one: Long, other: Long): Long =
        if (one > Long.MAX_VALUE / other) Long.MAX_VALUE else one * other

    private fun boundedPower(base: Long, exponent: Long): Long {
        var result = 1L
        repeat(exponent.coerceAtMost(MAX_POWER_EXPONENT).toInt()) {
            result = boundedMultiply(result, base)
        }
        return if (exponent > MAX_POWER_EXPONENT) Long.MAX_VALUE else result
    }

    internal companion object {
        const val DEFAULT_THRESHOLDS: String = "4,6,8,10,12"
        const val DEFAULT_POSITIONS_PER_THRESHOLD: Int = 8
        const val DEFAULT_SEED: Long = 76_001L
        const val DEFAULT_MAX_NODES_PER_POSITION: Int = 5_000_000
        const val DEFAULT_MAX_TOTAL_NODES: Long = 200_000_000L
        const val DEFAULT_MEMORY_MIB: Int = 1_024
        const val DEFAULT_MAX_SECONDS: Long = 86_400L
        const val MAX_THRESHOLD: Int = 62

        private const val INITIAL_FREE_CELLS = MAX_THRESHOLD
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000.0
        private const val BYTES_PER_MIB = 1024.0 * 1024.0
        private const val DUEL_SNAKES = 2
        private const val MAX_BRANCHING = 4L
        private const val MAX_POWER_EXPONENT = 32L
    }
}

private class SolveReading(
    val sample: EndgameSample,
    val result: ExactSolveResult,
    val proof: ExactProofResult?,
    val optimal: Boolean,
    val elapsedNanos: Long,
) {
    val proven: Boolean get() = proof?.complete == true
    val totalVisits: Long get() = result.stats.calls + (proof?.visits ?: 0L)
}

private class ProjectionPoint(
    val remainingCells: Int,
    val medianCalls: Long,
    val worstCalls: Long,
    val solverCalls: Long,
    val visits: Long,
    val elapsedNanos: Long,
)
