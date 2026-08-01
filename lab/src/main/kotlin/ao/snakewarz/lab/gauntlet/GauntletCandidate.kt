package ao.snakewarz.lab.gauntlet

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.gauntlet.GauntletLevel
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
import ao.snakewarz.match.tournament.Contestant

/** One exact level as the lab measures it, including a wall seed independent of the match seed. */
internal class GauntletTrialLevel(
    val index: Int,
    val opponent: Contestant,
    val rows: Int,
    val cols: Int,
    val shape: MapShape,
    val mapSeed: Long,
) {
    init {
        require(index >= 1) { "a trial level is numbered from 1, was $index" }
        require(opponent.budgetPerTurn != null) { "trial level $index must pin its opponent allowance" }
        require(rows >= shape.minimumSide && cols >= shape.minimumSide) {
            "trial level $index draws ${shape.slug} on ${rows}x$cols, under its ${shape.minimumSide}-square minimum"
        }
    }

    /** Materialised without a match seed, so retries and opening schedules cannot redraw this level. */
    fun walls(): IntArray = generateMap(rows, cols, shape, seed = mapSeed).walls()

    companion object {
        /** The exact shipped configuration, including its wall seed independent of the run seed. */
        fun shipped(level: GauntletLevel): GauntletTrialLevel = GauntletTrialLevel(
            index = level.index,
            opponent = Contestant(level.opponent, level.budgetPerTurn, level.params),
            rows = level.rows,
            cols = level.cols,
            shape = level.shape,
            mapSeed = level.mapSeed,
        )
    }
}

/** The exact seven-level campaign table while its replacement machine profile is measured. */
internal object GauntletCandidate {
    const val TABLE: String = "p7-candidate"

    val levels: List<GauntletTrialLevel> = listOf(
        // Each retained shape appears once: the open lattice is the gentlest wall lesson.
        level(1, "chase", 0, 12, MapShape.PILLARS),
        level(2, "cartographer", 0, 16, MapShape.ROOMS),

        // P4's smallest useful search; deeper lookahead left no stable P7 rung under both references.
        level(3, "lookahead", 4, 12, MapShape.ARENA, "depth" to "1"),
        level(4, "flat-monte-carlo", 400, 12, MapShape.SCATTER),

        // Search advances from learning around blocks to territory planning through wide lanes.
        level(5, "uct", 600, 12, MapShape.ISLANDS),
        level(6, "puct", 600, 12, MapShape.PINWHEEL, "eval" to "territory"),

        // P5's deployable common-field winner, on the complete empty-8 opening population it won.
        level(7, "alphabeta", 1_700, 8, MapShape.EMPTY, "eval" to "territory"),
    )

    private fun level(
        index: Int,
        slug: String,
        budgetPerTurn: Int,
        side: Int,
        shape: MapShape,
        vararg params: Pair<String, String>,
    ): GauntletTrialLevel = GauntletTrialLevel(
        index = index,
        opponent = Contestant(
            bot = BotId(slug),
            budgetPerTurn = budgetPerTurn,
            params = if (params.isEmpty()) BotParams.EMPTY else BotParams(mapOf(*params)),
        ),
        rows = side,
        cols = side,
        shape = shape,
        mapSeed = MAP_SEED,
    )

    /** Zero pins the two seeded shapes and states the contract explicitly for the other five. */
    private const val MAP_SEED = 0L
}
