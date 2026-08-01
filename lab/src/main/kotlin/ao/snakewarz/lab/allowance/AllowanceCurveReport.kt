package ao.snakewarz.lab.allowance

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.log.EMPTY_MAP
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.lab.strength.Bootstrap
import ao.snakewarz.match.tournament.TournamentFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/** A shared-complete-opening bootstrap interval over score share. */
internal data class AllowanceCurveInterval(
    val low: Double,
    val high: Double,
)

/** One candidate allowance against one member of the fixed opponent panel. */
internal data class AllowanceCurveCell(
    val variant: String,
    val opponent: String,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val score: Double,
    val interval: AllowanceCurveInterval,
    val distinctGames: Int,
) {
    val played: Int get() = wins + draws + losses
}

/** One fixed per-turn allowance, reduced to its worst fixed-panel opponent. */
internal data class AllowanceCurvePoint(
    val spec: String,
    val allowance: Int,
    val maximin: Double,
    val maximinInterval: AllowanceCurveInterval,
    val worstOpponents: List<String>,
)

/**
 * Complete-opening evidence for one allowance family against one unchanged finalist panel.
 *
 * The variants supplied to this report are expected to have passed their external Chrome lane
 * first. [strongest] therefore selects among eligible fixed points; this instrument does not turn
 * field elapsed time into a cost measurement.
 */
internal class AllowanceCurveReport private constructor(
    val replications: Int,
    val seed: Long,
    val variants: List<AllowanceCurvePoint>,
    val panel: List<String>,
    val cells: List<AllowanceCurveCell>,
    val matchCount: Int,
    val distinctGames: Int,
    val runIds: List<String>,
) {
    val strongest: AllowanceCurvePoint get() = variants.first()

    fun cell(variant: String, opponent: String): AllowanceCurveCell =
        cells.firstOrNull { it.variant == variant && it.opponent == opponent }
            ?: error("no allowance-curve cell for '$variant' against '$opponent'")

    fun lines(): List<String> = renderAllowanceCurve(this)

    companion object {
        /** Validates and analyses the pair runs produced by [AllowanceCurveCommand]. */
        fun of(
            runs: List<RunHeader>,
            matches: List<LoggedMatch>,
            variants: List<AllowanceVariant>,
            panel: List<String>,
            replications: Int,
            seed: Long,
        ): AllowanceCurveReport {
            val evidence = validateEvidence(runs, matches, variants, panel, replications, seed)
            val statistics = allowanceStatistics(evidence, variants, panel)
            val unranked = variants.mapIndexed { index, variant ->
                val row = statistics.cells[index]
                val worst = row.minOf { it.score }
                AllowanceCurvePoint(
                    spec = variant.spec,
                    allowance = variant.allowance,
                    maximin = worst,
                    maximinInterval = statistics.maximinIntervals[index],
                    worstOpponents = row.filter { it.score == worst }.map { it.opponent },
                )
            }
            val ranked = unranked.sortedWith(
                compareByDescending<AllowanceCurvePoint> { it.maximin }
                    .thenBy { it.allowance }
                    .thenBy { it.spec },
            )

            return AllowanceCurveReport(
                replications = replications,
                seed = seed,
                variants = ranked,
                panel = panel.toList(),
                cells = statistics.cells.flatMap { it.toList() },
                matchCount = evidence.size,
                distinctGames = evidence.mapTo(LinkedHashSet()) { it.moveStreamHash }.size,
                runIds = runs.map { it.id },
            )
        }
    }
}

/** Expanded logged identity and the explicit per-turn allowance it carries. */
internal data class AllowanceVariant(
    val spec: String,
    val allowance: Int,
) {
    init {
        require(spec.isNotBlank()) { "an allowance variant needs an expanded spec" }
        require(allowance >= 0) { "an allowance must not be negative, was $allowance" }
    }
}

private class AllowanceStatistics(
    val cells: Array<Array<AllowanceCurveCell>>,
    val maximinIntervals: List<AllowanceCurveInterval>,
)

private class OutcomeCounts {
    var wins: Int = 0
    var draws: Int = 0
    var losses: Int = 0

