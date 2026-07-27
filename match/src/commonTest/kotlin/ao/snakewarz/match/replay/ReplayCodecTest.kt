package ao.snakewarz.match.replay

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.TerminalEvent
import ao.snakewarz.match.TestRegistry
import ao.snakewarz.match.matchOf
import kotlin.io.encoding.Base64
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

            val budgetPerTurn = rng.nextInt(100_000)

            // Configured about half the time, and left alone the other half, so both layouts are
            // fuzzed and neither is only ever exercised by a hand-written case.
            val configured = rng.nextInt(2) == 0
            val budgets = if (!configured) IntArray(0) else IntArray(slotCount) { rng.nextInt(100_000) }
            val slotParams = if (!configured) {
                emptyList()
            } else {
                List(slotCount) {
                    val values = LinkedHashMap<String, String>()
                    repeat(rng.nextInt(4)) { knob -> values["knob$knob"] = rng.nextInt(1000).toString() }
                    BotParams(values)
                }
            }

            val setup = MatchSetup.create(
                rows = rows,
                cols = cols,
                slots = List(slotCount) { BotId("bot$it") },
                seed = rng.nextLong(),
                rules = RulesConfig(growEveryNthMove = 1 + rng.nextInt(4), maxTurns = 1 + rng.nextInt(5000)),
                budgetPerTurn = budgetPerTurn,
                budgets = budgets,
                slotParams = slotParams,
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
    fun `a configured match carries what it was played under`() {
        val setup = MatchSetup.create(
            12,
            12,
            listOf(BotId("cycle"), BotId("south")),
            seed = 77,
            budgetPerTurn = 40_000,
            budgets = intArrayOf(40_000, 4_000),
            slotParams = listOf(BotParams(mapOf("exploration" to "1.5", "rolloutDepth" to "25")), BotParams.EMPTY),
        )
        val match = Match(setup, TestRegistry.ALL).also { it.runToCompletion() }
        val record = match.record()

        val decoded = ReplayCodec.decode(ReplayCodec.encode(record))

        assertEquals(record, decoded)
        assertEquals(4_000, decoded.setup.budgetFor(1))
        assertEquals("1.5", decoded.setup.paramsFor(0).string("exploration", "?"))
        assertTrue(decoded.setup.paramsFor(1).isEmpty)
    }

    @Test
    fun `configuring a match costs bytes, and not configuring one costs none`() {
        val slots = listOf(BotId("cycle"), BotId("south"))
        val plain = MatchSetup.create(12, 12, slots, seed = 77, budgetPerTurn = 40_000)
        val tuned = MatchSetup.create(
            12,
            12,
            slots,
            seed = 77,
            budgetPerTurn = 40_000,
            budgets = intArrayOf(40_000, 4_000),
        )

        val plainPayload = ReplayCodec.encode(Match(plain, TestRegistry.ALL).also { it.runToCompletion() }.record())
        val tunedPayload = ReplayCodec.encode(Match(tuned, TestRegistry.ALL).also { it.runToCompletion() }.record())

        // The whole reason for two versions: an unconfigured replay is what it always was.
        assertEquals('A', plainPayload.first(), "an unconfigured payload still opens with version 1")
        assertTrue(
            tunedPayload.length - plainPayload.length < 24,
            "configuring two slots cost ${tunedPayload.length - plainPayload.length} characters",
        )
    }

    @Test
    fun `a version 1 payload may not claim a configuration block`() {
        val setup = MatchSetup.create(
            8,
            8,
            listOf(BotId("cycle"), BotId("south")),
            seed = 4,
            budgets = intArrayOf(10, 20),
        )
        val record = Match(setup, TestRegistry.ALL).also { it.runToCompletion() }.record()

        val bytes = base64.decode(ReplayCodec.encode(record))
        assertEquals(ReplayCodec.FORMAT_VERSION.toByte(), bytes[0], "a configured record is written at version 2")
        bytes[0] = 1

        assertFailsWith<IllegalArgumentException> { ReplayCodec.decode(base64.encode(bytes)) }
    }

    @Test
    fun `an already-shared link still decodes, byte for byte`() {
        // The one test that protects links people have already sent each other. Captured from the
        // encoder as it shipped, and asserted from both ends: this payload must keep decoding, and
        // the match it describes must keep encoding to exactly it. Do not regenerate it to make a
        // change pass -- a change that moves these bytes has broken every replay URL in existence.
        //
        // SHIPPED_BUDGET is spelled out rather than defaulted because a record carries the allowance
        // it was *played* under. `MatchSetup.DEFAULT_BUDGET_PER_TURN` has since moved -- the unit
        // changed from simulated moves to evaluations -- and a link written before that must still
        // come back as the match it was, which is the whole reason the figure is in the header.
        val setup = MatchSetup.create(
            rows = 10,
            cols = 10,
            slots = listOf(BotId("cycle"), BotId("south")),
            seed = 2005,
            budgetPerTurn = SHIPPED_BUDGET,
        )
        val match = Match(setup, TestRegistry.ALL)
        match.runToCompletion()

        assertEquals(SHIPPED_PAYLOAD, ReplayCodec.encode(match.record()))
        assertEquals(match.record(), ReplayCodec.decode(SHIPPED_PAYLOAD))
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
    fun `a payload claiming a huge board is refused rather than allocated for`() {
        // The geometry is the decoded field that allocates most, and it is one a stranger controls.
        // Refusing it has to be an IllegalArgumentException: an OutOfMemoryError is not something
        // :app can catch, so the tab would die during boot and blame the browser.
        val match = matchOf(10, 10, "cycle", "south")
        match.runToCompletion()
        val bytes = base64.decode(ReplayCodec.encode(match.record()))

        // Byte 2 is the rows varint, one byte wide for any board this game offers. Widen it to 5000.
        val huge = bytes.copyOfRange(0, 2) +
            byteArrayOf(0x87.toByte(), 0x27) +
            bytes.copyOfRange(3, bytes.size)

        assertFailsWith<IllegalArgumentException> { ReplayCodec.decode(base64.encode(huge)) }
    }

    @Test
    fun `a future format version is refused, not guessed at`() {
        val match = matchOf(6, 6, "cycle", "cycle")
        match.runToCompletion()

        val bytes = base64.decode(ReplayCodec.encode(match.record()))
        bytes[0] = (ReplayCodec.FORMAT_VERSION + 1).toByte()

        assertFailsWith<IllegalArgumentException> { ReplayCodec.decode(base64.encode(bytes)) }
    }

    private companion object {
        /**
         * A replay of a match played before per-slot configuration existed.
         *
         * Captured from the encoder as it shipped. Asserted from both ends, so it is not merely a
         * decoder test: an unconfigured match must keep encoding to exactly these bytes.
         */
        const val SHIPPED_PAYLOAD = "AQAJCdUHAAAAAAAAAoAgwLgCAgVjeWNsZQVzb3V0aABjAAECBQABAQA"

        /** What `MatchSetup.DEFAULT_BUDGET_PER_TURN` was when [SHIPPED_PAYLOAD] was captured. */
        const val SHIPPED_BUDGET = 40_000

        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}
