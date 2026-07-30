package ao.snakewarz.bots.search.uct

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.reactive.space.SpaceBot
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.bots.search.RolloutPolicy
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
public class UctBot private constructor(
    setup: BotSetup,
    /** What each snake plays inside a rollout — see [ROLLOUT_POLICY] and [withRolloutPolicy]. */
    private val policy: RolloutPolicy,
) : Bot {
    /** The registry's path, and the only one a match ever takes: the policy comes off the knob. */
    public constructor(setup: BotSetup) : this(setup, RolloutPolicy(ROLLOUT_POLICY.read(setup.params), setup.grid))

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
        val judge = space ?: return randomPlayout(playout, rng, policy)
        return truncatedPlayout(playout, rng, rolloutDepth, judge, policy)
    }

    override fun toString(): String = "UctBot"

    internal companion object {
        /**
         * This bot with a [RolloutPolicy] no knob can name — a measurement seam, and nothing else.
         *
         * [ROLLOUT_POLICY] is a [BotKnob.Choice] and `Choice.read` coerces a value it does not offer
         * back to the default, so there is no [ao.snakewarz.botapi.knob.BotParams] spelling that
         * reaches a policy this bot does not declare. Pricing one that it does not — the swept prior
         * of [RolloutPolicy]'s own table — otherwise means either freezing a `Choice` value for a
         * setting nobody has yet shown is worth playing, or timing the policy outside the bot and
         * composing the turn back together out of two blocks.
         *
         * The whole point of the seam is that the timed call site stays **monomorphic**: every
         * subject a block hands [ao.snakewarz.bots.AppraisalTape] is still a `UctBot`, which is what
         * `RolloutPolicyTest` records a foreign control costing 25% of a control's own reading.
         */
        internal fun withRolloutPolicy(setup: BotSetup, policy: RolloutPolicy): UctBot = UctBot(setup, policy)

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
         * ([truncatedPlayout], [SpaceOwnership]) and played against this at 100 evaluations a turn
         * on a 12x12 — `:lab` for the wins, a thousand rounds a depth against the undepthed bot,
         * and `RolloutTruncationTest` for the clocks:
         *
         * | depth | wins per 1,000, fixed | mirrored | µs/turn, JVM | µs/turn, Chrome | against full |
         * |---|---|---|---|---|---|
         * | 10 | 619 | 563 | 225 | 355 | 1.5× / 1.3× |
         * | 25 | 541 | 517 | 167 | 267 | 1.1× / 1.0× |
         * | 60 | 480 | 497 | 176 | 322 | 1.1× / 1.2× |
         * | played out | — | — | 154 | 264 | — |
         *
         * **The wins column is a thousand rounds because forty could not carry what it was read
         * for.** One sigma over forty matches is ±3.2 wins, which is eight points, and the column
         * this replaces — 27, 20 and 17 of 40 — was read as *cutting hard is ahead and cutting late
         * is behind*. Half of that survived. Cutting hard is genuinely ahead per iteration, and by
         * more under a fixed opening than a mirrored one; cutting at 25 is a couple of points ahead;
         * cutting at 60 is **level**, not behind. Which is the shape to expect once it is measured
         * finely enough to see: an ownership sweep is a less noisy reading of a position than one
         * random playout, and a rollout cut at sixty has usually finished anyway.
         *
         * **[EXPLORATION] is a null on this**, and it is worth recording because that constant moved
         * after the first measurement and is the obvious suspect for the difference. The same
         * thousand boards played at the old `5.0` on both sides read 602 / 550 / 500 against the
         * 619 / 541 / 480 above — every one of them inside a sigma. It re-rolls all forty games of
         * `RolloutTruncationTest`'s fixture without moving the rate they sample.
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
         * than the one this default was settled on, and the wins column above is the strength half
         * re-run against it.** What it says is that the depth which pays is the depth which costs —
         * 619 of a thousand at 10, for one and a half times a turn — and that the two nearly free
         * ones buy nothing. Whether that trade is worth taking at a fixed frame budget is a question
         * about what an iteration is worth in Elo *at this allowance*, and nobody has measured that
         * one: `MatchSetup.DEFAULT_BUDGET_PER_TURN` carries the only exchange rate of that kind and
         * it was taken at the shipped 1,000 rather than at the hundred this table is played at.
         *
         * **Somebody has now measured that one, by accident, and the answer is not the one above.**
         * The table is played at 100 evaluations on a 12x12 and `RolloutTruncationTest.BUDGET` said
         * in as many words that the ratio does not turn on the allowance. The strength half of it
         * does. Same pairing, same mirrored openings, 200 distinct games a cell, at the **shipped
         * 1,000**:
         *
         * | `rolloutDepth=25` against the undepthed bot | 8x8 | 12x12 | 20x20 |
         * |---|---|---|---|
         * | at 100 evaluations — the table above | not measured | 51.7% | not measured |
         * | at 1,000 | **50.8%** | **58.5%** | **67.0%** |
         *
         * The 8x8 cell was filled last and is not one of that block's: it is 400 games pooled out of
         * the field below, on seeds neither of the other two touched. The two it sits beside are 200
         * distinct games a cell.
         *
         * That was recorded as a **lead and not a finding**, because it was neither a cost result —
         * nothing had been timed at the shipped allowance, and this whole trade is decided by cost —
         * nor a strength one, a head-to-head between two settings of one bot being a style match-up.
         * **It has since had both, on all three boards, and it survives them on one of the three.**
         *
         * ### The cost, paired, at the allowance the bot ships at
         *
         * [ao.snakewarz.bots.AppraisalTape]'s fixed line, five passes a cell, median of three whole
         * runs on the two larger boards and of eight on the 8x8, the undepthed bot read first and
         * last as the control — both subjects this class, so the timed call site stays monomorphic,
         * which is what [ao.snakewarz.bots.search.RolloutPolicy] records a foreign control swinging
         * 25% for want of. Control pairs landed within 1% on both larger boards and within 2% on
         * every one of the eight 8x8 runs.
         *
         * | at 1,000 evaluations | 8x8 | 12x12 | 20x20 |
         * |---|---|---|---|
         * | `rolloutDepth=25` against the undepthed turn | **1.05x** | **1.12x** | **1.32x** |
         * | the allowance that buys the same wall clock | **950** | **890** | **760** |
         * | what that costs, at 80-137 Elo per e-fold | 4-7 Elo | 9-16 Elo | 23-38 Elo |
         *
         * The 1.0-1.1x in the table above is not wrong so much as taken somewhere else: an ownership
         * sweep is priced by the squares and the rollout it replaces is not, so the ratio grows with
         * the board. [ao.snakewarz.bots.search.EvaluationCost] asks for exactly this re-measurement
         * whenever two kinds of rollout stop costing the same, and this is it.
         *
         * **The 8x8 row was taken last, and it is the bottom of that trend rather than a fourth
         * shape.** Eight whole runs spread 1.04-1.08x — far tighter than
         * [ao.snakewarz.bots.search.RolloutPolicy]'s own 8x8 row, because what is being measured here
         * is a few percent of a turn rather than half of one, and a small effect on a short line is
         * not automatically a noisy one. `RolloutTruncationTest.EQUAL_CLOCK`'s 950 is *verified*
         * against the control's clock at 96-99% over three runs rather than derived from the ratio,
         * for that constant's stated reason. At `ln(1000/950)` = 0.051 e-folds the handicap the 8x8
         * field below carries is **4-5 Elo** at the conservative end of the exchange rate, which is
         * inside every bar in it.
         *
         * Three of those runs re-read the other two boards for free and both reproduce — 1.06-1.10x
         * at 12x12 and 1.31-1.51x at 20x20 — but read the second of those as agreement in sign only:
         * its control pair swung 13-18% *within* a run, which is the 20x20 instability
         * [ao.snakewarz.bots.search.RolloutPolicy] measures at 11% on the same instrument. Those two
         * cells stay where the runs that were taken for them put them. It is the reason the allowance
         * and not the ratio is the quantity anything is verified against.
         *
         * ### The strength, in a field, because the percentages above are a style match-up
         *
         * Eight rungs, two blocks of 200 rounds on disjoint seeds pooled per board, 11,200 matches a
         * board, 92-97% of them distinct games, no forfeits. **Every rating below is quotable only
         * beside this field**: the baseline `uct:budget=1000`; `rolloutDepth=25` at the allowance
         * above *and* at `budget=1000` as a labelled control, which is the handicap quantified rather
         * than a candidate; `uct:rolloutPolicy=liberty` at its own allowance; `puct:eval=territory`
         * and `alphabeta:eval=territory` at 1,000; `flat-monte-carlo`, which shares this bot's
         * rollout and never got this knob; and `chase`.
         *
         * | rating, 95% | 8x8 | 12x12 | 20x20 |
         * |---|---|---|---|
         * | `alphabeta:eval=territory@1k` | 176 (+163..+189) | 179 (+166..+193) | 162 (+149..+173) |
         * | `puct:eval=territory@1k` | 144 (+132..+156) | 108 (+95..+120) | 77 (+63..+88) |
         * | `rolloutDepth=25` at **equal allowance**, *control* | 78 (+65..+91) | 66 (+54..+78) | 125 (+112..+138) |
         * | **`rolloutDepth=25` at equal clock** | **74 (+60..+89)** | **64 (+52..+75)** | **99 (+86..+113)** |
         * | `uct:budget=1000` — the baseline | 78 (+65..+89) | 51 (+39..+63) | 21 (+10..+33) |
         * | `uct:rolloutPolicy=liberty` at its allowance | 56 (+43..+68) | 13 (+2..+25) | 35 (+23..+48) |
         *
         * **At 20x20 it is +78 over the baseline with the intervals disjoint, and it passes
         * `puct:eval=territory` by +22. At 12x12 it is +13 and the intervals overlap**, which is a
         * null on this evidence. A paired sequential test at `elo0=0, elo1=10` says the same thing
         * twice: **BETTER, +93 Elo ±33 over 260 boards** at 20x20, and **UNDECIDED, +17 ±17 over
         * 800** at 12x12, stopped at the ceiling rather than settled. No blindness note fired on
         * either.
         *
         * **At 8x8 it is −4, and the row above it is the reason: the equal-allowance control rates
         * exactly level with the baseline as well.** The other two boards have something for the
         * allowance to hand back — +15 at 12x12 and +104 at 20x20 per iteration — and the smallest
         * board has **nothing**, so the 4-5 Elo the clock takes is not what makes this a null. The
         * paired `ab` agrees rather than fighting it: **UNDECIDED, −1 Elo ±16 over 800 boards**,
         * stopped at the ceiling, no blindness note. That agreement is worth stating because 8x8 is
         * where this repository's field and head-to-head have disagreed before — an unrelated phase
         * has a rung rating +131 above bare `puct` there while losing to it 89-111 — and here they do
         * not. It does live in that field, though, one rung up and between two entrants this is not
         * about: `puct:eval=territory` scores 54% off `alphabeta:eval=territory` while rating 32
         * below it, and the fit says so itself. Read the 8x8 bars knowing they are optimistic by up
         * to ~1.6x as well: an opening is a function of the match seed and an 8x8 has about 40 usable
         * ones, so 400 rounds resample ~40 boards while the interval treats 100 groups as
         * independent. Widening them changes nothing about a 4-point gap.
         *
         * The head-to-heads pool, per board, to **47.5% / 55.8% / 64.8%** at equal clock and
         * **50.8% / 57.8% / 65.5%** at equal allowance. The 12x12 and 20x20 cells of the second row
         * reproduce the 58.5% and 67.0% above **on seeds that measurement never touched**, which is
         * the part that says the lead was real rather than a lucky block; the 8x8 cell is the first
         * reading that board has ever had at any allowance, and it is a coin.
         *
         * **And the two depth rungs are a third reading of the exchange rate, for free.** They differ
         * in nothing but allowance, so the 26 Elo between them at 20x20 over `ln(1000/760)` = 0.274
         * e-folds is **95 Elo per e-fold** — inside [ao.snakewarz.bots.search.RolloutPolicy]'s 80-137
         * band and beside the 111 an unrelated phase measured on `alphabeta`. The 12x12 pair differ
         * by 2 Elo over 0.113 e-folds and the 8x8 pair by 4 over 0.051, and overlapping intervals
         * cannot resolve either, so neither says anything in either direction.
         *
         * ### What it is still not, which is a default
         *
         * Moving this one moves `GoldenMoveStreamTest`'s `UCT against random on 12x12` on **both**
         * targets, inverts `RolloutTruncationTest`'s fixture — which compares a depthed variant
         * against a default that plays out — and moves three `BotLadderTest` figures: `uct` over
         * `flat-monte-carlo`, the cramped-allowance `uct`-against-itself case, and **`puct` over
         * `uct`, which is the narrowest rung on that ladder at 12 of 20 against a threshold of 11.**
         * A stronger `uct` pushes that rung down, and the board this is worth the most on is not the
         * board the ladder is measured on. That is a release decision and it belongs to a person.
         *
         * **What the three boards say together, which is what the decision is actually about:** the
         * gain is monotone in board size and it is worth nothing at the bottom of the range. One
         * board of the three supports adopting, and the two that do not are the ladder's own 12x12
         * and the 8x8 `index.html` opens on — so the board a player meets first is the board where
         * this default would buy them **−4 Elo against 4-5 Elo of clock**. That is not an argument
         * against the 20x20 result, which is large and replicated; it is the shape of the tradeoff a
         * single default has to be right about on every board at once.
         *
         * It ships wired and off rather than deleted, because a measured "no" is worth more with the
         * thing still there to re-measure — and this is what that is for:
         * `./gradlew :lab:run --args="play uct uct:rolloutDepth=25"`,
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
         * What each snake plays inside a rollout, which until this existed was uniform-random and
         * nothing else.
         *
         * [RolloutPolicy] carries the settings, what each costs to read, and the divergence probe
         * that says how often each of them would play a different move from the default — the number
         * that had to come first, since a policy that changes one step in a thousand cannot move a
         * search however good it is.
         *
         * **It defaults to [RolloutPolicy.UNIFORM] and the blast radius of that is nothing.** A
         * rollout is what `flat-monte-carlo` is made of as well, so a default moving here would move
         * two golden hashes and the ladder rung between those two bots; declaring the alternatives as
         * a setting reaches them from `:lab` and from a replay while leaving every shipped bot
         * playing the stream it played.
         *
         * Not a [BotKnob.tradeoff], and after the field below that is settled rather than pending:
         * the whole question this asks is which policy is *stronger per millisecond*, and
         * [ao.snakewarz.bots.search.EvaluationCost.ROLLOUT] being a flat `1` means a matrix at equal
         * allowance cannot answer it — a dearer policy there buys the same number of iterations and
         * pays for them in wall clock nobody charged it for. Measured at equal clock, neither setting
         * is worth playing on the two boards a match is usually played on. [RolloutPolicy] carries
         * the ratings, the field they are only quotable beside, and the one board where the sign
         * turns over.
         */
        val ROLLOUT_POLICY = BotKnob.Choice(
            name = "rolloutPolicy",
            label = "Rollout policy",
            help = "How a rollout picks moves: at random, refusing dead ends, or from the move prior.",
            default = RolloutPolicy.UNIFORM,
            values = RolloutPolicy.VALUES,
        )

        /**
         * Everything this bot lets you tune, in the order a form would show it.
         *
         * The sidebar shows the first two — see [ao.snakewarz.botapi.registry.BotEntry.offered]. The other three are
         * settled numbers, tunable from `:lab` and from a replay and nowhere a player can reach.
         * Appended, never inserted: `:lab` logs an entrant as its knobs in this order.
         */
        val KNOBS: List<BotKnob> = listOf(SEARCH, EXPLORATION, MAX_NODES, ROLLOUT_DEPTH, ROLLOUT_POLICY)

        /**
         * Deeper than the tree can grow at any sane allowance: one node is added per iteration, so
         * a chain this long would need [MAX_DEPTH] iterations all descending the same line.
         */
        const val MAX_DEPTH = 512
    }
}
