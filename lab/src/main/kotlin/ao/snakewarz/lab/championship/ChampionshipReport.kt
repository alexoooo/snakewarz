package ao.snakewarz.lab.championship

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.EMPTY_MAP
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.lab.strength.Ladder
import ao.snakewarz.match.tournament.TournamentFormat
import kotlin.math.abs

/** A percentile interval over complete-opening blocks, in score share rather than percentage points. */
internal data class ChampionshipInterval(
    val low: Double,
    val high: Double,
)

/** One directed cell of the finalist matrix: [one]'s result against [other]. */
internal data class ChampionshipCell(
    val one: String,
    val other: String,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val score: Double,
    val interval: ChampionshipInterval,
) {
    val played: Int get() = wins + draws + losses
}

/** The evidence used to place one pure configuration in the championship order. */
internal data class ChampionshipFinalist(
    val spec: String,
    val label: String,
    val maximin: Double,
    val maximinInterval: ChampionshipInterval,
    val worstOpponents: List<String>,
    val rating: Double,
    val ratingPriorDetermined: Boolean,
    val chromeWorstTurnMillis: Double,
    val practicalBand: Int = 0,
)

/** One pairing a single common-opponent rating misses by at least five points of score. */
internal data class ChampionshipResidual(
    val one: String,
    val other: String,
    val observed: Double,
    val expected: Double,
) {
    val difference: Double get() = observed - expected
}

/** Whether the ranked challenger directly clears the named incumbent on unseen complete openings. */
internal data class IncumbentGate(
    val incumbent: String,
    val challenger: String,
    val directCell: ChampionshipCell?,
    val clears: Boolean,
)

/**
 * The finalist evidence from one complete empty-8 run.
 *
 * [rankedFinalists] is a ranking of pure configurations. It first forms five-point practical bands
 * from point maximin, then orders a band by common-opponent rating, Chrome worst-turn cost, and the
 * expanded spec. The intervals describe uncertainty; they do not silently replace that declared
 * point-estimate rule.
 */
internal class ChampionshipReport private constructor(
    val runId: String,
    val replications: Int,
    val openingBlocks: Int,
    val matchCount: Int,
    val distinctGames: Int,
    val specs: List<String>,
    val cells: List<ChampionshipCell>,
    val rankedFinalists: List<ChampionshipFinalist>,
    val residuals: List<ChampionshipResidual>,
    val incumbentGate: IncumbentGate,
) {
    /** The directed matrix cell from [one] to [other]. */
    fun cell(one: String, other: String): ChampionshipCell =
        cells.firstOrNull { it.one == one && it.other == other }
            ?: error("no championship cell for '$one' against '$other'")

    /** Text for a command to emit without putting I/O into the statistical core. */
    fun lines(): List<String> = renderChampionship(this)

    companion object {
        /**
         * Validates and analyses one run from a [ao.snakewarz.lab.log.MatchLog].
         *
         * [finalists] and [incumbent] are expanded logged specs, not shortened display labels.
         * Every supplied finalist needs an independently measured raw Chrome worst-turn cost.
         */
        fun of(
            run: RunHeader,
            matches: List<LoggedMatch>,
            finalists: List<String>,
            incumbent: String,
            chromeWorstTurnMillis: Map<String, Double>,
            registry: BotRegistry,
        ): ChampionshipReport {
            val replications = validateRun(run, matches, finalists, incumbent, chromeWorstTurnMillis)
            val finalistSet = finalists.toHashSet()
            val played = matches.filter { match -> match.slots.all { it.spec in finalistSet } }
            validateSchedule(played, finalists, replications)

            val statistics = championshipStatistics(played, finalists)
            val ladder = Ladder.of(played, registry, TournamentFormat.HEAD_TO_HEAD)
            val ladderIndex = ladder.specs.withIndex().associate { (index, spec) -> spec to index }

            val unranked = finalists.mapIndexed { entrant, spec ->
                val row = statistics.cells[entrant]
                val worst = row.filterNotNull().minOf { it.score }
                val worstOpponents = row.filterNotNull().filter { it.score == worst }.map { it.other }
                val rung = ladderIndex.getValue(spec)
                ChampionshipFinalist(
                    spec = spec,
                    label = ladder.label(rung),
                    maximin = worst,
                    maximinInterval = statistics.maximinIntervals[entrant],
                    worstOpponents = worstOpponents,
                    rating = ladder.ratings.rating(rung),
                    ratingPriorDetermined = ladder.ratings.priorDetermined(rung),
                    chromeWorstTurnMillis = chromeWorstTurnMillis.getValue(spec),
                )
            }
            val ranked = rankFinalists(unranked)
            val cells = statistics.cells.flatMap { row -> row.filterNotNull() }
            val residuals = residuals(finalists, statistics.cells, ladder, ladderIndex)
            val challenger = ranked.first().spec
            val direct =
                if (challenger == incumbent) null else cells.first { it.one == challenger && it.other == incumbent }

            return ChampionshipReport(
                runId = run.id,
                replications = replications,
                openingBlocks = Openings.COMPLETE_POPULATION,
                matchCount = played.size,
                distinctGames = played.mapTo(LinkedHashSet()) { it.moveStreamHash }.size,
                specs = finalists.toList(),
                cells = cells,
                rankedFinalists = ranked,
                residuals = residuals,
                incumbentGate = IncumbentGate(
                    incumbent = incumbent,
                    challenger = challenger,
                    directCell = direct,
                    clears = direct != null && direct.interval.low > INCUMBENT_THRESHOLD,
                ),
            )
        }
    }
}

