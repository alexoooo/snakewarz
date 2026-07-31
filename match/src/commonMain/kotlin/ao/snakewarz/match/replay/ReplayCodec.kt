package ao.snakewarz.match.replay

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.grid.Occupancy
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.TerminalEvent
import kotlin.io.encoding.Base64

/**
 * Turns a [MatchRecord] into a base64url string and back.
 *
 * The layout is a small header followed by the move stream at two bits a turn, so an 800-turn match
 * is under 400 characters and fits comfortably in a URL. Slots are named by their **slug**, never by
 * a registry index: indices break the instant the registry is reordered, and a released slug never
 * changes.
 *
 * `kotlin.io.encoding.Base64` rather than `btoa`, so the codec is common code and every round-trip
 * test runs on the JVM in milliseconds. Padding is dropped because `=` in a URL is noise.
 *
 * ```
 * version : byte           flags : byte -- bit 0: per-slot configuration, bit 1: an obstacle map
 * rows-1  : varint         cols-1  : varint          seed : 8 bytes, little endian
 * growEveryNthMove, maxTurns, budgetPerTurn, slotCount : varint
 * per slot : varint slug length, slug bytes
 * per slot : varint spawn (row * cols + col)
 * per slot : varint turn order entry
 * if MAPPED:
 *     ceil(rows * cols / 8) bytes -- bit (i and 7) of byte (i shr 3) is set when playable square i
 *     is wall, low bit first, exactly as DirectionStream packs a move
 * if CONFIGURED:
 *     per slot : varint allowance
 *     per slot : varint knob count, then per knob:
 *                    varint name length, name bytes, varint value length, value bytes
 * moveCount : varint, then ceil(moveCount / 4) packed bytes
 * terminalCount : varint, then per event: varint turn, varint slot, byte reason
 * outcome present : byte, then varint winner+1, byte end
 * ```
 *
 * ### Why the map is a bitmap and not a name
 *
 * `MatchSetup`'s KDoc argues the general case: geometry, rules, spawns and turn order are recorded,
 * never re-derived. Carrying the squares themselves rather than a shape id is what lets a map be
 * redesigned or deleted without breaking a single shared link. Raw rather than run-length encoded
 * because RLE is worse on the shape a map actually has: a 20x20 pillar lattice is about twenty runs
 * a row, some four hundred bytes, against a raw bitmap's fifty.
 *
 * ### Why the version is the oldest one that can express the record
 *
 * A match nobody configured, on a board with no map, is written as **version 1 with no flags**, byte
 * for byte as it was before either existed — so no link anybody has already shared has changed, and
 * a default match's URL is no longer than it used to be. Per-slot configuration raises it to version
 * 2 and a map raises it to version 3, and [versionFor] is the only place that says so.
 *
 * Writing the *oldest version that can express the record* is what buys both. The version alone
 * would cost every plain replay two bytes and a needless incompatibility; the flag alone would leave
 * an older page saying "the flags byte is reserved and must be zero", which is true and useless.
 * Together, an older page says the version is unsupported, which somebody can act on.
 */
public object ReplayCodec {
    /** Bumped only for a layout change. A decoder rejects anything it does not recognise. */
    public const val FORMAT_VERSION: Int = 3

    /** The oldest layout still written, for a record with nothing per-slot to say and no map. */
    private const val UNCONFIGURED_VERSION: Int = 1

    /** What a configured record on an empty board is written at, which is what it always was. */
    public const val CONFIGURED_VERSION: Int = 2

    /** Flags bit 0: the per-slot allowance and knob block is present. */
    private const val CONFIGURED: Int = 1

    /** Flags bit 1: a wall bitmap is present, one bit per playable square. */
    private const val MAPPED: Int = 2

    private const val KNOWN_FLAGS: Int = CONFIGURED or MAPPED

    private val BASE64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    public fun encode(record: MatchRecord): String {
        val setup = record.setup
        val out = Writer()
        val configured = setup.configured
        val flags = (if (configured) CONFIGURED else 0) or (if (setup.mapped) MAPPED else 0)

        out.byte(versionFor(flags))
        out.byte(flags)
        out.varint(setup.rows - 1)
        out.varint(setup.cols - 1)
        out.long(setup.seed)
        out.varint(setup.rules.growEveryNthMove)
        out.varint(setup.rules.maxTurns)
        out.varint(setup.budgetPerTurn)

        out.varint(setup.slotCount)
        for (id in setup.slots) {
            out.text(id.slug)
        }
        for (spawn in setup.spawns()) {
            out.varint(spawn)
        }
        for (slot in setup.turnOrder()) {
            out.varint(slot)
        }

        if (setup.mapped) {
            out.bytes(wallBitmap(setup.walls(), setup.rows * setup.cols))
        }

        if (configured) {
            for (budget in setup.budgets()) {
                out.varint(budget)
            }
            for (slot in 0 until setup.slotCount) {
                val params = setup.paramsFor(slot)
                out.varint(params.names.size)
                for (name in params.names) {
                    out.text(name)
                    out.text(params.string(name, ""))
                }
            }
        }

        out.varint(record.moves.size)
        out.bytes(record.moves.bytes())

        out.varint(record.terminals.size)
        for (event in record.terminals) {
            out.varint(event.turnIndex)
            out.varint(event.slot.index)
            out.byte(event.reason.ordinal)
        }

        val outcome = record.outcome
        if (outcome == null) {
            out.byte(0)
        } else {
            out.byte(1)
            out.varint(outcome.winner.index + 1)
            out.byte(outcome.end.ordinal)
        }

        return BASE64.encode(out.toByteArray())
    }

