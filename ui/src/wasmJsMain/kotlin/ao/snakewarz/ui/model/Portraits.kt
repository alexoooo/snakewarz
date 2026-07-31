package ao.snakewarz.ui.model

/**
 * Where a bot's picture comes from, answered by whoever knows what is deployed beside the page.
 *
 * `:ui` cannot depend on `:bots` and does not want to: it asks by slug and takes a URL or nothing,
 * so a registry it has never heard of still gets faces, and a bot contributed tomorrow still gets
 * one. The fallback for `null` is drawn here and needs no assets at all — see [SlotPortraits].
 *
 * A slug rather than a `BotEntry` because a slug is the one part of a bot that is frozen: it is
 * what a replay URL carries, so a file named after one keeps pointing at the same bot forever.
 */
public fun interface Portraits {
    /** The address of [slug]'s picture, or `null` where nothing was shipped for it. */
    public fun urlFor(slug: String): String?
}