    val played: Int get() = wins + draws + losses

    val score: Double
        get() {
            check(played > 0) { "an allowance cell has no evidence" }
            return (wins + draws / 2.0) / played
        }

    fun add(other: OutcomeCounts) {
        wins += other.wins
        draws += other.draws
        losses += other.losses
    }
}

private fun validateEvidence(
    runs: List<RunHeader>,
    matches: List<LoggedMatch>,
    variants: List<AllowanceVariant>,
    panel: List<String>,
    replications: Int,
    seed: Long,
): List<LoggedMatch> {
    require(variants.size >= 2) { "an allowance curve needs at least two variants" }
    require(variants.map { it.spec }.distinct().size == variants.size) { "allowance variant specs must be distinct" }
    require(variants.map { it.allowance }.distinct().size == variants.size) {
        "allowance variants must use distinct fixed budgets"
    }
    require(variants.all { expandedBudget(it.spec) == it.allowance }) {
        "each allowance variant must name the fixed budget recorded in its expanded spec"
    }
    require(panel.isNotEmpty() && panel.distinct().size == panel.size) {
        "the fixed panel must be non-empty and distinct"
    }
    require(panel.all { expandedBudget(it) >= 0 }) { "every fixed-panel spec needs an explicit budget" }
    require(variants.none { it.spec in panel }) { "allowance variants and the fixed panel must be disjoint" }
    require(replications > 0 && replications <= Int.MAX_VALUE / Openings.COMPLETE_ROUNDS_PER_REPLICATION) {
        "replications must fit a complete-opening schedule, was $replications"
    }

    val expectedPairs = variants.flatMap { variant -> panel.map { opponent -> listOf(variant.spec, opponent) } }
    require(runs.map { it.contestants } == expectedPairs) {
        "allowance evidence must contain exactly the variant-by-panel runs in declared order"
    }
    require(runs.map { it.id }.distinct().size == runs.size) { "allowance pair runs need distinct run ids" }
    require(runs.map { it.build }.distinct().size == 1) { "allowance pair runs must come from one build" }
    require(runs.map { it.threads }.distinct().size == 1) { "allowance pair runs must use one thread count" }

    val expectedRounds = replications * Openings.COMPLETE_ROUNDS_PER_REPLICATION
    val defaults = RulesConfig()
    val runIds = runs.mapTo(HashSet()) { it.id }
    require(matches.all { it.run in runIds }) { "allowance evidence contains a match from an undeclared run" }

    val validated = ArrayList<LoggedMatch>(runs.size * expectedRounds)
    for ((runIndex, run) in runs.withIndex()) {
        require(run.rows == Openings.COMPLETE_ROWS && run.cols == Openings.COMPLETE_COLS && run.map == EMPTY_MAP) {
            "allowance curves require an empty 8x8; run ${run.id} was ${run.rows}x${run.cols} map ${run.map}"
        }
        require(run.format == TournamentFormat.HEAD_TO_HEAD.name && run.openings == Openings.COMPLETE.name) {
            "allowance curves require head-to-head complete openings in run ${run.id}"
        }
        require(run.rounds == expectedRounds && run.seed == seed) {
            "run ${run.id} needs $expectedRounds rounds from seed $seed, was ${run.rounds} from ${run.seed}"
        }
        require(
            run.growEveryNthMove == defaults.growEveryNthMove &&
                run.maxTurns == defaults.maxTurns &&
                run.lastSnakeMustBeMoving == defaults.lastSnakeMustBeMoving,
        ) {
            "run ${run.id} did not use the default championship rules"
        }
        require(run.budgetPerTurn == 0) {
            "run ${run.id} needs explicit contestant allowances and a zero fallback budget"
        }

        val expected = expectedPairs[runIndex]
        val played = matches.filter { it.run == run.id }.sortedBy { it.index }
        require(played.size == expectedRounds && played.map { it.index } == (0 until expectedRounds).toList()) {
            "run ${run.id} must contain exactly indices 0 until $expectedRounds"
        }
        for (match in played) {
            val group = match.index / Openings.SEATINGS_PER_OPENING
            val expectedOpening = openingIdentity(group % Openings.COMPLETE_POPULATION)
            val expectedSeats = if (match.index % 2 == 0) expected else expected.reversed()
            val expectedContestants = if (match.index % 2 == 0) listOf(0, 1) else listOf(1, 0)

            require(match.pairKey == group && match.seed == seed + group) {
                "run ${run.id} match ${match.index} broke the shared-seed pair schedule"
            }
            require(match.openingIdentity == expectedOpening) {
                "run ${run.id} match ${match.index} expected $expectedOpening, was ${match.openingIdentity}"
            }
            require(match.slots.size == 2 && match.slots.map { it.seat } == listOf(0, 1)) {
                "run ${run.id} match ${match.index} needs seats zero and one exactly once"
            }
            require(match.slots.map { it.spec } == expectedSeats) {
                "run ${run.id} match ${match.index} did not swap the declared pair"
            }
            require(match.slots.map { it.contestant } == expectedContestants) {
                "run ${run.id} match ${match.index} has inconsistent contestant indices"
            }
            require(match.slots.count { it.winner } <= 1) {
                "run ${run.id} match ${match.index} recorded more than one winner"
            }
            require(match.slots.all { it.budget == expandedBudget(it.spec) }) {
                "run ${run.id} match ${match.index} did not grant its recorded fixed allowances"
            }
            require(match.slots.none { it.fate == FORFEIT }) {
                "a forfeit invalidates allowance evidence (${run.id}#${match.index})"
            }
        }
        validated += played
    }
    return validated
}

