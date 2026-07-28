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
     */
    MIRRORED,
}
