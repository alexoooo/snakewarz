package ao.snakewarz.match.tournament

import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.match.MatchSetup

/**
 * What a batch tournament is: who is playing, in what [format], on what, how many times, and from
 * where.
 *
 * Head to head, every pair of [contestants] meets over [rounds] matches, so the schedule is
 * `contestants * (contestants - 1) / 2 * rounds` matches long. Free for all, every match seats
 * everybody, so the schedule is [rounds] matches long. Either way it is fixed the moment this
 * exists.
 *
 * Two things about the schedule are deliberate and both are about not measuring the wrong thing.
 * [rounds] is **even** because each seed is played from more than one seating: acting first is a
 * real advantage on this board, and a schedule that never moves anybody measures the seating as
 * much as the bots. And every pairing draws from the *same* seeds, so contestants are compared on
 * the same set of games rather than on independent samples of them — a paired comparison, which is
 * a great deal tighter for the same amount of compute.
 *
 * ### How much acting first is worth, and that it stops being worth anything at three seats
 *
 * Measured rather than assumed, on the nine shipped bots at a 12x12, mirrored openings, the shipped
 * allowance, one seat count against the other and nothing else moved:
 *
 * | seats | matches | the first mover won | even is | z |
 * |---|---|---|---|---|
 * | 2 | 14,400 | **54.3%** | 50% | +10.2 |
 * | 3 | 39,600 | **32.5%** | 33.3% | −3.4 |
 *
 * So at two seats the advantage is real and large, which is the whole reason for the even [rounds]
 * above. **At three it is gone and slightly reversed** — moving first commits against *two* unknown
 * replies instead of one, and the tempo does not pay for it. The rule stands anyway: the schedule
 * still has to move everybody, because [TournamentSchedule.seatInto] rotates the *seats* and the
 * corner a snake starts in is a separate question from the order it acts in.
 */
public class TournamentConfig(
    /**
     * At least two, all different — but different as *configurations*, so one bot may enter twice
     * at two allowances. See [Contestant].
     */
    public val contestants: List<Contestant>,
    public val rows: Int,
    public val cols: Int,
    /** Matches per pairing head to head, matches in total free for all. Even — see the class doc. */
    public val rounds: Int = DEFAULT_ROUNDS,
    public val format: TournamentFormat = TournamentFormat.HEAD_TO_HEAD,
    /** The first seed. The rest are [seed] onwards, one per pair of seat-swapped matches. */
    public val seed: Long = 1L,
    public val rules: RulesConfig = RulesConfig(),
    /** What a match grants a slot before [Contestant.budgetPerTurn] overrides it. */
    public val budgetPerTurn: Int = MatchSetup.DEFAULT_BUDGET_PER_TURN,
    /**
     * The map every match of the batch is played on, as **playable** indices, strictly ascending.
     *
     * One map for the whole schedule and not one per match, because a batch is a comparison and a
     * batch that changed the board underneath it would be several. The seat swap and the seating
     * rotation are unaffected: a map from `ao.snakewarz.match.map.generateMap` is invariant under the
     * half turn, so the seats it seats are already equivalent.
     */
    walls: IntArray = IntArray(0),
) {
    private val map: IntArray = walls.copyOf()

    init {
        require(contestants.size >= 2) { "a tournament needs at least two contestants, was ${contestants.size}" }
        require(contestants.toSet().size == contestants.size) { "a contestant appears twice in $contestants" }
        require(rows > 0 && cols > 0) { "a board must be at least 1x1, was ${rows}x$cols" }
        require(rounds > 0) { "a pairing needs at least one match, was $rounds" }
        require(rounds % 2 == 0) { "rounds must be even so each seed is played from both seats, was $rounds" }
        require(budgetPerTurn >= 0) { "budgetPerTurn must not be negative, was $budgetPerTurn" }

        // The same canonical form [MatchSetup] demands, checked here so a bad map is refused before a
        // schedule exists rather than at whichever match first tried to seat itself on it.
        var previous = -1
        for (i in map.indices) {
            require(map[i] in 0 until rows * cols) {
                "wall $i is at ${map[i]}, which is off a ${rows}x$cols board"
            }
            require(map[i] > previous) { "walls must ascend and not repeat; ${map[i]} follows $previous" }
            previous = map[i]
        }
    }

    /** The map's wall squares, as a fresh array. */
    public fun walls(): IntArray = map.copyOf()

    public val wallCount: Int get() = map.size

    /** Snakes on the board per match: everybody free for all, two otherwise. */
    public val seatsPerMatch: Int
        get() = if (format == TournamentFormat.FREE_FOR_ALL) contestants.size else HEAD_TO_HEAD_SEATS

    /** Unordered pairs, so two contestants meet in one pairing rather than two. */
    public val pairingCount: Int get() = contestants.size * (contestants.size - 1) / 2

    public val matchCount: Int
        get() = if (format == TournamentFormat.FREE_FOR_ALL) rounds else pairingCount * rounds

    /**
     * How many matches share a seed before the schedule moves on to the next one.
     *
     * Head to head a seed is played twice, once from each seat; free for all it is played once per
     * contestant, the seating rotated a step each time, so everybody starts from every corner of the
     * same board. For two contestants the two schemes are the same schedule.
     */
    public val seedGroup: Int
        get() = if (format == TournamentFormat.FREE_FOR_ALL) contestants.size else HEAD_TO_HEAD_SEATS

    // The wall count and not the wall indices, following `MatchSetup.toString`: this string is what
    // `:lab` prints above a batch, and "which map" has to be readable there.
    override fun toString(): String =
        "TournamentConfig(${contestants.size} bots, $format, ${rows}x$cols, $wallCount walls, " +
            "$rounds rounds, $matchCount matches)"

    public companion object {
        /**
         * Twenty a pairing, which is what the shipped ladder was measured over.
         *
         * Small enough to finish while somebody watches and large enough that a rung ordering means
         * something. It is not large enough to separate two bots that are genuinely close — twenty
         * matches puts a one-sigma band of about two and a half wins around an even pairing — and the
         * panel says so rather than implying a precision it does not have.
         */
        public const val DEFAULT_ROUNDS: Int = 20

        /** Head to head is a statement about pairs, so a match of it seats a pair. */
        internal const val HEAD_TO_HEAD_SEATS: Int = 2
    }
}
