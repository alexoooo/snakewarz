package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.bots.reactive.space.SpaceBot
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.bots.search.uct.UctBot
import ao.snakewarz.bots.search.uct.portableLog
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId

/**
 * PUCT with a hand-written evaluation where AlphaZero has a neural network.
 *
 * The same shape as [UctBot] — descend, expand, judge, credit — with the two things a network would
 * supply written out by hand instead. The **policy** becomes a proportional prior over how much room
 * each move leads into ([priorsInto]), and the **value** becomes a [LeafEval] that appraises the
 * position rather than playing it out ([TerritoryEval]).
 *
 * ### The evaluation is the experiment, so everything else is held still
 *
 * The interesting claim about a hand-written evaluation is not that it works but that it is worth
 * what it costs, and that is a comparison. So `eval` is a knob rather than a decision, and its three
 * values are the same search told to think three prices' worth: [MobilityEval] reads sixteen squares
 * and buys about a hundred times the tree, [TerritoryEval] sweeps the board once, [SurvivalEval]
 * sweeps it and then works out how much of each region its owner could actually use. Two entrants
 * differing only in that line is what makes a batch over them mean anything.
 *
 * `uct` is the control this bot is read against, and it is a real one: a tree with a random rollout
 * where this has an appraisal. There was once an `eval=rollout` that put the same rollout behind
 * *this* tree, and it is gone — it said nothing `uct` was not already saying, and it made the knob
 * offer a setting nobody would pick to play against.
 *
 * ### Every evaluation pays the same, whatever it does
 *
 * An allowance is counted in evaluations, and this is the bot where that matters: `eval=survival`
 * takes a whole board apart and `eval=mobility` reads sixteen squares, and both are one iteration of
 * the same search. So the charge is [LeafEval.cost], paid by asking for the iteration's playout
 * before descending, and the three settings differ in what they buy per unit rather than in how many
 * units they can smuggle. What the units are *worth* against each other is [EvaluationCost], where
 * the calibration is openly still to do.
 *
 * ### No logarithm
 *
 * PUCT is `Q + c·P·sqrt(N)/(1+n)`, and `sqrt` is exactly specified by IEEE-754. So the standing rule
 * that nothing in `:bots` may call `kotlin.math.ln` binds this bot without costing it anything —
 * see [PuctTree.selectPuct], and [portableLog] for what UCB1 has to do instead.
 *
 * **Handed no allowance it costs exactly nothing**, exactly as [UctBot]: the loop is guarded on
 * `budget.remaining` and the answer then comes from [SpaceBot]'s flood fill, which charges nothing.
 */
public class PuctBot(setup: BotSetup) : Bot {
    private val unbudgeted = SpaceBot(setup)
    private val slotCount = setup.opponentCount + 1

    private val exploration = CPUCT.read(setup.params)
    private val tree = PuctTree()
    private val path = IntArray(MAX_DEPTH)

    /** One value per slot, refilled at every leaf and read back by [PuctTree.record]. */
    private val values = DoubleArray(slotCount)

    /** One node's four priors, refilled at every expansion and copied out by [PuctTree.open]. */
    private val priors = DoubleArray(Direction.entries.size)

    private val directions = Direction.entries

    private val eval: LeafEval = when (EVAL.read(setup.params)) {
        MOBILITY -> MobilityEval(slotCount)

        SURVIVAL -> SurvivalEval(
            setup.grid,
            slotCount,
            TERRITORY_WEIGHT.read(setup.params),
            MOBILITY_WEIGHT.read(setup.params),
            TRAP_PENALTY.read(setup.params),
            SEPARATION_BONUS.read(setup.params),
        )

        // `else` rather than a third named branch: BotKnob.Choice.read is total, so a value from a
        // mangled `#r=` fragment arrives here, and this is a field initializer with nothing above it
        // to catch a throw.
        else -> TerritoryEval(
            setup.grid,
            slotCount,
            TERRITORY_WEIGHT.read(setup.params),
            MOBILITY_WEIGHT.read(setup.params),
            TRAP_PENALTY.read(setup.params),
            SEPARATION_BONUS.read(setup.params),
        )
    }

    /** Nodes in the tree after the last decision. Diagnostic only. */
    internal val nodesSearched: Int get() = tree.size

    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        if (legal.isEmpty) {
            return Decision.Move(Direction.NORTH)
        }

        // Searching a forced move spends an allowance that a real choice will want later.
        legal.singleOrNull()?.let { return Decision.Move(it) }

        tree.reset()

        // Unconditionally, allowance or no allowance. An unopened node's edges are -1, and
        // DirectionSet(-1) reports a size of 32 — so both bestMoveAtRoot and selectPuct would walk
        // off the end of Direction.entries. Putting this behind the budget guard would make the
        // zero-allowance path the one that crashes, which is the path a contract test takes.
        priorsInto(turn.board, turn.self, legal)
        tree.open(PuctTree.ROOT, legal, priors)

        while (iterate(turn)) {
            // The only exit is inside iterate(): the allowance would not stretch to another
            // evaluation.
        }

