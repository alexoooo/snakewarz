package ao.snakewarz.ui.model

/** The new-match form, read off the DOM once and handed on as a value. */
internal class MatchOptions(
    val rows: Int,
    val cols: Int,
    val seed: Long,
    /** In slot order, empty seats already dropped. Never empty: the first seat has no "none". */
    val slots: List<SlotOptions>,
) {
    override fun toString(): String = "MatchOptions(${rows}x$cols, seed=$seed, $slots)"
}
