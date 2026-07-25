package ao.snakewarz.match

import ao.snakewarz.botapi.BotId
import ao.snakewarz.core.Direction
import ao.snakewarz.core.EliminationReason
import ao.snakewarz.core.MatchEnd
import ao.snakewarz.core.MatchOutcome
import ao.snakewarz.core.RulesConfig
import ao.snakewarz.core.SnakeId
import ao.snakewarz.core.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayCodecTest {
    @Test
    fun `a played match survives the round trip`() {
        val match = matchOf(15, 15, "cycle", "south", seed = 31)
        match.runToCompletion()
        val record = match.record()

        assertEquals(record, ReplayCodec.decode(ReplayCodec.encode(record)))
    }

    @Test
    fun `resignations and forfeits survive the round trip`() {
        // The side table is the part with a bespoke layout, so it is the part most worth fuzzing.
        val match = matchOf(9, 9, "quitter", "thrower", "cycle", seed = 8)
        match.runToCompletion()
        val record = match.record()

        assertEquals(2, record.terminals.size, "one resignation and one forfeit")
        assertEquals(record, ReplayCodec.decode(ReplayCodec.encode(record)))
    }

    @Test
    fun `an unfinished recording survives the round trip`() {
        val match = matchOf(15, 15, "cycle", "cycle")
        repeat(9) { match.step() }
        val record = match.record()

        assertEquals(null, record.outcome)
        assertEquals(record, ReplayCodec.decode(ReplayCodec.encode(record)))
    }

    @Test
    fun `fuzzing round trips exactly`() {
        val rng = SplitMix64(20050101)

        repeat(200) { iteration ->
            val slotCount = 1 + rng.nextInt(4)
            val rows = 1 + rng.nextInt(40)
            val cols = 1 + rng.nextInt(40)
            if (rows * cols < slotCount) {
                return@repeat
            }

            val setup = MatchSetup.create(
                rows = rows,
                cols = cols,
                slots = List(slotCount) { BotId("bot$it") },
                seed = rng.nextLong(),
                rules = RulesConfig(growEveryNthMove = 1 + rng.nextInt(4), maxTurns = 1 + rng.nextInt(5000)),
                budgetPerTurn = rng.nextInt(100_000),
            )

            // A real match cannot hold more moves than its own turn limit, and the decoder rejects a
            // payload claiming otherwise — so the generator has to respect it too.
            val moves = DirectionStream()
            repeat(minOf(rng.nextInt(300), setup.rules.maxTurns)) { moves.add(Direction.entries[rng.nextInt(4)]) }

            // Terminal turns ascend, and there are at most as many as the field allows — which for a
            // solo match is one, not zero.
            val terminals = mutableListOf<TerminalEvent>()
            var turn = 0
            repeat(rng.nextInt(MatchRecord.maxTerminals(slotCount) + 1)) {
                turn += 1 + rng.nextInt(10)
                terminals += TerminalEvent(
                    turn,
                    SnakeId(rng.nextInt(slotCount)),
                    if (rng.nextInt(2) == 0) EliminationReason.RESIGNED else EliminationReason.FORFEIT,
                )
            }

            val outcome = when (rng.nextInt(3)) {
                0 -> null
                1 -> MatchOutcome(SnakeId.NONE, MatchEnd.entries[rng.nextInt(MatchEnd.entries.size)])
                else -> MatchOutcome(SnakeId(rng.nextInt(slotCount)), MatchEnd.LAST_SNAKE_STANDING)
            }

            val record = MatchRecord(setup, moves, terminals, outcome)
            assertEquals(record, ReplayCodec.decode(ReplayCodec.encode(record)), "iteration $iteration")
        }
    }

    @Test
    fun `a payload is URL safe and short enough to share`() {
        val match = matchOf(20, 20, "cycle", "cycle", seed = 4)
        match.runToCompletion()
        val payload = ReplayCodec.encode(match.record())

        assertTrue(
            payload.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' },
            "a replay travels in a URL hash and must need no escaping: $payload",
        )
        assertTrue(payload.length < 400, "a 20x20 match encoded to ${payload.length} characters")
    }

    @Test
    fun `corruption is rejected rather than silently decoded`() {
        val match = matchOf(10, 10, "cycle", "south")
        match.runToCompletion()
        val payload = ReplayCodec.encode(match.record())

        assertFailsWith<IllegalArgumentException>("not base64url at all") { ReplayCodec.decode("!!!not base64!!!") }
        assertFailsWith<IllegalArgumentException>("truncated") { ReplayCodec.decode(payload.substring(0, 8)) }
        assertFailsWith<IllegalArgumentException>("trailing rubbish") { ReplayCodec.decode(payload + "AAAA") }
        assertFailsWith<IllegalArgumentException>("empty") { ReplayCodec.decode("") }
    }

    @Test
    fun `a future format version is refused, not guessed at`() {
        val match = matchOf(6, 6, "cycle", "cycle")
        match.runToCompletion()

        val bytes = kotlin.io.encoding.Base64.UrlSafe
            .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .decode(ReplayCodec.encode(match.record()))
        bytes[0] = (ReplayCodec.FORMAT_VERSION + 1).toByte()

        assertFailsWith<IllegalArgumentException> {
            ReplayCodec.decode(
                kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
                    .encode(bytes),
            )
        }
    }
}
