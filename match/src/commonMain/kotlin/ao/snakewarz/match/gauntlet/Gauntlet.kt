package ao.snakewarz.match.gauntlet

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.map.MapShape

/**
 * The eleven levels, weakest first — ten algorithms on eleven different boards.
 *
 * ### Ten slugs, not one bot at ten allowances
 *
 * Every level up to the tenth names a different algorithm, so what a player meets on the way up is
 * ten ways of playing rather than one opponent thinking for longer. That is also what makes the curve
 * teachable: the move that beats level 4 does not beat level 6, because level 6 is not level 4 with
 * more time. The eleventh repeats a slug and no configuration — see the boss, below.
 *
 * ### **This ordering is a hypothesis. It has not been run since the maps changed**
 *
 * The ten-level table this grew from *was* measured, by the instrument named below, and the run is in
 * `docs/Bots.md`. Then three shapes were redrawn — `rooms`, `diagonals` and `double-spiral` — three
 * were added — `arena`, `islands` and `pinwheel` — and four levels changed map. A shape travels as
 * squares rather than as a name, so redrawing one is invisible to every link anybody has shared and
 * *completely* visible to a measurement: the old numbers were taken on boards that no longer exist.
 *
 * What survives unchanged is three rows — `cross` at level 2, `pillars` at level 3 and `ring` at
 * level 5, each the same drawing under the same opponent it was measured under. **Every other map
 * assignment here is a guess**, including level 6's, which is the one the argument below rested on.
 * The re-measurement is on the research agenda; until it is run, read this table as an intention.
 *
 * ### The order is measured on the geometry each level plays, which is why it has to be re-run
 *
 * `BotLadderTest` certifies its rungs on an **empty 12x12**, and that ordering does not survive a map
 * or a board size. Both are documented failures rather than suspicions: `alphabeta:eval=territory`
 * rates above bare `puct` at 8x8 while losing its head-to-head to it, and a six-entrant field measured
 * on `cross` moved `wallhug` about 400 Elo up the table and compressed the whole field to half its
 * empty-board width. So a level table ordered on one board is a hypothesis about ten others.
 *
 * `:lab`'s `gauntlet` subcommand is the instrument that settles it. It plays every level's opponent on
 * **that level's own board, map and allowance** against one fixed reference, and prints the
 * reference's score per level; the ordering is right when that score falls.
 *
 * ### Two maps were placed by that measurement, and they are why placement is measured at all
 *
 * **`cross` sits at level 2, near the bottom, because it lifts a room-filler enormously.** Its first
 * assignment here was level 4, where it put `space` above the two levels over it. A map that
 * compresses the field can only go under a bot too weak to be lifted past anything. That drawing and
 * that level are both untouched, so this one still stands on its number.
 *
 * **`double-spiral` sits at level 6, under the last opponent that does not search, because it inverted
 * what search is worth.** At 16x16, `puct` at a quarter of an allowance beat the same bot at the full
 * one 77-23 on that map and lost 23-77 on a bare board of the same size — the corridor turned the game
 * into a filling race, and a deeper search finds no more of one. Moving it above level 7 would have
 * made a bigger allowance buy a *weaker* level.
 *
 * **That reading was taken on the old drawing, and the redraw went at exactly the property it rested
 * on**: the corridors alternated one and two squares and are now a uniform two. So level 6 is the
 * placement here with the strongest prior *and* the most specific reason to doubt it — which is the
 * shape of every row in this table now, and the reason a guess is written down as a guess.
 *
 * ### Where this deviates from the shipped registry order, and why
 *
 * `burninhell` is a contributed bot, is not a rung of `BotLadderTest`, and `docs/Bots.md` claims
 * nothing about its strength; its place here was a measurement rather than an inheritance.
 *
 * ### The boss is the top search running its dearest appraisal, on the one board that can afford it
 *
 * Levels 9 and 10 play their **shipped default appraisal** rather than the dearest one available:
 * `eval=chamber` costs about 4.6x `territory` per evaluation, which on a 20x20 overruns `:ui`'s frame
 * slice several times over. Level 11 is where that stops being true — an 8x8 is small enough to pay
 * for it, and a small bare board is a pure tactical duel with nowhere to hide. Eleven levels, ten
 * algorithms, no repeated configuration.
 *
 * It is a different opponent rather than a proven harder one, and `AlphaBetaBot.EVAL` is why the
 * distinction is worth keeping: that leaf finishes *below* the cheap one in a common field on a bare
 * 12x12. Whether it is harder at 8x8 is the last row the re-measurement has to answer.
 */
