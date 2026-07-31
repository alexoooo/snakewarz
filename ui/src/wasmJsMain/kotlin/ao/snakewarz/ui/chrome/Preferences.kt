package ao.snakewarz.ui.chrome

import kotlinx.browser.localStorage

/**
 * What this browser remembers between visits: the player's taste, and how far up the ladder they got.
 *
 * Neither is anything a match depends on. A theme is not part of a replay and never travels in a
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
     * Ladder progress as it was last written, or `null` for a browser that has cleared nothing.
     *
     * A string rather than a parsed value, because what the text means is
     * [ao.snakewarz.ui.model.ladder.LadderProgress]'s to say and *whether it can be read at all* is
     * this object's — the two failures are unrelated and answering both here would fuse them.
     */
    fun ladder(): String? = read(LADDER_KEY)

    fun setLadder(progress: String) {
        write(LADDER_KEY, progress)
    }

    override fun toString(): String = "Preferences(${theme() ?: "unset"}, ${ladder() ?: "unset"})"

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
    private const val LADDER_KEY = "snakewarz.ladder.v1"
}
