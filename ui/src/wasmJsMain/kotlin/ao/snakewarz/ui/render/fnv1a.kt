package ao.snakewarz.ui.render

/**
 * FNV-1a, written out here so that it cannot move.
 *
 * Two things in this package need a hash that answers the same way forever, and neither can use
 * `String.hashCode()` or `Any.hashCode()`: Kotlin does not specify either to be identical across
 * targets, and both of these are *pictures* a player recognises. [identicon] gives a bot the same
 * mark on every machine, and [TexturePack] gives a board the same figure on every resize.
 *
 * One implementation rather than two, which is what the second caller cost: `Int` arithmetic wraps
 * identically everywhere, and that is the whole of what [OFFSET] and [MIX] need.
 *
 * **The arithmetic is frozen.** `IdenticonTest` pins a literal against it — the mark a known slug has
 * always drawn — so a "tidier" mixing step is a mark that changes under everybody who had one.
 */
internal fun fnv1a(text: String): Int {
    var hash = OFFSET
    for (ch in text) {
        hash = fold(hash, ch.code)
    }
    return hash
}

/**
 * FNV-1a over a pair of numbers, for a figure keyed on a square of the board.
 *
 * The same mixing step as the string form, seeded the same way, because a pair of coordinates is
 * two units the way a two-character string is: FNV mixes each of them into all thirty-two bits, so
 * neighbouring squares land nowhere near each other and any handful of bits is usable as a choice.
 */
internal fun fnv1a(first: Int, second: Int): Int = fold(fold(OFFSET, first), second)

// -- internals

/** One unit folded into a running hash: the whole of FNV-1a, and the part that must not be edited. */
private fun fold(hash: Int, unit: Int): Int = (hash xor unit) * MIX

/** FNV-1a's 32-bit offset basis and prime, as `Int`s, since that is the width the mixing needs. */
private const val OFFSET = -2128831035
private const val MIX = 16777619
