package ao.snakewarz.bots

import ao.snakewarz.botapi.BoardScratch
import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Direction
import ao.snakewarz.core.MatchOutcome

/**
 * Monte Carlo tree search with UCB1 — the strongest thing in the box, and the reason this project
 * exists. A rewrite of legacy `UctAi`/`Node`/`BiState` onto [ao.snakewarz.botapi.Playout] and
 * [UctTree].
 *
 * Each iteration walks down the tree by UCB1, plays the rest of the game out at random, and credits
 * every node it passed through with the result. Repeat until the allowance runs out, then play the
 * move whose child scored best. Nothing about that is new; what makes it fast here is that the
 * engine mutates and unwinds a single arena instead of building a board per node, and that the tree
 * is six flat arrays instead of an object graph.
 *
 * **It searches the real N-player game.** `docs/MIGRATION.md` planned an abstract `DuelBot` that
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
    private val exploration = setup.params.double("exploration", EXPLORATION)
    private val tree = UctTree(setup.params.int("maxNodes", UctTree.MAX_NODES))
    private val path = IntArray(MAX_DEPTH)

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
     * the live board — so [ao.snakewarz.botapi.Playout.undo] is never called here. A reset is one
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
                result = randomPlayout(playout, rng)
                break
            }
            if (!tree.isOpen(child)) {
                tree.open(child, playout.board.legalMoves(playout.toAct))
                result = randomPlayout(playout, rng)
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

    override fun toString(): String = "UctBot"

    private companion object {
        /** Legacy's `5` at `Node.java:423` — an exploration constant of `sqrt(1/5)`. */
        const val EXPLORATION = 5.0

        /**
         * Deeper than the tree can grow at any sane allowance: one node is added per iteration, so
         * a chain this long would need [MAX_DEPTH] iterations all descending the same line.
         */
        const val MAX_DEPTH = 512
    }
}
