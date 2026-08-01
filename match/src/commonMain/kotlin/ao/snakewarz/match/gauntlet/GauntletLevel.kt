package ao.snakewarz.match.gauntlet

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.map.BoardMap
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap

/**
 * One rung of the single-player gauntlet: an opponent, what it may spend, and the board it defends.
 *
 * A level is **a whole match configuration and not a difficulty number**, because three things move
 * from rung to rung and only one of them is the bot. The geometry ramps, the map shape changes, and a
 * searcher's allowance grows — and each of those moves the game as much as swapping the algorithm
 * does. Keeping all three here is what lets `:lab` play the exact match a player will play.
 *
 * [opponent] is a slug resolved through the `BotRegistry` interface, never a class: this module has
 * never seen a bot and must not start now. It is also **frozen** once released, like every released
 * `BotId` and for a related reason — saved progress and a level link name it.
 *
 * [params] is pinned rather than left to the registry's defaults on purpose. A level is a character a
 * player learns to beat, and a knob default moving under it would quietly hand somebody a different
 * opponent at the same level number.
 *
 * [mapSeed] is pinned separately from the match seed. A retry may vary turn order and bot randomness,
 * but it must not redraw the walls: the board measured in `:lab` is the board shipped to the player.
 */
public class GauntletLevel(
    /** 1-based, and the identifier saved progress is keyed on. */
    public val index: Int,
    /** What the level is called on the level-select screen. */
    public val title: String,
    /** One line, in the player's language, on how this opponent plays. */
    public val blurb: String,
    public val opponent: BotId,
    public val params: BotParams,
    /**
     * Evaluations the opponent may spend on a turn.
     *
     * Zero for every level whose opponent declares no search allowance, which is the honest figure
     * rather than a placeholder: those bots spend nothing whatever they are granted.
     */
    public val budgetPerTurn: Int,
    public val rows: Int,
    public val cols: Int,
    public val shape: MapShape,
    public val mapSeed: Long = 0L,
) {
    init {
        require(index >= 1) { "a level is numbered from 1, was $index" }
        require(title.isNotBlank()) { "level $index has no title" }
        require(blurb.isNotBlank()) { "level $index has no blurb" }
        require(budgetPerTurn >= 0) { "level $index grants $budgetPerTurn evaluations" }
        require(rows >= shape.minimumSide && cols >= shape.minimumSide) {
            "level $index draws ${shape.slug} on ${rows}x$cols, under its ${shape.minimumSide}-square minimum"
        }
    }

    /**
     * The walls this level is played behind, at its pinned [mapSeed].
     *
     * Some shapes read the seed and some do not. Keeping it here makes both kinds one fixed place a
     * player can learn, and keeps a fresh match seed from changing the measured configuration.
     */
    public fun map(): BoardMap = generateMap(rows, cols, shape, seed = mapSeed)

    /**
     * The match: [human] in slot 0, the opponent in slot 1, on this level's board and map.
     *
     * The turn order is still shuffled from [seed] by `MatchSetup.create`, so acting first is not
     * something either seat is handed — a level is meant to be hard rather than unfair.
     */
    public fun setup(seed: Long, human: BotId): MatchSetup = MatchSetup.create(
        rows = rows,
        cols = cols,
        slots = listOf(human, opponent),
        seed = seed,
        budgetPerTurn = budgetPerTurn,
        walls = map().walls(),
        slotParams = listOf(BotParams.EMPTY, params),
    )

    override fun toString(): String =
        "GauntletLevel($index $title, ${opponent.slug}, ${rows}x$cols ${shape.slug}@$mapSeed, $budgetPerTurn)"
}
