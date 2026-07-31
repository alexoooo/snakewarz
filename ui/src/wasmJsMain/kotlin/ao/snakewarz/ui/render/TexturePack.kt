package ao.snakewarz.ui.render

import ao.snakewarz.match.map.MapShape

/**
 * How the *ground* is drawn: the treatment a board's walls and its own surface are given, over and
 * above the colours [Theme] hands out.
 *
 * ### A pack is a second axis over the board, the way a scheme is a second axis over a theme
 *
 * The same split [Theme] already makes. A pack picks a **treatment** — how deep a block sits in its
 * square, whether it carries a stud, what figure the bare board is stippled with — and the theme
 * still picks the **colour**, so all three themes times both schemes keep working without a pack
 * knowing a single hex string. What a pack is allowed to touch is exactly [Theme.wall],
 * [Theme.wallEdge] and [Theme.background] shaded towards the first of them.
 *
 * **It may never touch [Theme.body], [Theme.head] or [Theme.accent].** A trail is what a snake *is*
 * and a route is the player's own; a texture that moved either would make the board's one reliable
 * colour channel depend on which level somebody happens to be on.
 *
 * ### The one hard rule: a per-cell figure is a pure function of `(row, col)`
 *
 * Never an RNG, and never anything read off a clock. The board bitmap is laid down only by
 * `BoardRenderer.fit`, so a resize redraws every wall from scratch — and a pattern that shuffled when
 * it did would be reported as *the map changed*. [wallInset] and [groundShade] are therefore
 * functions of a square alone, and where they want variety they take it from [fnv1a], which answers
 * the same way on both targets forever. The cell size enters only as a cut-off: below
 * `BoardRenderer.TEXTURE_MIN_CELL` a groove is most of the square, so every pack collapses to [PLAIN].
 *
 * ### Chosen by whoever starts the match, because a shape never reaches one
 *
 * `MatchSetup` takes wall *squares* and no shape, by design — see `docs/Maps.md` — so there is
 * nothing on the board to derive a pack from and deriving one would be a picture that changed when a
 * link was reopened. `GameSession.startLevel` reads `GauntletLevel.shape`, a custom match reads
 * `MatchOptions.shape`, and a `#r=` link or a map that came out of a replay has no shape at all and
 * gets `null` — which is [PLAIN], the board this game always drew.
 *
 * ### Four packs across eleven shapes
 *
 * A pack is a *feeling*, and eleven feelings is eleven times the drawing for a difference nobody
 * could name. So `empty` and `arena` share one, everything built out of straight runs shares
 * another, and the two shapes that scatter their walls share a third. [of] is a `when` with **no
 * `else`**, so a twelfth shape is a compile error rather than a board that quietly comes out plain.
 */
internal enum class TexturePack(
    /**
     * How much of its square a wall block gives back on every side, as a share of the cell.
     *
     * Zero fills the square, which is what the game drew before there were packs. Anything above it
     * opens a groove between two adjacent wall squares, and that groove is most of what tells a run
     * of blocks from a slab.
     */
    private val blockInset: Double,
    /** Whether a block carries a stud at its centre, in the wall's own edge colour. */
    val studded: Boolean,
) {
    /** Nothing was built here: the bare board `empty` and `arena` are played on, and every replay. */
    PLAIN(blockInset = 0.0, studded = false),

    /**
     * Walls somebody laid: the long straight runs of `ring`, `cross`, `rooms` and `double-spiral`.
     *
     * A groove and nothing else. These are the shapes whose whole character is a wall you follow, and
     * the one thing worth saying about a wall you follow is where each block of it ends.
     */
    MASONRY(blockInset = 0.09, studded = false),

    /**
     * A regular pattern of small obstacles: `pillars`, `pinwheel`, `diagonals`.
     *
     * Blocks stand well clear of their squares and carry a stud, so a lone one reads as something
     * placed rather than as a chip out of a wall, and the bare board is stippled on the same period —
     * which is the one pack where the ground itself says the board was laid out.
     */
    LATTICE(blockInset = 0.16, studded = true),

    /**
     * Debris nobody placed: `scatter` and `islands`, the two shapes that are different every seed.
     *
     * The only pack whose blocks vary square by square, which is what [fnv1a] is here for.
     */
    RUBBLE(blockInset = 0.05, studded = false),
    ;

    /**
     * How much of its square the block at `(row, col)` gives back on every side.
     *
     * Pure in `(row, col)` — see the one hard rule above. [RUBBLE] is the only pack that varies, and
     * it varies by a chip of one of [CHIP_STEPS] sizes, which is enough to read as rubble and few
     * enough that a block never disappears into its own square.
     */
    fun wallInset(row: Int, col: Int): Double = when (this) {
        PLAIN, MASONRY, LATTICE -> blockInset
        RUBBLE -> blockInset + CHIP_DEPTH * (fnv1a(row, col).ushr(CHIP_SHIFT) and CHIP_MASK) / CHIP_STEPS
    }

    /**
     * How much of [Theme.wall] the bare board at `(row, col)` carries, over [Theme.background].
     *
     * Zero is a flat board, which is three packs out of four: a figure under the snakes is the
     * loudest thing a pack can do and the two boards that want one are the two whose shapes say
     * something about how the squares are arranged. It is a shade of the board towards its own wall
     * colour rather than a colour of its own, so it follows every theme and both schemes for free.
     */
    fun groundShade(row: Int, col: Int): Double = when (this) {
        PLAIN, MASONRY -> 0.0
        LATTICE -> if (row % LATTICE_PERIOD == 0 && col % LATTICE_PERIOD == 0) LATTICE_SHADE else 0.0
        RUBBLE -> if (fnv1a(row, col) and SPECKLE_MASK == 0) RUBBLE_SHADE else 0.0
    }

    companion object {
        /**
         * The pack [shape] is drawn with, and [PLAIN] for a board that arrived without one.
         *
         * Total, and deliberately so: `null` is the ordinary answer for a `#r=` link and for a map
         * taken out of a replay, both of which carry wall squares and no shape at all.
         *
         * **No `else`.** A shape added to the catalogue has to be given a feeling here, and the
         * compiler is what asks — the same enforcement `drawShape` relies on in `:match`.
         */
        fun of(shape: MapShape?): TexturePack = when (shape) {
            null, MapShape.EMPTY, MapShape.ARENA -> PLAIN
            MapShape.RING, MapShape.CROSS, MapShape.ROOMS, MapShape.DOUBLE_SPIRAL -> MASONRY
            MapShape.PILLARS, MapShape.PINWHEEL, MapShape.DIAGONALS -> LATTICE
            MapShape.SCATTER, MapShape.ISLANDS -> RUBBLE
        }

        /** How deep a rubble chip cuts, on top of the block's own inset. */
        private const val CHIP_DEPTH = 0.14

        /**
         * The chip: four sizes, taken from four bits well clear of the ones [groundShade] reads.
         *
         * Two figures off one hash need different bits of it, or every speckled square would also be
         * the deepest-chipped one and the board would come out in stripes.
         */
        private const val CHIP_SHIFT = 8
        private const val CHIP_MASK = 3
        private const val CHIP_STEPS = 3.0

        /** The lattice: a mark every third square, at a whisper of the wall colour. */
        private const val LATTICE_PERIOD = 3
        private const val LATTICE_SHADE = 0.16

        /** The speckle: one square in four, quieter still, because it has no pattern to be read as. */
        private const val SPECKLE_MASK = 3
        private const val RUBBLE_SHADE = 0.10
    }
}
