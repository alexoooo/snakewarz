package ao.snakewarz.lab.strength

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Arena
import ao.snakewarz.lab.arena.BatchResult
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig

/**
 * Plays a candidate against a baseline in blocks of fresh boards until [Sprt] settles or a cap says
 * stop.
 *
 * The loop `ab` is, and the loop every step of a search is, kept in one place so that the two cannot
 * drift into measuring slightly different things. What it owns is small and all of it is load
 * bearing: the block size, the cap, and above all **where the seeds come from**.
 *
 * ### Every block moves on
 *
 * A block is [blockPairs] boards, each played from both seats, so a block consumes seeds
 * `seed + block * blockPairs` onwards and the next starts where this one stopped. Blocks that shared
 * seeds would add matches to the sample without adding anything to the evidence, and a sequential
 * test — which reads its own sample's variance — has no way at all of telling the difference. It is
 * the failure that instrument is most exposed to, so it is arithmetic here rather than a convention
 * at three call sites.
 */
internal class SequentialTest(
    val baseline: Contestant,
    val candidate: Contestant,
    val rows: Int,
    val cols: Int,
    val seed: Long,
    val budgetPerTurn: Int,
    /** The map both arms play on, held still across every block for the reason the board is. */
    val walls: IntArray = IntArray(0),
    val openings: Openings,
    val threads: Int,
    val sprt: Sprt,
    val blockPairs: Int,
    val maxPairs: Int,
    /** Whether the batches keep their move streams, which only a caller writing the match log needs. */
    val keepRecords: Boolean = false,
) {
    init {
        require(baseline != candidate) {
            "'${candidate.label}' and '${baseline.label}' are the same entrant. That measures the " +
                "seating, and a sequential test on it never stops."
        }
        require(blockPairs > 0) { "a block is at least one board, was $blockPairs" }
        require(maxPairs >= Sprt.MINIMUM_PAIRS) {
            "a cap has to leave room for the ${Sprt.MINIMUM_PAIRS} boards a verdict needs, was $maxPairs"
        }
    }

    /**
     * Plays until the evidence decides. [onBlock] sees each batch as it lands, for a caller that
     * logs its progress or records its matches.
     */
    fun run(registry: BotRegistry, onBlock: (BatchResult, Outcome) -> Unit = { _, _ -> }): Outcome {
        val scores = mutableListOf<Double>()
        var forfeits = 0
        var matches = 0
        val hashes = LinkedHashSet<Long>()
        var report = sprt.test(scores)
        var block = 0

        while (report.verdict == Sprt.Verdict.UNDECIDED && scores.size < maxPairs) {
            val batch = Arena(
                config = configFor(block),
                registry = registry,
                openings = openings,
                threads = threads,
                keepRecords = keepRecords,
            ).run()

            scores += pairScores(batch, CANDIDATE)
            forfeits += batch.forfeits
            matches += batch.reports.size
            batch.reports.mapTo(hashes) { it.moveStreamHash }
            report = sprt.test(scores)
            block++

            onBlock(batch, Outcome(report, scores.toList(), forfeits, matches, hashes.size, maxPairs))
        }

        return Outcome(report, scores, forfeits, matches, hashes.size, maxPairs)
    }

    override fun toString(): String = "SequentialTest($candidate vs $baseline, $sprt)"

    private fun configFor(block: Int): TournamentConfig = TournamentConfig(
        contestants = listOf(baseline, candidate),
        rows = rows,
        cols = cols,
        rounds = blockPairs * MATCHES_PER_BOARD,
        seed = seed + block.toLong() * blockPairs,
        budgetPerTurn = budgetPerTurn,
        walls = walls,
    )

    /** What the test has seen, and the three things a reader has to check before believing it. */
    class Outcome(
        val report: Sprt.Report,
        val scores: List<Double>,
        /** A bot that threw. Always a defect, and never a result — fix it before reading anything else. */
        val forfeits: Int,
        val matches: Int,
        /** How many of those matches were different games. The honest sample size. */
        val distinct: Int,
        private val maxPairs: Int,
    ) {
        val boards: Int get() = scores.size

        /**
         * Boards the two split exactly, which is what two entrants playing the same game score on
         * every one.
         *
         * A null verdict sitting on a pile of these is a test that never saw the change rather than
         * a change that is not worth having — see `AbCommand.blindness`.
         */
        val splits: Int get() = scores.count { it == EVEN }

        /** Whether it stopped because it ran out of boards rather than because the evidence settled. */
        val cappedOut: Boolean get() = report.verdict == Sprt.Verdict.UNDECIDED && boards >= maxPairs

        val better: Boolean get() = report.verdict == Sprt.Verdict.BETTER

        override fun toString(): String = "${report.verdict} over $boards boards"
    }

    private companion object {
        /** The baseline enters first, so the candidate is contestant one. */
        const val CANDIDATE = 1

        /** A board is played from both seats, which is what makes it one observation. */
        const val MATCHES_PER_BOARD = 2

        /** A board shared down the middle — what two entrants that play alike score on every one. */
        const val EVEN = 0.5
    }
}
