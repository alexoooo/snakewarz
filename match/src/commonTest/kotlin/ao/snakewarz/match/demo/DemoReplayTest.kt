package ao.snakewarz.match.demo

import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.Match
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.replay.ReplayCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the demo's **story**, not merely that its payload parses.
 *
 * A demo that still decoded but ended in a draw, or in the winner running into a wall on its own,
 * would teach the opposite of what it is on the page to teach, and nothing else in the build would
 * notice. So the assertions below are about being boxed in, and they are the reason the payload
 * lives in `:match` rather than beside the canvas that draws it: `:ui` has no JVM target, and its
 * suite runs only under `-PbrowserTests=true`.
 */
class DemoReplayTest {
    @Test
    fun opensOnTheBoardTheGameOpensOn() {
        val setup = ReplayCodec.decode(DemoReplay.PAYLOAD).setup

        assertEquals(8, setup.rows)
        assertEquals(8, setup.cols)
        assertEquals(2, setup.slotCount)
        assertEquals(0, setup.wallCount, "an empty board: a map would be a second thing to explain")
    }

    @Test
    fun isShortEnoughToWatchWithoutDeciding() {
        val record = ReplayCodec.decode(DemoReplay.PAYLOAD)

        // Long enough for the bodies to become an obstacle and short enough to loop on a menu.
        assertTrue(record.turnCount in 20..48, "the demo runs ${record.turnCount} turns")
    }

    @Test
    fun endsWithTheLoserBoxedIn() {
        val record = ReplayCodec.decode(DemoReplay.PAYLOAD)
        val play = Match.playback(record)

        while (true) {
            val result = play.step()
            assertFalse(
                result == StepResult.AwaitingInput,
                "the demo is a complete recording, so playback must never park for input",
            )
            if (result is StepResult.Finished) {
                break
            }
        }

        val outcome = play.outcome
        assertEquals(MatchEnd.LAST_SNAKE_STANDING, outcome?.end)
        assertEquals(
            SnakeId(0),
            outcome?.winner,
            "slot 0 is written first so the cornering snake wears the colour a person plays in",
        )

        val slots = play.stats().slots
        assertTrue(slots[0].alive, "the winner is still moving")
        assertEquals(
            EliminationReason.TRAPPED,
            slots[1].fate,
            "the loser must run out of room rather than merely blunder into something",
        )
    }

    @Test
    fun isStoredInTheOldestFormThatHoldsIt() {
        val record = ReplayCodec.decode(DemoReplay.PAYLOAD)

        // The same round-trip the shared-link format is held to, so the constant cannot drift into a
        // spelling the codec would re-encode differently.
        assertEquals(DemoReplay.PAYLOAD, ReplayCodec.encode(record))
    }
}
