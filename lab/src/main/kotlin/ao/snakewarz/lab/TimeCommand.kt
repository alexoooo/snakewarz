package ao.snakewarz.lab

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.tournament.Contestant
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * What a turn of one bot costs, measured against an opponent handed **no allowance at all**.
 *
 * That is what makes the number about one bot: [SPARRING_PARTNER] is the strongest thing in the
 * registry that spends nothing, so it plays a real game and contributes no search time to the clock.
 * The fastest of [passes] is reported rather than the mean, following `RolloutTruncationTest` — every
 * source of noise on a wall clock only ever adds time, so the minimum is the closest thing to the
 * figure being asked for.
 */
internal class TimeCommand(
    val subject: Contestant,
    val rows: Int,
    val cols: Int,
    val seed: Long,
    val budgetPerTurn: Int,
    val walls: IntArray,
    val passes: Int,
) : LabCommand {
    override fun run(registry: BotRegistry, log: (String) -> Unit) {
        val allowance = subject.budgetIn(budgetPerTurn)
        log(
            "[lab] $subject on ${rows}x$cols with ${walls.size} walls at an allowance of " +
                "$allowance, best of $passes",
        )

        val setup = MatchSetup.create(
            rows = rows,
            cols = cols,
            slots = listOf(subject.bot, SPARRING_PARTNER),
            seed = seed,
            budgetPerTurn = budgetPerTurn,
            walls = walls,
            budgets = intArrayOf(allowance, 0),
            slotParams = listOf(subject.params, BotParams.EMPTY),
        )

        var best = Duration.INFINITE
        var turns = 0
        repeat(passes) {
            val match = Match(setup, registry)
            val mark = TimeSource.Monotonic.markNow()
            match.runToCompletion()
            val elapsed = mark.elapsedNow()

            turns = match.stats().slots[0].movesMade
            if (elapsed < best) {
                best = elapsed
            }
        }

        if (turns == 0) {
            log("[lab] ${subject.label} never got a turn, so there is nothing to time")
            return
        }
        log("[lab] ${subject.label}: ${best.inWholeMicroseconds / turns} us/turn over $turns turns")
    }

    override fun toString(): String =
        "Time($subject, ${rows}x$cols, ${walls.size} walls, budget=$budgetPerTurn, passes=$passes)"

    private companion object {
        /** Strongest of the bots that spend nothing, so it plays a real game and costs no clock. */
        val SPARRING_PARTNER = BotId("space")
    }
}
