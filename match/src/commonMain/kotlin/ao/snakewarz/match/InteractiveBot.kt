package ao.snakewarz.match

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Direction

/**
 * A human player, wearing the same interface as every search bot.
 *
 * There is no separate human code path anywhere in the driver: a person is a slot that happens to
 * be slow at deciding, which is what [Decision.Pending] is for. `Match.step` parks on
 * `StepResult.AwaitingInput` without consuming a turn, the renderer keeps painting, and the next
 * frame asks again. That is also why `Bot.chooseMove` can stay synchronous — the polling lives in
 * the driver rather than in a suspend function nobody could call from inside an MCTS rollout.
 *
 * Under replay this bot is never constructed: `Match.playback` substitutes a scripted stand-in for
 * every slot, so `Pending` cannot appear on a deterministic path.
 */
public class InteractiveBot(
    private val buffer: InputBuffer,
    private val stallPolicy: StallPolicy = StallPolicy.WAIT_FOR_INPUT,
) : Bot {
    /** The one bot in the project that may answer [Decision.Pending]. */
    override val interactive: Boolean get() = true

    override fun chooseMove(turn: Turn): Decision {
        val chosen = buffer.take(turn.legalMoves)
        if (chosen != null) {
            return Decision.Move(chosen)
        }

        // Trapped, so there is no key left that would help: [InputBuffer.take] filters illegal
        // input, and under [StallPolicy.WAIT_FOR_INPUT] waiting for one that can never come would
        // park the match for good. Every direction from here is the same death, which the engine
        // records as `TRAPPED` whichever is played — this is a move in the sense that a snake has
        // to make one, not a choice.
        if (turn.legalMoves.isEmpty) {
            return Decision.Move(turn.me.lastDirection ?: Direction.NORTH)
        }

        if (stallPolicy == StallPolicy.WAIT_FOR_INPUT) {
            return Decision.Pending
        }

        // Sustains a heading the player picked; never invents one. Before the first move there is
        // nothing to continue, so waiting is the honest answer even under CONTINUE_STRAIGHT.
        val heading = turn.me.lastDirection ?: return Decision.Pending
        return Decision.Move(heading)
    }

    override fun toString(): String = "InteractiveBot($stallPolicy)"
}
