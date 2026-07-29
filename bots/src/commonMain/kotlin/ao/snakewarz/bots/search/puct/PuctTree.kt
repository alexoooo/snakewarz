package ao.snakewarz.bots.search.puct

import ao.snakewarz.bots.search.uct.UctTree
import ao.snakewarz.bots.search.uct.portableLog
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.snake.SnakeId
import kotlin.math.sqrt

/**
 * The search tree [PuctBot] grows: [UctTree]'s six flat arrays, a seventh holding a prior, two more
 * for what the solver has settled and two more again for what AMAF has seen.
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
 *
 * ### A proven value is not an average
 *
 * A descent that walks into a finished game has not *estimated* anything, and averaging that
 * certainty in with whatever else the leaf saw throws away the one thing the search knows for sure.
 * [proveTerminal] and [proveFromChildren] carry it instead, as the MCTS-Solver does, and
 * [PuctBot.SOLVER] is what asks for them — and carries how often any of this fires, which is the
 * figure that bounds what it can be worth.
 *
 * **A proven node's exact value is always some terminal outcome's**, which is what makes it a byte
 * rather than a vector: the only exact values in the tree are the ones a finished game supplies, and
 * [proveFromChildren] propagates one of its children's rather than computing a new one. So
 * [provenWinner] holds what `outcomeValues` reads — a winning slot, or [NOBODY] for a draw — and
 * everything else follows from it by [rankOf].
 *
 * **Nothing is proven unless somebody asks.** The bot simply does not call the two prove methods when
 * the knob is off, so [proven] stays false everywhere and every branch that reads it is dead. That is
 * why the move stream `puct` plays at its defaults is unchanged by any of this rather than merely
 * intended to be, and it is what keeps `GoldenMoveStreamTest`'s `puct` hash a hash of the same bot.
 *
 * ### And a second estimate of the same edge, from moves played later in the descent
 *
 * [recordRave] and [blended] are RAVE, and [PuctBot.RAVE] is what asks for them — on the same terms
 * as the solver, so at the default the two AMAF arrays are length zero and the blend is never
 * reached. What makes the variant here **not** the textbook one is that this bot has no rollout to
 * harvest from: the whole simulation is the tree descent, so the AMAF set of a node is the moves its
 * own mover plays further down that descent and nothing else. [PuctBot.RAVE] carries what that costs
 * and what it is worth.
 */
