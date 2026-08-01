package ao.snakewarz.lab.allowance

import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentConfig

/** One edge of the deliberately bipartite allowance-curve schedule. */
internal class AllowanceCurvePairing(
    val variant: Contestant,
    val opponent: Contestant,
    val config: TournamentConfig,
)

/**
 * The matches an allowance curve is permitted to buy.
 *
 * Variants are one bot with one parameterization at explicit fixed per-turn allowances. The panel
 * is disjoint, so the Cartesian product below cannot contain a curve-versus-curve or
 * panel-versus-panel game. Each edge is its own two-contestant complete-opening run; this avoids
 * paying for the irrelevant edges a round robin would add.
 */
internal class AllowanceCurvePlan(
    variants: List<Contestant>,
    panel: List<Contestant>,
    val replications: Int,
    val seed: Long,
    val threads: Int,
) {
    init {
        require(variants.size >= MINIMUM_VARIANTS) {
            "an allowance curve needs at least $MINIMUM_VARIANTS variants, was ${variants.size}"
        }
        require(panel.isNotEmpty()) { "an allowance curve needs at least one fixed-panel opponent" }
        require(replications > 0) { "replications must be positive, was $replications" }
        require(replications <= Int.MAX_VALUE / Openings.COMPLETE_ROUNDS_PER_REPLICATION) {
            "replications are too large, was $replications"
        }
        require(threads > 0) { "threads must be positive, was $threads" }
        val requestedMatches =
            variants.size.toLong() * panel.size * replications * Openings.COMPLETE_ROUNDS_PER_REPLICATION
        require(requestedMatches <= Int.MAX_VALUE) { "allowance schedule is too large: $requestedMatches matches" }
        require(variants.distinct().size == variants.size) { "allowance variants must be distinct" }
        require(panel.distinct().size == panel.size) { "fixed-panel entrants must be distinct" }
        require(variants.all { it.budgetPerTurn != null }) {
            "every allowance variant needs an explicit fixed per-turn budget"
        }
        require(panel.all { it.budgetPerTurn != null }) {
            "every fixed-panel entrant needs an explicit fixed per-turn budget"
        }
        require(variants.map { it.budgetPerTurn }.distinct().size == variants.size) {
            "an allowance curve needs one distinct fixed budget per variant"
        }

        val family = variants.first()
        require(variants.all { it.bot == family.bot && it.params == family.params }) {
            "allowance variants must differ only in their fixed per-turn budget"
        }
        require(variants.none { it in panel }) {
            "allowance variants and the fixed panel must be disjoint"
        }
    }

    val variants: List<Contestant> = variants.toList()
    val panel: List<Contestant> = panel.toList()
    val rounds: Int = replications * Openings.COMPLETE_ROUNDS_PER_REPLICATION

    val pairings: List<AllowanceCurvePairing> = buildList(variants.size * panel.size) {
        for (variant in variants) {
            for (opponent in panel) {
                add(
                    AllowanceCurvePairing(
                        variant = variant,
                        opponent = opponent,
                        config = TournamentConfig(
                            contestants = listOf(variant, opponent),
                            rows = Openings.COMPLETE_ROWS,
                            cols = Openings.COMPLETE_COLS,
                            rounds = rounds,
                            seed = seed,
                            budgetPerTurn = FIXED_ALLOWANCE_FALLBACK,
                        ),
                    ),
                )
            }
        }
    }

    val matchCount: Int = pairings.size * rounds

    private companion object {
        const val MINIMUM_VARIANTS = 2

        /** Unreachable because every contestant carries its own allowance; makes omissions obvious. */
        const val FIXED_ALLOWANCE_FALLBACK = 0
    }
}
