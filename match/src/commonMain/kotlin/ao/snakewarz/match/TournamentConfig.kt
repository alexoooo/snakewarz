package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.RulesConfig

/**
 * What a batch tournament is: who is playing, on what, how many times, and from where.
 *
 * Every pair of [contestants] meets over [rounds] matches, so the schedule is
 * `contestants * (contestants - 1) / 2 * rounds` matches long and is fixed the moment this exists.
 *
 * Two things about the schedule are deliberate and both are about not measuring the wrong thing.
 * [rounds] is **even** because each seed is played from both seats: acting first is a real advantage
 * on this board, and a pairing that never swaps seats measures the seating as much as the bots. And
 * every pairing draws from the *same* seeds, so contestants are compared on the same set of games
 * rather than on independent samples of them — a paired comparison, which is a great deal tighter for
 * the same amount of compute.
 */
public class TournamentConfig(
    /** At least two, all different. A bot cannot be entered twice under one id. */
    public val contestants: List<BotId>,
    public val rows: Int,
    public val cols: Int,
    /** Matches per pairing. Even, because each seed is played from both seats. */
    public val rounds: Int = DEFAULT_ROUNDS,
    /** The first seed. The rest are [seed] onwards, one per pair of seat-swapped matches. */
    public val seed: Long = 1L,
    public val rules: RulesConfig = RulesConfig(),
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

    /** Unordered pairs, so two contestants meet in one pairing rather than two. */
    public val pairingCount: Int get() = contestants.size * (contestants.size - 1) / 2

    public val matchCount: Int get() = pairingCount * rounds

    /** How many distinct boards each pairing is played on. Half of [rounds], the other half swaps seats. */
    public val seedCount: Int get() = rounds / 2

    override fun toString(): String =
        "TournamentConfig(${contestants.size} bots, ${rows}x$cols, $rounds rounds, $matchCount matches)"

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
    }
}
