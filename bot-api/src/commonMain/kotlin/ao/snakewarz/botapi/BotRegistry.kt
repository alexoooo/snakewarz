package ao.snakewarz.botapi

/**
 * Every bot the program can instantiate, looked up by its permanent [BotId].
 *
 * This is an interface in `:bot-api` and not a concrete list for one specific reason: the match
 * driver resolves slots through it, `:app` injects the implementation, and so `:match` — the replay
 * codec included — never references a bot class. A replay decodes to slugs and the codec has no
 * opinion about what they mean.
 *
 * Implementations must iterate [entries] in a stable, declared order. Never a `HashSet` or the
 * `keys` of a `HashMap`: a registry that reorders itself between runs reorders anything derived
 * from it, and the derived thing here is a tournament.
 */
public interface BotRegistry {
    /** Every registered bot, in registration order. */
    public val entries: List<BotEntry>

    public operator fun get(id: BotId): BotEntry?

    /** As [get], but fails loudly — used where a missing bot means an unplayable replay. */
    public fun entryOf(id: BotId): BotEntry =
        get(id) ?: throw IllegalArgumentException(
            "no bot is registered as '$id'; known ids are ${entries.map { it.id }}",
        )
}
