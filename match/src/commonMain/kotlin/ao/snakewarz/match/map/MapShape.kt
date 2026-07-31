package ao.snakewarz.match.map

/**
 * The catalogue: every map shape the game knows how to draw, in difficulty order.
 *
 * A shape is a function of `(rows, cols)`, so the same name means the same *idea* at 8x8, 12x12,
 * 20x20 and 40x40 — which is what lets one research field be run per geometry per map.
 *
 * **[slug] is frozen once released** (SW-05). It reaches a `:lab` flag, a `:ui` picker and a ladder
 * level, so it takes the same charset discipline as `BotId`: lowercase letters, digits and hyphens,
 * safe in a URL and in a filename without escaping. It is deliberately *not* [name] lowercased —
 * `double-spiral` rather than `double_spiral` — so the whole project spells an identifier one way.
 * Name a shape for what it looks like, never for lineage.
 *
 * A shape id never enters a replay: the codec carries the wall bitmap itself, so a shape can be
 * redesigned or deleted without breaking a link anybody has shared. Freezing the slug is about the
 * flag and the ladder, not about the URL.
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
     * Isolated single squares on a lattice of period 3.
     *
     * Barely changes how the board plays and changes its **colour balance**, which is what
     * `ChamberEval`'s parity term is about.
     */
    PILLARS("pillars", 5),

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
     */
    DIAGONALS("diagonals", 9),

    /** Chambers on a grid of corridors, one doorway per shared wall: where a chamber decomposition earns its keep. */
    ROOMS("rooms", 11),

    /**
     * Two interleaved arms winding out from the centre, leaving one long corridor.
     *
     * **Two arms and not one.** A single spiral is chiral — it cannot be invariant under the half
     * turn — so it could not be fair under the rule [generateMap] enforces. Two arms, each the
     * other's image, can be. The game on one is close to pure space-filling, and parity dominates.
     */
    DOUBLE_SPIRAL("double-spiral", 13),

    /** Isolated squares scattered to a requested density: the randomiser, for a field that is not four games. */
    SCATTER("scatter", 5),
    ;

    public companion object {
        /**
         * The shape [slug] names, or `null`.
         *
         * Answered here rather than by each caller so a `:lab` flag, a `:ui` picker and a ladder
         * level cannot come to disagree about what a slug matches.
         */
        public fun ofSlug(slug: String): MapShape? = entries.firstOrNull { it.slug == slug }
    }
}