/** Stable ranking without a non-transitive pairwise "within five points" comparator. */
internal fun rankFinalists(finalists: List<ChampionshipFinalist>): List<ChampionshipFinalist> {
    val remaining = finalists.sortedWith(compareByDescending<ChampionshipFinalist> { it.maximin }.thenBy { it.spec })
    val ranked = ArrayList<ChampionshipFinalist>(remaining.size)
    var start = 0
    var band = 1
    while (start < remaining.size) {
        val anchor = remaining[start].maximin
        var end = start + 1
        while (end < remaining.size && anchor - remaining[end].maximin <= PRACTICAL_BAND + EPSILON) {
            end++
        }
        ranked += remaining.subList(start, end)
            .sortedWith(
                compareByDescending<ChampionshipFinalist> { it.rating }
                    .thenBy { it.chromeWorstTurnMillis }
                    .thenBy { it.spec },
            ).map { it.copy(practicalBand = band) }
        start = end
        band++
    }
    return ranked
}

private fun validateRun(
    run: RunHeader,
    matches: List<LoggedMatch>,
    finalists: List<String>,
    incumbent: String,
    chromeWorstTurnMillis: Map<String, Double>,
): Int {
    require(run.rows == Openings.COMPLETE_ROWS && run.cols == Openings.COMPLETE_COLS) {
        "championship requires an empty 8x8 run, was ${run.rows}x${run.cols}"
    }
    require(run.map == EMPTY_MAP) { "championship requires map empty, was ${run.map}" }
    require(run.format == TournamentFormat.HEAD_TO_HEAD.name) {
        "championship requires head-to-head, was ${run.format}"
    }
    require(run.openings == Openings.COMPLETE.name) {
        "championship requires complete openings, was ${run.openings}"
    }
    require(run.rounds > 0 && run.rounds % Openings.COMPLETE_ROUNDS_PER_REPLICATION == 0) {
        "complete rounds must be a positive multiple of ${Openings.COMPLETE_ROUNDS_PER_REPLICATION}, was ${run.rounds}"
    }
    require(matches.isNotEmpty()) { "championship run ${run.id} has no matches" }
    require(matches.all { it.run == run.id }) { "championship analysis accepts exactly one run (${run.id})" }
    require(matches.map { it.index }.toSet().size == matches.size) { "run ${run.id} has duplicate match indices" }
    require(matches.all { it.slots.size == 2 }) { "championship requires exactly two seats per match" }
    require(matches.all { match -> match.slots.map { it.seat }.sorted() == listOf(0, 1) }) {
        "championship requires seats zero and one exactly once per match"
    }
    require(matches.all { match -> match.slots.map { it.spec }.distinct().size == 2 }) {
        "championship entrants must be distinct within each match"
    }
    require(matches.all { match -> match.slots.count { it.winner } <= 1 }) {
        "a head-to-head match cannot record more than one winner"
    }
    require(matches.none { match -> match.slots.any { it.fate == FORFEIT } }) {
        "a forfeit invalidates championship evidence"
    }
    require(finalists.size >= 2) { "championship needs at least two finalists" }
    require(finalists.distinct().size == finalists.size) { "finalist expanded specs must be distinct" }
    require(finalists.all { it in run.contestants }) { "every finalist must be an expanded spec from run ${run.id}" }
    require(incumbent in finalists) { "incumbent '$incumbent' is not a finalist" }
    for (spec in finalists) {
        val cost = chromeWorstTurnMillis[spec]
        require(cost != null && cost.isFinite() && cost >= 0.0) {
            "finalist '$spec' needs a finite non-negative Chrome worst-turn cost"
        }
    }

    val identities = matches.mapNotNullTo(sortedSetOf()) { it.openingIdentity }
    val expected = (0 until Openings.COMPLETE_POPULATION).mapTo(sortedSetOf()) { openingIdentity(it) }
    require(matches.all { it.openingIdentity != null } && identities == expected) {
        "championship requires all ${Openings.COMPLETE_POPULATION} complete opening blocks"
    }
    return run.rounds / Openings.COMPLETE_ROUNDS_PER_REPLICATION
}

