package ao.snakewarz.bots

import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotFactory
import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotKnob
import ao.snakewarz.botapi.BotRegistry

/**
 * Every bot compiled into the app.
 *
 * Adding one is three lines and a file: write the class, [register] it here, open a PR. CI then runs
 * it against the shared bot contract suite, which is what makes accepting that PR safe.
 *
 * A bot with anything worth tuning declares it as a list of `BotKnob`s and passes it here as well.
 * That list is the only way the sidebar could know the knob exists — and passing it is the whole of
 * the work, because the form builds itself from the registry exactly as the pickers do.
 *
 * The list is ordered by registration and looked up by hash, never iterated by hash — a registry
 * that reorders itself between runs reorders every tournament derived from it.
 *
 * It comes in three sections. The first is **the ladder**: seven bots, weakest first, each rung
 * beating the one below it over twenty matches — `BotLadderTest` is what says so. The second is the
 * bots **contributed** to the original 2005 project, ported semantically and ordered by slug. The
 * third is **experimental**: registered, gated by exactly the same contract suite, and making no
 * claim about strength that nobody has measured. Only the first section claims anything at all.
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
        register("wallhug", "Wall Hugger", BotFactory { WallHugBot() })
        register("space", "Space Filler", ::SpaceBot)
        register("pressure", "Pressure", ::PressureBot, PressureBot.KNOBS)
        register("chase", "Chaser", ::ChaseBot, ChaseBot.KNOBS)
        register("flat-monte-carlo", "Flat Monte Carlo", ::FlatMonteCarloBot, FlatMonteCarloBot.KNOBS)
        register("uct", "UCT", ::UctBot, UctBot.KNOBS)

        // Contributed to the original project. Not ladder rungs.
        register("burninhell", "Burnin Hell", BotFactory { BurninHellBot() })
        register("tomsnake", "Tom Snake", ::TomSnakeBot, TomSnakeBot.KNOBS)

        // Experimental. A rung asserts that it beats the one below it, and where this one belongs
        // against `uct` is a measurement rather than a preference -- ExpertEval's KDoc carries the
        // two tables and the `:lab` command that re-runs them. Promote it into the ladder when the
        // number is in, or leave it here and say why, but do not let it assert something nobody
        // checked.
        register("puct", "PUCT", ::PuctBot, PuctBot.KNOBS)
    }

    private val byId: Map<BotId, BotEntry> = entries.associateByTo(LinkedHashMap()) { it.id }

    override fun get(id: BotId): BotEntry? = byId[id]

    override fun toString(): String = "ShippedBots(${entries.size})"
}

private fun MutableList<BotEntry>.register(
    slug: String,
    displayName: String,
    factory: BotFactory,
    knobs: List<BotKnob> = emptyList(),
) {
    add(BotEntry(BotId(slug), displayName, factory, knobs))
}