    public fun decode(payload: String): MatchRecord {
        val bytes = try {
            BASE64.decode(payload)
        } catch (malformed: IllegalArgumentException) {
            throw IllegalArgumentException("replay payload is not valid base64url", malformed)
        }

        val input = Reader(bytes)

        val version = input.byte()
        require(version in UNCONFIGURED_VERSION..FORMAT_VERSION) {
            "replay format version $version is not supported"
        }

        val flags = input.byte()
        require(flags and KNOWN_FLAGS.inv() == 0) {
            "replay flags byte $flags holds a bit this decoder does not know"
        }
        require(version >= versionFor(flags)) {
            "replay flags byte $flags is not recognised at format version $version"
        }
        val configured = flags and CONFIGURED != 0

        val rows = input.varint() + 1
        val cols = input.varint() + 1
        // Bounded at the read site rather than left to `MatchSetup.init`, because the map block
        // below allocates from this geometry and a varint can claim a quarter of a billion squares.
        // SW-09: a bound that protects an allocation runs before the allocation, and the range test
        // is what catches a `+ 1` that has wrapped into a negative.
        require(rows in 1..MatchSetup.MAX_SIDE && cols in 1..MatchSetup.MAX_SIDE) {
            "a replay claims a ${rows}x$cols board"
        }

        val seed = input.long()
        val rules = RulesConfig(growEveryNthMove = input.varint(), maxTurns = input.varint())
        val budgetPerTurn = input.varint()

        val slotCount = input.varint()
        require(slotCount in 1..Occupancy.MAX_SNAKES) { "a replay names $slotCount slots" }

        val slots = List(slotCount) { BotId(input.text(1..BotId.MAX_LENGTH, "a bot slug")) }
        val spawns = IntArray(slotCount) { input.varint() }
        val turnOrder = IntArray(slotCount) { input.varint() }

        // Safe: both sides are at most MAX_SIDE, checked above.
        val playableCount = rows * cols
        val walls = if (flags and MAPPED == 0) {
            IntArray(0)
        } else {
            wallIndices(input.bytes((playableCount + 7) ushr 3), playableCount, rows, cols)
        }

        // Absent, every slot is handed budgetPerTurn and nothing tuned — which is what MatchSetup
        // builds from empties, so an old payload decodes to a setup equal to the one that wrote it.
        val budgets = if (!configured) IntArray(0) else IntArray(slotCount) { input.varint() }
        val slotParams = if (!configured) {
            emptyList()
        } else {
            List(slotCount) {
                val knobCount = input.varint()
                require(knobCount <= BotKnob.MAX_PER_BOT) { "a slot claims $knobCount tuned knobs" }

                val values = LinkedHashMap<String, String>(knobCount)
                repeat(knobCount) {
                    val name = input.text(1..BotKnob.MAX_NAME_LENGTH, "a knob name")
                    values[name] = input.text(0..BotKnob.MAX_VALUE_LENGTH, "a knob value")
                }
                BotParams(values)
            }
        }

        val moveCount = input.varint()
        require(moveCount <= rules.maxTurns) { "$moveCount moves exceeds the recorded turn limit" }
        val moves = DirectionStream.of(input.bytes((moveCount + 3) shr 2), moveCount)

        val terminalCount = input.varint()
        require(terminalCount <= MatchRecord.maxTerminals(slotCount)) {
            "$terminalCount terminal events for $slotCount slots"
        }
        val terminals = List(terminalCount) {
            val turnIndex = input.varint()
            val slot = input.varint()
            val reason = EliminationReason.entries.getOrNull(input.byte())
                ?: throw IllegalArgumentException("unknown elimination reason in replay")
            TerminalEvent(turnIndex, SnakeId(slot), reason)
        }

        val outcome = when (val present = input.byte()) {
            0 -> null
            1 -> MatchOutcome(
                SnakeId(input.varint() - 1),
                MatchEnd.entries.getOrNull(input.byte())
                    ?: throw IllegalArgumentException("unknown match end in replay"),
            )

            else -> throw IllegalArgumentException("outcome marker $present is neither absent nor present")
        }

        require(input.exhausted) { "replay payload has ${input.remaining} trailing bytes" }

        return MatchRecord(
            MatchSetup(
                seed = seed,
                rows = rows,
                cols = cols,
                rules = rules,
                budgetPerTurn = budgetPerTurn,
                slots = slots,
                turnOrder = turnOrder,
                spawns = spawns,
                walls = walls,
                budgets = budgets,
                slotParams = slotParams,
            ),
            moves,
            terminals,
            outcome,
        )
    }

