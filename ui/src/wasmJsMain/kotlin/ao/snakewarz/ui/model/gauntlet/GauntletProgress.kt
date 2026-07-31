package ao.snakewarz.ui.model.gauntlet

import ao.snakewarz.match.gauntlet.Gauntlet

/**
 * How far up the gauntlet this browser has got: the highest level it may open, and which it has beaten.
 *
 * A value rather than a store. Reading and writing `localStorage` is `Preferences`' job and the
 * session's — this only knows how to turn two numbers into a string and a string back into two
 * numbers, which is what makes every way of the string being wrong answerable here and testable
 * without a browser.
 *
 * ### The text, and every way it can arrive broken
 *
 * `v1:<highest unlocked>:<cleared bits>`, hand-written. Not JSON: there is none in the bundle and
 * pulling one in for two integers is what [ao.snakewarz.ui.render.Theme]'s size budget is about.
 *
 * The version is **in the value** as well as in the key, so a later format can be recognised rather
 * than merely stored beside this one — a v2 writer and a v1 reader share a browser the moment
 * somebody has two tabs open across a deploy. Anything this reader does not recognise — a missing
 * key, a version it has never heard of, junk from a devtools console, storage that threw and answered
 * `null` — is [NONE], because a boot that died looking up progress would be a black page for
 * everybody playing.
 *
 * ### Clamped on the way in
 *
 * A stored `highest` above the table is a gauntlet that lost a level between deploys, and it must clamp
 * rather than index past the end. The cleared set is masked to what is unlocked for the same reason.
 *
 * A level index is **frozen** once released, exactly like the `BotId` a level names: it is the key
 * this is stored under, and renumbering the table would hand everybody somebody else's progress.
 */
internal class GauntletProgress private constructor(
    /** The highest level that may be opened, counting from 1. Always within the table. */
    val highest: Int,
    /** One bit per level, level `n` at bit `n - 1`. Never carries a bit above [highest]. */
    private val clearedBits: Int,
) {
    /** Whether there is anything to come back to, which is what puts Continue on the menu. */
    val started: Boolean get() = highest > FIRST_LEVEL || clearedBits != 0

    fun stateOf(level: Int): State = when {
        level > highest -> State.LOCKED
        isCleared(level) -> State.CLEARED
        else -> State.OPEN
    }

    fun isCleared(level: Int): Boolean = level in FIRST_LEVEL..LEVELS && (clearedBits and bitOf(level)) != 0

    /**
     * This progress with [level] beaten, which unlocks the one above it and nothing further.
     *
     * Beating the last level unlocks nothing and is still recorded, so a finished gauntlet reads as a
     * full column of cleared tiles rather than one short of it and an open one.
     */
    fun withCleared(level: Int): GauntletProgress {
        require(level in FIRST_LEVEL..LEVELS) { "there are $LEVELS levels, so there is no level $level" }
        return of(maxOf(highest, level + 1), clearedBits or bitOf(level))
    }

    fun format(): String = "$VERSION$SEPARATOR$highest$SEPARATOR$clearedBits"

    override fun toString(): String = "GauntletProgress(${format()})"

    /** What a tile says about itself: beaten, playable, or not yet reachable. */
    enum class State {
        CLEARED,
        OPEN,
        LOCKED,
    }

    companion object {
        /** A browser that has never played a level, and the answer to every unreadable value. */
        val NONE: GauntletProgress = GauntletProgress(FIRST_LEVEL, 0)

        /** What [stored] says, or [NONE] where it says nothing this version understands. */
        fun parse(stored: String?): GauntletProgress {
            val fields = stored?.split(SEPARATOR) ?: return NONE
            if (fields.size != FIELDS || fields[0] != VERSION) {
                return NONE
            }
            val highest = fields[1].toIntOrNull() ?: return NONE
            val cleared = fields[2].toIntOrNull() ?: return NONE
            return of(highest, cleared)
        }

        private fun of(highest: Int, cleared: Int): GauntletProgress {
            val reachable = highest.coerceIn(FIRST_LEVEL, LEVELS)
            return GauntletProgress(reachable, cleared and maskUpTo(reachable))
        }

        private fun bitOf(level: Int): Int = 1 shl (level - 1)

        /** Every bit from level 1 up to and including [level]. */
        private fun maskUpTo(level: Int): Int = bitOf(level) or (bitOf(level) - 1)

        private const val VERSION = "v1"
        private const val SEPARATOR = ":"
        private const val FIELDS = 3
        private const val FIRST_LEVEL = 1

        /**
         * The table this is progress through.
         *
         * Checked rather than assumed, because the cleared set is a bitmask over one `Int`: a gauntlet
         * grown past that many rungs would silently drop the top of itself out of saved progress.
         */
        private val LEVELS: Int = Gauntlet.size.also {
            check(it < Int.SIZE_BITS) { "progress is a bitmask of one Int, so $it levels will not fit" }
        }
    }
}
