package ao.snakewarz.bots.search.uct

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.scratch.BoardScratch
import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.reactive.space.SpaceBot
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.bots.search.randomPlayout
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.MatchOutcome

/**
 * Monte Carlo tree search with UCB1 — the strongest thing in the box, and the reason this project
 * exists. A rewrite of legacy `UctAi`/`Node`/`BiState` onto [ao.snakewarz.botapi.scratch.Playout] and
 * [UctTree].
 *
 * Each iteration walks down the tree by UCB1, plays the rest of the game out at random, and credits
 * every node it passed through with the result. Repeat until the allowance runs out, then play the
 * move whose child scored best. Nothing about that is new; what makes it fast here is that the
 * engine mutates and unwinds a single arena instead of building a board per node, and that the tree
 * is six flat arrays instead of an object graph.
 *
 * **It searches the real N-player game.** The rewrite was planned around an abstract `DuelBot` that
 * would reduce the field to the nearest opponent and solve a duel, because legacy's `BiState` held
 * exactly two snakes and had no choice. `Playout` sequences however many are alive and rotates the
 * turn order for us, so the reduction buys nothing and costs a third snake's worth of accuracy. The
 * price is that credit assignment has to be per-actor rather than alternating — see [UctTree].
 *
 * **Handed no allowance it costs exactly nothing.** The iteration loop is guarded on
 * `budget.remaining`, and with nothing to spend the answer comes from [SpaceBot]'s flood fill, which
 * charges nothing. So `budgetPerTurn = 0` is not a degenerate case to survive; it is a real playing
 * strength, and the bot degrades toward it smoothly rather than falling off a cliff.
 *
 * Dropped from the legacy version, deliberately: `Reward` (a `double` wrapper allocated per
 * rollout), `Rollout` (folded into the return value), the variance-ceiling UCB1-Tuned branch (its
 * only caller passed the flag off, and a knob that ships off is dead code), and `AdaptiveUct`
 * entirely (it pondered on a background thread, which wasm does not have and determinism would not
 * survive).
 */
public class UctBot(setup: BotSetup) : Bot {
    private val rng = setup.rng
    private val unbudgeted = SpaceBot(setup)
    private val exploration = EXPLORATION.read(setup.params)
    private val tree = UctTree(MAX_NODES.read(setup.params))
    private val path = IntArray(MAX_DEPTH)

    /** Moves a rollout is cut short at, or [ROLLOUT_TO_THE_END] to play every one of them out. */
    private val rolloutDepth = ROLLOUT_DEPTH.read(setup.params)

    /** Allocated only when a rollout is going to be cut short, since that is the only reader. */
    private val space =
        if (rolloutDepth == ROLLOUT_TO_THE_END) null else SpaceOwnership(setup.grid, setup.opponentCount + 1)

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
        tree.open(UctTree.ROOT, legal)

        while (turn.budget.remaining > 0 && iterate(turn)) {
            // Every exit is inside iterate(): the allowance ran out, or a rollout could not finish.
        }

