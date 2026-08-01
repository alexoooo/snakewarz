package ao.snakewarz.match.gauntlet

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.map.MapShape

/**
 * The seven-level single-player campaign, weakest first.
 *
 * Five trivial reactive opponents were removed. The curve now begins with the strongest useful
 * reactive opponent, introduces a wall-aware non-search bot, and then climbs through fixed-depth,
 * guided-tree and alpha-beta search before the empty-board championship winner. The six wall layouts
 * use each surviving non-empty map shape once; the boss returns to an empty 8x8 for a pure tactical
 * duel.
 *
 * Every opponent configuration and [GauntletLevel.mapSeed] is pinned. Retries vary the match seed —
 * and therefore turn order and bot randomness — without redrawing the level or changing its effort.
 * `:lab`'s `gauntlet` command measures this exact table on each level's own board.
 *
 * The final boss is `alphabeta:budget=1700,eval=territory`, the strongest deployable configuration
 * found by the complete empty-8 championship. That is a strongest-measured result under the browser
 * envelope, not a claim of solved or unbeatable play.
 */
public object Gauntlet {
    /** Level 1 upward, with [GauntletLevel.index] equal to its one-based position. */
    public val levels: List<GauntletLevel> = listOf(
        GauntletLevel(
            index = 1,
            title = "The Hunter",
            blurb = "Tracks your head and tries to close the distance. The first opponent that fights back.",
            opponent = BotId("chase"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 12,
            cols = 12,
            shape = MapShape.PILLARS,
            mapSeed = MAP_SEED,
        ),
        GauntletLevel(
            index = 2,
            title = "The Cartographer",
            blurb = "Reads walls, exits and pockets, then claims safe territory without searching ahead.",
            opponent = BotId("cartographer"),
            params = BotParams.EMPTY,
            budgetPerTurn = NO_ALLOWANCE,
            rows = 16,
            cols = 16,
            shape = MapShape.ROOMS,
            mapSeed = MAP_SEED,
        ),
        GauntletLevel(
            index = 3,
            title = "The Lookout",
            blurb = "Reads five turns into every branch and fights for the arena instead of waiting for a trap.",
            opponent = BotId("lookahead"),
            params = BotParams(mapOf("depth" to "5")),
            budgetPerTurn = 1_024,
            rows = 12,
            cols = 12,
            shape = MapShape.ARENA,
            mapSeed = MAP_SEED,
        ),
        GauntletLevel(
            index = 4,
            title = "The Gambler",
            blurb = "Builds a guided search tree, valuing territory while testing hundreds of futures.",
            opponent = BotId("puct"),
            params = BotParams(mapOf("eval" to "territory")),
            budgetPerTurn = 600,
            rows = 12,
            cols = 12,
            shape = MapShape.SCATTER,
            mapSeed = MAP_SEED,
        ),
        GauntletLevel(
            index = 5,
            title = "The Student",
            blurb = "Uses the island walls as part of its territorial search instead of treating them as scenery.",
            opponent = BotId("puct"),
            params = BotParams(mapOf("eval" to "territory")),
            budgetPerTurn = 600,
            rows = 12,
            cols = 12,
            shape = MapShape.ISLANDS,
            mapSeed = MAP_SEED,
        ),
        GauntletLevel(
            index = 6,
            title = "The Planner",
            blurb = "Reads replies with alpha-beta and turns the pinwheel's narrow exits into commitments.",
            opponent = BotId("alphabeta"),
            params = BotParams(mapOf("eval" to "territory")),
            budgetPerTurn = 600,
            rows = 12,
            cols = 12,
            shape = MapShape.PINWHEEL,
            mapSeed = MAP_SEED,
        ),
        GauntletLevel(
            index = 7,
            title = "Final Boss",
            blurb = "Reads every reply on an empty 8x8 and fights for every square. Nowhere to hide.",
            opponent = BotId("alphabeta"),
            params = BotParams(mapOf("eval" to "territory")),
            budgetPerTurn = 1_700,
            rows = 8,
            cols = 8,
            shape = MapShape.EMPTY,
            mapSeed = MAP_SEED,
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

private const val NO_ALLOWANCE: Int = 0
private const val MAP_SEED: Long = 0L
