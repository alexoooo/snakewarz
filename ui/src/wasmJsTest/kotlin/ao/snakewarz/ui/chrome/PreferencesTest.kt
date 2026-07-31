package ao.snakewarz.ui.chrome

import ao.snakewarz.ui.render.Theme
import kotlinx.browser.localStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The three ways a stored preference is not there, and the one answer to all of them.
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
        localStorage.removeItem(LADDER_KEY)
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
    fun `ladder progress round-trips under its own key`() {
        // Two things kept under one roof and neither reading the other's key: a browser that has
        // picked a theme has not necessarily played a level, and the reverse.
        Preferences.setLadder("v1:4:7")

        assertEquals("v1:4:7", Preferences.ladder())
        assertNull(Preferences.theme(), "and the theme is untouched by it")
    }

    @Test
    fun `storage the browser will not hand over is not an error`() {
        breakStorage()

        assertNull(Preferences.theme(), "reading answers 'nothing stored' rather than throwing")
        assertNull(Preferences.ladder(), "and so does progress, which is what opens the ladder at 1")
        Preferences.setTheme(Theme.ALL.last())
        Preferences.setLadder("v1:4:7")

        restoreStorage()
        assertNull(localStorage.getItem(THEME_KEY), "and the write it could not do went nowhere")
        assertNull(localStorage.getItem(LADDER_KEY))
    }

    private companion object {
        /** `Preferences`' own keys, which are private and stay that way for one test. */
        const val THEME_KEY = "snakewarz.theme.v1"
        const val LADDER_KEY = "snakewarz.ladder.v1"
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
