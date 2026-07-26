package ao.snakewarz.ui

/**
 * Colour, keyed by slot index and by nothing else.
 *
 * This is the whole of the rewrite's answer to the legacy `PlayerAvatar`, which fused player
 * identity, the AI delegate and a `java.awt.Image` into one class — and was then the key type of the
 * game state's map, so the engine transitively dragged in AWT. Those three concerns are now three
 * types in three modules: `SnakeId` in `:core`, `Bot` in `:bot-api`, and this. Nothing below `:ui`
 * has ever heard of a colour.
 *
 * Colours cycle past the last hue rather than being generated, because six distinguishable ones are
 * more than any playable match needs and a generated palette lands two snakes on adjacent hues about
 * as often as not.
 */
internal class Palette private constructor(
    val background: String,
    val gridline: String,
    private val heads: Array<String>,
) {
    /** The trail colour of the snake in [slot]. */
    fun body(slot: Int): String = bodyColour(slot)

    /**
     * The head colour of the snake in [slot] — lighter than its body on a dark page and darker on a
     * light one, so the head reads as the bright end of the trail either way.
     */
    fun head(slot: Int): String = heads[slot % heads.size]

    companion object {
        /**
         * How much of its colour a dead snake keeps.
         *
         * Corpses stay on the board as obstacles, so they have to be visible; they are also out of
         * the game, so they must not compete with the snakes still in it.
         */
        const val CORPSE_ALPHA: Double = 0.28

        /**
         * A snake's trail colour, which is the same under either theme — only the head, the
         * gridlines and the board behind them change. That is why the scoreboard swatches can be
         * painted without a palette instance, and why they never need repainting on a theme change.
         */
        fun bodyColour(slot: Int): String = BODIES[slot % BODIES.size]

        private val BODIES = arrayOf("#2f9e68", "#e08a2e", "#4a86d8", "#c25aa0", "#8a72d8", "#d05a52")

        private val LIGHT = Palette(
            background = "#ffffff",
            gridline = "#e7eaee",
            heads = arrayOf("#14663d", "#94530a", "#1c5296", "#8a2f6c", "#4f3899", "#8d2a24"),
        )

        private val DARK = Palette(
            background = "#171b1f",
            gridline = "#282e34",
            heads = arrayOf("#7fe3ac", "#ffc27a", "#98c1f5", "#efa4d4", "#c1b0ff", "#ffa099"),
        )

        fun of(dark: Boolean): Palette = if (dark) DARK else LIGHT
    }
}
