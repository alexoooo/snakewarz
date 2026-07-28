package ao.snakewarz.match.tournament

/**
 * A rating per contestant, and how much of each one was actually measured.
 *
 * A single ordering is what a matrix cannot give once the field is larger than a pair: a bot can win
 * more matches than another and still lose to it, because the two met different opposition. A rating
 * answers "how strong, on this evidence" rather than "how many did it win", which is the question a
 * ladder is really asking.
 *
 * ### Read [priorDetermined] before believing a gap
 *
 * A rating is only a measurement where the results connect. A contestant that beat everybody it met
 * and never lost has no upper bound in the data at all, and neither does a group that never played
 * outside itself — the fit still produces a number, but that number comes from the prior which makes
 * the arithmetic finite rather than from any game. Those are flagged, and a caller showing a ladder
 * is expected to say so rather than let a regularizer read as a result.
 *
 * ### Ordering comes from the strengths, not from the ratings
 *
 * [fitRatings] works in Bradley-Terry strengths, where every step is `+ - * /`; the Elo figure is
 * that strength's logarithm and exists to be read. `log10` is not specified bit-identical across the
 * JVM and wasm, so the *displayed* number may differ in its last digits between targets — which is
 * harmless, and would not be if two close contestants could swap places because of it. So [ranking]
 * and [expectedScore] both read the strengths, and nothing that decides an order goes through a
 * logarithm.
 */
public class Ratings internal constructor(
    public val contestants: List<Contestant>,
    /** Bradley-Terry strengths: the fit itself, and what every comparison here is computed from. */
    private val strengths: DoubleArray,
    private val elo: DoubleArray,
    private val played: IntArray,
    private val components: IntArray,
    private val determinedByPrior: BooleanArray,
) {
    public val size: Int get() = contestants.size

    /** Elo, centred on the mean of everybody who played. Meaningless where [measured] is false. */
    public fun rating(contestant: Int): Double = elo[contestant]

    public fun games(contestant: Int): Int = played[contestant]

    /** Whether anything at all is known about this one. */
    public fun measured(contestant: Int): Boolean = played[contestant] > 0

    /**
     * Which group of mutually-comparable contestants this belongs to.
     *
     * Two contestants in different groups were never connected by a chain of results, so the
     * difference between their ratings measures nothing.
     */
    public fun component(contestant: Int): Int = components[contestant]

    /**
     * Whether this rating rests on the prior rather than on results.
     *
     * True for a contestant that never lost, never won, played nothing, or sits in a group that
     * never met the rest of the field. The rating is still finite and still ordered sensibly — it
     * just is not evidence, and a gap involving one is not a gap anybody measured.
     */
    public fun priorDetermined(contestant: Int): Boolean = determinedByPrior[contestant]

    /** Contestant indices strongest first, the unmeasured last and in entry order among themselves. */
    public fun ranking(): List<Int> =
        (0 until size).sortedWith(
            compareByDescending<Int> { measured(it) }.thenByDescending { strengths[it] },
        )

    /**
     * The score this model expects [one] to take against [other], in `0.0..1.0`.
     *
     * What makes a rating checkable rather than merely orderable: compare it with what actually
     * happened, and a cell that disagrees is a pairing the single number cannot describe. Snakes are
     * a rock-paper-scissors sort of game, so those cells exist and are the interesting ones.
     *
     * This is the Bradley-Terry model itself — a share of two strengths — rather than the Elo
     * formula it is usually written as. The two are the same statement, and this one divides.
     */
    public fun expectedScore(one: Int, other: Int): Double {
        val total = strengths[one] + strengths[other]
        return if (total == 0.0) EVEN else strengths[one] / total
    }

    override fun toString(): String = "Ratings(${contestants.size} contestants)"

    public companion object {
        /**
         * Four hundred points to a tenfold difference in strength, which is what Elo means everywhere
         * else. Kept so a figure from here can be read against a figure from anywhere else.
         */
        public const val SCALE: Double = 400.0

        private const val EVEN = 0.5
    }
}
