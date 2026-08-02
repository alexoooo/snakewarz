package ao.snakewarz.ui.model

/**
 * Where a bot's picture comes from, answered by whoever knows what is deployed beside the page.
 *
 * `:ui` cannot depend on `:bots` and does not want to: it asks by stable artwork key and takes a URL or nothing,
 * so a registry it has never heard of still gets faces, and a bot contributed tomorrow still gets
 * one. The fallback for `null` is drawn here and needs no assets at all — see [SlotPortraits].
 *
 * Generic keys are frozen bot slugs. Campaign keys are the `gauntlet-<stage>` names owned by the
 * visual catalogue, allowing repeated bot implementations to appear as different characters.
 */
public fun interface Portraits {
    /** The address of [key]'s picture, or `null` where nothing was shipped for it. */
    public fun urlFor(key: String): String?
}