internal class PuctTree(
    private val maxNodes: Int = MAX_NODES,
    /** RAVE's equivalence parameter in visits, or [NO_RAVE] to leave the AMAF machinery unbuilt. */
    private val raveEquivalence: Double = NO_RAVE,
) {
    /** Whether AMAF statistics are collected at all. Read by [PuctBot] to gate the backup that fills them. */
    val raving: Boolean = raveEquivalence > NO_RAVE

    private var capacity = INITIAL_CAPACITY
    private var visits = IntArray(capacity)
    private var rewardSum = DoubleArray(capacity)
    private var edges = IntArray(capacity)
    private var actor = ByteArray(capacity)
    private var children = IntArray(capacity * CHILDREN)

    /** P(s,a), laid out beside [children] and indexed the same way. Normalised over the legal set. */
    private var prior = DoubleArray(capacity * CHILDREN)

    /**
     * How many times this node's mover played each direction *later in the same descent*, and what
     * those descents were worth to it — AMAF, laid out beside [children] and indexed the same way.
     *
     * Empty rather than allocated when [raving] is false, for the reason this class is a sibling of
     * [UctTree] rather than a mode of it: a pair of `capacity * 4` arrays allocated for every match
     * that never reads a word of them is the cost that argument was made against.
     */
    private var raveVisits = IntArray(if (raving) capacity * CHILDREN else 0)
    private var raveSum = DoubleArray(if (raving) capacity * CHILDREN else 0)

    /** Whether a node's value is known exactly rather than sampled. Never true with the solver off. */
    private var proven = BooleanArray(capacity)

    /** Who wins a [proven] node, or [NOBODY] for a draw. Inert while that node is unproven. */
    private var provenWinner = ByteArray(capacity)

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

    /** Whether [node]'s value is known exactly — see this class's KDoc on what that buys. */
    fun isProven(node: Int): Boolean = proven[node]

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
     * A **test seam**, as [visitsOf], [averageOf], [priorOf] and [provenWinnerOf] all are here: the
     * search reads the arrays directly, and these exist so `PuctTreeTest` can assert per-actor
     * credit, the trapped node's single-edge prior, the value backup and what max^n settled on by
     * name instead of by index. Said out loud because unlike [UctTree], where [UctTree.averageOf] is
     * on the selection path, none of these five has a production caller — which is a fact about how
     * the two trees are shaped, not a leftover.
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

    /**
     * Who wins [node]'s settled outcome, or a negative slot for a draw. Meaningful only where
     * [isProven]. A test seam; see [actorOf].
     */
    fun provenWinnerOf(node: Int): Int = provenWinner[node].toInt()

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
     * With [raving] on it is only the fallback: an unvisited child with AMAF behind it is judged by
     * that instead — see [blended].
     *
     * A **proven** child is skipped outright rather than scored, because PUCT allocates work against
     * uncertainty and a settled child has none left to reduce — this is what stops the allowance
     * draining into a move already known to lose. It cannot skip everything: a node whose children
     * are all proven is itself proven, and [PuctBot] never descends into one. [chosen] still starts
     * on a real edge, so even a caller that broke that invariant gets a direction rather than
     * nothing.
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
            val edge = base + direction.ordinal
            val child = children[edge]
            if (child != NO_NODE && proven[child]) {
                continue
            }

            val childVisits = if (child == NO_NODE) 0 else visits[child]

            var exploit = if (childVisits == 0) firstPlay else rewardSum[child] / childVisits
            if (raving) {
                exploit = blended(edge, childVisits, exploit)
            }
            val explore = exploration * prior[edge] * sqrtParent / (1 + childVisits)

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
     * Credits the edge from [node] along [ordinal] with a descent in which its mover played that
     * direction, worth [value] to it. Meaningless — and never called — while [raving] is false.
     *
     * This is the *all-moves-as-first* half of RAVE: the mover's move at some deeper ply is counted
     * as evidence about the same direction here, on the assumption that a move's worth does not
     * depend much on when it is played. [PuctBot.RAVE] carries how far that assumption survives
     * contact with a game where a snake's own body is what makes a direction fatal.
     */
    fun recordRave(node: Int, ordinal: Int, value: Double) {
        val edge = node * CHILDREN + ordinal
        raveVisits[edge]++
        raveSum[edge] += value
    }

    /** How many AMAF samples the edge from [node] along [direction] holds. A test seam; see [actorOf]. */
    fun raveVisitsOf(node: Int, direction: Direction): Int = raveVisits[node * CHILDREN + direction.ordinal]

    /** Their mean, or zero where there are none. A test seam; see [actorOf]. */
    fun raveAverageOf(node: Int, direction: Direction): Double {
        val edge = node * CHILDREN + direction.ordinal
        return if (raveVisits[edge] == 0) 0.0 else raveSum[edge] / raveVisits[edge]
    }

    /**
     * Records [node] as a game that really ended, won by slot [winner] or drawn at [NOBODY].
     *
     * The only exact value the tree ever receives from outside. Everything else it calls proven it
     * derived from one of these through [proveFromChildren].
     */
    fun proveTerminal(node: Int, winner: Int) {
        proven[node] = true
        provenWinner[node] = winner.toByte()
    }

    /**
     * Tries to settle [node] from its children, answering whether it is settled afterwards.
     *
     * **max^n**, and the assumption it rests on is worth stating rather than leaving implicit:
     * *every snake plays the move maximising its own component of the outcome*. At two snakes that is
     * minimax and holds unconditionally — there is one opponent and one thing it can want. At three
     * and four it is a choice, and the alternative (assume everyone is out to get *me* specifically)
     * is paranoid search, which is wrong here in the direction that matters: a bot that treats two
     * opponents as a coalition talks itself out of every line where they might collide with each
     * other, and they do that constantly.
     *
     * Two ways a node settles, and the asymmetry is the whole of the algorithm:
     *
     * - **One winning reply is enough.** If any child is a win for the mover, the mover plays it, and
     *   what the unexplored moves would have done cannot matter.
     * - **Anything less needs every child.** The best of what is left is only known once nothing is
     *   unexamined, so a single unvisited edge — or one the pool had no room for — leaves the node
     *   open. Conservative in the safe direction: an unproven node is merely searched further.
     *
     * Ties among children the mover values equally go to the lowest direction ordinal, matching
     * [selectPuct] and [bestMoveAtRoot]. It only decides *which loss* is recorded when every reply
     * loses, and at three snakes that is which opponent is credited with the win rather than anything
     * the mover cares about.
     *
     * [node] must be open: an unopened node's [edges] are [UNOPENED], and `DirectionSet(-1)` reports
     * more directions than exist.
     */
    fun proveFromChildren(node: Int): Boolean {
        val set = DirectionSet(edges[node])
        val base = node * CHILDREN

        var mover = NO_ACTOR
        var everyChildProven = true
        // Below LOST, so the first proven child is taken whatever it turns out to say.
        var bestRank = LOST - 1
        var bestWinner = NOBODY

        for (i in 0 until set.size) {
            val child = children[base + set.nth(i).ordinal]
            if (child == NO_NODE || !proven[child]) {
                everyChildProven = false
                continue
            }

            // Every child of a node was moved into by the same snake, so any one of them names the
            // mover whose payoff this maximises.
            mover = actor[child].toInt()

            val rank = rankOf(child, mover)
            if (rank > bestRank) {
                bestRank = rank
                bestWinner = provenWinner[child]
            }
        }

        if (mover == NO_ACTOR || (bestRank < WON && !everyChildProven)) {
            return false
        }

        proven[node] = true
        provenWinner[node] = bestWinner
        return true
    }

    /**
     * The root's best move, or `null` if nothing was ever visited.
     *
     * Most visits rather than [UctTree]'s highest average, and the difference is real at this
     * iteration count: a value-backed child visited twice can hold a better mean than the move the
     * search actually believes in, while the visit count *is* the search's own integrated confidence
     * in it — it is what PUCT spent its allowance on. Ties go to the lower direction ordinal and
     * nothing is drawn, so re-running the same search answers the same way.
     *
     * **A proven move outranks a counted one**, in both directions. A win the search has settled is
     * the answer however few visits it took to establish, and a move settled as a loss is not the
     * answer while any other move exists — which the visit count on its own gets wrong, since a child
     * proven lost on its five hundredth visit keeps every one of them and stops collecting more.
     * A settled draw ranks with the unsettled, because [PuctBot]'s `firstPlay` already reads an
     * unknown value as exactly even.
     */
    fun bestMoveAtRoot(): Direction? {
        val set = DirectionSet(edges[ROOT])
        var chosen: Direction? = null
        var bestRank = LOST
        var bestVisits = 0

        for (i in 0 until set.size) {
            val direction = set.nth(i)
            val child = childOf(ROOT, direction)
            if (child == NO_NODE || visits[child] == 0) {
                // Nobody arrived, so there is nothing to prefer it by — and nothing to have settled
                // it with either, since a node is only ever settled on a path that was credited.
                continue
            }

            val rank = rankOf(child, actor[child].toInt())
            val seen = visits[child]
            if (rank > bestRank || (rank == bestRank && seen > bestVisits)) {
                bestRank = rank
                bestVisits = seen
                chosen = direction
            }
        }

        return chosen
    }

    override fun toString(): String = "PuctTree($count/$maxNodes)"

    /**
     * [exploit] moved toward the AMAF estimate of [edge], by however much that estimate is worth.
     *
     * `beta = m / (n + m + n * m / k)` — RAVE's equivalence schedule, where `n` is what the edge has
     * really been visited, `m` what AMAF has seen and `k` is [raveEquivalence]. It is one at `n = 0`,
     * so a child nobody has tried is judged by AMAF instead of by [PuctBot.FIRST_PLAY]; it falls to
     * zero as the real statistic accumulates, so a well-visited child is judged by what actually
     * happened. `+ - * /` only, which is what keeps this bot in `GoldenMoveStreamTest`'s cross-target
     * set — SW-02 in `docs/Coding-Standards.md`.
     */
    private fun blended(edge: Int, childVisits: Int, exploit: Double): Double {
        val m = raveVisits[edge]
        if (m == 0) {
            return exploit
        }

        val samples = m.toDouble()
        val real = childVisits.toDouble()
        val beta = samples / (real + samples + real * samples / raveEquivalence)
        return exploit + beta * (raveSum[edge] / samples - exploit)
    }

    /**
     * What [node]'s settled outcome is worth to [forSlot], as [LOST], [EVEN] or [WON].
     *
     * An unproven node ranks [EVEN] alongside a settled draw, which is the same reading `firstPlay`
     * gives an unvisited child: half is what "unknown" is worth on [LeafEval]'s scale.
     */
    private fun rankOf(node: Int, forSlot: Int): Int {
        if (!proven[node]) {
            return EVEN
        }

        val winner = provenWinner[node].toInt()
        return when {
            winner == forSlot -> WON
            winner < 0 -> EVEN
            else -> LOST
        }
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
        proven[node] = false
        provenWinner[node] = NOBODY

        val base = node * CHILDREN
        for (i in 0 until CHILDREN) {
            children[base + i] = NO_NODE
            // Cleared as well, so that "allocate writes every field of a node it hands out" stays
            // true and reset() can stay O(1). Nothing reads an unopened node's prior, but the
            // invariant is what makes that safe to rely on rather than a thing to check.
            prior[base + i] = 0.0
            if (raving) {
                raveVisits[base + i] = 0
                raveSum[base + i] = 0.0
            }
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
        proven = proven.copyOf(enlarged)
        provenWinner = provenWinner.copyOf(enlarged)
        if (raving) {
            raveVisits = raveVisits.copyOf(enlarged * CHILDREN)
            raveSum = raveSum.copyOf(enlarged * CHILDREN)
        }
        capacity = enlarged
    }

    companion object {
        const val ROOT: Int = 0
        const val NO_NODE: Int = -1

        /** An equivalence parameter of zero collects no AMAF statistics and allocates nothing for them. */
        const val NO_RAVE: Double = 0.0

        /** The root was not arrived at by a move, so no snake owns its payoff. */
        const val NO_ACTOR: Int = -1

        /** A drawn game has nobody to credit, and is what an unproven node's winner slot holds. */
        private const val NOBODY: Byte = -1

        /**
         * [LeafEval]'s three points as an order rather than as a value.
         *
         * A rank is what [proveFromChildren] maximises and what [bestMoveAtRoot] prefers by, and both
         * only ever compare two of them — so the ordering is the whole content and the arithmetic of
         * `1.0`, `0.5` and `0.0` would be a comparison of doubles for nothing.
         */
        private const val LOST = 0
        private const val EVEN = 1
        private const val WON = 2

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
