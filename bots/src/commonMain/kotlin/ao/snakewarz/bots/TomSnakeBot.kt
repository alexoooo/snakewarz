package ao.snakewarz.bots

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotKnob
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn

/**
 * Plays [PressureBot] one turn in five and [RandomBot] the rest. A semantic port of `TomSnakeAi`,
 * contributed to the original 2005 project (`legacy/java/ao/ai/da/TomSnakeAi.java`).
 *
 * The mixture is the bot, and it is honestly weak: four turns in five throw away the appraisal the
 * fifth one paid for, so it lands a little above [RandomBot] and a long way below [PressureBot]. It
 * is here because it is what was contributed, and because a stochastic mixture of a cheap heuristic
 * and a cheaper one is a real design — just not at this ratio.
 *
 * Directly above the random branch, legacy has `new UctAi(256)` commented out. That is evidently
 * what the author wanted — a search bot with a heuristic mixed in — and presumably could not afford,
 * since legacy's UCT allocated a whole persistent game per rollout. It is recorded here rather than
 * shipped: a bot the contributed file never ran is a different bot. Mixing [UctBot] in at this
 * ratio would be worth measuring in its own right.
 *
 * Costs no budget: both delegates are budget-free, so an allowance of zero is spent exactly.
 *
 * ### Notes on the port
 *
 * `Rand.nextBoolean(p)` has no counterpart on `Rng` and needs none — `nextDouble() < forkShare` is
 * the same draw. The coin, [PressureBot]'s tie-break and [RandomBot]'s pick all come off the one
 * `setup.rng`, because that is one slot and one stream.
 *
 * There is no `legalMoves.isEmpty` guard here, which is unusual enough to say out loud: both
 * delegates already have one, and a third would only change which draw the stream is on.
 *
 * Five legacy defects are not reproduced:
 *
 * - Three separate draws per turn from the global `Rand` — the mixture coin, `RandomAi`'s pick, and
 *   `ForkPathAi`'s `Math.random()` tie-break. No bot here may reach a generator it was not handed.
 * - `new ForkPathAi()` and `new RandomAi()` were constructed **per turn**, which allocated per turn
 *   and stepped `RandomAi`'s `static int nextId` on every turn of every match. A static counter is
 *   exactly what `BotContractTest`'s cross-match state check exists to catch. The delegates here are
 *   built once, in the constructor, as [ChaseBot] builds its own.
 * - `private Direction lastDir` was dead: written and read only inside a commented-out block.
 * - The inherited `PvpAi` reduction, which was live in this bot alone and picked the **walled-off**
 *   opponent every time — `AStar.pathBetween` returns an empty list for an unreachable target and
 *   `PvpAi` read its size, so `0` beat every real distance. It also narrowed `ForkPathAi` to
 *   `singletonList(opp)`, which that bot never wanted; [PressureBot] takes the mean over every
 *   living opponent, as `ForkPathAi` did before the narrowing.
 * - Along with `ForkPathAi`'s own four, all fixed inside [PressureBot].
 *
 * One number in the legacy source is a red herring: the commented `if (Rand.nextBoolean(9.0/10))`
 * on the `else`. The branch is exhaustive, so it never ran — 0.2 is the whole of the ratio.
 */
public class TomSnakeBot(setup: BotSetup) : Bot {
    private val rng = setup.rng
    private val pressure = PressureBot(setup)
    private val random = RandomBot(setup)
    private val forkShare = FORK_SHARE.read(setup.params)

    override fun chooseMove(turn: Turn): Decision =
        if (rng.nextDouble() < forkShare) {
            pressure.chooseMove(turn)
        } else {
            random.chooseMove(turn)
        }

    override fun toString(): String = "TomSnakeBot"

    internal companion object {
        /** Legacy's `Rand.nextBoolean(2.0/10)` at `TomSnakeAi.java:34`. */
        val FORK_SHARE = BotKnob.Decimal(
            name = "forkShare",
            label = "Pressure share",
            help = "How often it plays Pressure instead of Random. 1 is Pressure every turn.",
            default = 0.2,
            min = 0.0,
            max = 1.0,
            step = 0.05,
        )

        val KNOBS: List<BotKnob> = listOf(FORK_SHARE)
    }
}
