package ao.snakewarz.lab.endgame

/**
 * A fixed-allocation transposition table whose hashes are only indexes into structurally verified
 * records. Signature collisions continue probing until all [ExactStateCodec.WORDS] agree.
 */
internal class ExactTable(
    maxEntries: Int,
    memoryMiB: Int,
) {
    private val capacity = capacityFor(maxEntries)

    init {
        require(maxEntries > 0) { "exact table needs a positive entry cap, was $maxEntries" }
        require(memoryMiB > 0) { "exact table memory must be positive, was $memoryMiB MiB" }
        val required = bytesFor(capacity)
        val available = memoryMiB.toLong() * BYTES_PER_MIB
        require(required <= available) {
            "exact table needs ${mib(required)} MiB for $maxEntries entries, cap is $memoryMiB MiB"
        }
    }

    private val keys = LongArray(capacity * ExactStateCodec.WORDS)
    private val signatures = LongArray(capacity)
    private val values = ByteArray(capacity)
    private val optimalMasks = ByteArray(capacity)
    private val verified = ByteArray(capacity)

    var size: Int = 0
        private set

    var structuralCollisions: Long = 0
        private set

    val allocatedBytes: Long get() = bytesFor(capacity)

    fun clear() {
        values.fill(EMPTY)
        verified.fill(NOT_VERIFIED)
        size = 0
        structuralCollisions = 0
    }

    /** A matching slot, or the bitwise complement of the first empty slot. */
    fun find(words: LongArray, offset: Int, signature: Long): Int {
        var slot = indexOf(signature)
        while (true) {
            if (values[slot] == EMPTY) {
                return slot.inv()
            }
            if (signatures[slot] == signature) {
                if (sameKey(slot, words, offset)) {
                    return slot
                }
                structuralCollisions++
            }
            slot = (slot + 1) and (capacity - 1)
        }
    }

    fun put(emptySlot: Int, words: LongArray, offset: Int, signature: Long, value: Int, optimalMask: Int): Int {
        val slot = emptySlot.inv()
        check(slot in values.indices && values[slot] == EMPTY) { "exact table slot $slot is not empty" }
        check(size < capacity) { "exact table filled its $capacity slots" }
        check(optimalMask in 1..DIRECTION_MASK) { "nonterminal state has an invalid move mask $optimalMask" }

        words.copyInto(keys, slot * ExactStateCodec.WORDS, offset, offset + ExactStateCodec.WORDS)
        signatures[slot] = signature
        values[slot] = encodeValue(value)
        optimalMasks[slot] = optimalMask.toByte()
        size++
        return slot
    }

    fun valueAt(slot: Int): Int = decodeValue(values[slot])

    fun optimalMaskAt(slot: Int): Int = optimalMasks[slot].toInt() and DIRECTION_MASK

    fun isVerified(slot: Int): Boolean = verified[slot] == VERIFIED

    fun markVerified(slot: Int) {
        check(values[slot] != EMPTY) { "cannot verify empty exact table slot $slot" }
        verified[slot] = VERIFIED
    }

    fun clearVerification() {
        verified.fill(NOT_VERIFIED)
    }

    private fun sameKey(slot: Int, words: LongArray, offset: Int): Boolean {
        val stored = slot * ExactStateCodec.WORDS
        for (word in 0 until ExactStateCodec.WORDS) {
            if (keys[stored + word] != words[offset + word]) {
                return false
            }
        }
        return true
    }

    private fun indexOf(signature: Long): Int = ((signature xor (signature ushr 32)).toInt()) and (capacity - 1)

    private companion object {
        const val LOAD_NUMERATOR = 4L
        const val LOAD_DENOMINATOR = 3L
        const val BYTES_PER_MIB = 1024L * 1024L
        const val DIRECTION_MASK = 0b1111
        const val EMPTY: Byte = 0
        const val NOT_VERIFIED: Byte = 0
        const val VERIFIED: Byte = 1

        fun capacityFor(maxEntries: Int): Int {
            require(maxEntries > 0) { "exact table needs a positive entry cap, was $maxEntries" }
            val needed = (maxEntries.toLong() * LOAD_NUMERATOR + LOAD_DENOMINATOR - 1) / LOAD_DENOMINATOR
            require(needed <= MAX_CAPACITY) { "exact table entry cap $maxEntries is too large" }
            var capacity = 1L
            while (capacity < needed) {
                capacity = capacity shl 1
            }
            require(capacity * ExactStateCodec.WORDS <= Int.MAX_VALUE) {
                "exact table entry cap $maxEntries cannot be addressed by one key array"
            }
            return capacity.toInt()
        }

        fun bytesFor(capacity: Int): Long =
            capacity.toLong() * (ExactStateCodec.WORDS * Long.SIZE_BYTES + Long.SIZE_BYTES + BYTE_ARRAYS)

        fun mib(bytes: Long): Long = (bytes + BYTES_PER_MIB - 1) / BYTES_PER_MIB

        fun encodeValue(value: Int): Byte {
            require(value in -1..1) { "exact value must be -1, 0 or 1, was $value" }
            return (value + VALUE_BIAS).toByte()
        }

        fun decodeValue(encoded: Byte): Int {
            check(encoded != EMPTY) { "empty exact table slot has no value" }
            return encoded.toInt() - VALUE_BIAS
        }

        const val VALUE_BIAS = 2
        const val BYTE_ARRAYS = 3
        const val MAX_CAPACITY = Int.MAX_VALUE.toLong()
    }
}