public object Gauntlet {
    /**
     * Level 1 upward. The index into this list is [GauntletLevel.index] minus one, and [levelAt] is
     * the lookup that does not make a caller remember that.
     */
    public val levels: List<GauntletLevel> = listOf(
        GauntletLevel(
            index = 1,
            title = "Static",
            blurb = "Wanders at random. It has no plan, and it will walk into a wall on its own.",
            opponent = BotId("random"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 8,
            cols = 8,
            shape = MapShape.ARENA,
        ),
        GauntletLevel(
            index = 2,
            title = "The Sweeper",
            blurb = "Sweeps the board column by column. It never once looks at where you are.",
            opponent = BotId("burninhell"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 10,
            cols = 10,
            shape = MapShape.CROSS,
        ),
        GauntletLevel(
            index = 3,
            title = "The Hugger",
            blurb = "Runs for the nearest wall and spirals inward, packing itself away neatly.",
            opponent = BotId("wallhug"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 10,
            cols = 10,
            shape = MapShape.PILLARS,
        ),
        GauntletLevel(
            index = 4,
            title = "Room Reader",
            blurb = "Always takes the move that leaves it the most room. It still ignores you entirely.",
            opponent = BotId("space"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 12,
            cols = 12,
            shape = MapShape.SCATTER,
        ),
        GauntletLevel(
            index = 5,
            title = "The Crowder",
            blurb = "Keeps its own room, then leans on yours. The first opponent that has noticed you.",
            opponent = BotId("pressure"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 12,
            cols = 12,
            shape = MapShape.RING,
        ),
        GauntletLevel(
            index = 6,
            title = "The Hunter",
            blurb = "Walks the shortest path to your head and closes the door behind you.",
            opponent = BotId("chase"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 14,
            cols = 14,
            shape = MapShape.DOUBLE_SPIRAL,
        ),
        GauntletLevel(
            index = 7,
            title = "The Gambler",
            blurb = "Plays hundreds of games to the finish in its head and takes whichever move won most.",
            opponent = BotId("flat-monte-carlo"),
            params = BotParams.EMPTY,
            budgetPerTurn = 400,
            rows = 14,
            cols = 14,
            shape = MapShape.DIAGONALS,
        ),
        GauntletLevel(
            index = 8,
            title = "The Student",
            blurb = "Same guesses, but it remembers them — and spends its next thousand where they paid.",
            opponent = BotId("uct"),
            params = BotParams.EMPTY,
            budgetPerTurn = 600,
            rows = 16,
            cols = 16,
            shape = MapShape.ISLANDS,
        ),
        GauntletLevel(
            index = 9,
            title = "The Planner",
            blurb = "Starts each search with a hunch, and judges a position by the ground it would own.",
            opponent = BotId("puct"),
            params = BotParams(mapOf("eval" to "territory")),
            budgetPerTurn = 1_000,
            rows = 16,
            cols = 16,
            shape = MapShape.PINWHEEL,
        ),
        GauntletLevel(
            index = 10,
            title = "The Oracle",
            blurb = "Reads every reply to every move, a dozen turns ahead, and guesses at nothing nearer.",
            opponent = BotId("alphabeta"),
            params = BotParams(mapOf("eval" to "territory")),
            budgetPerTurn = 1_000,
            rows = 20,
            cols = 20,
            shape = MapShape.ROOMS,
        ),
        GauntletLevel(
            index = 11,
            title = "Final Boss",
            blurb = "Reads as deeply as the last one and weighs every room by who reaches it first. Nowhere to hide.",
            opponent = BotId("alphabeta"),
            params = BotParams(mapOf("eval" to "chamber")),
            budgetPerTurn = 1_000,
            rows = 8,
            cols = 8,
            shape = MapShape.EMPTY,
        ),
    )

    public val size: Int get() = levels.size

    /** The level numbered [index], counting from 1. */
    public fun levelAt(index: Int): GauntletLevel {
        require(index in 1..size) { "there are $size levels, so there is no level $index" }
        return levels[index - 1]
    }

    override fun toString(): String = "Gauntlet($size levels)"
}

/**
 * What a level grants an opponent that declares no search allowance.
 *
 * Zero rather than a default nobody reads: six of the eleven levels are bots that spend nothing
 * whatever they are handed, and writing the figure they actually use keeps the table from implying
 * that their difficulty has a knob in it.
 */
private const val NO_ALLOWANCE: Int = 0
