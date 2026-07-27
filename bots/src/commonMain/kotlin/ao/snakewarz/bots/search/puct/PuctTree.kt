package ao.snakewarz.bots.search.puct

import ao.snakewarz.bots.search.uct.UctTree
import ao.snakewarz.bots.search.uct.portableLog
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.snake.SnakeId
import kotlin.math.sqrt

/**
 * The search tree [PuctBot] grows: [UctTree]'s six flat arrays plus a seventh holding a prior.
 *
 * A sibling of [UctTree] rather than a mode of it, and the reason is not the sixty lines of allocator
 * they have in common. `GoldenMoveStreamTest` pins the move stream `uct` plays as a hash, re-run in
 * real Chrome, and SW-01 in `docs/Coding-Standards.md` makes a golden failure a question rather than
 * a hash to update.
 * Refactoring the tree UCT selects through risks reordering a floating-point expression and moving
 * that hash for no reason anybody would be able to name afterwards. A mode flag would be worse
 * still: a branch in the hottest loop in the program, and a `DoubleArray(capacity * 4)` allocated
 * for every UCT match that never reads a word of it.
 *
 * What actually differs is most of the algorithm. Selection is PUCT rather than UCB1, backup takes a
 * *value* per actor rather than a win, an unvisited child is ordered by its prior rather than by an
 * enormous random number, and the root answers with its most-visited move rather than its best
 * average. Only the allocator, the four-wide child block and the trapped-mover trick survive
 * unchanged — and those are copied deliberately, with [UctTree]'s reasons.
 */
internal class PuctTree(private val maxNodes: Int = MAX_NODES) {
    private var capacity = INITIAL_CAPACITY
    private var visits = IntArray(capacity)
    private var rewardSum = DoubleArray(capacity)
    private var edges = IntArray(capacity)
    private var actor = ByteArray(capacity)
    private var children = IntArray(capacity * CHILDREN)

    /** P(s,a), laid out beside [children] and indexed the same way. Normalised over the legal set. */
    private var prior = DoubleArray(capacity * CHILDREN)

    private var count = 0

    /** How many nodes are in use. Reported so a test can watch the pool grow and stop growing. */
    val size: Int get() = count

    /** Empties the tree and installs a fresh root. Costs nothing but the root's own fields. */
    fun reset() {
        count = 0
        allocate(NO_ACTOR)
    }

    /** Whether [node]'s legal moves are known yet. An unopened node is a leaf to judge from. */
    fun isOpen(node: Int): Boolean = edges[node] != UNOPENED

