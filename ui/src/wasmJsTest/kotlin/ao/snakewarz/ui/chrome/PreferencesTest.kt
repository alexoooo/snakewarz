package ao.snakewarz.ui.chrome

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.replay.MatchRecord
import ao.snakewarz.match.replay.ReplayCodec
import ao.snakewarz.ui.render.Theme
import kotlinx.browser.localStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The three ways a stored preference is not there, the one answer to all of them, and the keys the
 * three things kept here are frozen under.
 *
 * None of these is defensive. A key that was never written is every first visit; a value nothing
 * recognises is a version that offered a theme this one does not, or a devtools console; and storage
 * that throws outright is Safari in private browsing, which refuses the property itself. A boot that
 * died on any of the three would be a black page for the whole game — so the pairing with
 * [Theme.of]'s total lookup is what is really under test, and it is asserted as the pair.
 */
class PreferencesTest {
    @AfterTest
    fun restore() {
        // Restored first, and in this order: a test that broke the prototype has to put it back
        // before it can clear anything, and every test here leaves the store as it found it.
        restoreStorage()
        localStorage.removeItem(THEME_KEY)
        localStorage.removeItem(GAUNTLET_KEY)
        localStorage.removeItem(replayKey(1))
        localStorage.removeItem(replayKey(2))
    }

    @Test
    fun `a browser that has never been here opens on the default`() {
        localStorage.removeItem(THEME_KEY)

        assertNull(Preferences.theme())
        assertEquals(Theme.DEFAULT_ID, Theme.of(Preferences.theme() ?: Theme.DEFAULT_ID, dark = false).id)
    }

    @Test
    fun `a value from another version opens on the default`() {
        localStorage.setItem(THEME_KEY, "chartreuse")

        // Read back rather than swallowed: what is stored is somebody's choice under a version that
        // may still be able to honour it, and this one only has to be able to open.
        assertEquals("chartreuse", Preferences.theme())
        assertEquals(Theme.DEFAULT_ID, Theme.of(Preferences.theme() ?: Theme.DEFAULT_ID, dark = false).id)
    }

    @Test
    fun `a choice made comes back`() {
        Preferences.setTheme(Theme.ALL.last())

        assertEquals(Theme.ALL.last(), Preferences.theme())
    }

    @Test
    fun `gauntlet progress round-trips under its own key`() {
        // Two things kept under one roof and neither reading the other's key: a browser that has
        // picked a theme has not necessarily played a level, and the reverse.
        Preferences.setGauntlet("v1:4:7")

        assertEquals("v1:4:7", Preferences.gauntlet())
        assertNull(Preferences.theme(), "and the theme is untouched by it")
    }

    @Test
    fun `a level's winning run round-trips under its own key`() {
        Preferences.setLevelReplay(1, RUN)

        assertEquals(RUN, Preferences.levelReplay(1))
        assertEquals(RECORD, ReplayCodec.decode(Preferences.levelReplay(1)!!), "and it is still a match")
        assertEquals(RUN, localStorage.getItem(replayKey(1)), "under the key that is frozen once released")
    }

    @Test
    fun `junk under a level's key is nothing to play rather than a failure`() {
        localStorage.setItem(replayKey(1), "not a replay")

        // Handed back rather than swallowed, exactly as an unknown theme id is: whether the store can
        // be read at all is this object's question and whether the text is a match is the codec's,
        // and what has to hold is that the pair ends at "there is no run" and not at a black page.
        assertEquals("not a replay", Preferences.levelReplay(1))
        assertFailsWith<IllegalArgumentException> { ReplayCodec.decode(Preferences.levelReplay(1)!!) }
    }

    @Test
    fun `writing one level's run leaves every other level alone`() {
        // The whole reason there are eleven keys rather than one holding eleven records: beating a
        // rung rewrites that rung, and a value that arrives corrupt costs that rung and no other.
        Preferences.setLevelReplay(1, RUN)
        Preferences.setLevelReplay(2, OTHER_RUN)

        assertEquals(RUN, Preferences.levelReplay(1))
        assertEquals(OTHER_RUN, Preferences.levelReplay(2))
        assertNull(Preferences.levelReplay(3), "and a rung nobody has beaten has nothing to offer")
    }

