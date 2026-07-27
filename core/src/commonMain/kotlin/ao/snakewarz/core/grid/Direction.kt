package ao.snakewarz.core.grid

/**
 * One of the four moves a snake can make. The top-left of the grid is `(0, 0)`, so [NORTH]
 * decreases the row — matching the legacy engine's convention.
 *
 * Note there is deliberately **no** "cannot reverse" rule anywhere in the engine. Reversing is
 * illegal only because your own neck occupies that square, which falls out of ordinary occupancy
 * checks. At body length 1 there is no neck, so a first-move reversal is legal — that emergent
 * behaviour is intended, and is covered by tests.
 */
public enum class Direction(public val dRow: Int, public val dCol: Int) {
    NORTH(-1, 0),
    SOUTH(1, 0),
    EAST(0, 1),
    WEST(0, -1),
    ;

    public val opposite: Direction get() = OPPOSITES[ordinal]

    /**
     * A quarter turn to the left of a snake facing this way — counter-clockwise on screen, since the
     * row axis points down. Facing [NORTH], left is [WEST].
     */
    public val turnedLeft: Direction get() = LEFT_TURNS[ordinal]

    /** A quarter turn to the right of a snake facing this way. Facing [NORTH], right is [EAST]. */
    public val turnedRight: Direction get() = RIGHT_TURNS[ordinal]

    private companion object {
        /**
         * Indexed by ordinal. Safe to reference entries here: enum constants are initialized before
         * any other static state, including the companion itself.
         */
        val OPPOSITES: Array<Direction> = arrayOf(SOUTH, NORTH, WEST, EAST)
        val LEFT_TURNS: Array<Direction> = arrayOf(WEST, EAST, NORTH, SOUTH)
        val RIGHT_TURNS: Array<Direction> = arrayOf(EAST, WEST, SOUTH, NORTH)
    }
}
