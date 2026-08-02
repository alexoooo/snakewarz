package ao.snakewarz.ui.chrome

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.gauntlet.Gauntlet
import ao.snakewarz.match.gauntlet.GauntletLevel
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.ui.model.Portraits
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import ao.snakewarz.ui.model.gauntlet.GauntletProgress
import ao.snakewarz.ui.render.GauntletVisual
import ao.snakewarz.ui.render.Theme
import ao.snakewarz.ui.render.identicon
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

/**
 * `#screen-gauntlet`: seven tiles, one per rung, and which of them may be played.
 *
 * [Gauntlet] is `:match`, which this module already sees, so a tile needs no seam and no injected
 * table — unlike the opponent's *name* and *face*, which are facts about a bot and therefore arrive
 * through the `BotRegistry` interface and [Portraits] by artwork key. Nothing here can tell a wall hugger
 * from a human, which is the whole of why the gauntlet table lives a module down.
 *
 * Seven is a fixed number, so the tiles are **static markup** and this only ever writes their text,
 * their state and their picture — the same arrangement as the four scoreboard cards, and not a third
 * exception to *"Kotlin never constructs structure"*. Title, blurb and the line naming the board are
 * written once at construction, because a level's identity does not change while the page is open.
 *
 * A locked tile is a `disabled` `<button>` rather than a `<div>`: it announces itself as unavailable,
 * it cannot be clicked, and it leaves the tab order — which together are what "locked" has to mean to
 * somebody who is not looking at the screen.
 *
 * **The ▷ that plays a cleared rung back is a sibling of the tile, not a child of it.** A tile is a
 * `<button>`, so a button inside it would be invalid markup and would never receive the click; the
 * control sits beside it in the `<li>` and is put over the corner in `styles.css`. It is `hidden`
 * where there is nothing to play rather than disabled, which is the argument `Mode.offers` already
 * makes for the panel openers: a control that can never apply should not be present at all.
 *
 * **The open tile carries `[data-focus]`, so arriving here lands on the level you would play.** That
 * attribute is read by [Shell] on the frame the screen appears, which is why `Chrome.render` runs
 * this before the shell rather than after it.
 */
