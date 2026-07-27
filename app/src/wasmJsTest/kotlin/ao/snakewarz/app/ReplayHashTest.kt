package ao.snakewarz.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Fragment routing, which is one of the two places a stranger's bytes reach this program.
 *
 * The contract is narrow and entirely about failure: anything that is not a replay must come back
 * `null` so `main()` opens on a fresh match, and nothing may come back as a thrown exception — the
 * boot path has no handler for one, and the page would reveal a panel blaming the reader's browser
 * for what is really a typo in a link.
 */
class ReplayHashTest {
    @Test
    fun `a shared link decodes to the match it was made from`() {
        val record = assertNotNull(replayIn("#r=$SHIPPED_PAYLOAD"))

        assertEquals(10, record.setup.rows)
        assertEquals(10, record.setup.cols)
        assertEquals(listOf("cycle", "south"), record.setup.slots.map { it.slug })
    }

    @Test
    fun `the leading hash is optional, because the browser is not consistent about it`() {
        assertNotNull(replayIn("r=$SHIPPED_PAYLOAD"))
    }

    @Test
    fun `a replay is found beside other fragment parameters, in either position`() {
        assertNotNull(replayIn("#theme=dark&r=$SHIPPED_PAYLOAD"))
        assertNotNull(replayIn("#r=$SHIPPED_PAYLOAD&theme=dark"))
    }

    @Test
    fun `a fragment about something else is not a replay`() {
        assertNull(replayIn(""))
        assertNull(replayIn("#"))
        assertNull(replayIn("#theme=dark"))
        assertNull(replayIn("#readme"), "a prefix match on 'r' alone would claim this one")
    }

    @Test
    fun `every way of being a bad link ends at null rather than at an exception`() {
        assertNull(replayIn("#r="), "empty")
        assertNull(replayIn("#r=!!!not base64!!!"), "not base64url at all")
        assertNull(replayIn("#r=${SHIPPED_PAYLOAD.substring(0, 8)}"), "truncated")
        assertNull(replayIn("#r=${SHIPPED_PAYLOAD}AAAA"), "trailing rubbish")
        assertNull(replayIn("#r=" + "A".repeat(10_000)), "long and meaningless")
    }

    private companion object {
        /**
         * A real match, encoded by the shipped codec — `ReplayCodecTest.SHIPPED_PAYLOAD`, which
         * `:app` cannot import because it lives in `:match`'s test source set.
         *
         * A hand-written fixture would be testing this file against itself.
         */
        const val SHIPPED_PAYLOAD = "AQAJCdUHAAAAAAAAAoAgwLgCAgVjeWNsZQVzb3V0aABjAAECBQABAQA"
    }
}
