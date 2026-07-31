package ao.snakewarz.ui.model.ladder

import ao.snakewarz.match.ladder.Ladder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every way a stored value is not what this version writes, and the one answer to all of them.
 *
 * None of these is defensive. A key that was never written is every first visit; a version this
 * reader has never heard of is a tab left open across a deploy; junk is a devtools console; and a
 * `highest` past the end of the table is a ladder that lost a rung. A boot that died on any of them
 * would be a black page for the whole game, so the answer is always a playable level 1.
 *
 * Storage that throws is [ao.snakewarz.ui.chrome.Preferences]' half rather than this one's — it
 * answers `null`, which is the first case here — and `PreferencesTest` covers it against the store
 * itself.
 */
class LadderProgressTest {
    @Test
    fun `a browser that has never played opens on level 1 with nothing cleared`() {
        val fresh = LadderProgress.parse(null)

        assertEquals(1, fresh.highest)
        assertFalse(fresh.started, "so the menu offers no Continue")
        assertEquals(LadderProgress.State.OPEN, fresh.stateOf(1))
        assertEquals(LadderProgress.State.LOCKED, fresh.stateOf(2))
    }

    @Test
    fun `progress written comes back`() {
        val played = LadderProgress.NONE.withCleared(1).withCleared(2)

        val reopened = LadderProgress.parse(played.format())

        assertEquals(played.format(), reopened.format())
        assertEquals(3, reopened.highest)
        assertTrue(reopened.isCleared(1) && reopened.isCleared(2))
        assertTrue(reopened.started)
    }

    @Test
    fun `junk, a version nobody wrote, and a value with the wrong shape all open on level 1`() {
        for (stored in listOf("", "nonsense", "v2:5:31", "v1:5", "v1:5:31:7", "v1:x:31", "v1:5:x")) {
            assertEquals(1, LadderProgress.parse(stored).highest, "from '$stored'")
            assertFalse(LadderProgress.parse(stored).started, "from '$stored'")
        }
    }

    @Test
    fun `a highest above the table clamps to it`() {
        // A ladder that lost a rung between deploys. Clamping is what keeps a tile lookup in range;
        // indexing past the end would be a boot that fails on somebody else's saved game.
        val shrunk = LadderProgress.parse("v1:${Ladder.size + 5}:-1")

        assertEquals(Ladder.size, shrunk.highest)
        assertEquals(LadderProgress.State.CLEARED, shrunk.stateOf(Ladder.size))
    }

    @Test
    fun `clearing a level unlocks exactly the one above it`() {
        val beaten = LadderProgress.NONE.withCleared(1)

        assertEquals(LadderProgress.State.CLEARED, beaten.stateOf(1))
        assertEquals(LadderProgress.State.OPEN, beaten.stateOf(2))
        assertEquals(LadderProgress.State.LOCKED, beaten.stateOf(3))
        assertEquals(1, Ladder.levels.count { beaten.stateOf(it.index) == LadderProgress.State.OPEN })
    }

    @Test
    fun `beating the last rung leaves nothing locked and nothing open`() {
        val finished = Ladder.levels.fold(LadderProgress.NONE) { progress, level ->
            progress.withCleared(level.index)
        }

        assertEquals(Ladder.size, finished.highest)
        assertTrue(
            Ladder.levels.all { finished.stateOf(it.index) == LadderProgress.State.CLEARED },
            "every rung reads as beaten rather than nine beaten and one still open",
        )
    }

    @Test
    fun `replaying a level already beaten unlocks nothing further`() {
        val ahead = LadderProgress.NONE.withCleared(1).withCleared(2)

        assertEquals(ahead.format(), ahead.withCleared(1).format())
    }
}
