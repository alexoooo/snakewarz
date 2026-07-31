package ao.snakewarz.ui.render

import kotlin.io.encoding.Base64

/**
 * A mark for a bot nobody drew a portrait for, in the colour of the seat it is sitting in.
 *
 * This is what keeps a *contributed* bot working on day one: a registry `:ui` has never heard of
 * still gets a face per entrant, without an asset, a network request, or a slug named in this module.
 *
 * **The same bot must get the same mark in every match, on every machine, forever** — a mark that
 * moved would be worse than none, because a player identifies an opponent by it. So the hash is
 * written out below rather than taken from `String.hashCode()`, which Kotlin does not specify to be
 * identical across targets. `Int` arithmetic does wrap identically everywhere, which is the whole of
 * what [OFFSET] and [MIX] need.
 *
 * Mirroring left to right is what makes a random mask read as a *face* rather than as noise: three
 * generated columns, two reflected. It is also why the grid is odd-sided.
 */
internal fun identicon(slug: String, colour: String): String {
    val bits = hashOf(slug)
    val blocks = StringBuilder()

    for (row in 0 until GRID) {
        for (col in 0 until COLUMNS) {
            if ((bits shr (row * COLUMNS + col)) and 1 == 0) {
                continue
            }
            blocks.block(col, row)
            // The reflected half. The middle column is its own mirror, so it is drawn once.
            if (col < COLUMNS - 1) {
                blocks.block(GRID - 1 - col, row)
            }
        }
    }

    val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $SIDE $SIDE'>" +
        "<rect width='$SIDE' height='$SIDE' rx='$CORNER' fill='$TILE'/>" +
        "<g fill='$colour'>$blocks</g>" +
        "</svg>"

    // Base64 rather than percent-encoding, because a data URI has no escaping to get *partly* right:
    // the colour alone carries a `#`, and a safe set would have to be re-checked against the markup
    // every time a line of it changed.
    return DATA_PREFIX + Base64.encode(svg.encodeToByteArray())
}

// -- internals

/** One cell of the block grid, placed in viewBox units. */
private fun StringBuilder.block(col: Int, row: Int) {
    append("<rect x='").append(MARGIN + col * CELL)
        .append("' y='").append(MARGIN + row * CELL)
        .append("' width='").append(CELL)
        .append("' height='").append(CELL)
        .append("'/>")
}

/**
 * FNV-1a over the slug's UTF-16 units, written out so that it cannot move.
 *
 * The low `GRID * COLUMNS` bits are the mask, and taking the bottom fifteen is not the same as
 * reading the tail of the string: FNV mixes every unit into all thirty-two.
 */
private fun hashOf(slug: String): Int {
    var hash = OFFSET
    for (ch in slug) {
        hash = (hash xor ch.code) * MIX
    }
    return hash
}

/** FNV-1a's 32-bit offset basis and prime, as `Int`s, since that is the width the mixing needs. */
private const val OFFSET = -2128831035
private const val MIX = 16777619

/** Five squares a side, of which three are generated and two reflected. */
private const val GRID = 5
private const val COLUMNS = (GRID + 1) / 2

/**
 * The tile these are drawn on, in the same colour and the same proportions as `app/.../favicon.svg`.
 *
 * Portraits follow the favicon rather than inventing a second visual language, and an identicon has
 * to sit beside a hand-drawn one on the same card without reading as a different kind of thing. The
 * side is whatever centres five blocks of [CELL] inside two margins — the `<img>` is sized in CSS, so
 * the only thing the number has to be is exact.
 */
private const val CELL = 5
private const val MARGIN = 4
private const val SIDE = 2 * MARGIN + GRID * CELL
private const val CORNER = 6
private const val TILE = "#16191d"

private const val DATA_PREFIX = "data:image/svg+xml;base64,"
