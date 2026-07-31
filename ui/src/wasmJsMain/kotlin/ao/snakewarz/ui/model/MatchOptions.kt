package ao.snakewarz.ui.model

import ao.snakewarz.match.map.MapShape

/** The new-match form, read off the DOM once and handed on as a value. */
internal class MatchOptions(
    val rows: Int,
    val cols: Int,
    val seed: Long,
    /**
     * The map, as **playable** indices `row * cols + col`, strictly ascending. Empty is a bare
     * rectangle, and a match started on one encodes to the bytes it always did.
     *
     * Already drawn rather than named by its shape, because that is what `MatchSetup` takes: a shape
     * never reaches a match, and it never reaches a replay.
     */
    val walls: IntArray,
    /**
     * The shape [walls] was drawn from, and **a decoration hint and nothing else**.
     *
     * `GameSession.setupFrom` builds a `MatchSetup` out of [walls] and never reads this — the one
     * thing it is for is picking a `TexturePack`, which is a fact about how the board is *painted*.
     * Thread it into the match and the property `docs/Maps.md` was designed for is gone: a map
     * travels as squares, so a shape can be redesigned or deleted without breaking a link anybody
     * has shared, and two shapes that happen to draw the same walls are the same board.
     *
     * `null` where the picker is showing the bitmap a replay arrived with, which has no shape by
     * construction — the plain pack, and the board this game always drew.
     */
    val shape: MapShape?,
    /** In slot order, empty seats already dropped. Never empty: the first seat has no "none". */
    val slots: List<SlotOptions>,
) {
    override fun toString(): String =
        "MatchOptions(${rows}x$cols, seed=$seed, ${walls.size} walls, ${shape?.slug}, $slots)"
}
