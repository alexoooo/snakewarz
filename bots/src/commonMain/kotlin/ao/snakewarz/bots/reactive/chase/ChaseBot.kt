package ao.snakewarz.bots.reactive.chase

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.bots.reactive.space.PressureBot
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet

/**
 * Walks the shortest path to the nearest opponent, and stops walking once it is on top of them. A
 * semantic port of legacy `PathAi`, over `PvpAi`'s nearest-opponent reduction.
 *
 * Chasing is not, on its own, a good idea — a snake that only closes distance walks into its own
 * corridor. What makes it work is the hand-off: from [CLOSE_RANGE] squares away the path stops
 * meaning anything and [PressureBot] takes over, which is space-first and only then aggressive. So
 * this bot crosses the board to *reach* a fight and plays the fight on better principles.
 *
 * That hand-off alone is not enough, and a measurement says where it falls short: over a thousand
 * matches against each of the weak field, **every single loss was `TRAPPED` at around 40% board
 * fill** — the board still open and no free square left, which is the corridor its own description
 * predicts. None of them were blunders. The trap is sprung during the *approach*, while the quarry
 * is still far away and the close-range hand-off has no reason to fire yet. So the walk is guarded
 * as well as handed off: [ROOM_SHARE] refuses a chase step that leaves materially less room than the
 * best legal move would, and pressure plays the turn instead. The guard is scale-free on purpose —
 * "you had a way out and did not take it" is the mistake, and that is a comparison rather than a
 * threshold.
 *
 * **A head-to-head test cannot see that guard, and `ab` says so out loud.** Guarded against stock
 * over 260 boards it measures `1 Elo +-3`; against a field it is worth `+14`. Both are right. The
 * pocket that springs the trap is one *this bot's own approach* walks into, so an opponent playing
 * the same approach is in the same corridor at the same moment and the guard changes almost nothing
 * between them. Anything whose value is "does not lose games it should not" has to be measured
 * against opponents that would not have lost them either — which is `play` over a field and `rate`,
 * not `ab`. [ROOM_SHARE] carries that table.
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
    private val fill = FloodFill(setup.grid)
    private val closeQuarters = PressureBot(setup)
    private val closeRange = CLOSE_RANGE.read(setup.params)
    private val roomShare = ROOM_SHARE.read(setup.params)

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
        return if (step != null && step in legal && keepsRoom(turn, legal, step)) {
            Decision.Move(step)
        } else {
            closeQuarters.chooseMove(turn)
        }
    }

    /**
     * Whether [step] leaves at least [ROOM_SHARE] of the room the roomiest legal move would.
     *
     * The fill is the same measurement [PressureBot] ranks by, taken here for a different question:
     * not "which move is roomiest" but "is the one the path wants bad enough to overrule the path".
     * At the default share of `0` every move passes, so the guard costs a fill per legal direction
     * and changes nothing until somebody sets it — which is how it was introduced, and the number
     * that settled it is on [ROOM_SHARE].
     */
    private fun keepsRoom(turn: Turn, legal: DirectionSet, step: Direction): Boolean {
        val me = turn.me
        val board = turn.board
        val grid = board.grid
        val vacating = if (me.growsOnNextMove) Cell.NONE else me.tail

        var chosen = 0
        var best = 0
        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val room = fill.reachable(board, grid.step(me.head, direction), vacating)
            if (room > best) {
                best = room
            }
            if (direction == step) {
                chosen = room
            }
        }

        return chosen >= best * roomShare
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

        /**
         * How much of the roomiest move's room a chase step has to leave before it is worth taking.
         *
         * `0.0` never refuses, which is what this bot did before the guard existed; `1.0` chases
         * only when the path already agrees with the roomiest move, which is barely a chase at all.
         *
         * The default is a measurement rather than a preference, and it was taken **against a field
         * rather than head to head** — see [ChaseBot]'s note on why an `ab` run cannot see this. Over
         * 39,600 matches on 12x12 against the six reactive bots, rated by `rate`, 6,600 games each:
         *
         * | roomShare | rating | 95% |
         * |---|---|---|
         * | 0.6 | 163 | +156..+169 |
         * | 0.45 | 161 | +155..+168 |
         * | 0.8 | 160 | +154..+166 |
         * | 0.3 | 160 | +154..+165 |
         * | 0.15 | 158 | +152..+164 |
         * | **0.0 (was)** | **147** | **+141..+153** |
         *
         * Everything from `0.15` to `0.8` is one flat plateau and the whole of it clears the old
         * behaviour with no interval overlap. `0.5` is the middle of that plateau rather than its
         * peak, chosen so a different board size moves the best value without moving off it.
         * Re-run with `:lab:run --args="play chase chase:roomShare=0.3 ... --rounds 600"` and `rate`.
         */
        val ROOM_SHARE = BotKnob.Decimal(
            name = "roomShare",
            label = "Room share",
            help = "A chase step must leave at least this much of the roomiest move's room.",
            default = 0.5,
            min = 0.0,
            max = 1.0,
            step = 0.05,
        )

        /**
         * Its own two, and the ones the [PressureBot] it delegates to reads from the same params.
         *
         * That bot is constructed with **this** bot's `setup`, so `adjacencyFloor` and
         * `adjacencyPenalty` have always been live inside a chase — a replay URL carrying one would
         * take effect. Leaving them undeclared meant the sidebar could not offer them and `:lab`
         * refused them as unknown, so the two knobs that decide how this bot plays every fight it
         * reaches were the only ones nothing could tune. Declaring them changes no default.
         */
        val KNOBS: List<BotKnob> = listOf(CLOSE_RANGE, ROOM_SHARE) + PressureBot.KNOBS
    }
}
