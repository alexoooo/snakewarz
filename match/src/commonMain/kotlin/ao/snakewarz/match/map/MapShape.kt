package ao.snakewarz.match.map

/**
 * The catalogue: every map shape the game knows how to draw, in difficulty order.
 *
 * A shape is a function of `(rows, cols)`, so the same name means the same *idea* at 8x8, 12x12,
 * 20x20 and 40x40 — which is what lets one research field be run per geometry per map.
 *
 * **[slug] is frozen once released** (SW-05). It reaches a `:lab` flag, a `:ui` picker and a gauntlet
 * level, so it takes the same charset discipline as `BotId`: lowercase letters, digits and hyphens,
 * safe in a URL and in a filename without escaping. It is deliberately *not* [name] lowercased —
 * `double-spiral` rather than `double_spiral` — so the whole project spells an identifier one way.
 * Name a shape for what it looks like, never for lineage.
 *
 * A shape id never enters a replay: the codec carries the wall bitmap itself, so a shape can be
 * redesigned or deleted without breaking a link anybody has shared. Freezing the slug is about the
 * flag and the gauntlet, not about the URL.
 */
public enum class MapShape(
    public val slug: String,
    /**
     * The smallest board on which the shape can express itself.
     *
     * [generateMap] refuses a smaller one by name rather than emitting a degenerate map: a cross
     * with no arms and a spiral with half a turn both look like bugs in the *game*.
     */
    public val minimumSide: Int,
) {
    /** The incumbent, and the neutral setting every wall test is measured against. */
    EMPTY("empty", 1),

    /**
     * A solid block at the centre of the board and four satellite squares around it.
     *
     * The most open thing in the catalogue after [EMPTY], and the one that makes a game about
     * position rather than about corridors. The minimum is eight because the centre block is nudged
     * to the board's parity so that it can be its own image, and a seven-square side takes a
     * three-square block whose clearance ring reaches the border lane, leaving a satellite nowhere
     * to stand.
     */
    ARENA("arena", 8),

    /**
     * Isolated single squares on a lattice of period 3.
     *
     * Barely changes how the board plays and changes its **colour balance**, which is what
     * `ChamberEval`'s parity term is about.
     */
    PILLARS("pillars", 5),

    /**
     * Two straight arms offset from the centre, and the two the half turn supplies: wide lanes.
     *
     * The minimum is eleven because an arm runs from the two-square border lane to the centre line,
     * which is four squares at a side of eleven and three below it — a stub to step past rather than
     * a lane to go round.
     */
    PINWHEEL("pinwheel", 11),

    /** A hollow rectangle inset from the border with one gap a side: an inside and an outside. */
    RING("ring", 7),

    /** One horizontal and one vertical bar with a gap at the centre: four rooms joined at a chokepoint. */
    CROSS("cross", 7),

    /**
     * Parallel anti-diagonal bars, each opened at its middle.
     *
     * Breaks the axis-aligned assumption in `MovePrior`'s wall reading and in the Manhattan proxy
     * `SurvivalHorizon` takes for tail distance. The anti-diagonal family is the one that can be
     * half-turn symmetric at all: a *main*-diagonal bar maps to itself only under a reflection.
     *
     * The minimum is ten because a bar shorter than its own opening leaves nothing behind, and ten
     * is the smallest side at which two of them survive it. One surviving bar is a lone diagonal
     * wall rather than a family of parallel ones.
     */
    DIAGONALS("diagonals", 10),

    /**
     * Chambers on a grid of corridors, a doorway two or three squares wide per shared wall.
     *
     * Where a chamber decomposition earns its keep. The minimum is fourteen because a band has to be
     * wider than its own door for the wall to survive on both sides of it, and below fourteen the
     * outer bands are three squares against a three-square door — a lattice of crossing points
     * rather than rooms.
     */
    ROOMS("rooms", 14),

    /**
     * Two interleaved arms winding out from the centre, leaving one long corridor.
     *
     * **Two arms and not one.** A single spiral is chiral — it cannot be invariant under the half
     * turn — so it could not be fair under the rule [generateMap] enforces. Two arms, each the
     * other's image, can be. The game on one is close to pure space-filling, and parity dominates.
     *
     * The minimum is thirteen because an arm has to turn twice to wind at all: a corridor two
     * squares wide puts the third leg five squares in, and the arm only reaches an inset the board's
     * half-extent is past. One turn is a bend rather than a spiral.
     */
    DOUBLE_SPIRAL("double-spiral", 13),

    /** Isolated squares scattered to a requested density: the randomiser, for a field that is not four games. */
    SCATTER("scatter", 5),

    /**
     * Isolated blocks of mixed size scattered to a requested density: [SCATTER] with room to hide.
     *
     * The minimum is nine because a block keeps a free square between itself and every edge, and the
     * six-square interior that leaves on an eight-square side can come out holding **one** block:
     * the worst order the shuffle can offer fills four squares of sixty-four, where lone squares
     * reach eight. Nine is the smallest side whose interior no single block can lock out.
     */
    ISLANDS("islands", 9),
    ;

    public companion object {
        /**
         * The shape [slug] names, or `null`.
         *
         * Answered here rather than by each caller so a `:lab` flag, a `:ui` picker and a gauntlet
         * level cannot come to disagree about what a slug matches.
         */
        public fun ofSlug(slug: String): MapShape? = entries.firstOrNull { it.slug == slug }
    }
}