    /**
     * Records which moves exist at [node] and what the prior thinks of them.
     *
     * [priors] is read by [Direction.ordinal] and only at the ordinals in [legal], so a caller need
     * not clear the ones it is not offering. An empty [legal] means the mover is trapped and is
     * handled here rather than by the caller: every direction from a trapped position produces a
     * bit-identical board, so one edge is exact rather than an approximation — [UctTree]'s reasoning,
     * unchanged — and that one edge is the whole of the prior.
     */
    fun open(node: Int, legal: DirectionSet, priors: DoubleArray) {
        val base = node * CHILDREN

        if (legal.isEmpty) {
            edges[node] = TRAPPED_EDGE
            prior[base + Direction.NORTH.ordinal] = 1.0
            return
        }

        edges[node] = legal.bits
        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            prior[base + ordinal] = priors[ordinal]
        }
    }

    /**
     * The snake that moved into [node], or [NO_ACTOR] at the root, which nobody moved into.
     *
     * A **test seam**, as [visitsOf], [averageOf] and [priorOf] all are here: the search reads the
     * arrays directly, and these exist so `PuctTreeTest` can assert per-actor credit, the trapped
     * node's single-edge prior and the value backup by name instead of by index. Said out loud
     * because unlike [UctTree], where [UctTree.averageOf] is on the selection path, none of these
     * four has a production caller — which is a fact about how the two trees are shaped, not a
     * leftover.
     */
    fun actorOf(node: Int): Int = actor[node].toInt()

    /** [node]'s visit count. A test seam; see [actorOf]. */
    fun visitsOf(node: Int): Int = visits[node]

    /**
     * [node]'s mean value as its own actor sees it, or zero if nobody has been here.
     *
     * Plain `rewardSum / visits`, and deliberately **not** [UctTree]'s `visits + 1`. That denominator
     * is legacy's prior at `Node.java:286`, carried there on purpose and flagged there as the sort of
     * thing somebody tidies away without measuring — which is an argument for keeping it where it was
     * ported, not for importing it into an algorithm that never had it.
     *
     * [selectPuct] computes the same quotient inline rather than calling this, because it needs
     * `firstPlay` for an unvisited child where this returns zero. Two answers to what looks like one
     * question, so they are two pieces of code on purpose.
     */
    fun averageOf(node: Int): Double = if (visits[node] == 0) 0.0 else rewardSum[node] / visits[node]

    /** [node]'s prior along [direction]. Meaningful only after [open]. A test seam; see [actorOf]. */
    fun priorOf(node: Int, direction: Direction): Double = prior[node * CHILDREN + direction.ordinal]

    /** [node]'s child along [direction], or [NO_NODE] if it has never been taken. */
    fun childOf(node: Int, direction: Direction): Int = children[node * CHILDREN + direction.ordinal]

    /**
     * [node]'s child along [direction], creating it if this is the first visit.
     *
     * [NO_NODE] when the pool has hit its ceiling — the caller then judges where it stands instead of
     * deepening, so the search degrades at the frontier rather than failing.
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
     * The move PUCT wants explored next from [node], which is always one that exists.
     *
     * `Q(s,a) + c * P(s,a) * sqrt(N(s)) / (1 + N(s,a))` — AlphaZero's rule with a hand-written prior
     * where the policy head would be. Three differences from [UctTree.selectUcb1] are load-bearing:
     *
     * - **[exploration] is a multiplier here and a divisor there.** `UctBot.EXPLORATION` is legacy's
     *   `5` inside `sqrt(ln(v) / (5 * cv))`, so raising *that* one explores less. Raising this one
     *   explores more. They read the same way round on a form and mean opposite things, which is why
     *   they are not named the same thing.
     * - **No logarithm anywhere.** `sqrt` is exactly specified by IEEE-754 and so is bit-identical on
     *   the JVM and in wasm; `ln` is not, which is the whole reason [portableLog] exists. PUCT needs
     *   neither, so the standing rule against `kotlin.math.ln` binds this bot without costing it
     *   anything.
     * - **No randomness at all.** UCB1 gives an unvisited child an enormous randomised score so that
     *   every child is tried once in uniform order. The prior already orders the unvisited children,
     *   so this draws nothing from the stream — and at a static evaluation the whole bot then
     *   consumes none.
     *
     * [firstPlay] is `Q` for a child nobody has visited. AlphaZero uses zero, which is right at a
     * hundred thousand simulations a move and wrong at the few hundred a hand-written evaluation buys
     * under a browser's allowance: it makes the search prior-bound, so a move the prior dislikes is
     * never tried at all. Half is "unknown" on a `0..1` scale, which is what an unvisited child is.
     */
    fun selectPuct(node: Int, exploration: Double, firstPlay: Double): Direction {
        val set = DirectionSet(edges[node])
        val parentVisits = visits[node]

        // Floored at one: sqrt(0) would zero the exploration term the first time a node is selected
        // from, leaving every child on exactly firstPlay and the choice made by ordinal.
        val sqrtParent = sqrt(if (parentVisits < 1) 1.0 else parentVisits.toDouble())
        val base = node * CHILDREN

        var chosen = set.nth(0)
        var best = Double.NEGATIVE_INFINITY

        for (i in 0 until set.size) {
            val direction = set.nth(i)
            val child = children[base + direction.ordinal]
            val childVisits = if (child == NO_NODE) 0 else visits[child]

            val exploit = if (childVisits == 0) firstPlay else rewardSum[child] / childVisits
            val explore = exploration * prior[base + direction.ordinal] * sqrtParent / (1 + childVisits)

            val value = exploit + explore
            if (value > best) {
                best = value
                chosen = direction
            }
        }

        return chosen
    }

    /**
     * Credits [node] with one visit and with [values] as its own actor sees it.
     *
     * One array read rather than a comparison against a winner, which is the whole of what value
     * backup changes — and the reason [LeafEval] answers per slot. Credit is still per actor, for
     * [UctTree]'s reason: a child's actor is exactly the snake to act at its parent, so maximising a
     * child's average maximises the mover's own payoff at every node, for any number of snakes.
     */
    fun record(node: Int, values: DoubleArray) {
        visits[node]++

        val by = actor[node].toInt()
        if (by == NO_ACTOR) {
            // The root. Its visit count is PUCT's N; nobody moved into it, so it has no payoff.
            return
        }

        rewardSum[node] += values[by]
    }

    /**
     * The root's most-visited move, or `null` if nothing was ever visited.
     *
     * Most visits rather than [UctTree]'s highest average, and the difference is real at this
     * iteration count: a value-backed child visited twice can hold a better mean than the move the
     * search actually believes in, while the visit count *is* the search's own integrated confidence
     * in it — it is what PUCT spent its allowance on. Ties go to the lower direction ordinal and
     * nothing is drawn, so re-running the same search answers the same way.
     */
    fun bestMoveAtRoot(): Direction? {
        val set = DirectionSet(edges[ROOT])
        var chosen: Direction? = null
        var best = 0

        for (i in 0 until set.size) {
            val direction = set.nth(i)
            val child = childOf(ROOT, direction)
            if (child == NO_NODE) {
                continue
            }

            val seen = visits[child]
            if (seen > best) {
                best = seen
                chosen = direction
            }
        }

        return chosen
    }

    override fun toString(): String = "PuctTree($count/$maxNodes)"

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
            // Cleared as well, so that "allocate writes every field of a node it hands out" stays
            // true and reset() can stay O(1). Nothing reads an unopened node's prior, but the
            // invariant is what makes that safe to rely on rather than a thing to check.
            prior[base + i] = 0.0
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
        prior = prior.copyOf(enlarged * CHILDREN)
        capacity = enlarged
    }

    companion object {
        const val ROOT: Int = 0
        const val NO_NODE: Int = -1

        /** The root was not arrived at by a move, so no snake owns its payoff. */
        const val NO_ACTOR: Int = -1

        private const val CHILDREN = 4
        private const val UNOPENED = -1
        private const val INITIAL_CAPACITY = 1_024

        /**
         * A backstop, not a working limit — [UctTree.MAX_NODES]'s reasoning, and its figure.
         *
         * One node is created per iteration at most, and iterations are bounded by the allowance
         * divided by what a leaf costs. At a static evaluation that is a few hundred a turn.
         */
        const val MAX_NODES: Int = 1 shl 16

        /** A trapped mover's one edge. Every direction produces the same position, so one suffices. */
        private val TRAPPED_EDGE = DirectionSet.of(Direction.NORTH).bits
    }
}
