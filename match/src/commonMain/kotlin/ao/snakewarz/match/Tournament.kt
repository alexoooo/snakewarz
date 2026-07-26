package ao.snakewarz.match

import ao.snakewarz.botapi.BotRegistry
import ao.snakewarz.core.MatchOutcome

/**
 * A batch of matches, played one **turn** at a time, filling in a win-rate matrix as it goes.
 *
 * This is the point of the whole testbed: "is this bot better than that one" is a question about a
 * few hundred matches, not about one, and the engine runs tens of millions of turns a second, so
 * asking it properly is nearly free.
 *
 * ### Why [step] is a turn and not a match
 *
 * Because the only caller that matters is a browser, and a browser must not stop answering the mouse
 * for as long as an MCTS match takes. `:match` still owns no clock — [step] does the smallest unit of
 * work there is and returns, exactly as [Match.step] does, and `:ui` decides how many of them a frame
 * is worth. A tournament of search bots therefore runs at a visible pace on a page that stays alive,
 * with no worker, no threads and no asynchrony anywhere in the driver.
 *
 * The schedule is fixed at construction and is a pure function of [config], so a tournament is as
 * reproducible as a match: same config, same table, on either target.
 *
 * Bots are resolved through the [BotRegistry] *interface*, like everything else here. `:match` has
 * never seen a bot class and this does not change that.
 */
public class Tournament(
    public val config: TournamentConfig,
    private val registry: BotRegistry,
) {
    /** Filled in as the batch runs. The same instance every time — [TournamentTable.copy] to keep one. */
    public val table: TournamentTable = TournamentTable(config.contestants)

    public val matchCount: Int = config.matchCount

    /** Matches finished. While one is in progress this is also its index in the schedule. */
    public var matchesPlayed: Int = 0
        private set

    /** Turns played across the whole batch, which is the honest denominator for a throughput figure. */
    public var turnsPlayed: Long = 0
        private set

    /** The match still being stepped. `null` between matches, which is what starts the next one. */
    private var active: Match? = null

    /** The most recently seated match, which outlives [active] so that [current] can report it. */
    private var latest: Match? = null

    /** Which contestant sits in which slot of [active]. */
    private val seating = IntArray(SEATS)

    /** Every unordered pair, lowest index first, in a fixed order. The schedule, in order. */
    private val pairings: List<Pair<Int, Int>> = buildList {
        for (one in config.contestants.indices) {
            for (other in one + 1 until config.contestants.size) {
                add(one to other)
            }
        }
    }

    /**
     * The match being played, or — once the batch is over — the last one that was.
     *
     * It keeps reporting the final match rather than going `null` because somebody is usually
     * *looking* at it. A caller painting the batch would otherwise have the board go blank on the
     * frame the tournament finished, which is the one frame the result is interesting on. `null`
     * only before the first [step], when there genuinely is no match yet.
     */
    public val current: Match? get() = latest

    public val finished: Boolean get() = matchesPlayed == matchCount

    /** How far through the schedule, in `0.0..1.0`, counting whole matches only. */
    public val progress: Double get() = matchesPlayed.toDouble() / matchCount

    /**
     * Plays at most one turn, starting the next match if the previous one just ended.
     *
     * Never plays more than one turn, so the worst case is one bot call — the same bound [Match.step]
     * gives, and the reason a frame can stop between any two of these.
     */
    public fun step(): Progress {
        if (finished) {
            return Progress.FINISHED
        }

        val match = active ?: startMatch()
        when (match.step()) {
            StepResult.AwaitingInput ->
                // A tournament has nobody to ask. The check in startMatch catches a human seat before
                // a single turn is played; reaching here means a bot claimed to be interactive, which
                // the contract suite forbids of a registry entry.
                error("${match.setup.slots} is waiting for input a tournament cannot supply")

            else -> turnsPlayed++
        }

        val outcome = match.outcome ?: return Progress.PLAYED_TURN

        record(outcome)
        active = null
        matchesPlayed++
        return if (finished) Progress.FINISHED else Progress.FINISHED_MATCH
    }

    /**
     * Plays the whole batch and returns the table.
     *
     * For headless callers — a JVM test, a benchmark. A browser wants [step] on a frame budget
     * instead, because this one does not come back for a while.
     */
    public fun runToCompletion(): TournamentTable {
        while (step() != Progress.FINISHED) {
            // Every unit of work is inside step(); the loop condition is the whole of the driver.
        }
        return table
    }

    /**
     * How match [index] of the schedule is set up, without playing anything.
     *
     * A pure function of [config] — which is the whole schedule laid open, so a caller can see what
     * is coming, and a test can assert that a seed really is played from both seats without having to
     * catch the tournament between two steps.
     */
    public fun setupFor(index: Int): MatchSetup {
        require(index in 0 until matchCount) { "match $index is not in a schedule of $matchCount" }

        val seats = IntArray(SEATS)
        seat(index, seats)
        return MatchSetup.create(
            rows = config.rows,
            cols = config.cols,
            slots = listOf(config.contestants[seats[0]], config.contestants[seats[1]]),
            seed = config.seed + (index % config.rounds) / 2,
            rules = config.rules,
            budgetPerTurn = config.budgetPerTurn,
        )
    }

    override fun toString(): String = "Tournament($matchesPlayed/$matchCount matches, $config)"

    /** What one [step] did. */
    public enum class Progress {
        /** A turn was played and the match it belongs to is still going. */
        PLAYED_TURN,

        /** A match ended and the table has been updated. Another one starts on the next step. */
        FINISHED_MATCH,

        /** The last match ended, or there was never anything left to do. */
        FINISHED,
    }

    // -- internals ------------------------------------------------------------------------------

    private fun startMatch(): Match {
        val setup = setupFor(matchesPlayed)
        seat(matchesPlayed, seating)

        val match = Match(setup, registry)
        check(!match.interactive) {
            "${setup.slots} seats somebody who plays by hand, and a tournament has nobody to ask"
        }

        active = match
        latest = match
        return match
    }

    /**
     * Works out who sits where in match [index].
     *
     * Pairing `p` plays rounds `0 until config.rounds`; each pair of rounds shares a seed and swaps
     * seats, so an odd round is the even one before it played from the other side of the board.
     */
    private fun seat(index: Int, into: IntArray) {
        val pairing = pairings[index / config.rounds]
        val swapped = (index % config.rounds) % 2 == 1
        into[0] = if (swapped) pairing.second else pairing.first
        into[1] = if (swapped) pairing.first else pairing.second
    }

    private fun record(outcome: MatchOutcome) {
        val winner = outcome.winner
        if (winner.isNone) {
            table.recordDraw(seating[0], seating[1])
        } else {
            val winnerSeat = winner.index
            table.recordWin(seating[winnerSeat], seating[1 - winnerSeat])
        }
    }

    private companion object {
        /** Head to head. A win-rate matrix is a statement about pairs, so a match here is a pair. */
        const val SEATS = 2
    }
}
