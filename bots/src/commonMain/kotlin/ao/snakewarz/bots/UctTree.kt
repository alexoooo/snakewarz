package ao.snakewarz.bots

import ao.snakewarz.core.Direction
import ao.snakewarz.core.DirectionSet
import ao.snakewarz.core.MatchOutcome
import ao.snakewarz.core.Rng
import ao.snakewarz.core.SnakeId
import kotlin.math.sqrt

/**
 * The search tree [UctBot] grows, as six flat arrays and no objects at all.
 *
 * A node is an index. Its statistics live in parallel `IntArray`/`DoubleArray` pools, and its
 * children sit in a fixed four-wide block at `node * 4`, indexed by [Direction.ordinal] — so
 * reaching a child is one multiply-add and one load, with no pointer to chase and no per-node array
 * to allocate. Of everything that keeps the wasm target within about 3x of the JVM this is the
 * highest-leverage single choice, which is why it was made in this class's first commit rather than
 * profiled into later — see `docs/Bots.md`.
 *
 * The pools are allocated once per match and reused every turn. [reset] is O(1): it sets the count
 * back to one and re-initialises the root. Nothing is cleared, because [allocate] writes every field
 * of a node it hands out, so stale data past the count is unreachable rather than merely stale.
 *
 * ### Whose number is stored
 *
 * Each node holds one reward sum, and it is **from the point of view of the snake that moved into
 * it** — [actorOf]. That single array is what replaces legacy's negamax. `Node.propagateValue`
 * complemented the value at every step up the path, which is right for two players alternating and
 * wrong the moment a third exists: "bad for A" is not "good for B" when there is a C, and the bot
 * ends up helping whichever opponent is not on the current line. Because a child's actor is exactly
 * the snake to act at its parent, maximising a child's average maximises the mover's own payoff at
 * every node — which is correct for any number of snakes, and reduces to the legacy behaviour
 * exactly when there are two.
 *
 * ### Trapped movers get one edge, not four
 *
 * A snake with nothing legal left dies whichever way it turns, and `Board.apply` produces a
 * bit-identical position for all four — it eliminates the snake without touching the body, the head
 * or the last direction. So such a node is opened with a single `NORTH` edge. Four times fewer
 * nodes at the deepest and most common part of the tree, and it is exact rather than an
 * approximation.
 */
internal class UctTree(private val maxNodes: Int = MAX_NODES) {
    private var capacity = INITIAL_CAPACITY
    private var visits = IntArray(capacity)
    private var rewardSum = DoubleArray(capacity)
    private var edges = IntArray(capacity)
    private var actor = ByteArray(capacity)
    private var children = IntArray(capacity * CHILDREN)
    private var count = 0

    /** How many nodes are in use. Reported so a test can watch the pool grow and stop growing. */
    val size: Int get() = count

    /** Empties the tree and installs a fresh root. Costs nothing but the root's own fields. */
    fun reset() {
        count = 0
        allocate(NO_ACTOR)
    }

    /** Whether [node]'s legal moves are known yet. An unopened node is a leaf to roll out from. */
    fun isOpen(node: Int): Boolean = edges[node] != UNOPENED

    /** Records which moves exist at [node]. [legal] being empty means the mover is trapped. */
    fun open(node: Int, legal: DirectionSet) {
        edges[node] = if (legal.isEmpty) TRAPPED_EDGE else legal.bits
    }

    /**
     * The snake that moved into [node], or [NO_ACTOR] at the root, which nobody moved into.
     *
     * A **test seam**, and stated to be one so the next reader does not go looking for the caller:
     * the search itself reads `actor` directly in [record]. It exists so `UctTreeTest` can assert
     * per-actor credit — the property every bot above this depends on, and the one a refactor is
     * most likely to break without failing anything else — through a name rather than an array.
     */
    fun actorOf(node: Int): Int = actor[node].toInt()

    /** [node]'s visit count, which is UCB1's `N`. A test seam, for [actorOf]'s reason. */
    fun visitsOf(node: Int): Int = visits[node]

    /**
     * [node]'s mean reward as its own actor sees it.
     *
     * The denominator is `visits + 1`, not `visits` — legacy's prior at `Node.java:286`, which
     * shrinks every estimate toward zero and so makes a single lucky rollout worth half of what it
     * looks like. Kept because it is part of the algorithm being ported, and flagged because it is
     * the sort of thing somebody tidies away without measuring.
     */
    fun averageOf(node: Int): Double = rewardSum[node] / (visits[node] + 1)

    /** [node]'s child along [direction], or [NO_NODE] if it has never been taken. */
    fun childOf(node: Int, direction: Direction): Int = children[node * CHILDREN + direction.ordinal]

    /**
     * [node]'s child along [direction], creating it if this is the first visit.
     *
     * [NO_NODE] when the pool has hit its ceiling — the caller then rolls out from where it stands
     * instead of deepening, so the search degrades into flat Monte Carlo at the frontier rather
     * than failing.
     */
    fun childOrCreate(node: Int, direction: Direction, mover: SnakeId): Int {
        val slot = node * CHILDREN + direction.ordinal
        val existing = children[slot]
        if (existing != NO_NODE) {
            return existing
        }

        val created = allocate(mover.index)
        if (created != NO_NODE) {
            children[slot] = created
        }
        return created
    }

