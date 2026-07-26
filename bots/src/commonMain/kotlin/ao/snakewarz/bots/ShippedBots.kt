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
 * It comes in two sections. The first is **the ladder**: seven bots, weakest first, each rung
 * beating the one below it over twenty matches — `BotLadderTest` is what says so. The second is the
 * bots **contributed** to the original 2005 project, ported semantically and ordered by slug; they
 * are gated by exactly the same contract suite, but they are not rungs and the ordering claims
 * nothing about their strength.
 *
 * `random` must stay first. `:ui` seats the second slot from the first bot on the list, and the
 * opening screen of a game nobody has configured yet should be the weakest opponent there is.
 *
 * **Slugs are frozen once released.** They are written into replay URLs; renaming one breaks every
 * link ever shared.
 */
public object ShippedBots : BotRegistry {
    override val entries: List<BotEntry> = buildList {
        // The ladder, weakest first.
        register("random", "Random", ::RandomBot)
        register("wallhug", "Wall Hugger") { WallHugBot() }
        register("space", "Space Filler", ::SpaceBot)
        register("pressure", "Pressure", ::PressureBot)
        register("chase", "Chaser", ::ChaseBot)
        register("flat-monte-carlo", "Flat Monte Carlo", ::FlatMonteCarloBot)
        register("uct", "UCT", ::UctBot)

        // Contributed to the original project. Not ladder rungs.
        register("burninhell", "Burnin Hell") { BurninHellBot() }
        register("tomsnake", "Tom Snake", ::TomSnakeBot)
    }

    private val byId: Map<BotId, BotEntry> = entries.associateByTo(LinkedHashMap()) { it.id }

    override fun get(id: BotId): BotEntry? = byId[id]

    override fun toString(): String = "ShippedBots(${entries.size})"
}

private fun MutableList<BotEntry>.register(slug: String, displayName: String, factory: BotFactory) {
    add(BotEntry(BotId(slug), displayName, factory))
}
