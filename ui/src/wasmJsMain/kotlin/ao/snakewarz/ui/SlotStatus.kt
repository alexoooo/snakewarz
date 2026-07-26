package ao.snakewarz.ui

/** One scoreboard line. Named and worded for a person; the engine's own vocabulary stops here. */
internal class SlotStatus(
    val slot: Int,
    val name: String,
    val length: Int,
    val alive: Boolean,
    /** How this snake left, in plain words, or empty while it is still in the match. */
    val fate: String,
    val winner: Boolean,
)
