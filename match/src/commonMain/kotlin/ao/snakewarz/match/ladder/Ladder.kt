package ao.snakewarz.match.ladder

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.map.MapShape

/**
 * The ten levels, weakest first — ten different bots on ten different boards.
 *
 * ### Ten slugs, not one bot at ten allowances
 *
 * Every level names a different algorithm, so what a player meets on the way up is ten ways of
 * playing rather than one opponent thinking for longer. That is also what makes the curve teachable:
 * the move that beats level 4 does not beat level 6, because level 6 is not level 4 with more time.
 *
 * ### The order is measured, on the geometry each level plays
 *
 * `BotLadderTest` certifies its rungs on an **empty 12x12**, and that ordering does not survive a map
 * or a board size. Both are documented failures rather than suspicions: `alphabeta:eval=territory`
 * rates above bare `puct` at 8x8 while losing its head-to-head to it, and a six-entrant field measured
 * on `cross` moved `wallhug` about 400 Elo up the table and compressed the whole field to half its
 * empty-board width. So a level table ordered on one board is a hypothesis about ten others.
 *
 * `:lab`'s `ladder` subcommand is the instrument that settles it. It plays every level's opponent on
 * **that level's own board, map and allowance** against one fixed reference, and prints the
 * reference's score per level; the ordering is right when that score falls. The command and the run
 * behind this table are in `docs/Bots.md`.
 *
 * ### Two maps are placed by that measurement and will look misplaced without it
 *
 * **`cross` sits at level 2, near the bottom, because it lifts a room-filler enormously.** Its first
 * assignment here was level 4, where it put `space` above the two levels over it. A map that
 * compresses the field can only go under a bot too weak to be lifted past anything.
 *
 * **`double-spiral` sits at level 6, under the last opponent that does not search, because it inverts
 * what search is worth.** At 16x16, `puct` at a quarter of an allowance beats the same bot at the full
 * one 77-23 on that map and loses 23-77 on a bare board of the same size — the corridor turns the game
 * into a filling race, and a deeper search finds no more of one. Moving it above level 7 would make a
 * bigger allowance buy a *weaker* level.
 *
 * ### Where this deviates from the shipped registry order, and why
 *
 * `burninhell` is a contributed bot, is not a rung of `BotLadderTest`, and `docs/Bots.md` claims
 * nothing about its strength; its place here is a measurement rather than an inheritance. And the two
 * search bots at the top play their **shipped default appraisal** rather than the dearest one
 * available: `eval=chamber` costs about 4.6x `territory` per evaluation, which on a 20x20 overruns
 * `:ui`'s frame slice several times over, and `AlphaBetaBot.EVAL` records that it also finishes
 * *below* the cheap leaf in a common field. A level nobody can play smoothly and that is weaker than
 * the level below it fails twice.
 */
public object Ladder {
    /**
     * Level 1 upward. The index into this list is [LadderLevel.index] minus one, and [levelAt] is the
     * lookup that does not make a caller remember that.
     */
    public val levels: List<LadderLevel> = listOf(
        LadderLevel(
            index = 1,
            title = "Static",
            blurb = "Wanders at random. It has no plan, and it will walk into a wall on its own.",
            opponent = BotId("random"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 8,
            cols = 8,
            shape = MapShape.EMPTY,
        ),
        LadderLevel(
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
        LadderLevel(
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
        LadderLevel(
            index = 4,
            title = "Room Reader",
            blurb = "Always takes the move that leaves it the most room. It still ignores you entirely.",
            opponent = BotId("space"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 12,
            cols = 12,
            shape = MapShape.EMPTY,
        ),
        LadderLevel(
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
        LadderLevel(
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
        LadderLevel(
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
        LadderLevel(
            index = 8,
            title = "The Student",
            blurb = "Same guesses, but it remembers them — and spends its next thousand where they paid.",
            opponent = BotId("uct"),
            params = BotParams.EMPTY,
            budgetPerTurn = 600,
            rows = 16,
            cols = 16,
            shape = MapShape.SCATTER,
        ),
        LadderLevel(
            index = 9,
            title = "The Planner",
            blurb = "Starts each search with a hunch, and judges a position by the ground it would own.",
            opponent = BotId("puct"),
            params = BotParams(mapOf("eval" to "territory")),
            budgetPerTurn = 1_000,
            rows = 16,
            cols = 16,
            shape = MapShape.ROOMS,
        ),
        LadderLevel(
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
    )

    public val size: Int get() = levels.size

    /** The level numbered [index], counting from 1. */
    public fun levelAt(index: Int): LadderLevel {
        require(index in 1..size) { "there are $size levels, so there is no level $index" }
        return levels[index - 1]
    }

    override fun toString(): String = "Ladder($size levels)"
}

/**
 * What a level grants an opponent that declares no search allowance.
 *
 * Zero rather than a default nobody reads: six of the ten levels are bots that spend nothing whatever
 * they are handed, and writing the figure they actually use keeps the table from implying that their
 * difficulty has a knob in it.
 */
private const val NO_ALLOWANCE: Int = 0
