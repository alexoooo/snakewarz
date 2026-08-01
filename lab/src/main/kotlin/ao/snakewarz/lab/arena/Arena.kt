package ao.snakewarz.lab.arena

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.match.Match
import ao.snakewarz.match.tournament.TournamentConfig
import ao.snakewarz.match.tournament.TournamentFormat
import ao.snakewarz.match.tournament.TournamentSchedule
import ao.snakewarz.match.tournament.pairwiseOutcomes
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.TimeSource

/**
 * Plays a whole schedule as fast as the machine allows, and keeps what each match knew.
 *
 * ### Why not `Tournament`
 *
 * `Tournament` steps a turn at a time so a browser stays answering the mouse, and folds each match
 * into a win matrix the moment it ends. Neither is what a measuring instrument wants: `:lab` has no
 * frame to fit inside, has cores going spare, and needs the per-match detail the matrix throws away.
 *
 * So the schedule stays where it is and only the *playing* moves here. Every match is
 * `TournamentSchedule.setupFor` plus an opening, which is a pure function of the index, and the
 * results are collected **by index** rather than by completion order — so the batch produces exactly
 * the same output on one thread as on sixteen, and a test says so.
 *
 * Concurrency is safe by construction rather than by locking: a `Match` allocates its own board,
 * budgets, scratch and bot instances, every slot's randomness is forked from the match seed, and
 * `:bots` holds no mutable global state.
 */
internal class Arena(
    val config: TournamentConfig,
    private val registry: BotRegistry,
    private val openings: Openings = Openings.MIRRORED,
    private val threads: Int = defaultThreads(),
    private val keepRecords: Boolean = false,
) {
    private val schedule = TournamentSchedule(config)

    init {
        require(threads > 0) { "a batch needs at least one thread, was $threads" }
        if (openings == Openings.COMPLETE) {
            require(config.rows == Openings.COMPLETE_ROWS && config.cols == Openings.COMPLETE_COLS) {
                "complete openings need an empty 8x8, was ${config.rows}x${config.cols}"
            }
            require(config.wallCount == 0) { "complete openings need an empty 8x8, was ${config.wallCount} walls" }
            require(config.format == TournamentFormat.HEAD_TO_HEAD) {
                "complete openings need head-to-head, was ${config.format}"
            }
            require(config.rounds % Openings.COMPLETE_ROUNDS_PER_REPLICATION == 0) {
                "complete openings need ${Openings.COMPLETE_ROUNDS_PER_REPLICATION} rounds per replication, " +
                    "was ${config.rounds}"
            }
        }
    }

    val matchCount: Int get() = schedule.matchCount

    /**
     * Plays every match and hands back one report each, in schedule order.
     *
     * Does not come back for a while — that is what `:lab` is for. One pool for the whole batch, and
     * the results read back in index order, which is both the determinism guarantee and the reason
     * nothing here needs a lock.
     */
    fun run(): BatchResult {
        val pool = Executors.newFixedThreadPool(threads)
        val reports = try {
            warmUp(pool)
            val playing = (0 until matchCount).map { index -> pool.submit(Callable { play(index) }) }
            playing.map { finished ->
                try {
                    finished.get()
                } catch (failure: ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
        } finally {
            pool.shutdown()
        }
        return BatchResult(config, reports)
    }

    /**
     * Plays one match of every pairing and throws the results away.
     *
     * The schedule runs pairing by pairing, so without this the first contestant plays every one of
     * its matches against a JVM that has not finished compiling itself — and it is the *timings*
     * that carry the damage, because whoever is on the board while the interpreter is still running
     * gets charged for it. A reactive bot would report milliseconds a turn and a reader would
     * reasonably believe it.
     *
     * A match per pairing, so every bot is warm rather than only the engine. It costs a few percent
     * of a batch and nothing at all to the results, which are played fresh below.
     */
    private fun warmUp(pool: ExecutorService) {
        val first = (0 until matchCount step config.rounds).map { index -> pool.submit(Callable { play(index) }) }
        for (finished in first) {
            try {
                finished.get()
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
        }
    }

    private fun play(index: Int): MatchReport {
        val openingIndex = completeOpeningIndex(index)
        val setup = openingSetup(schedule.setupFor(index), openings, openingIndex)
        val match = Match(setup, registry)
        check(!match.interactive) {
            "${setup.slots} seats somebody who plays by hand, and a batch has nobody to ask"
        }

        val started = TimeSource.Monotonic.markNow()
        match.runToCompletion()
        val elapsed = started.elapsedNow()

        val record = match.record()
        val stats = match.stats()
        return MatchReport(
            index = index,
            pairKey = schedule.pairKeyFor(index),
            openingIdentity = openingIndex?.let(::completeOpeningIdentity),
            seating = schedule.seatingFor(index),
            stats = stats,
            comparisons = pairwiseOutcomes(config.format, stats),
            moveStreamHash = moveStreamHash(record.moves.toList()),
            elapsedMicros = elapsed.inWholeMicroseconds,
            record = if (keepRecords) record else null,
        )
    }

    /** Opening selection is schedule arithmetic, never a draw from the match or either bot's seed. */
    private fun completeOpeningIndex(index: Int): Int? {
        if (openings != Openings.COMPLETE) {
            return null
        }
        val round = index % config.rounds
        return (round / Openings.SEATINGS_PER_OPENING) % Openings.COMPLETE_POPULATION
    }

    companion object {
        /**
         * Every core but two, so a long batch leaves the machine usable.
         *
         * A batch is minutes to hours of saturated CPU, and the person who started it is usually
         * still working on the thing it is measuring.
         */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1)
    }
}
