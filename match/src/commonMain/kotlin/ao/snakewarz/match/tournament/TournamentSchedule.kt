package ao.snakewarz.match.tournament

import ao.snakewarz.match.MatchSetup

/**
 * Which matches a batch is, and who sits where in each — the whole schedule, without playing any of
 * it.
 *
 * A pure function of [config], so it needs no registry, holds no table and reads nothing back. That
 * is what lets a caller see what is coming: a panel can say what is next, a test can assert a seed
 * really is played from both seats without catching a tournament between two steps, and a batch
 * runner elsewhere can play the same schedule its own way — in parallel, from diversified openings —
 * and still be running the schedule this module defines rather than a re-derived guess at it.
 *
 * [Tournament] owns one of these and answers through it.
 */
public class TournamentSchedule(
    public val config: TournamentConfig,
) {
    public val matchCount: Int = config.matchCount

    /** Every unordered pair, lowest index first — the head-to-head schedule, in order. */
    private val pairings: List<Pair<Int, Int>> = buildList {
        for (one in config.contestants.indices) {
            for (other in one + 1 until config.contestants.size) {
                add(one to other)
            }
        }
    }

    /** How many seed groups one pairing is divided into, the last one short if it does not divide. */
    private val groupsPerPairing = (config.rounds + config.seedGroup - 1) / config.seedGroup

    /**
     * The seed match [index] is played from.
     *
     * Every pairing draws from the *same* seeds, so contestants are compared on the same set of games
     * rather than on independent samples of them. See [TournamentConfig].
     */
    public fun seedFor(index: Int): Long {
        checkIndex(index)
        return config.seed + (index % config.rounds) / config.seedGroup
    }

    /**
     * Which matches share a board with [index], as a number that is the same for all of them.
     *
     * The unit a batch is measured in, rather than the match: head to head a key names two matches,
     * the same seed played from both seats, and free for all it names one per contestant, the seating
     * rotated a step each time. Comparing two bots over *pairs* rather than over games is what the
     * schedule was built for and is a great deal tighter for the same compute — but only if everybody
     * agrees where the boundaries are, which is why this is answered here rather than recomputed from
     * `rounds` by every caller that wants it.
     */
    public fun pairKeyFor(index: Int): Int {
        checkIndex(index)
        return (index / config.rounds) * groupsPerPairing + (index % config.rounds) / config.seedGroup
    }

    /** Which contestant sits in which seat of match [index], as a fresh array. */
    public fun seatingFor(index: Int): IntArray {
        val seats = IntArray(config.seatsPerMatch)
        seatInto(index, seats)
        return seats
    }

    /** How match [index] is set up, without playing anything. */
    public fun setupFor(index: Int): MatchSetup {
        val seated = seatingFor(index).map { config.contestants[it] }

        return MatchSetup.create(
            rows = config.rows,
            cols = config.cols,
            slots = seated.map { it.bot },
            seed = seedFor(index),
            rules = config.rules,
            budgetPerTurn = config.budgetPerTurn,
            walls = config.walls(),
            budgets = IntArray(seated.size) { seated[it].budgetIn(config.budgetPerTurn) },
            slotParams = seated.map { it.params },
        )
    }

    override fun toString(): String = "TournamentSchedule($matchCount matches, $config)"

    /**
     * Works out who sits where in match [index], into a caller-owned array.
     *
     * Head to head, pairing `p` plays rounds `0 until config.rounds`; each pair of rounds shares a
     * seed and swaps seats, so an odd round is the even one before it played from the other side of
     * the board. Free for all, each group of `contestants` matches shares a seed and rotates the
     * seating a step, so everybody starts from every corner of the same board — the seat swap,
     * generalized. (A group is cut short when the field does not divide [TournamentConfig.rounds];
     * the matches that were played still counted fairly, there are just fewer of that seed.)
     *
     * ### The rotation is cyclic, and that is visible in the matrix but not in the score
     *
     * A complete group gives every contestant every seat exactly once, so a seat worth something
     * cancels out of each contestant's **score**. It does not cancel out of a **cell**: cyclic
     * rotation covers `contestants` of the `contestants!` seatings, so at three seats a pair meets in
     * each unordered pair of seats once and never in the reversed orientation. Any seat advantage
     * therefore lands on the same side of every cell and comes out as a perfect intransitive cycle.
     *
     * Measured, with three byte-identical entrants on a 12x12 so that every cell should read even:
     * under `--openings fixed` the matrix reads **3669–2331 round the cycle** while all three score
     * exactly 50%, and under the default `mirrored` it reads 2992–2957 — a 61%/39% artefact against a
     * 50.3%/49.7% one. So the cycle is a property of the *opening*, not of this rotation on its own,
     * and a mirrored batch is clean.
     *
     * It is left cyclic rather than made exhaustive because the fix buys nothing where it matters:
     * with the openings a measurement actually uses, the artefact is inside the noise, and covering
     * every permutation would need `rounds` divisible by `contestants!` and would move a pinned
     * schedule. What it costs instead is a caveat: **at more than two seats, read a free-for-all
     * score column; do not read one of its cells, and do not read `rate`'s residual table as
     * evidence of intransitivity between bots.**
     */
    internal fun seatInto(index: Int, into: IntArray) {
        checkIndex(index)

        when (config.format) {
            TournamentFormat.HEAD_TO_HEAD -> {
                val pairing = pairings[index / config.rounds]
                val swapped = (index % config.rounds) % 2 == 1
                into[0] = if (swapped) pairing.second else pairing.first
                into[1] = if (swapped) pairing.first else pairing.second
            }

            TournamentFormat.FREE_FOR_ALL -> {
                val rotation = index % into.size
                for (seat in into.indices) {
                    into[seat] = (seat + rotation) % into.size
                }
            }
        }
    }

    private fun checkIndex(index: Int) {
        require(index in 0 until matchCount) { "match $index is not in a schedule of $matchCount" }
    }
}
