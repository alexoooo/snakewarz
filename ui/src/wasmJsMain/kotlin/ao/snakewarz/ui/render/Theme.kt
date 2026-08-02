package ao.snakewarz.ui.render

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Colour, keyed by slot index and by nothing else.
 *
 * This is the whole of the rewrite's answer to the legacy `PlayerAvatar`, which fused player
 * identity, the AI delegate and a `java.awt.Image` into one class — and was then the key type of the
 * game state's map, so the engine transitively dragged in AWT. Those three concerns are now three
 * types in three modules: `SnakeId` in `:core`, `Bot` in `:bot-api`, and this. Nothing below `:ui`
 * has ever heard of a colour.
 *
 * ### A theme is a second axis over the scheme, not a replacement for it
 *
 * There is one instance per theme *and* per light/dark scheme, and the two axes carry different
 * things. What a snake **is** belongs to the theme: [body] is the same string under either scheme, so
 * a trail identifies its snake whether or not the reader's system has gone dark, and a scoreboard
 * swatch survives sunset without repainting. What is **readable against the page** belongs to the
 * scheme: the board, the gridlines, the walls and [head] all move with it — lighter than the trail on
 * a dark page and darker on a light one, so the head reads as the bright end of the trail either way.
 *
 * Which theme is showing is the player's, and which scheme is their system's. That is why [of] takes
 * the two separately and why flipping the OS to dark keeps the theme they chose.
 *
 * ### Six hues, cycled rather than generated
 *
 * Colours cycle past the last hue rather than being generated, because six distinguishable ones are
 * more than any playable match needs and a generated palette lands two snakes on adjacent hues about
 * as often as not.
 *
 * ### The canvas and the page are one theme
 *
 * [applyToPage] writes the same values out as the CSS custom properties `styles.css` reads, so the
 * board and the frame around it cannot disagree — see its own note for why nothing here is spelled
 * a second time in the stylesheet.
 */