private fun allowanceStatistics(
    matches: List<LoggedMatch>,
    variants: List<AllowanceVariant>,
    panel: List<String>,
): AllowanceStatistics {
    val variantIndex = variants.withIndex().associate { (index, variant) -> variant.spec to index }
    val opponentIndex = panel.withIndex().associate { (index, spec) -> spec to index }
    val cellCount = variants.size * panel.size
    val point = Array(cellCount) { OutcomeCounts() }
    val blocks = Array(Openings.COMPLETE_POPULATION) { Array(cellCount) { OutcomeCounts() } }
    val hashes = Array(cellCount) { LinkedHashSet<Long>() }

    for (match in matches) {
        val variantSlot = match.slots.single { it.spec in variantIndex }
        val opponentSlot = match.slots.single { it.spec in opponentIndex }
        val variant = variantIndex.getValue(variantSlot.spec)
        val opponent = opponentIndex.getValue(opponentSlot.spec)
        val cell = variant * panel.size + opponent
        val opening = openingIndex(checkNotNull(match.openingIdentity))
        record(point[cell], variantSlot.winner, opponentSlot.winner)
        record(blocks[opening][cell], variantSlot.winner, opponentSlot.winner)
        hashes[cell] += match.moveStreamHash
    }

    val cellSamples = Array(cellCount) { DoubleArray(Bootstrap.DRAWS) }
    val maximinSamples = Array(variants.size) { DoubleArray(Bootstrap.DRAWS) }
    val rng = SplitMix64(ALLOWANCE_BOOTSTRAP_SEED)
    repeat(Bootstrap.DRAWS) { draw ->
        val sampled = Array(cellCount) { OutcomeCounts() }
        repeat(Openings.COMPLETE_POPULATION) {
            val block = blocks[rng.nextInt(Openings.COMPLETE_POPULATION)]
            for (cell in sampled.indices) {
                sampled[cell].add(block[cell])
            }
        }
        for (variant in variants.indices) {
            var minimum = Double.POSITIVE_INFINITY
            for (opponent in panel.indices) {
                val cell = variant * panel.size + opponent
                val score = sampled[cell].score
                cellSamples[cell][draw] = score
                minimum = minOf(minimum, score)
            }
            maximinSamples[variant][draw] = minimum
        }
    }

    val cells = Array(variants.size) { variant ->
        Array(panel.size) { opponent ->
            val cell = variant * panel.size + opponent
            AllowanceCurveCell(
                variant = variants[variant].spec,
                opponent = panel[opponent],
                wins = point[cell].wins,
                draws = point[cell].draws,
                losses = point[cell].losses,
                score = point[cell].score,
                interval = interval(cellSamples[cell]),
                distinctGames = hashes[cell].size,
            )
        }
    }
    return AllowanceStatistics(cells, maximinSamples.map(::interval))
}

