package ao.snakewarz.ui.chrome

import kotlinx.browser.localStorage

/**
 * What this browser remembers between visits: the player's taste, how far up the gauntlet they got,
 * and the run that cleared each rung.
 *
 * None of it is anything a match depends on. A theme is not part of a replay and never travels in a
 * link, and progress is one browser's memory of one person's evenings — which is exactly why every
 * route through here has an answer for "there is nothing stored" and none of them can fail.
 *
 * **Reading must survive a missing key, a value from another version, and `localStorage` throwing
 * outright.** The last is real rather than defensive: Safari in private browsing throws on the
 * property itself, and a boot that dies on a theme lookup is a black page for the whole game. That
 * is the [ao.snakewarz.ui.render.Theme.of] carve-out one level up — CC-08's fail-fast rule asks
 * whether the fallback hides a logic error, and here there is a correct thing to do and it is to
 * open on the default.
 *
 * The key carries its own version, so a later format can be stored beside this one rather than
 * having to be recognised inside it.
 */
internal object Preferences {
    /** The theme id last chosen on this browser, or `null` for a reader who has never chosen one. */
    fun theme(): String? = read(THEME_KEY)

    fun setTheme(id: String) {
        write(THEME_KEY, id)
    }

    /**
     * Gauntlet progress as it was last written, or `null` for a browser that has cleared nothing.
     *
     * A string rather than a parsed value, because what the text means is
     * [ao.snakewarz.ui.model.gauntlet.GauntletProgress]'s to say and *whether it can be read at all*
     * is this object's — the two failures are unrelated and answering both here would fuse them.
     */
    fun gauntlet(): String? = read(GAUNTLET_KEY)

    fun setGauntlet(progress: String) {
        write(GAUNTLET_KEY, progress)
    }

    /**
     * The run that cleared level [level] on this browser, or `null` where nothing is kept for it.
     *
     * The text is a `ReplayCodec` payload — the very string a shared link carries — and this knows
     * nothing about it beyond that, for the reason [gauntlet] hands its text on unparsed: whether the
     * store can be read at all and whether what came back is a match are unrelated failures, and
     * answering both here would fuse them.
     */
    fun levelReplay(level: Int): String? = read(levelReplayKey(level))

    fun setLevelReplay(level: Int, payload: String) {
        write(levelReplayKey(level), payload)
    }

    override fun toString(): String = "Preferences(${theme() ?: "unset"}, ${gauntlet() ?: "unset"})"

    // -- internals

    private fun read(key: String): String? =
        try {
            localStorage.getItem(key)
        } catch (denied: Throwable) {
            null
        }

    /** Silent on failure, because the alternative is a picker that throws when you use it. */
    private fun write(key: String, value: String) {
        try {
            localStorage.setItem(key, value)
        } catch (denied: Throwable) {
            // Storage the browser will not hand over. The choice still applies to this page; it
            // just will not be here next time, which is the whole of what is lost.
        }
    }

    private const val THEME_KEY = "snakewarz.theme.v1"

    /**
     * The constant and the string it holds deliberately disagree.
     *
     * The campaign was called the Ladder when this key shipped, and the key is somebody's saved
     * place: renaming the *string* would hand every existing player a browser that has never heard
     * of them and open them back at level 1. SW-05 freezes what was released, so the word survives
     * where only the storage can see it and nowhere a player can.
     */
    private const val GAUNTLET_KEY = "snakewarz.ladder.v1"

    /**
     * One key per rung — `snakewarz.gauntlet.replay.<n>.v1` — rather than one key holding them all.
     *
     * Writing one level then rewrites nothing else, a value that arrives corrupt costs that one rung
     * rather than the lot, and there is no concatenation format for anybody to parse. A record is on
     * the order of a kilobyte at the turn limit, so eleven of them sit far inside any quota.
     *
     * The version belongs to the *board* as much as to the layout: a rung whose map or opponent
     * changed would otherwise hand somebody a game on a board that no longer exists, and bumping to
     * `.v2` leaves the old value unread rather than played.
     */
    private fun levelReplayKey(level: Int): String = "snakewarz.gauntlet.replay.$level.v1"
}