    /**
     * The oldest version that can express [flags].
     *
     * A future bit is one more branch here and nowhere else: the encoder asks what to stamp and the
     * decoder asks what the payload had to have been written at to mean what it claims.
     */
    private fun versionFor(flags: Int): Int = when {
        flags and MAPPED != 0 -> FORMAT_VERSION
        flags and CONFIGURED != 0 -> CONFIGURED_VERSION
        else -> UNCONFIGURED_VERSION
    }

    /** [walls] as one bit per playable square, low bit of each byte first. */
    private fun wallBitmap(walls: IntArray, playableCount: Int): ByteArray {
        val bitmap = ByteArray((playableCount + 7) ushr 3)
        for (wall in walls) {
            bitmap[wall shr 3] = (bitmap[wall shr 3].toInt() or (1 shl (wall and 7))).toByte()
        }
        return bitmap
    }

    /**
     * The set bits of [bitmap], ascending — which is the canonical order `MatchSetup` demands.
     *
     * Counted before it is filled, so the array is exactly sized and both passes are bounded by a
     * geometry the caller has already held to `MatchSetup.MAX_SIDE`.
     *
     * The two rejections keep a map's spelling unique. Without them two payloads describe one map,
     * so `encode(decode(x))` stops being `x` and a link can come back spelled differently from the
     * way somebody sent it.
     */
    private fun wallIndices(bitmap: ByteArray, playableCount: Int, rows: Int, cols: Int): IntArray {
        val spare = playableCount and 7
        require(spare == 0 || bitmap[bitmap.size - 1].toInt() and (-1 shl spare) and 0xFF == 0) {
            "the obstacle map has bits set past the last square of a ${rows}x$cols board"
        }

        var count = 0
        for (square in 0 until playableCount) {
            if (bitmap.holdsWall(square)) {
                count++
            }
        }
        require(count > 0) { "a replay declares an obstacle map and then puts no wall on it" }

        val walls = IntArray(count)
        var next = 0
        for (square in 0 until playableCount) {
            if (bitmap.holdsWall(square)) {
                walls[next++] = square
            }
        }
        return walls
    }

    private fun ByteArray.holdsWall(square: Int): Boolean =
        this[square shr 3].toInt() shr (square and 7) and 1 == 1

    private class Writer {
        private var bytes = ByteArray(INITIAL_CAPACITY)
        private var size = 0

        fun byte(value: Int) {
            if (size == bytes.size) {
                bytes = bytes.copyOf(bytes.size * 2)
            }
            bytes[size++] = value.toByte()
        }

        /** LEB128, unsigned. Every length and index in this format is non-negative. */
        fun varint(value: Int) {
            require(value >= 0) { "cannot encode a negative varint: $value" }

            var remaining = value
            while (remaining >= 0x80) {
                byte((remaining and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
            byte(remaining)
        }

        fun long(value: Long) {
            for (i in 0 until 8) {
                byte(((value ushr (i * 8)) and 0xFF).toInt())
            }
        }

        fun bytes(source: ByteArray) {
            for (b in source) {
                byte(b.toInt())
            }
        }

        /** A length-prefixed string, which is how every name in this format travels. */
        fun text(value: String) {
            val encoded = value.encodeToByteArray()
            varint(encoded.size)
            bytes(encoded)
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private companion object {
            const val INITIAL_CAPACITY = 64
        }
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        val exhausted: Boolean get() = position == bytes.size

        val remaining: Int get() = bytes.size - position

        fun byte(): Int {
            require(position < bytes.size) { "replay payload ended early" }
            return bytes[position++].toInt() and 0xFF
        }

        fun varint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val next = byte()
                result = result or ((next and 0x7F) shl shift)
                if (next < 0x80) {
                    return result
                }
                shift += 7
                require(shift <= 28) { "malformed varint in replay payload" }
            }
        }

        fun long(): Long {
            var result = 0L
            for (i in 0 until 8) {
                result = result or (byte().toLong() shl (i * 8))
            }
            return result
        }

        fun bytes(count: Int): ByteArray {
            require(count >= 0 && count <= remaining) { "replay payload cannot supply $count bytes" }
            return ByteArray(count) { byte().toByte() }
        }

        /**
         * A length-prefixed string, bounded before a byte of it is allocated.
         *
         * [what] names the field so a corrupt payload says which one it corrupted.
         */
        fun text(length: IntRange, what: String): String {
            val size = varint()
            require(size in length) { "$what of $size bytes is not plausible" }
            return bytes(size).decodeToString()
        }
    }
}
