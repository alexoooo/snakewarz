package ao.snakewarz.bots

import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotFactory
import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotRegistry

/**
 * Every bot compiled into the app.
 *
 * Adding one is three lines and a file: write the class, [register] it here, open a PR. CI then runs
 * it against the shared bot contract suite, which is what makes accepting that PR safe.
 *
 * The list is ordered by registration and looked up by hash, never iterated by hash — a registry
 * that reorders itself between runs reorders every tournament derived from it.
 *
 * **Slugs are frozen once released.** They are written into replay URLs; renaming one breaks every
 * link ever shared.
 */
public object ShippedBots : BotRegistry {
    override val entries: List<BotEntry> = buildList {
        register("random", "Random", ::RandomBot)
        register("wallhug", "Wall Hugger") { WallHugBot() }
    }

    private val byId: Map<BotId, BotEntry> = entries.associateByTo(LinkedHashMap()) { it.id }

    override fun get(id: BotId): BotEntry? = byId[id]

    override fun toString(): String = "ShippedBots(${entries.size})"
}

private fun MutableList<BotEntry>.register(slug: String, displayName: String, factory: BotFactory) {
    add(BotEntry(BotId(slug), displayName, factory))
}
