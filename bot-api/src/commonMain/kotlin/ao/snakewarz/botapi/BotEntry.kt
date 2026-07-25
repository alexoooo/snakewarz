package ao.snakewarz.botapi

/**
 * One bot as the rest of the program sees it: a permanent [id], a name for humans, and a way to make
 * an instance.
 *
 * [displayName] is free to change — it is never persisted. [id] is not; see [BotId].
 */
public class BotEntry(
    public val id: BotId,
    public val displayName: String,
    public val factory: BotFactory,
) {
    init {
        require(displayName.isNotBlank()) { "bot '$id' needs a display name" }
    }

    override fun toString(): String = "BotEntry($id, \"$displayName\")"
}
