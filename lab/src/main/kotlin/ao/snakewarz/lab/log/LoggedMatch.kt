package ao.snakewarz.lab.log

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.lab.arena.MatchReport
import ao.snakewarz.match.tournament.TournamentConfig

/**
 * One match as the log holds it: what the board was, and how each seat came out of it.
 *
 * Read back rather than played, so nothing here is a live object — a slot is its numbers and its
 * expanded spec, and that is deliberate. An analysis over six weeks of batches must not need the
 * bots that produced them to still exist, still be registered, or still behave as they did.
 */
internal class LoggedMatch(
    val run: String,
    val index: Int,
    /** Which matches shared this board — the unit a paired comparison counts in. */
    val pairKey: Int,
    /** Stable complete-population member, or `null` for legacy and sampled-opening runs. */
    val openingIdentity: String? = null,
    val seed: Long,
    /** Seats in the order they acted, which is the confound a mirrored pair exists to cancel. */
    val turnOrder: List<Int>,
    val end: String,
    val turnsPlayed: Int,
    val elapsedMicros: Long,
    val moveStreamHash: Long,
    val slots: List<LoggedSlot>,
) {
    val isDraw: Boolean get() = slots.none { it.winner }

    val winner: LoggedSlot? get() = slots.firstOrNull { it.winner }

    fun of(spec: String): LoggedSlot? = slots.firstOrNull { it.spec == spec }

    override fun toString(): String = "LoggedMatch($run#$index, ${slots.map { it.spec }}, $end)"

    companion object {
        fun of(
            run: String,
            config: TournamentConfig,
            registry: BotRegistry,
            report: MatchReport,
        ): LoggedMatch {
            val stats = report.stats
            return LoggedMatch(
                run = run,
                index = report.index,
                pairKey = report.pairKey,
                openingIdentity = report.openingIdentity,
                seed = report.seed,
                turnOrder = stats.setup.turnOrder().toList(),
                end = stats.outcome?.end?.name.orEmpty(),
                turnsPlayed = stats.turnsPlayed,
                elapsedMicros = report.elapsedMicros,
                moveStreamHash = report.moveStreamHash,
                slots = stats.slots.map { slot ->
                    val contestant = report.seating[slot.slot.index]
                    LoggedSlot(
                        seat = slot.slot.index,
                        contestant = contestant,
                        spec = expandedSpec(config.contestants[contestant], registry, config.budgetPerTurn),
                        budget = stats.setup.budgetFor(slot.slot.index),
                        length = slot.length,
                        movesMade = slot.movesMade,
                        alive = slot.alive,
                        fate = slot.fate?.name.orEmpty(),
                        winner = slot.winner,
                    )
                },
            )
        }
    }
}
