package ao.snakewarz.bots.search.uct

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.reactive.space.SpaceBot
import ao.snakewarz.bots.search.EvaluationCost
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
 * **One iteration is one evaluation**, which is what the allowance counts — [EvaluationCost.ROLLOUT]
 * is charged by asking for the playout, and the rollout then runs to the end however long it takes.
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

        while (iterate(turn)) {
            // The only exit is inside iterate(): the allowance would not stretch to another rollout.
        }

        val best = tree.bestMoveAtRoot()
        return if (best != null) Decision.Move(best) else unbudgeted.chooseMove(turn)
    }

    /**
     * One descend-simulate-credit pass. Returns `false` when the search should stop.
     *
     * The rollout is paid for on the first line, which is also what says whether there is one left to
     * run: an unaffordable playout comes back reporting an outcome before a move is made.
     *
     * The moves this applies are unwound by the next call's `playout()`, which resets the arena from
     * the live board — so [ao.snakewarz.botapi.scratch.Playout.undo] is never called here. A reset is one
     * array copy against a rollout of a hundred-odd moves, and it makes an off-by-one unwind, which
     * would quietly poison every later iteration, impossible rather than merely unlikely.
     */
    private fun iterate(turn: Turn): Boolean {
        val playout = turn.scratch.playout(EvaluationCost.ROLLOUT)
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
            // IllegalStateException, and any move can be the one that ends the game.
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
         * How many evaluations of a turn this may spend, and over what range moving it is worth
         * anything.
         *
         * Ten times the shipped allowance at the top. `MatchSetup.DEFAULT_BUDGET_PER_TURN` carries
         * the measurements the shipped figure came from, and the argument for the headroom.
         */
        val SEARCH = BotKnob.Search(min = 0, max = 10_000, step = 100)

        /**
         * UCB1's constant, as a **divisor** — [UctTree.selectUcb1] is
         * `average + sqrt(logParent / (exploration * childVisits))`, so the effective constant is
         * `sqrt(1/exploration)` and a *smaller* number explores *more*. The floor is above zero
         * rather than at it because at zero the term is an infinity and the tree stops choosing.
         *
         * Legacy shipped `5` at `Node.java:423` — an effective `sqrt(1/5) ≈ 0.447` against the
         * textbook `sqrt(2)` — and that was this bot's default until it was swept. It is not the best
         * setting: `5` sits on the low-exploration edge of a broad optimum rather than in it.
         *
         * Swept against a field at the shipped allowance on a 12x12, `chase`, `flat-monte-carlo` and
         * `puct` for opposition, as ratings relative to `5.0` within each run:
         *
         * | exploration | effective | 9,450 games | 6,720 games, fresh seeds |
         * |---|---|---|---|
         * | 0.6 | 1.29 | −17 | — |
         * | 1.2 | 0.91 | +4 | — |
         * | 1.8 | 0.75 | +26 | −6 |
         * | 2.5 | 0.63 | +15 | +8 |
         * | 3.5 | 0.53 | +23 | +10 |
         * | 5.0 | 0.45 | — | — |
         * | 10 | 0.32 | −63 | — |
         *
         * A wider pilot put `0.3`, `20` and `40` below all of these, so the curve has an interior
         * peak rather than a slope. `1.8` topping the first sweep and then losing to the default in
         * the second is the whole reason the second was run: a field sweep is cheap enough to be
         * greedy, and one lucky seed base will name a winner. What replicated is the `2.5..3.5`
         * plateau, and three sequential tests at `elo0=0, elo1=10` settled it there:
         *
         * | candidate | seed base | boards | verdict |
         * |---|---|---|---|
         * | 3.5 | 5001 | 1,100 | BETTER, +21 ±14 |
         * | 2.5 | 5001 | 1,240 | BETTER, +20 ±14 |
         * | **3.0** | 7001 | 960 | BETTER, +24 ±15 |
         *
         * So the default is the middle of the region that replicated, confirmed on a seed base
         * neither sweep had touched. It costs nothing to take: `time` reads 2,071 µs/turn at `5.0`
         * against 2,137 at `3.0`, one sample's worth of noise apart. An allowance is a count of
         * evaluations, and this decides which move each one is spent on rather than how many there
         * are.
         *
         * Still a tradeoff, and the only one this bot has besides its allowance: exploring wide and
         * digging deep are both ways to win, the best setting moves with the board and the opponent,
         * and the two ends play visibly differently at the same budget.
         */
        val EXPLORATION = BotKnob.Decimal(
            name = "exploration",
            label = "Exploration",
            help = "UCB1's constant, as a divisor. Lower tries more moves, higher digs deeper into the best one.",
            default = 3.0,
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
         * at the same allowance, on a 12x12. Re-measured at 100 evaluations a turn, `:lab` for the
         * wins and `RolloutTruncationTest` for the clocks:
         *
         * | depth | wins of 40 | µs/turn, JVM | µs/turn, Chrome | against full |
         * |---|---|---|---|---|
         * | 10 | 27 | 225 | 355 | 1.5× / 1.3× |
         * | 25 | 20 | 167 | 267 | 1.1× / 1.0× |
         * | 60 | 17 | 176 | 322 | 1.1× / 1.2× |
         * | played out | — | 154 | 264 | — |
         *
         * One sigma over forty matches is ±3.2, so cutting *hard* is a little ahead per iteration
         * (27 of 40 is two sigma) and cutting late is a little behind — which is the shape to
         * expect, since an ownership sweep is a less noisy reading of a position than one random
         * playout, and a rollout cut at sixty has usually finished anyway.
         *
         * **The cost columns are the ones to watch, and they have already moved once.** A rollout
         * here is mutate-and-undo over a flat arena at tens of nanoseconds a move, and the sweep it
         * is judged by is a bitmap one — [SpaceOwnership] advances whole breadth-first layers with a
         * shift and a mask rather than a square at a time, which took the truncated iteration from
         * costing two to three times a full rollout to costing between one and one and a half. Both
         * targets agree on that, and Chrome agrees more strongly than the JVM does, which is what
         * settles the standing worry about `Long` on wasm: a bitmap sweep is not paying an emulation
         * tax there.
         *
         * So per *millisecond* the trade is now close to even where it used to be plainly bad, and
         * the currency still does not favour it. An allowance is counted in evaluations, so a
         * truncated iteration and a full one buy exactly one each — the extra iterations that were
         * the entire argument for truncating are not on offer, and what is left is a slightly better
         * leaf for a little more than the price of the same number of them. **That is a narrower gap
         * than the one this default was settled on, and nobody has re-run the strength half of the
         * table against it.** The wins column is what would have to move, and it is `:lab` work.
         *
         * It ships wired and off rather than deleted, because a measured "no" is worth more with the
         * thing still there to re-measure: `./gradlew :lab:run --args="play uct uct:rolloutDepth=25"`,
         * or set `rolloutDepth` in [ao.snakewarz.botapi.knob.BotParams], and `RolloutTruncationTest`
         * re-runs the table above.
         *
         * Not a [BotKnob.tradeoff], so it is not on the sidebar: the table above is what settling a
         * number by measurement looks like, and having settled it there is nothing left for a player
         * to decide. It is the first thing to re-measure if [ao.snakewarz.bots.search.EvaluationCost]
         * is ever calibrated, because a truncated iteration would then stop costing what a full one
         * costs and the trade would be a different one.
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