    @Test
    fun `storage the browser will not hand over is not an error`() {
        breakStorage()

        assertNull(Preferences.theme(), "reading answers 'nothing stored' rather than throwing")
        assertNull(Preferences.gauntlet(), "and so does progress, which is what opens the gauntlet at 1")
        assertNull(Preferences.levelReplay(1), "and so does a run, which is a tile with no ▷ on it")
        Preferences.setTheme(Theme.ALL.last())
        Preferences.setGauntlet("v1:4:7")
        Preferences.setLevelReplay(1, RUN)

        restoreStorage()
        assertNull(localStorage.getItem(THEME_KEY), "and the write it could not do went nowhere")
        assertNull(localStorage.getItem(GAUNTLET_KEY))
        assertNull(localStorage.getItem(replayKey(1)))
    }

    private companion object {
        /** `Preferences`' own keys, which are private and stay that way for one test. */
        const val THEME_KEY = "snakewarz.theme.v1"

        /**
         * The old word, on purpose: the campaign was the Ladder when this key shipped and the key is
         * somebody's saved place, so SW-05 freezes the string while the name above moved on. This
         * assertion is what would fail if a rename ever reached it.
         */
        const val GAUNTLET_KEY = "snakewarz.ladder.v1"

        /**
         * The per-rung key, written out here rather than asked for, for [GAUNTLET_KEY]'s reason: it
         * is the one genuinely new frozen string in this feature, and a test that derived it from the
         * code it is checking would agree with any rename.
         */
        fun replayKey(level: Int): String = "snakewarz.gauntlet.replay.$level.v1"

        val SETUP: MatchSetup = MatchSetup.create(rows = 8, cols = 8, slots = listOf(BotId("space")), seed = 1)

        /** One seat, so a real record can be taken off a real board rather than invented. */
        val ONE_SEAT = object : BotRegistry {
            private val entry = BotEntry(BotId("space"), "Space", BotFactory { Quitter })

            override val entries: List<BotEntry> = listOf(entry)

            override fun get(id: BotId): BotEntry? = entry.takeIf { it.id == id }
        }

        object Quitter : Bot {
            override fun chooseMove(turn: Turn): Decision = Decision.Resign
        }

        /**
         * What is actually stored: `ReplayCodec`'s own string, not a format of this feature's own.
         *
         * Two of them, from two different boards, so that "one level's value is untouched by writing
         * another's" cannot pass by both keys holding the same text.
         */
        val RECORD: MatchRecord = Match(SETUP, ONE_SEAT).record()
        val RUN: String = ReplayCodec.encode(RECORD)
        val OTHER_RUN: String = ReplayCodec.encode(
            Match(MatchSetup.create(rows = 12, cols = 12, slots = listOf(BotId("space")), seed = 2), ONE_SEAT).record(),
        )
    }
}

/**
 * Makes every `localStorage` call throw, the way a browser refusing storage does.
 *
 * On the prototype rather than on the instance, because that is where the refusal actually lives —
 * and hand-written interop because there is no typed way to say it.
 */
private fun breakStorage(): Unit = js(
    """{
        var proto = Storage.prototype;
        if (!proto.snakewarzGetItem) {
            proto.snakewarzGetItem = proto.getItem;
            proto.snakewarzSetItem = proto.setItem;
        }
        proto.getItem = function () { throw new Error("storage denied"); };
        proto.setItem = function () { throw new Error("storage denied"); };
    }""",
)

/** Puts the prototype back, so a broken store cannot leak into whatever test runs next. */
private fun restoreStorage(): Unit = js(
    """{
        var proto = Storage.prototype;
        if (proto.snakewarzGetItem) {
            proto.getItem = proto.snakewarzGetItem;
            proto.setItem = proto.snakewarzSetItem;
        }
    }""",
)