private fun validateSchedule(matches: List<LoggedMatch>, finalists: List<String>, replications: Int) {
    val expectedPerOpening = replications * Openings.SEATINGS_PER_OPENING
    for (one in finalists.indices) {
        for (other in one + 1 until finalists.size) {
            val pair = setOf(finalists[one], finalists[other])
            val paired = matches.filter { match -> match.slots.mapTo(HashSet()) { it.spec } == pair }
            for (opening in 0 until Openings.COMPLETE_POPULATION) {
                val identity = openingIdentity(opening)
                val block = paired.filter { it.openingIdentity == identity }
                require(block.size == expectedPerOpening) {
                    "pair ${finalists[one]} vs ${finalists[other]} has ${block.size} matches at $identity, " +
                        "expected $expectedPerOpening"
                }
                for (spec in pair) {
                    for (seat in 0 until Openings.SEATINGS_PER_OPENING) {
                        val seated = block.count { match -> match.slots.single { it.spec == spec }.seat == seat }
                        require(seated == replications) {
                            "pair ${finalists[one]} vs ${finalists[other]} did not put '$spec' in seat $seat " +
                                "$replications times at $identity"
                        }
                    }
                }
            }
        }
    }
}

private fun residuals(
    specs: List<String>,
    cells: Array<Array<ChampionshipCell?>>,
    ladder: Ladder,
    ladderIndex: Map<String, Int>,
): List<ChampionshipResidual> = buildList {
    for (one in specs.indices) {
        for (other in one + 1 until specs.size) {
            val observed = checkNotNull(cells[one][other]).score
            val expected = ladder.ratings.expectedScore(
                ladderIndex.getValue(specs[one]),
                ladderIndex.getValue(specs[other]),
            )
            val residual = observed - expected
            if (abs(residual) >= NOTABLE_RESIDUAL) {
                add(
                    if (residual >= 0.0) {
                        ChampionshipResidual(specs[one], specs[other], observed, expected)
                    } else {
                        ChampionshipResidual(specs[other], specs[one], 1.0 - observed, 1.0 - expected)
                    },
                )
            }
        }
    }
}.sortedByDescending { it.difference }

private fun openingIdentity(index: Int): String = "empty8-rho-${index.toString().padStart(2, '0')}"

internal const val PRACTICAL_BAND: Double = 0.05
internal const val INCUMBENT_THRESHOLD: Double = 0.50
internal const val NOTABLE_RESIDUAL: Double = 0.05

private const val FORFEIT = "FORFEIT"
private const val EPSILON = 1e-12
