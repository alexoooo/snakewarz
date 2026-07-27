package ao.snakewarz.bots.reactive.chase

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.bots.reactive.space.PressureBot
import ao.snakewarz.core.grid.Direction

/**
 * Walks the shortest path to the nearest opponent, and stops walking once it is on top of them. A
 * semantic port of legacy `PathAi`, over `PvpAi`'s nearest-opponent reduction.
 *
 * Chasing is not, on its own, a good idea — a snake that only closes distance walks into its own
 * corridor. What makes it work is the hand-off: from [CLOSE_RANGE] squares away the path stops
 * meaning anything and [PressureBot] takes over, which is space-first and only then aggressive. So
 * this bot crosses the board to *reach* a fight and plays the fight on better principles.
 *
 * Costs no budget: one breadth-first sweep, one flood fill per candidate.
 *
 * Two notes on the port. Legacy's `path == null` branch, which was supposed to fall back to
 * `WallHugAi`, was dead — `AStar.pathBetween` returns an empty list rather than null, and an empty
 * path has size 0, which is under its threshold and went to `ForkPathAi` anyway. So the fallback
 * here *is* [PressureBot], matching what legacy actually did rather than what it appeared to say.
 * And the path sweep deliberately does **not** model this snake's own retracting tail: legality is
 * evaluated before the tail retracts, so a route that started by stepping into it would name a move
 * the engine will not accept. One square makes no difference to a distance and every difference to
 * a legality check.
 */
public class ChaseBot(setup: BotSetup) : Bot {
    private val self = setup.self
    private val paths = ShortestPaths(setup.grid)
    private val closeQuarters = PressureBot(setup)
    private val closeRange = CLOSE_RANGE.read(setup.params)

    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        if (legal.isEmpty) {
            return Decision.Move(Direction.NORTH)
        }

        val board = turn.board
        paths.scanFrom(board, turn.me.head)

        val quarry = nearestOpponent(board, self, paths)
        if (quarry.isNone) {
            return closeQuarters.chooseMove(turn)
        }

        val theirHead = board.snake(quarry).head
        if (paths.distanceBeside(theirHead) < closeRange) {
            return closeQuarters.chooseMove(turn)
        }

        val step = paths.firstStepBeside(theirHead)
        return if (step != null && step in legal) {
            Decision.Move(step)
        } else {
            closeQuarters.chooseMove(turn)
        }
    }

    override fun toString(): String = "ChaseBot"

    internal companion object {
        /**
         * Legacy's `path.size() < 3`, which counted cells including the start — the same number
         * `ShortestPaths.distanceBeside` reports.
         */
        val CLOSE_RANGE = BotKnob.Integer(
            name = "closeRange",
            label = "Close range",
            help = "How near an opponent has to be before pressure takes over from the chase.",
            default = 3,
            min = 1,
            max = 64,
        )

        val KNOBS: List<BotKnob> = listOf(CLOSE_RANGE)
    }
}
