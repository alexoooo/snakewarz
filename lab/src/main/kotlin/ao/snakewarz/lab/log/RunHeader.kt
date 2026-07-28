package ao.snakewarz.lab.log

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.tournament.TournamentConfig
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The conditions a batch was played under, written once per run.
 *
 * Everything here is something that makes two batches incomparable if it differs, which is what the
 * file is for: a log accumulated over weeks pools whatever you ask it to, and without these columns
 * it would pool a 12x12 result with a 20x20 one, a thousand-evaluation allowance with a hundred, and
 * yesterday's bots with today's, all silently and all under the same names.
 */
internal class RunHeader(
    /** Unique and sortable, so a log reads chronologically without a join. */
    val id: String,
    val startedAt: String,
    /** See [buildFingerprint] — which bots, not just which settings. */
    val build: String,
    val format: String,
    val rows: Int,
    val cols: Int,
    val growEveryNthMove: Int,
    val maxTurns: Int,
    val budgetPerTurn: Int,
    val rounds: Int,
    val seed: Long,
    val openings: String,
    val threads: Int,
    /** Every entrant in full — see [expandedSpec] — space separated, in seating order. */
    val contestants: List<String>,
) {
    /** What a batch has to agree on before its matches may be pooled with another's. */
    val comparabilityKey: String
        get() = "$build|$format|${rows}x$cols|$growEveryNthMove|$maxTurns|$budgetPerTurn|$openings"

    override fun toString(): String = "RunHeader($id, $build, ${rows}x$cols, ${contestants.size} entrants)"

    companion object {
        fun of(
            config: TournamentConfig,
            registry: BotRegistry,
            openings: String,
            threads: Int,
            startedAt: Instant = Instant.now(),
        ): RunHeader = RunHeader(
            id = ID_FORMAT.format(startedAt.atOffset(ZoneOffset.UTC)),
            startedAt = startedAt.toString(),
            build = buildFingerprint(),
            format = config.format.name,
            rows = config.rows,
            cols = config.cols,
            growEveryNthMove = config.rules.growEveryNthMove,
            maxTurns = config.rules.maxTurns,
            budgetPerTurn = config.budgetPerTurn,
            rounds = config.rounds,
            seed = config.seed,
            openings = openings,
            threads = threads,
            contestants = config.contestants.map { expandedSpec(it, registry, config.budgetPerTurn) },
        )

        /**
         * UTC to the millisecond, so an id sorts chronologically as text and two batches started
         * back to back do not collide.
         */
        private val ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    }
}
