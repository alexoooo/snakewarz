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
import ao.snakewarz.bots.search.AlphaBetaBot
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
 * It comes in two sections, and it used to come in three. The first is **the ladder**: nine bots,
 * weakest first, each rung beating the one below it over twenty matches — `BotLadderTest` is what
 * says so. The second is the bots **contributed** to the original 2005 project, ported semantically
 * and ordered by slug. There used to be a third, **experimental** — registered, gated by the same
 * contract suite, and making no claim about strength that nobody had measured. It held `puct` and
 * `alphabeta`, it is empty now, and the block below `uct` records what emptied it.
 *
 * **A bot earns its place by what it lets you measure, not by what it scores.** A roster is a set of
 * instruments: an Elo floor (`random`), an ablation control (`flat-monte-carlo` is `uct` minus the
 * tree), a deterministic move stream (`wallhug`, `burninhell`), an opponent strong enough to take
 * games off a searcher (`chase`). A bot that is merely weak is not an instrument — `random` already
 * is that one, more cleanly — which is what retired `tomsnake`.
 *
 * `random` must stay first, and the reason is not the one this comment used to give. It said `:ui`
 * seats the second slot from the first bot on the list; `Chrome.kt` has seated `DEFAULT_OPPONENT`
 * for some time and falls back to `bots.firstOrNull()` only when a registry does not offer it. So
 * registration order decides the opening opponent for a *custom* registry and nothing else — and the
 * order still has to be weakest-first, because that is the claim `BotLadderTest` asserts rung by
 * rung and the order the sidebar shows.
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

        // The two rungs above `uct` were registered experimental from the day each arrived, under a
        // comment asking for the number that would promote `puct` "or leave it here and say why".
        // The number came
        // in: three twelve-rung fields at *equal clock* -- 13,200 matches a board, per-entrant
        // allowances from a Chrome cost sweep -- put `puct` over `uct` by +54 / +58 / +62 Elo on
        // 8x8 / 12x12 / 20x20 with disjoint intervals every time, and put `alphabeta` over `puct`
        // on all three as well. Seating one without the other would have been the inconsistency, so
        // this was one decision and not two. `BotLadderTest` asserts both rungs and its KDoc carries
        // the board-size reversal that comes with the second one.
        //
        // What made the older reading disagree is that it was taken at equal *allowance*, where the
        // dearer leaves are handed several times the wall clock -- see AlphaBetaBot.EVAL.
        register("puct", "PUCT", ::PuctBot, PuctBot.KNOBS)

        // The top rung, and still the only exact search here: everything else in this list samples
        // lines and keeps a mean. What put it on top was moving its leaf rather than its search.
        //
        // It rates first at equal clock on all three boards P2 measured -- but read AlphaBetaBot.EVAL
        // before quoting that, because the ranking claim is the weaker of the two and the adoption
        // was made on the other one. At 8x8 this bot loses its head-to-head to `puct` while rating
        // above it. The rung below asserts the 12x12 ordering, which is the board it is measured on.
        register("alphabeta", "Alpha-Beta", ::AlphaBetaBot, AlphaBetaBot.KNOBS)

        // Contributed to the original project. Not a ladder rung, and kept for a reason that is not
        // strength: with `wallhug` it is one of only two bots here that draw no randomness at all, so
        // the pair of them is the one pairing whose games repeat under a fixed opening. `ArenaTest`
        // measures the openings machinery with it. `tomsnake` was the other contributed bot and was
        // retired -- see docs/Legacy.md.
        //
        // It moved down the list when the two searchers above graduated. Nothing reads a registry
        // position -- a replay carries slugs and `Chrome.kt` seats `DEFAULT_OPPONENT` by slug -- so
        // what changed is the order the sidebar lists them in, which is the order the ladder claims.
        register("burninhell", "Burnin Hell", BotFactory { BurninHellBot() })
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