private fun record(counts: OutcomeCounts, variantWon: Boolean, opponentWon: Boolean) {
    when {
        variantWon -> counts.wins++
        opponentWon -> counts.losses++
        else -> counts.draws++
    }
}

private fun interval(samples: DoubleArray): AllowanceCurveInterval {
    val sorted = samples.sortedArray()
    return AllowanceCurveInterval(
        low = sorted[percentile(sorted.size, Bootstrap.LOW)],
        high = sorted[percentile(sorted.size, Bootstrap.HIGH)],
    )
}

private fun percentile(size: Int, fraction: Double): Int =
    ((size - 1) * fraction).toInt().coerceIn(0, size - 1)

private fun openingIdentity(index: Int): String = "empty8-rho-${index.toString().padStart(2, '0')}"

private fun openingIndex(identity: String): Int {
    val prefix = "empty8-rho-"
    require(identity.startsWith(prefix)) { "not a complete-opening identity: '$identity'" }
    val index = identity.removePrefix(prefix).toIntOrNull()
        ?: error("not a complete-opening identity: '$identity'")
    require(index in 0 until Openings.COMPLETE_POPULATION) { "not a complete-opening identity: '$identity'" }
    return index
}

private fun expandedBudget(spec: String): Int {
    val fields = spec.substringAfter(':', "").split(',').filter { it.isNotBlank() }
    val budgets = fields.filter { it.substringBefore('=') == BotKnob.Search.NAME }
    require(budgets.size == 1) { "expanded spec '$spec' needs exactly one ${BotKnob.Search.NAME}" }
    val value = budgets.single().substringAfter('=', "").toIntOrNull()
        ?: error("expanded spec '$spec' has an invalid ${BotKnob.Search.NAME}")
    require(value >= 0) { "expanded spec '$spec' has a negative ${BotKnob.Search.NAME}" }
    return value
}

private fun renderAllowanceCurve(report: AllowanceCurveReport): List<String> = buildList {
    add(
        "[allowance] empty 8x8 complete population: ${report.variants.size} fixed points x " +
            "${report.panel.size} panel opponents x ${report.replications} replications",
    )
    add("[allowance] ${report.distinctGames} of ${report.matchCount} matches were distinct games")
    add("[allowance] directed cells (score with shared-opening-block ${Bootstrap.CONFIDENCE} interval)")
    for (variant in report.variants.sortedBy { it.allowance }) {
        for (opponent in report.panel) {
            val cell = report.cell(variant.spec, opponent)
            add(
                "  ${variant.allowance}: ${variant.spec} -> $opponent: ${percent(cell.score)} " +
                    "[${percent(cell.interval.low)}..${percent(cell.interval.high)}] " +
                    "(${cell.wins}-${cell.draws}-${cell.losses}; ${cell.distinctGames}/${cell.played} distinct)",
            )
        }
    }
    add("[allowance] fixed-point order by panel maximin")
    for ((index, point) in report.variants.withIndex()) {
        add(
            "  ${index + 1}. budget ${point.allowance}: maximin ${percent(point.maximin)} " +
                "[${percent(point.maximinInterval.low)}..${percent(point.maximinInterval.high)}], " +
                "worst against ${point.worstOpponents.joinToString()}",
        )
    }
    add(
        "[allowance] strongest eligible fixed point: ${report.strongest.spec} " +
            "at budget ${report.strongest.allowance}",
    )
    add("[allowance] retained pair runs: ${report.runIds.joinToString()}")
}

private fun percent(value: Double): String {
    val tenths = (value * 1_000.0).roundToInt()
    return "${tenths / 10}.${abs(tenths % 10)}%"
}

/** Fixed so rereading one curve cannot move its confidence bounds. */
internal const val ALLOWANCE_BOOTSTRAP_SEED: Long = 20_260_802L

private const val FORFEIT = "FORFEIT"
