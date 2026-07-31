package ao.snakewarz.ui.model

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
    /** In slot order, empty seats already dropped. Never empty: the first seat has no "none". */
    val slots: List<SlotOptions>,
) {
    override fun toString(): String = "MatchOptions(${rows}x$cols, seed=$seed, ${walls.size} walls, $slots)"
}