internal class Theme private constructor(
    /** Frozen: it is written to `localStorage` and read back, so a rename orphans a stored choice. */
    val id: String,
    val background: String,
    val gridline: String,
    /**
     * A square of the map that can never be entered.
     *
     * A board colour rather than a snake colour, which is why it sits here beside [background] and
     * [gridline] and stays out of the trail hues. It is further from the background than a gridline
     * is and in the same direction the page's text runs — darker on a light board, lighter on a dark
     * one — so a wall reads as structure and a gridline still reads across it.
     *
     * Neutral, because a corpse is [CORPSE_ALPHA] of a trail hue: anything tinted here would read as
     * a snake that is out rather than as board that was never open.
     */
    val wall: String,
    /**
     * The line around a wall square, which is what stops a run of them reading as one slab.
     *
     * A map is drawn in blocks and a room's wall is a dozen squares in a row; without an edge the
     * shape of the map is legible only where it meets open board. Close to [wall] on purpose — this
     * is relief, not a second colour.
     */
    val wallEdge: String,
    private val heads: Array<String>,
    private val bodies: Array<String>,
    private val pageBackground: String,
    private val surface: String,
    private val ink: String,
    private val inkDim: String,
    private val line: String,
    /**
     * The one colour on the page that is the *player's* rather than a snake's or the board's.
     *
     * The page reads it as `--accent`; the canvas draws the route the player is drawing with it. Not
     * a trail hue, deliberately: a plan laid over the position must not read as a seventh snake.
     */
    val accent: String,
    private val accentInk: String,
) {
    /** The head colour of the snake in [slot]: the theme's answer to "this snake, but brighter". */
    fun head(slot: Int): String = heads[slot % heads.size]

    /** The trail colour of the snake in [slot], which the scoreboard swatch paints itself with. */
    fun body(slot: Int): String = bodies[slot % bodies.size]

    /** A level palette over the stored preference; the player remains the preference's first hue. */
    fun staged(visual: GauntletVisual): Theme {
        val stageHeads = heads.copyOf()
        val stageBodies = bodies.copyOf()
        stageHeads[1] = visual.enemyHead
        stageBodies[1] = visual.enemyBody
        return Theme(
            id = id,
            background = visual.board,
            gridline = visual.grid,
            wall = visual.wall,
            wallEdge = visual.wallEdge,
            heads = stageHeads,
            bodies = stageBodies,
            pageBackground = "#0b0a09",
            surface = "#181513",
            ink = "#eee4d4",
            inkDim = "#b4a796",
            line = "#493b31",
            accent = visual.accent,
            accentInk = "#100c09",
        )
    }

    /**
     * Writes this theme onto `<html>`, where `styles.css` reads it as custom properties.
     *
     * The canvas and the page are two views of one theme, and this is what keeps them from becoming
     * two answers: `--board` is the very string [background] fills the board with. `styles.css`
     * therefore carries the colours the page has *before* a theme arrives and nothing else, so none
     * of these values is spelled twice and there is no pair to keep in step.
     *
     * Written inline on the root element, which outranks every stylesheet rule without an
     * `!important`; custom properties inherit, so one write reaches the whole document.
     */
    fun applyToPage() {
        val root = document.documentElement as? HTMLElement ?: error("the page has no <html> element")

        root.style.setProperty("--board", background)
        root.style.setProperty("--bg", pageBackground)
        root.style.setProperty("--panel", surface)
        root.style.setProperty("--ink", ink)
        root.style.setProperty("--ink-dim", inkDim)
        root.style.setProperty("--line", line)
        root.style.setProperty("--accent", accent)
        root.style.setProperty("--accent-ink", accentInk)
    }

    override fun toString(): String = "Theme($id)"

    companion object {
        /**
         * How much of its colour a dead snake keeps.
         *
         * Corpses stay on the board as obstacles, so they have to be visible; they are also out of
         * the game, so they must not compete with the snakes still in it.
         */
        const val CORPSE_ALPHA: Double = 0.28

        /** What a page with nothing stored opens on, and what an id nothing offers falls back to. */
        const val DEFAULT_ID: String = "classic"

        /**
         * Every theme the picker offers, in the order `#panel-settings` lists them.
         *
         * Three, and deliberately not more: six palettes that all read well on both schemes is real
         * work, and a fourth adds nothing the first three did not. `SettingsPanel` checks the markup
         * against this at boot, so a theme with no `<option>` fails with its own name rather than
         * becoming a picker that quietly offers two of three.
         */
        val ALL: List<String> = listOf(DEFAULT_ID, "neon", "dusk")

        /**
         * The theme called [id] under the scheme in force, or the default where nothing is called
         * that.
         *
         * Total on purpose, which is the same carve-out `BotKnob.read` documents: the id arrives from
         * `localStorage`, so it can be a value a future version wrote, a value an older one did, or
         * whatever somebody typed into their devtools. There is a correct thing to do with all three
         * and it is not to take the page down over a preference.
         */
        fun of(id: String, dark: Boolean): Theme = when (id) {
            "neon" -> if (dark) NEON_DARK else NEON_LIGHT
            "dusk" -> if (dark) DUSK_DARK else DUSK_LIGHT
            else -> if (dark) CLASSIC_DARK else CLASSIC_LIGHT
        }

        /** The trails, one array per theme: what a snake *is* does not move when the sun goes down. */
        private val CLASSIC_BODIES =
            arrayOf("#2f9e68", "#e08a2e", "#4a86d8", "#c25aa0", "#8a72d8", "#d05a52")

        private val NEON_BODIES =
            arrayOf("#00b869", "#ff8a00", "#0096ff", "#ff00c8", "#7b5cff", "#ff3b30")

        private val DUSK_BODIES =
            arrayOf("#5a8f6b", "#c9a13f", "#5f7fa3", "#b06a86", "#6f6fa8", "#b3675e")

        private val CLASSIC_LIGHT = Theme(
            id = DEFAULT_ID,
            background = "#ffffff",
            gridline = "#e7eaee",
            wall = "#aab2bd",
            wallEdge = "#8d97a4",
            heads = arrayOf("#14663d", "#94530a", "#1c5296", "#8a2f6c", "#4f3899", "#8d2a24"),
            bodies = CLASSIC_BODIES,
            pageBackground = "#f6f7f9",
            surface = "#ffffff",
            ink = "#16191d",
            inkDim = "#5b6470",
            line = "#dfe3e8",
            accent = "#2f6f4f",
            accentInk = "#ffffff",
        )

        private val CLASSIC_DARK = Theme(
            id = DEFAULT_ID,
            background = "#171b1f",
            gridline = "#282e34",
            wall = "#4c555f",
            wallEdge = "#657079",
            heads = arrayOf("#7fe3ac", "#ffc27a", "#98c1f5", "#efa4d4", "#c1b0ff", "#ffa099"),
            bodies = CLASSIC_BODIES,
            pageBackground = "#14171a",
            surface = "#1c2024",
            ink = "#e8ebee",
            inkDim = "#99a3ae",
            line = "#2c3238",
            accent = "#3f9e6c",
            accentInk = "#06120c",
        )

        private val NEON_LIGHT = Theme(
            id = "neon",
            background = "#fbfbff",
            gridline = "#e9ebf6",
            wall = "#a2aacf",
            wallEdge = "#838cb6",
            heads = arrayOf("#00713f", "#a35200", "#005ba3", "#a3007f", "#4529a8", "#a3211a"),
            bodies = NEON_BODIES,
            pageBackground = "#f2f4fd",
            surface = "#ffffff",
            ink = "#0d1030",
            inkDim = "#585e88",
            line = "#dde1f2",
            accent = "#0067d6",
            accentInk = "#ffffff",
        )

        private val NEON_DARK = Theme(
            id = "neon",
            background = "#0a0c14",
            gridline = "#191d2c",
            wall = "#3a4272",
            wallEdge = "#5d68ad",
            heads = arrayOf("#5cffb0", "#ffc45c", "#66c6ff", "#ff7ae0", "#b9a6ff", "#ff8f88"),
            bodies = NEON_BODIES,
            pageBackground = "#07080f",
            surface = "#111426",
            ink = "#e6e9ff",
            inkDim = "#8f95c4",
            line = "#242a46",
            accent = "#00d0e6",
            accentInk = "#04121a",
        )

        private val DUSK_LIGHT = Theme(
            id = "dusk",
            background = "#fbf7f0",
            gridline = "#efe7d9",
            wall = "#bdae96",
            wallEdge = "#9e8f76",
            heads = arrayOf("#2f5c40", "#8a6a12", "#33506e", "#7a3f57", "#454379", "#7d3a32"),
            bodies = DUSK_BODIES,
            pageBackground = "#f3ecdf",
            surface = "#fbf7f0",
            ink = "#2a2620",
            inkDim = "#6b6254",
            line = "#e3d9c6",
            accent = "#406f63",
            accentInk = "#f7f2e9",
        )

        private val DUSK_DARK = Theme(
            id = "dusk",
            background = "#1e1b18",
            gridline = "#2d2823",
            wall = "#50483c",
            wallEdge = "#6b6151",
            heads = arrayOf("#8fd8a4", "#f0cd7a", "#9cbfe0", "#e2a3bd", "#b0abe0", "#e5a094"),
            bodies = DUSK_BODIES,
            pageBackground = "#191614",
            surface = "#241f1b",
            ink = "#ece5da",
            inkDim = "#a2988a",
            line = "#352f28",
            accent = "#6aa898",
            accentInk = "#0d1512",
        )
    }
}
