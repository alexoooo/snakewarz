package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayPlaybackTest {
    @Test
    fun `playback reproduces the match move for move, and its final position`() {
        val original = matchOf(14, 14, "cycle", "cycle", seed = 77)
        original.runToCompletion()
        val record = original.record()

        assertTrue(record.moves.size > 20, "a two-move match would prove nothing; got ${record.moves.size}")

        val played = Match.playback(record)
        played.runToCompletion()

        assertEquals(record, played.record())
        assertEquals(original.view.hash, played.view.hash, "and lands on the identical position")
        assertEquals(original.snapshot().toString(), played.snapshot().toString())
    }

    @Test
    fun `playback reproduces a resignation and a forfeit, through the driver's own mechanisms`() {
        val original = matchOf(9, 9, "quitter", "thrower", "cycle", seed = 8)
        original.runToCompletion()
        val record = original.record()

        val played = Match.playback(record)
        played.runToCompletion()

        assertEquals(record.terminals, played.record().terminals)
        assertEquals(record.outcome, played.outcome)
    }

    @Test
    fun `playback costs no bot, so a shared replay needs none of the bots that made it`() {
        // The registry a replay is played through knows nothing: this is what lets a URL from an
        // older build still play back after a bot has been retuned, or removed outright.
        val original = matchOf(12, 12, "cycle", "south", seed = 3)
        original.runToCompletion()

        val played = Match.playback(original.record())

        assertEquals(original.runToCompletionMoves(), played.runToCompletionMoves())
    }

    @Test
    fun `seeking is replaying into a scratch board`() {
        val original = matchOf(16, 16, "cycle", "south", seed = 21)
        original.runToCompletion()
        val record = original.record()

        val target = record.turnCount / 2
        val seeked = Match.playback(record)
        repeat(target) { seeked.step() }

        val reference = matchOf(16, 16, "cycle", "south", seed = 21)
        repeat(target) { reference.step() }

        assertEquals(reference.view.hash, seeked.view.hash, "seeking to turn $target")
    }

    @Test
    fun `playing past the end of a partial recording parks instead of inventing moves`() {
        val original = matchOf(16, 16, "cycle", "cycle")
        repeat(11) { original.step() }
        val partial = original.record()

        val played = Match.playback(partial)
        repeat(11) { assertTrue(played.step() !is StepResult.Finished) }

        assertEquals(StepResult.AwaitingInput, played.step())
        assertEquals(11, played.turnIndex, "and it consumed no turn doing so")
    }

    @Test
    fun `verify re-runs the real bots and agrees`() {
        val record = playedRecord()

        val verification = record.verify(TestRegistry.ALL)

        assertTrue(verification.matches, verification.detail)
        assertEquals(-1, verification.divergedAtMove)
    }

    @Test
    fun `verify catches a tampered move stream and says where`() {
        val record = playedRecord()

        val tampered = DirectionStream()
        for (i in 0 until record.moves.size) {
            tampered.add(if (i == 5) wrongTurn(record.moves[i]) else record.moves[i])
        }

        val verification = MatchRecord(record.setup, tampered, record.terminals, record.outcome)
            .verify(TestRegistry.ALL)

        assertEquals(false, verification.matches)
        assertEquals(5, verification.divergedAtMove)
    }

    @Test
    fun `verify catches a stream that stops early`() {
        val record = playedRecord()

        val truncated = DirectionStream()
        for (i in 0 until record.moves.size - 3) {
            truncated.add(record.moves[i])
        }

        val verification = MatchRecord(record.setup, truncated, record.terminals, record.outcome)
            .verify(TestRegistry.ALL)

        assertEquals(false, verification.matches)
        assertTrue(verification.detail.contains("moves"), verification.detail)
    }

    @Test
    fun `verify agrees with a recording that stops mid-match`() {
        // Which is the shape Share publishes: `record()` is taken at whatever turn the board is on,
        // so an outcome of null is the common case for a link somebody sent, not an odd one. The
        // replay runs to completion and is longer by construction; holding it to the recorded length
        // reported a divergence for every one of them.
        val match = matchOf(15, 15, "cycle", "cycle", seed = 1234)
        repeat(11) { match.step() }
        val partial = match.record()

        assertEquals(null, partial.outcome, "the fixture has to actually be partial")

        val verification = partial.verify(TestRegistry.ALL)

        assertTrue(verification.matches, verification.detail)
    }

    @Test
    fun `verify still catches a tampered prefix in a partial recording`() {
        // The prefix is checked as strictly as a whole match; it is only the tail that is not ours.
        val match = matchOf(15, 15, "cycle", "cycle", seed = 1234)
        repeat(11) { match.step() }
        val partial = match.record()

        val tampered = DirectionStream()
        for (i in 0 until partial.moves.size) {
            tampered.add(if (i == 5) wrongTurn(partial.moves[i]) else partial.moves[i])
        }

        val verification = MatchRecord(partial.setup, tampered, partial.terminals, null)
            .verify(TestRegistry.ALL)

        assertEquals(false, verification.matches)
        assertEquals(5, verification.divergedAtMove)
    }

    @Test
    fun `verify catches a replay that stops short of a partial recording`() {
        // The half of the length check that survives: longer than the record is expected, shorter is
        // a real divergence, and the leniency must not swallow it.
        val record = playedRecord()

        val overlong = DirectionStream()
        for (i in 0 until record.moves.size) {
            overlong.add(record.moves[i])
        }
        repeat(3) { overlong.add(Direction.NORTH) }

        val verification = MatchRecord(record.setup, overlong, record.terminals, null)
            .verify(TestRegistry.ALL)

        assertEquals(false, verification.matches)
        assertTrue(verification.detail.contains("moves"), verification.detail)
    }

    /** A match long enough that tampering with move five is tampering with something. */
    private fun playedRecord(): MatchRecord {
        val match = matchOf(15, 15, "cycle", "cycle", seed = 1234)
        match.runToCompletion()
        val record = match.record()

        assertTrue(record.moves.size > 20, "the fixture is too short to test against: ${record.moves.size} moves")
        return record
    }

    @Test
    fun `a shared replay is a round trip through a URL, played back`() {
        // End to end, the way the app will actually use this: play, encode into the hash, decode
        // whatever comes back, and watch it.
        val original = matchOf(18, 18, "cycle", "south", seed = 2005)
        original.runToCompletion()

        val hash = "#r=" + ReplayCodec.encode(original.record())
        val decoded = ReplayCodec.decode(hash.removePrefix("#r="))
        val played = Match.playback(decoded)
        played.runToCompletion()

        assertEquals(original.record(), played.record())
        assertEquals(original.outcome, played.outcome)
    }

    private fun wrongTurn(direction: Direction): Direction =
        Direction.entries[(direction.ordinal + 1) % Direction.entries.size]
}

private fun Match.runToCompletionMoves(): List<Direction> {
    runToCompletion()
    return record().moves.toList()
}
