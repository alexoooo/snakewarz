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

            // Mapped about half the time, for the same reason `configured` is: both layouts get
            // fuzzed rather than one of them living only in a hand-written case.
            val walls = if (rng.nextInt(2) == 0) IntArray(0) else combWalls(rng, rows, cols, slotCount)

            val setup = MatchSetup.create(
                rows = rows,
                cols = cols,
                slots = List(slotCount) { BotId("bot$it") },
                seed = rng.nextLong(),
                rules = RulesConfig(growEveryNthMove = 1 + rng.nextInt(4), maxTurns = 1 + rng.nextInt(5000)),
                budgetPerTurn = budgetPerTurn,
                walls = walls,
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
        val oldRules = RulesConfig(lastSnakeMustBeMoving = false)
        val plain = MatchSetup.create(12, 12, slots, seed = 77, rules = oldRules, budgetPerTurn = 40_000)
        val tuned = MatchSetup.create(
            12,
            12,
            slots,
            seed = 77,
            rules = oldRules,
            budgetPerTurn = 40_000,
            budgets = intArrayOf(40_000, 4_000),
        )

        val plainPayload = ReplayCodec.encode(Match(plain, TestRegistry.ALL).also { it.runToCompletion() }.record())
        val tunedPayload = ReplayCodec.encode(Match(tuned, TestRegistry.ALL).also { it.runToCompletion() }.record())

        // Old rules remain byte-identical; the moving-winner flag is what opts a new match into v4.
        assertEquals('A', plainPayload.first(), "an old-rules payload still opens with version 1")
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
            rules = RulesConfig(lastSnakeMustBeMoving = false),
            budgets = intArrayOf(10, 20),
        )
        val record = Match(setup, TestRegistry.ALL).also { it.runToCompletion() }.record()

        val bytes = base64.decode(ReplayCodec.encode(record))
        assertEquals(
            ReplayCodec.CONFIGURED_VERSION.toByte(),
            bytes[0],
            "a configured record on an empty board is written at version 2",
        )
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
            rules = RulesConfig(lastSnakeMustBeMoving = false),
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
    fun `a match on a map survives the round trip`() {
        val setup = MatchSetup.create(12, 12, MAPPED_SLOTS, seed = 5, walls = latticeWalls(12, 12))
        val record = play(setup)

        val decoded = ReplayCodec.decode(ReplayCodec.encode(record))

        assertEquals(record, decoded)
        assertEquals(setup.walls().toList(), decoded.setup.walls().toList())
    }

    @Test
    fun `the version written is the oldest one that can express the record`() {
        val oldRules = RulesConfig(lastSnakeMustBeMoving = false)
        val plain = MatchSetup.create(12, 12, MAPPED_SLOTS, seed = 5, rules = oldRules)
        val tuned = MatchSetup.create(
            12,
            12,
            MAPPED_SLOTS,
            seed = 5,
            rules = oldRules,
            budgets = intArrayOf(10, 20),
        )
        val mapped = MatchSetup.create(
            12,
            12,
            MAPPED_SLOTS,
            seed = 5,
            rules = oldRules,
            walls = latticeWalls(12, 12),
        )
        val moving = MatchSetup.create(12, 12, MAPPED_SLOTS, seed = 5)

        assertEquals(1, versionOf(plain), "nothing tuned and no map")
        assertEquals(ReplayCodec.CONFIGURED_VERSION, versionOf(tuned), "a per-slot allowance")
        assertEquals(ReplayCodec.MAPPED_VERSION, versionOf(mapped), "a map")
        assertEquals(ReplayCodec.FORMAT_VERSION, versionOf(moving), "the moving-winner rule")
    }

    @Test
    fun `a map travels as one bit a square, low bit of each byte first`() {
        // Walls at (1,1), (1,3), (3,1) and (3,3) -- playable squares 6, 8, 16 and 18 -- so the
        // packing is pinned by a bitmap somebody can check by hand, and byte 3 holds only square 24.
        val bytes = base64.decode(ReplayCodec.encode(play(smallLatticeMatch())))

        assertEquals(
            listOf(0x40, 0x01, 0x05, 0x00),
            (SMALL_HEADER until SMALL_HEADER + SMALL_BITMAP).map { bytes[it].toInt() and 0xFF },
            "square i is bit (i and 7) of byte (i shr 3), exactly as DirectionStream packs a move",
        )
    }

    @Test
    fun `a map that two payloads could spell is refused rather than decoded`() {
        val bytes = base64.decode(ReplayCodec.encode(play(smallLatticeMatch())))

        // Bits above the last square would let one map be written two ways, so encode(decode(x))
        // would stop being x -- and so would a flag claiming a map with no wall on it.
        val padded = bytes.copyOf().also { it[SMALL_HEADER + SMALL_BITMAP - 1] = 0x80.toByte() }
        val empty = bytes.copyOf().also { for (i in 0 until SMALL_BITMAP) it[SMALL_HEADER + i] = 0 }

        assertFailsWith<IllegalArgumentException>("bits past the last square") {
            ReplayCodec.decode(base64.encode(padded))
        }
        assertFailsWith<IllegalArgumentException>("a map with no walls") {
            ReplayCodec.decode(base64.encode(empty))
        }
    }

    @Test
    fun `a mapped payload declaring a huge board is refused before the map is allocated for`() {
        // The map block is the first thing that allocates from the decoded geometry, so the bound on
        // rows and cols has to run before it -- and refusing has to be an IllegalArgumentException,
        // because an OutOfMemoryError is not what :app catches to turn a bad link into a fresh match.
        val bytes = base64.decode(ReplayCodec.encode(play(smallLatticeMatch())))

        // Byte 2 is the rows varint, one byte wide for any board this game offers. Widen it to a
        // hundred million rows, which is a map block of a hundred and fifty megabytes.
        val huge = bytes.copyOfRange(0, 2) +
            byteArrayOf(0x80.toByte(), 0xC2.toByte(), 0xD7.toByte(), 0x2F) +
            bytes.copyOfRange(3, bytes.size)

        assertFailsWith<IllegalArgumentException> { ReplayCodec.decode(base64.encode(huge)) }
    }

    @Test
    fun `a map is refused at a version too old to carry one, and an unknown flag outright`() {
        val mapped = base64.decode(ReplayCodec.encode(play(smallLatticeMatch())))
        val stale = mapped.copyOf().also { it[0] = ReplayCodec.CONFIGURED_VERSION.toByte() }

        val plain = base64.decode(ReplayCodec.encode(play(MatchSetup.create(8, 8, MAPPED_SLOTS, seed = 4))))
        val unknown = plain.copyOf().also { it[1] = it[1].toInt().or(8).toByte() }

        assertFailsWith<IllegalArgumentException>("version 2 cannot carry a map") {
            ReplayCodec.decode(base64.encode(stale))
        }
        assertFailsWith<IllegalArgumentException>("flags bit 3 means nothing yet") {
            ReplayCodec.decode(base64.encode(unknown))
        }
    }

    @Test
    fun `a map costs its bitmap and stays inside the share budget`() {
        val setup = MatchSetup.create(20, 20, MAPPED_SLOTS, seed = 4, walls = latticeWalls(20, 20))
        val payload = ReplayCodec.encode(play(setup))

        // 400 squares is 50 bytes, and base64 without padding turns 50 bytes into 68 characters --
        // so a mapped 20x20 is the plain budget this suite already asserts, plus the map itself.
        val bitmapChars = ((20 * 20 + 7) / 8 + 2) / 3 * 4
        assertTrue(
            payload.length < 400 + bitmapChars,
            "a mapped 20x20 match encoded to ${payload.length} characters",
        )
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

        /** Two bots that stay alive by playing differently, so a mapped match is worth recording. */
        val MAPPED_SLOTS = listOf(BotId("cycle"), BotId("last"))

        /**
         * Where [smallLatticeMatch]'s map block starts, and how long it is.
         *
         * The map sits between the turn order and the move stream, so a test that corrupts one byte
         * of it has to count the header: a version and a flags byte, two geometry varints, eight
         * seed bytes, four header varints, then a length-prefixed slug, a spawn and a turn order
         * entry per slot. Every varint there is one byte on the board [smallLatticeMatch] picks.
         */
        val SMALL_HEADER = 2 + 2 + 8 + 4 + MAPPED_SLOTS.sumOf { it.slug.length + 1 } + 2 * MAPPED_SLOTS.size
        const val SMALL_BITMAP = (5 * 5 + 7) / 8

        fun play(setup: MatchSetup) = Match(setup, TestRegistry.ALL).also { it.runToCompletion() }.record()

        fun versionOf(setup: MatchSetup): Int = base64.decode(ReplayCodec.encode(play(setup))).first().toInt()

        /**
         * A 5x5 lattice, chosen so every header field before the map is one byte wide.
         *
         * `maxTurns` and the allowance are spelled out for that reason alone: the defaults are wider
         * than a varint byte, and [SMALL_HEADER] would then name a byte of the seed.
         */
        fun smallLatticeMatch(): MatchSetup = MatchSetup.create(
            rows = 5,
            cols = 5,
            slots = MAPPED_SLOTS,
            seed = 11,
            rules = RulesConfig(maxTurns = 100, lastSnakeMustBeMoving = false),
            budgetPerTurn = 0,
            walls = latticeWalls(5, 5),
        )

        /** Pillars on the odd squares — the shape a map most likely has, and connected by construction. */
        fun latticeWalls(rows: Int, cols: Int): IntArray {
            val walls = mutableListOf<Int>()
            for (row in 1 until rows step 2) {
                for (col in 1 until cols step 2) {
                    walls += row * cols + col
                }
            }
            return walls.toIntArray()
        }

        /**
         * Walls in odd rows only and never in column 0, so whatever the draw the open squares stay
         * one connected region — which is what `mostDistantSpawns` needs of a fuzzed map.
         *
         * Column 0 links every row, the even rows are untouched, and an open odd-row square always
         * borders one. Dropped entirely when it would leave fewer squares than there are snakes.
         */
        fun combWalls(rng: SplitMix64, rows: Int, cols: Int, slotCount: Int): IntArray {
            val walls = mutableListOf<Int>()
            for (row in 1 until rows step 2) {
                for (col in 1 until cols) {
                    if (rng.nextInt(2) == 0) {
                        walls += row * cols + col
                    }
                }
            }
            return if (rows * cols - walls.size < slotCount) IntArray(0) else walls.toIntArray()
        }
    }
}
