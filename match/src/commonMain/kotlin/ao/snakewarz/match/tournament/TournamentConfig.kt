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
) {
    init {
        require(contestants.size >= 2) { "a tournament needs at least two contestants, was ${contestants.size}" }
        require(contestants.toSet().size == contestants.size) { "a contestant appears twice in $contestants" }
        require(rows > 0 && cols > 0) { "a board must be at least 1x1, was ${rows}x$cols" }
        require(rounds > 0) { "a pairing needs at least one match, was $rounds" }
        require(rounds % 2 == 0) { "rounds must be even so each seed is played from both seats, was $rounds" }
        require(budgetPerTurn >= 0) { "budgetPerTurn must not be negative, was $budgetPerTurn" }
    }

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

    override fun toString(): String =
        "TournamentConfig(${contestants.size} bots, $format, ${rows}x$cols, $rounds rounds, $matchCount matches)"

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
        private const val HEAD_TO_HEAD_SEATS = 2
    }
}
