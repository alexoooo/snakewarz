package ao.snakewarz.lab.arena

/**
 * Where the snakes start, which is the batch's only real source of position diversity.
 *
 * ### Why this exists
 *
 * Almost nothing about a match varies with its seed. Spawns do not depend on it at all — two snakes
 * always start in opposite corners — and the seed's only other effect on the position is the turn
 * order, which for two slots has exactly two values. So on a fixed board, a pairing of bots that
 * draw no randomness plays **at most four distinct games**, however many rounds are asked for, and
 * `puct` against `puct` is exactly such a pairing.
 *
 * A rating, an interval or a sequential test computed over twenty replays of the same four games
 * would be confident and wrong, and a tuner reading it would ratchet on noise. So a batch that means
 * to measure something varies the opening, and [Arena] counts distinct games so the question can
 * never go unasked again.
 */
internal enum class Openings {
    /**
     * Opposite corners, every match — what the engine picks when nobody says otherwise.
     *
     * Kept because the shipped ladder was measured under it, so it is what a result has to be
     * compared against, and because the contrast with [MIRRORED] is the evidence for the paragraph
     * above.
     *
     * **Past two snakes it is not merely undiversified, it is unfair, and by a lot.**
     * `mostDistantSpawns` puts the third snake in a *third corner*, so on a square board two seats
     * are a diagonal apart and the third is an edge away from both of them — a geometry no seating
     * rotation makes even within one board. Three byte-identical `chase` entrants over 2,000 seed
     * groups on a 12x12 won **83.4% / 0.05% / 16.6%** by seat, against 32.3% / 34.3% / 33.4% under
     * [MIRRORED] on the same entrants and the same seeds. A seat there is worth more than any bot in
     * this repository, so a free-for-all measured under this is measuring the corner.
     */
    FIXED,

    /**
     * A square drawn from the seed, with the opponent at its image through the centre of the board.
     *
     * Point reflection maps the board onto itself and takes each direction to its opposite, so the
     * two snakes face congruent positions and neither side of the draw is the better one — the same
     * property opposite corners have, held over a few dozen openings instead of one. Matches sharing
     * a seed get the same squares, so a seat swap still exchanges two players on one board and the
     * paired comparison the schedule is built around survives.
     *
     * With more than two snakes there is no placement that is fair to everybody, so this spreads
     * them at random subject to a minimum separation and leaves the fairness to the seating
     * rotation, which is what a free-for-all seed group already does.
     *
     * **That delegation is measured rather than argued, and it holds.** The draw is a rejection
     * sample whose acceptance test is symmetric in the squares, so the accepted triple is
     * exchangeable across seats — and `MatchSetup.create` shuffles the turn order uniformly, so the
     * other per-seat asymmetry is exchangeable too. Three byte-identical entrants on a 12x12 win by
     * seat: `chase` **32.3 / 34.3 / 33.4%** over 2,000 seed groups, `puct:eval=territory@1000`
     * **32.8 / 31.6 / 35.6%** over 1,600 across two blocks on disjoint seeds — chi-square 1.2 and
     * 3.9 on two degrees of freedom, neither near significant. So a three-seat rating taken under
     * this is not confounded by the seating, to within about **2.3 points of win share** at that
     * sample size. Under [FIXED] the same probe reads 83 / 0 / 17.
     */
    MIRRORED,

    /**
     * Every opening [MIRRORED] can draw on an empty 8x8, in a fixed order.
     *
     * The population is forty **oriented** starts: twenty pairs under the half turn, with each end
     * appearing once as slot zero. [Arena] plays every one from both seatings per replication. This
     * is deliberately not every pair of distinct squares [ao.snakewarz.match.MatchSetup] accepts.
     */
    COMPLETE,

    ;

    companion object {
        const val COMPLETE_ROWS: Int = 8
        const val COMPLETE_COLS: Int = 8
        const val COMPLETE_POPULATION: Int = 40
        const val SEATINGS_PER_OPENING: Int = 2
        const val COMPLETE_ROUNDS_PER_REPLICATION: Int = COMPLETE_POPULATION * SEATINGS_PER_OPENING
    }
}