internal class GauntletScreen(
    registry: BotRegistry,
    private val portraits: Portraits,
    dispatch: (UiIntent) -> Unit,
) {
    private val tiles: List<Tile> = Gauntlet.levels.map { level ->
        Tile(
            root = elementById("level-${level.index}"),
            replay = elementById("level-replay-${level.index}"),
            level = level,
            opponent = registry[level.opponent]?.displayName ?: level.opponent.slug,
        )
    }

    /**
     * The faces, and the theme they were tinted for.
     *
     * A bot with no shipped art is drawn in the colour its snake will be, which is `Theme.body` and
     * therefore the same string under light and dark — so this is rebuilt when the player picks a
     * theme and not when the sun goes down, exactly as `SlotPortraits` is.
     */
    private var renderedTheme: String? = null
    private var faces: List<String> = emptyList()

    /**
     * The progress the tiles were last written for, compared by identity.
     *
     * Every screen is rendered once a *frame*, and while a match runs that is sixty times a second —
     * so seven tiles of unchanged text would be the one genuinely wasteful thing on that path. Progress
     * is an immutable value replaced only when a level is beaten, which makes identity the exact
     * question, and it is the same cache the session keeps over its labels and faces.
     */
    private var renderedProgress: GauntletProgress? = null

    init {
        for (tile in tiles) {
            val index = tile.level.index
            tile.root.addEventListener("click") { dispatch(UiIntent.StartLevel(index)) }
            tile.replay.addEventListener("click") { dispatch(UiIntent.WatchLevelReplay(index)) }
        }
    }

    fun render(model: UiModel) {
        if (model.gauntlet === renderedProgress && model.theme.id == renderedTheme) {
            return
        }
        renderedProgress = model.gauntlet
        resolveFaces(model.theme)

        // The one tile worth landing on: the level about to be played, or the last of a gauntlet that
        // has none left to unlock. Never a locked one, which could not take the focus anyway.
        val landing = tiles.firstOrNull { model.gauntlet.stateOf(it.level.index) == GauntletProgress.State.OPEN }
            ?: tiles.last()

        for ((position, tile) in tiles.withIndex()) {
            val state = model.gauntlet.stateOf(tile.level.index)
            tile.render(
                state = state,
                face = faces[position],
                landing = tile === landing,
                stored = state == GauntletProgress.State.CLEARED && hasRun(tile.level.index),
            )
        }
    }

    override fun toString(): String = "GauntletScreen(${tiles.size})"

    // -- internals

    /**
     * Whether there is a run stored for [level], asked of the store rather than of the model.
     *
     * Safe under the cache above, and only because of when the two move together: a run is written on
     * the turn a level is won, which is the same turn progress is replaced — so a read guarded by that
     * instance can never be a frame behind the write. Progress in the model rather than a payload
     * because a kilobyte per rung has no business being carried through a once-a-frame snapshot.
     */
    private fun hasRun(level: Int): Boolean = Preferences.levelReplay(level) != null

    private fun resolveFaces(theme: Theme) {
        if (theme.id == renderedTheme) {
            return
        }
        renderedTheme = theme.id
        faces = Gauntlet.levels.map { level ->
            val slug = level.opponent.slug
            val key = GauntletVisual.at(level.index)?.portraitKey ?: slug
            portraits.urlFor(key) ?: identicon(slug, theme.body(OPPONENT_SLOT))
        }
    }

    private class Tile(
        val root: HTMLButtonElement,
        val replay: HTMLButtonElement,
        val level: GauntletLevel,
        opponent: String,
    ) {
        private val portrait: HTMLImageElement = root.child(".portrait")
        private val number: HTMLElement = root.child(".level-no")
        private val title: HTMLElement = root.child(".level-title")
        private val blurb: HTMLElement = root.child(".level-blurb")
        private val meta: HTMLElement = root.child(".level-meta")
        private val badge: HTMLElement = root.child(".level-state")

        init {
            number.textContent = "Level ${level.index}"
            title.textContent = level.title
            blurb.textContent = level.blurb
            meta.textContent = "${level.rows}$TIMES${level.cols} · ${mapName(level.shape)} · $opponent"
        }

        /**
         * Both halves of what a state looks like, written out rather than derived from the enum's
         * name: one is the hook `styles.css` reads and the other is copy a player reads, and neither
         * should change because a constant was renamed.
         */
        fun render(state: GauntletProgress.State, face: String?, landing: Boolean, stored: Boolean) {
            root.disabled = state == GauntletProgress.State.LOCKED
            when (state) {
                GauntletProgress.State.CLEARED -> style("level cleared", "Cleared")
                GauntletProgress.State.OPEN -> style("level open", "Play")
                GauntletProgress.State.LOCKED -> style("level locked", "Locked")
            }
            portrait.showPortrait(face)
            // Gone rather than greyed, so it is out of the tab order too: on a browser that has
            // cleared nothing there are seven of these and none of them could ever do anything.
            replay.hidden = !stored

            if (landing) {
                root.setAttribute(FOCUS, "")
            } else {
                root.removeAttribute(FOCUS)
            }
        }

        private fun style(className: String, badgeText: String) {
            root.className = className
            root.setAttribute("data-stage", GauntletVisual.at(level.index)?.stageId ?: "")
            badge.textContent = badgeText
        }
    }

    private companion object {
        /**
         * Which seat the opponent takes, and therefore which trail colour a drawn mark is tinted in.
         *
         * `GauntletLevel.setup` seats the player first and the opponent second, so a tile's face is
         * already the colour that snake will be on the board.
         */
        const val OPPONENT_SLOT = 1

        /** The attribute [Shell] takes the focus to when a screen arrives. */
        const val FOCUS = "data-focus"

        /** A board is `12×12` rather than `12x12`, the way the size picker already spells one. */
        const val TIMES = "×"

        /**
         * The map's name in the player's language.
         *
         * Taken from the frozen slug rather than written out a second time: `double-spiral` is one
         * hyphen away from what a person reads, and a `when` over the catalogue would be a list
         * somebody has to remember to extend when a shape lands.
         */
        fun mapName(shape: MapShape): String =
            shape.slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }
}
