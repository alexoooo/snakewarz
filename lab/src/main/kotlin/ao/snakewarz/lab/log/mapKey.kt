package ao.snakewarz.lab.log

/**
 * Which map a batch was played on, as a short name two batches can be compared by.
 *
 * **Derived from the walls themselves and never from the name of a shape**, so it cannot disagree
 * with what was actually played. A run header that recorded `cross` would still read `cross` after
 * the generator was redrawn, and every rating fitted across the change would silently pool two
 * different games; a fingerprint of the squares cannot do that.
 *
 * `empty` is spelled out rather than left as a zero-wall fingerprint because it is the reading a
 * person is looking for. The research protocol's standing rule is *say which map a number was taken
 * on, including "empty"*, and a log where the incumbent has no name does not keep it.
 *
 * The count leads so the key is legible at a glance — `40w1a2b3c4d` is forty walls in one particular
 * arrangement — and the fingerprint is what separates two arrangements of the same size. Board size
 * is **not** folded in: [RunHeader.comparabilityKey] already carries it, and one field saying one
 * thing is what makes a mismatch readable.
 */
internal fun mapKey(walls: IntArray): String {
    if (walls.isEmpty()) {
        return EMPTY_MAP
    }

    var hash = FNV_OFFSET
    for (wall in walls) {
        // A byte at a time, low to high, so the mixing sees every part of an index rather than
        // folding four bytes into one multiply.
        var byte = 0
        while (byte < Int.SIZE_BYTES) {
            hash = hash xor ((wall ushr (byte * Byte.SIZE_BITS)) and 0xFF).toLong()
            hash *= FNV_PRIME
            byte++
        }
    }

    // Folded to 32 bits and rendered unsigned: a Long's hex is seventeen characters wide with a
    // leading '-' half the time, which reads as a corrupt field rather than as a name.
    val folded = ((hash ushr Int.SIZE_BITS) xor hash) and 0xFFFFFFFFL
    return "${walls.size}w${folded.toString(HEX).padStart(FINGERPRINT, '0')}"
}

/** What [mapKey] calls a board with no walls on it, and what `rate --map empty` narrows to. */
internal const val EMPTY_MAP: String = "empty"

/** FNV-1a, 64 bit: a byte-at-a-time hash with no table and no platform to depend on. */
private const val FNV_OFFSET = -3_750_763_034_362_895_579L
private const val FNV_PRIME = 1_099_511_628_211L

private const val HEX = 16
private const val FINGERPRINT = 8