    /**
     * The move UCB1 wants explored next from [node], which is always one that exists.
     *
     * The formula is legacy's, quirks included and deliberately so.
     * [`Node.java:423`] is `average + sqrt(ln(parentVisits) / (5 * childVisits))` — an exploration
     * constant of `sqrt(1/5) ≈ 0.447` where the textbook uses `sqrt(2)`. The average divides by
     * `visits + 1` rather than `visits` (`Node.java:286`), a prior that shrinks every estimate
     * toward zero. And an unvisited child scores an enormous randomised number
     * (`Node.java:398`), which is how every child gets one visit, in uniformly random order,
     * before any real comparison happens.
     *
     * `ln` comes from [portableLog], not `kotlin.math`, so the choice is the same on the JVM and in
     * wasm — and it is hoisted out of the child loop, which legacy recomputed it inside.
     */
    fun selectUcb1(node: Int, rng: Rng, exploration: Double): Direction {
        val set = DirectionSet(edges[node])
        val parentVisits = visits[node]

        // ln(1) is exactly zero and ln(0) is not a number; below two the exploration term vanishes
        // either way, and every child is unvisited at that point regardless.
        val logParent = if (parentVisits < 2) 0.0 else portableLog(parentVisits.toDouble())
        val base = node * CHILDREN

        var chosen = set.nth(0)
        var best = Double.NEGATIVE_INFINITY

        for (i in 0 until set.size) {
            val direction = set.nth(i)
            val child = children[base + direction.ordinal]

            val value = if (child == NO_NODE || visits[child] == 0) {
                UNVISITED_BASE + UNVISITED_SPREAD * rng.nextDouble()
            } else {
                averageOf(child) + sqrt(logParent / (exploration * visits[child]))
            }

            if (value > best) {
                best = value
                chosen = direction
            }
        }

        return chosen
    }

    /** Credits [node] with one visit and with [outcome] as its own actor sees it. */
    fun record(node: Int, outcome: MatchOutcome) {
        visits[node]++

        val by = actor[node].toInt()
        if (by == NO_ACTOR) {
            // The root. Its visit count is UCB1's N; nobody moved into it, so it has no payoff.
            return
        }

        rewardSum[node] += when {
            outcome.isDraw -> DRAW
            outcome.winner.index == by -> WIN
            else -> LOSS
        }
    }

    /**
     * The root's best move by highest average reward, or `null` if nothing was ever visited.
     *
     * Highest average rather than most visits, matching `UctAi`. Ties go to the lower direction
     * ordinal and no randomness is drawn, so re-running the same search answers the same way.
     */
    fun bestMoveAtRoot(): Direction? {
        val set = DirectionSet(edges[ROOT])
        var chosen: Direction? = null
        var best = Double.NEGATIVE_INFINITY

        for (i in 0 until set.size) {
            val direction = set.nth(i)
            val child = childOf(ROOT, direction)
            if (child == NO_NODE || visits[child] == 0) {
                continue
            }

            val average = averageOf(child)
            if (average > best) {
                best = average
                chosen = direction
            }
        }

        return chosen
    }

    private fun allocate(by: Int): Int {
        if (count == maxNodes) {
            return NO_NODE
        }
        if (count == capacity) {
            grow()
        }

        val node = count++
        visits[node] = 0
        rewardSum[node] = 0.0
        edges[node] = UNOPENED
        actor[node] = by.toByte()

        val base = node * CHILDREN
        for (i in 0 until CHILDREN) {
            children[base + i] = NO_NODE
        }
        return node
    }

    private fun grow() {
        val enlarged = if (capacity * 2 > maxNodes) maxNodes else capacity * 2
        visits = visits.copyOf(enlarged)
        rewardSum = rewardSum.copyOf(enlarged)
        edges = edges.copyOf(enlarged)
        actor = actor.copyOf(enlarged)
        children = children.copyOf(enlarged * CHILDREN)
        capacity = enlarged
    }

    companion object {
        const val ROOT: Int = 0
        const val NO_NODE: Int = -1

        /** The root was not arrived at by a move, so no snake owns its payoff. */
        const val NO_ACTOR: Int = -1

        /**
         * Far above any reachable UCB1 score, so an unvisited child always wins — and randomised,
         * so the order they are first tried in is uniform rather than by ordinal.
         */
        private const val UNVISITED_BASE = 10_000.0
        private const val UNVISITED_SPREAD = 1_000.0

        private const val WIN = 1.0
        private const val DRAW = 0.5
        private const val LOSS = 0.0

        private const val CHILDREN = 4
        private const val UNOPENED = -1
        private const val INITIAL_CAPACITY = 1_024

        /**
         * A backstop, not a working limit. One node is created per iteration at most, and
         * iterations are bounded by the budget divided by the length of a rollout — a few hundred
         * at the shipped allowance. This exists so that an allowance set to millions — the sidebar
         * and `:lab` both allow it — cannot eat the heap.
         */
        const val MAX_NODES: Int = 1 shl 16

        /** A trapped mover's one edge. Every direction produces the same position, so one suffices. */
        private val TRAPPED_EDGE = DirectionSet.of(Direction.NORTH).bits
    }
}
