package ao.snakewarz.bots

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.bots.reactive.BurninHellBot
import ao.snakewarz.bots.reactive.RandomBot
import ao.snakewarz.bots.reactive.WallHugBot
import ao.snakewarz.bots.reactive.chase.ChaseBot
import ao.snakewarz.bots.reactive.space.PressureBot
import ao.snakewarz.bots.reactive.space.SpaceBot
import ao.snakewarz.bots.search.FlatMonteCarloBot
import ao.snakewarz.bots.search.puct.PuctBot
import ao.snakewarz.bots.search.puct.TerritoryEval
import ao.snakewarz.bots.search.uct.UctBot

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
 * **A bot earns its place by what it lets you measure, not by what it scores.** A roster is a set of
 * instruments: an Elo floor (`random`), an ablation control (`flat-monte-carlo` is `uct` minus the
 * tree), a deterministic move stream (`wallhug`, `burninhell`), an opponent strong enough to take
 * games off a searcher (`chase`). A bot that is merely weak is not an instrument — `random` already
 * is that one, more cleanly — which is what retired `tomsnake`.
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

        // Contributed to the original project. Not a ladder rung, and kept for a reason that is not
        // strength: with `wallhug` it is one of only two bots here that draw no randomness at all, so
        // the pair of them is the one pairing whose games repeat under a fixed opening. `ArenaTest`
        // measures the openings machinery with it. `tomsnake` was the other contributed bot and was
        // retired -- see docs/Legacy.md.
        register("burninhell", "Burnin Hell", BotFactory { BurninHellBot() })

        // Experimental. A rung asserts that it beats the one below it, and where this one belongs
        // against `uct` is a measurement rather than a preference -- TerritoryEval's KDoc carries the
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
