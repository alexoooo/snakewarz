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
 * version : byte           flags : byte -- bit 0: a per-slot configuration block follows
 * rows-1  : varint         cols-1  : varint          seed : 8 bytes, little endian
 * growEveryNthMove, maxTurns, budgetPerTurn, slotCount : varint
 * per slot : varint slug length, slug bytes
 * per slot : varint spawn (row * cols + col)
 * per slot : varint turn order entry
 * if CONFIGURED:
 *     per slot : varint allowance
 *     per slot : varint knob count, then per knob:
 *                    varint name length, name bytes, varint value length, value bytes
 * moveCount : varint, then ceil(moveCount / 4) packed bytes
 * terminalCount : varint, then per event: varint turn, varint slot, byte reason
 * outcome present : byte, then varint winner+1, byte end
 * ```
 *
 * ### Why two versions rather than one
 *
 * A match nobody configured is written as **version 1 with no flags**, byte for byte as it was
 * before per-slot configuration existed — so no link anybody has already shared has changed, and a
 * default match's URL is no longer than it used to be. A configured match is written as version 2
 * with the flag set.
 *
 * Writing the *oldest version that can express the record* is what buys both. The version alone
 * would cost every unconfigured replay two bytes and a needless incompatibility; the flag alone
 * would leave an older page saying "the flags byte is reserved and must be zero", which is true and
 * useless. Together, an older page says the version is unsupported, which somebody can act on.
 */
public object ReplayCodec {
    /** Bumped only for a layout change. A decoder rejects anything it does not recognise. */
    public const val FORMAT_VERSION: Int = 2

    /** The oldest layout still written, for a record with nothing per-slot to say. */
    private const val UNCONFIGURED_VERSION: Int = 1

    /** Flags bit 0: the per-slot allowance and knob block is present. */
    private const val CONFIGURED: Int = 1

    private val BASE64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    public fun encode(record: MatchRecord): String {
        val setup = record.setup
        val out = Writer()
        val configured = setup.configured

        out.byte(if (configured) FORMAT_VERSION else UNCONFIGURED_VERSION)
        out.byte(if (configured) CONFIGURED else 0)
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
        require(version == UNCONFIGURED_VERSION || version == FORMAT_VERSION) {
            "replay format version $version is not supported"
        }

        val flags = input.byte()
        require(flags == 0 || (version >= FORMAT_VERSION && flags == CONFIGURED)) {
            "replay flags byte $flags is not recognised at format version $version"
        }
        val configured = flags == CONFIGURED

        val rows = input.varint() + 1
        val cols = input.varint() + 1
        val seed = input.long()
        val rules = RulesConfig(growEveryNthMove = input.varint(), maxTurns = input.varint())
        val budgetPerTurn = input.varint()

        val slotCount = input.varint()
        require(slotCount in 1..Occupancy.MAX_SNAKES) { "a replay names $slotCount slots" }

        val slots = List(slotCount) { BotId(input.text(1..BotId.MAX_LENGTH, "a bot slug")) }
        val spawns = IntArray(slotCount) { input.varint() }
        val turnOrder = IntArray(slotCount) { input.varint() }

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
            MatchSetup(seed, rows, cols, rules, budgetPerTurn, slots, turnOrder, spawns, budgets, slotParams),
            moves,
            terminals,
            outcome,
        )
    }

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