        val best = tree.bestMoveAtRoot()
        return if (best != null) Decision.Move(best) else unbudgeted.chooseMove(turn)
    }

    override fun toString(): String = "PuctBot($eval)"

    /**
     * One descend-judge-credit pass. Returns `false` when the search should stop.
     *
     * The evaluation is paid for on the first line, before anything is known about where the descent
     * will land — which is also what says whether there is one left to run. So every pass past that
     * line produces a value and is credited, whether it ended in a real outcome or in a judgement.
     *
     * The moves this applies are unwound by the next call's `playout()`, which resets the arena from
     * the live board — so `Playout.undo` is never called here, for [UctBot]'s reason: a reset is one
     * array copy, and it makes an off-by-one unwind impossible rather than merely unlikely.
     */
    private fun iterate(turn: Turn): Boolean {
        val playout = turn.scratch.playout(eval.cost)
        if (playout.outcome != null) {
            // Over before a move is made: the allowance would not stretch to another evaluation of
            // this kind, so there is nothing left to run.
            return false
        }

        var node = PuctTree.ROOT
        var depth = 0
        path[depth++] = node

        while (true) {
            val mover = playout.toAct
            val direction = tree.selectPuct(node, exploration, FIRST_PLAY)
            playout.advance(direction)

            // Re-read after every advance, never carried over: advancing on a stale reading is an
            // IllegalStateException, and any move can be the one that ends the game.
            val result = playout.outcome

            val child = tree.childOrCreate(node, direction, mover)
            if (child != PuctTree.NO_NODE && depth < path.size) {
                path[depth++] = child
            }

            if (result != null) {
                // A game that really ended is worth more than a judgement of it.
                outcomeValues(result, slotCount, values)
                break
            }

            if (child == PuctTree.NO_NODE || depth == path.size) {
                // The pool or the path array is full. Judge from here rather than deepening.
                eval.valuesInto(playout, values)
                break
            }

            if (!tree.isOpen(child)) {
                val next = playout.toAct
                val moves = playout.board.legalMoves(next)
                priorsInto(playout.board, next, moves)
                tree.open(child, moves, priors)

                eval.valuesInto(playout, values)
                break
            }

            node = child
        }

        for (i in 0 until depth) {
            tree.record(path[i], values)
        }
        return true
    }

    /**
     * P(s,a), normalised over [legal] and written into [priors] by [Direction.ordinal].
     *
     * **No softmax**, because nothing in `:bots` may call `exp` — and that is a feature rather than a
     * concession. A proportional prior over a small positive score is bounded, monotone, and has no
     * temperature to tune, so there is one fewer unmeasured constant in a bot that already has four.
     *
     * The feature is the destination square's *own* liberties, which is the cheapest question that
     * distinguishes a move into the open from a move into a pocket — sixteen board reads for the
     * whole node, paid once when it is expanded rather than once per iteration. [PRIOR_FLOOR] keeps
     * every score positive: a move whose prior was zero would score exactly [FIRST_PLAY] forever and
     * be frozen out of the search no matter how the position developed.
     */
    private fun priorsInto(board: BoardView, mover: SnakeId, legal: DirectionSet) {
        if (legal.isEmpty) {
            // PuctTree.open owns the trapped case: one edge, and the whole of the prior on it.
            return
        }

        val grid = board.grid
        val head = board.snake(mover).head
        var total = 0.0

        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val destination = grid.step(head, direction)

            var liberties = 0
            for (j in directions.indices) {
                if (board.isFree(grid.step(destination, directions[j]))) {
                    liberties++
                }
            }

            val score = PRIOR_FLOOR + PRIOR_LIBERTY * liberties
            priors[direction.ordinal] = score
            total += score
        }

        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            priors[ordinal] = priors[ordinal] / total
        }
    }

    internal companion object {
        /** The same range [UctBot] declares, so the two are comparable at the same numbers. */
        val SEARCH = BotKnob.Search(min = 0, max = 10_000, step = 100)

        /**
         * The experiment, and therefore the tradeoff — three value functions rather than three
         * settings of one. Each buys a different tree at the same allowance: [MOBILITY] is near-free
         * and buys about a hundred times the search, [TERRITORY] sweeps the board once, [SURVIVAL]
         * takes the sweep apart again. There is no ordering between them that holds on every board.
         *
         * **This knob used to offer `expert`, and [TERRITORY] is that same evaluation renamed.** A
         * `Choice` value is frozen by SW-05 because it travels in a replay URL, and the rename is
         * defensible only because [BotKnob.Choice.read] is total: `eval=expert` is not in [values],
         * so it falls through to [default], and [default] is the evaluation it named. That holds for
         * exactly as long as [TERRITORY] stays the default — moving it is what would make an old
         * link mean something new, so move it only deliberately and say so here.
         */
        val EVAL = BotKnob.Choice(
            name = "eval",
            label = "Evaluation",
            help = "How a leaf is judged: liberties, a share of the board, or how long each snake can last.",
            default = TERRITORY,
            values = listOf(TERRITORY, MOBILITY, SURVIVAL),
            tradeoff = true,
        )

        /**
         * PUCT's constant — a **multiplier**, where `UctBot.EXPLORATION` is a divisor.
         *
         * Higher explores more here and less there. They are not named the same thing for exactly
         * that reason, and unifying them would invert one of the two without anything noticing.
         *
         * ### `1.5` has been swept twice and survived both, which is the useful part
         *
         * | sweep | search settled on | confirmed on fresh boards |
         * |---|---|---|
         * | `tune puct --knobs cpuct --budget 400` | `0.5` | `+73` over 280 — but see below |
         * | `tune puct --knobs cpuct --budget 1000` | `2.2` | **`-19`, NOT CONFIRMED** over 800 |
         *
         * Two separate lessons, and the second is the one worth carrying.
         *
         * **A knob tuned at one allowance is tuned at that allowance.** The `0.5` from the 400-budget
         * sweep confirmed cleanly there and measures `-19 ±23` at the shipped 1000. An exploration
         * constant trades against how deep the tree gets, and that trade moves with the allowance, so
         * a sweep is only ever an answer about the budget it ran at. Re-run at the shipped one.
         *
         * **The second sweep found nothing, and finding nothing took a confirming run to establish.**
         * At the shipped allowance the search accepted `2.3` at `+112` Elo over 80 boards and then
         * `2.2` at `+127` over 40; both were noise, and the disjoint-seed re-run at the finer bound is
         * what said so. A coordinate descent that accepts on a lucky block sends the whole descent off
         * in that direction, which is precisely why the recommendation carries the confirming number
         * and nothing else. Leave `1.5` alone unless something beats it *there*.
         */
        val CPUCT = BotKnob.Decimal(
            name = "cpuct",
            label = "Exploration",
            help = "PUCT's constant. Higher tries more moves; lower digs into the best one.",
            default = 1.5,
            min = 0.1,
            max = 10.0,
            step = 0.1,
        )

        val TERRITORY_WEIGHT = BotKnob.Decimal(
            name = "territoryWeight",
            label = "Territory",
            help = "How much a share of the reachable board is worth while it is still contested.",
            default = 1.0,
            min = 0.0,
            max = 1.0,
            step = 0.05,
        )

        val MOBILITY_WEIGHT = BotKnob.Decimal(
            name = "mobilityWeight",
            label = "Mobility",
            help = "How much having more ways out than average is worth.",
            default = 0.2,
            min = 0.0,
            max = 1.0,
            step = 0.05,
        )

        val TRAP_PENALTY = BotKnob.Decimal(
            name = "trapPenalty",
            label = "Trapped",
            help = "Taken off a snake with nothing legal left, whatever else it has going for it.",
            default = 0.35,
            min = 0.0,
            max = 1.0,
            step = 0.05,
        )

        val SEPARATION_BONUS = BotKnob.Decimal(
            name = "separationBonus",
            label = "Separated",
            help = "How near to decided a position reads once the snakes can no longer reach each other.",
            default = 0.9,
            min = 0.0,
            max = 1.0,
            step = 0.05,
        )

        /**
         * Everything this bot lets you tune, in the order a form would show it.
         *
         * The sidebar shows the first two — see [ao.snakewarz.botapi.registry.BotEntry.offered]. The other
         * five are the ablation, and the ablation is a `:lab` batch rather than a form: [CPUCT] has
         * an optimum a sweep finds, and the four weights do nothing at all at [MOBILITY], so four of
         * the seven rows used to be dead most of the time they were on screen.
         *
         * [TerritoryEval] and [SurvivalEval] read the same four, with the same meanings — a share of
         * the board and a share of what can be filled are the same kind of quantity, so a weight
         * swept against one transfers. That is also why adding [SurvivalEval] added no knob.
         */
        val KNOBS: List<BotKnob> =
            listOf(SEARCH, EVAL, CPUCT, TERRITORY_WEIGHT, MOBILITY_WEIGHT, TRAP_PENALTY, SEPARATION_BONUS)

        /** [TerritoryEval] — a share of the board off one sweep. Released as `expert`; see [EVAL]. */
        const val TERRITORY: String = "territory"

        /** [MobilityEval] — nearly free, so the allowance buys a far bigger tree. */
        const val MOBILITY: String = "mobility"

        /** [SurvivalEval] — what each snake could still use, rather than what it can merely see. */
        const val SURVIVAL: String = "survival"

        /**
         * `Q` for a child nobody has visited. Half is "unknown" on a `0..1` scale.
         *
         * Not a knob, because there is nothing yet to say about sweeping it. Promote it if there is.
         */
        const val FIRST_PLAY: Double = 0.5

        /** Kept above zero so no move can be frozen out of the search entirely. */
        const val PRIOR_FLOOR: Double = 1.0

        /** What one liberty at the destination adds, against [PRIOR_FLOOR]. */
        const val PRIOR_LIBERTY: Double = 0.5

        /** Deeper than the tree can grow at any sane allowance — [UctBot]'s figure, for its reasons. */
        const val MAX_DEPTH: Int = 512
    }
}