        val best = tree.bestMoveAtRoot()
        return if (best != null) Decision.Move(best) else unbudgeted.chooseMove(turn)
    }

    /**
     * One descend-simulate-credit pass. Returns `false` when the search should stop.
     *
     * The moves this applies are unwound by the next call's `playout()`, which resets the arena from
     * the live board — so [ao.snakewarz.botapi.scratch.Playout.undo] is never called here. A reset is one
     * array copy against a rollout of a hundred-odd moves, and it makes an off-by-one unwind, which
     * would quietly poison every later iteration, impossible rather than merely unlikely.
     */
    private fun iterate(turn: Turn): Boolean {
        val playout = turn.scratch.playout()
        if (playout.outcome != null) {
            return false
        }

        var node = UctTree.ROOT
        var depth = 0
        path[depth++] = node
        var result: MatchOutcome?

        while (true) {
            val mover = playout.toAct
            val direction = tree.selectUcb1(node, rng, exploration)
            playout.advance(direction)

            // Re-read after every advance, never carried over: advancing on a stale reading is an
            // IllegalStateException, and the budget can expire on any move.
            result = playout.outcome

            val child = tree.childOrCreate(node, direction, mover)
            if (child != UctTree.NO_NODE && depth < path.size) {
                path[depth++] = child
            }

            if (result != null) {
                break
            }
            if (child == UctTree.NO_NODE || depth == path.size) {
                // The pool or the path array is full. Simulate from here rather than deepening.
                result = simulate(playout)
                break
            }
            if (!tree.isOpen(child)) {
                tree.open(child, playout.board.legalMoves(playout.toAct))
                result = simulate(playout)
                break
            }

            node = child
        }

        if (result === BoardScratch.EXHAUSTED) {
            // Not a draw -- no information at all. Crediting it would invent a result for whichever
            // line the allowance happened to expire on.
            return false
        }

        for (i in 0 until depth) {
            tree.record(path[i], result)
        }
        return true
    }

    /**
     * One simulation from the leaf, played to the end or cut short and judged.
     *
     * Which it is, is a *measurement* rather than a preference — see [ROLLOUT_DEPTH].
     */
    private fun simulate(playout: Playout): MatchOutcome {
        val judge = space ?: return randomPlayout(playout, rng)
        return truncatedPlayout(playout, rng, rolloutDepth, judge)
    }

    override fun toString(): String = "UctBot"

    internal companion object {
        /**
         * How much of a turn this may spend, and over what range moving it is worth anything.
         *
         * Ten times the shipped allowance at the top, which is around 40 ms a turn in Chrome — slow
         * enough to watch and well short of hanging a frame. `MatchSetup.DEFAULT_BUDGET_PER_TURN`
         * carries the measurements the shipped figure came from, and the argument for the headroom.
         */
        val SEARCH = BotKnob.Search(min = 0, max = 400_000, step = 10_000)

        /**
         * Legacy's `5` at `Node.java:423` — an exploration constant of `sqrt(1/5)`.
         *
         * A *divisor* inside UCB1, so the floor is above zero rather than at it: at zero the term is
         * an infinity and the tree stops choosing.
         *
         * A tradeoff, and the only one this bot has besides its allowance: exploring wide and digging
         * deep are both ways to win, the best setting moves with the board and the opponent, and the
         * two ends play visibly differently at the same budget.
         */
        val EXPLORATION = BotKnob.Decimal(
            name = "exploration",
            label = "Exploration",
            help = "UCB1's constant. Higher tries more moves, lower digs deeper into the best one.",
            default = 5.0,
            min = 0.1,
            max = 100.0,
            step = 0.1,
            tradeoff = true,
        )

        /**
         * A backstop on the pool rather than a working limit — see [UctTree.MAX_NODES].
         *
         * Which is why it is not a [BotKnob.tradeoff]: one node is added per iteration and iterations
         * are bounded by the allowance, so the ceiling is already implied by the budget above and
         * moving it changes no move this bot plays. It exists so an allowance set to millions cannot
         * eat the heap.
         */
        val MAX_NODES = BotKnob.Integer(
            name = "maxNodes",
            label = "Tree nodes",
            help = "A ceiling on the search tree. A backstop, not a working limit.",
            default = UctTree.MAX_NODES,
            min = 1 shl 10,
            max = 1 shl 20,
            step = 1 shl 10,
        )

        /** [ROLLOUT_DEPTH] for a bot that plays every rollout out in full. */
        const val ROLLOUT_TO_THE_END: Int = 0

        /**
         * Rollouts run to the end, and that is a **measured** decision rather than an omission.
         *
         * Truncation — cut the rollout at a depth, judge the position by reachable-space share —
         * was named the highest-value lever left when this bot landed, on the usual reasoning that a
         * hundred-move rollout is an expensive way to buy one bit. It was then built
         * ([truncatedPlayout], [SpaceOwnership]) and played against this over forty matches a depth,
         * at the same allowance, on a 12x12:
         *
         * | depth | wins of 40 | µs/turn | against full |
         * |---|---|---|---|
         * | 10 | 20 | 1,120 | 3.1× |
         * | 25 | 22 | 630 | 1.8× |
         * | 60 | 23 | 435 | 1.2× |
         * | played out | — | 357 | — |
         *
         * Dead even on strength — one sigma over forty matches is ±3.2, so 20, 22 and 23 are the
         * same number — for one and a fifth to three times the wall-clock. The reasoning does not
         * survive contact with *this* engine: a rollout here is mutate-and-undo over a flat arena at
         * tens of nanoseconds a move, so a hundred of them cost about what **one** board-wide
         * ownership sweep costs, and truncating at ten buys seven times as many sweeps rather than
         * seven times as much search. The shape of the table is the tell — truncation gets cheaper
         * and very slightly better as the cut moves *out* toward not truncating at all.
         *
         * Equal budget is the generous comparison, too — a budget is simulated moves, and buying
         * more iterations per move is the entire point of truncating. Losing there and costing more
         * leaves no allowance at which it is the better use of a millisecond.
         *
         * It ships wired and off rather than deleted, because a measured "no" is worth more with the
         * thing still there to re-measure: `./gradlew :lab:run --args="play uct uct:rolloutDepth=25"`,
         * or set `rolloutDepth` in [ao.snakewarz.botapi.knob.BotParams], and `RolloutTruncationTest`
         * re-runs the table above.
         *
         * Not a [BotKnob.tradeoff], so it is not on the sidebar: the table above is what settling a
         * number by measurement looks like, and having settled it there is nothing left for a player
         * to decide.
         */
        val ROLLOUT_DEPTH = BotKnob.Integer(
            name = "rolloutDepth",
            label = "Rollout depth",
            help = "Moves a rollout is cut short at and judged by space instead. 0 plays it all out.",
            default = ROLLOUT_TO_THE_END,
            min = ROLLOUT_TO_THE_END,
            max = 500,
            step = 5,
        )

        /**
         * Everything this bot lets you tune, in the order a form would show it.
         *
         * The sidebar shows the first two — see [ao.snakewarz.botapi.registry.BotEntry.offered]. The other two are settled
         * numbers, tunable from `:lab` and from a replay and nowhere a player can reach.
         */
        val KNOBS: List<BotKnob> = listOf(SEARCH, EXPLORATION, MAX_NODES, ROLLOUT_DEPTH)

        /**
         * Deeper than the tree can grow at any sane allowance: one node is added per iteration, so
         * a chain this long would need [MAX_DEPTH] iterations all descending the same line.
         */
        const val MAX_DEPTH = 512
    }
}
