package ao.snakewarz.bots.search.puct

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.bots.reactive.space.SpaceBot
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.bots.search.learned.LearnedEval
import ao.snakewarz.bots.search.uct.UctBot
import ao.snakewarz.bots.search.uct.portableLog
import ao.snakewarz.core.grid.Direction

/**
 * PUCT with a hand-written evaluation where AlphaZero has a neural network.
 *
 * The same shape as [UctBot] — descend, expand, judge, credit — with the two things a network would
 * supply written out by hand instead. The **policy** becomes a weighted reading of each move's
 * destination square ([MovePrior]), and the **value** becomes a [LeafEval] that appraises the
 * position rather than playing it out ([TerritoryEval]).
 *
 * ### The evaluation is the experiment, so everything else is held still
 *
 * The interesting claim about a hand-written evaluation is not that it works but that it is worth
 * what it costs, and that is a comparison. So `eval` is a knob rather than a decision, and its six
 * values are the same search told to think six prices' worth: [MobilityEval] reads sixteen squares
 * and buys about a hundred times the tree, [TerritoryEval] sweeps the board once, [SurvivalEval]
 * sweeps it and then works out how much of each region its owner could actually use, [HorizonEval]
 * prices that same reading in moves rather than in squares, [ChamberEval] keeps the chambers the
 * other two sum away and asks what each is worth on its own, and
 * [ao.snakewarz.bots.search.learned.LearnedEval] reads that same decomposition as a feature vector
 * and weights it by a fit rather than by a sweep. Two entrants differing only in that line is what
 * makes a batch over them mean anything.
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
 * ### What it knows, it can carry as known
 *
 * A descent that ends in a finished game has an exact answer rather than an estimate, and averaging
 * that into a running mean throws it away. [SOLVER] turns on the MCTS-Solver, which carries it —
 * [PuctTree]'s KDoc has the construction and [PuctTree.proveFromChildren] the max^n assumption it
 * rests on. Off by default, so the knob is one variable against this bot rather than a new bot.
 *
 * ### No logarithm, and an exponential this bot had to build
 *
 * PUCT is `Q + c·P·sqrt(N)/(1+n)`, and `sqrt` is exactly specified by IEEE-754. So the standing rule
 * that nothing in `:bots` may call `kotlin.math.ln` costs the *selection* nothing — see
 * [PuctTree.selectPuct], and [portableLog] for what UCB1 has to do instead.
 *
 * The prior is where that rule actually bites, because a softmax wants `exp`. [PRIOR_TEMPERATURE] is
 * off by default and the proportional prior below it needs none; above zero the exponential comes
 * from [portableExp], built from `+ - * /` for the same reason [portableLog] is. That is what keeps
 * `puct` in `GoldenMoveStreamTest`'s cross-target set rather than pinned to one target.
 *
 * **Handed no allowance it costs exactly nothing**, exactly as [UctBot]: the loop is guarded on
 * `budget.remaining` and the answer then comes from [SpaceBot]'s flood fill, which charges nothing.
 */
public class PuctBot(setup: BotSetup) : Bot {
    private val unbudgeted = SpaceBot(setup)
    private val slotCount = setup.opponentCount + 1

    private val exploration = CPUCT.read(setup.params)
    private val solving = SOLVER.read(setup.params)
    private val tree = PuctTree(raveEquivalence = RAVE.read(setup.params))
    private val raving = tree.raving
    private val path = IntArray(MAX_DEPTH)

    /** The direction played out of `path[i]`, and by whom — the AMAF set of one descent. */
    private val playedMove = IntArray(if (raving) MAX_DEPTH else 0)
    private val playedBy = IntArray(if (raving) MAX_DEPTH else 0)

    /** One value per slot, refilled at every leaf and read back by [PuctTree.record]. */
    private val values = DoubleArray(slotCount)

    /** One node's four priors, refilled at every expansion and copied out by [PuctTree.open]. */
    private val priors = DoubleArray(Direction.entries.size)

    private val policy = MovePrior(
        setup.grid,
        PRIOR_LIBERTY.read(setup.params),
        PRIOR_PINCH.read(setup.params),
        PRIOR_WALL.read(setup.params),
        PRIOR_TAIL.read(setup.params),
        PRIOR_TEMPERATURE.read(setup.params),
    )

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

        HORIZON -> HorizonEval(
            setup.grid,
            slotCount,
            TERRITORY_WEIGHT.read(setup.params),
            MOBILITY_WEIGHT.read(setup.params),
            TRAP_PENALTY.read(setup.params),
            SEPARATION_BONUS.read(setup.params),
        )

        CHAMBER -> ChamberEval(
            setup.grid,
            slotCount,
            TERRITORY_WEIGHT.read(setup.params),
            MOBILITY_WEIGHT.read(setup.params),
            TRAP_PENALTY.read(setup.params),
            SEPARATION_BONUS.read(setup.params),
            PARITY_WEIGHT.read(setup.params),
            FRONTIER_PENALTY.read(setup.params),
            SEAL_PENALTY.read(setup.params),
        )

        // No weights from the params: this one's coefficients are the fit, and a fit is not four
        // numbers somebody can reasonably be offered a slider for.
        LEARNED -> LearnedEval(setup.grid, slotCount)

        // `else` rather than a fifth named branch: BotKnob.Choice.read is total, so a value from a
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
        policy.into(turn.board, turn.self, legal, priors)
        tree.open(PuctTree.ROOT, legal, priors)

        while (iterate(turn)) {
            // The other exit is inside iterate(): the allowance would not stretch to another
            // evaluation. This one is the solver's — once the root's value is known exactly there is
            // nothing another iteration could add, and with the solver off it never fires.
            if (tree.isProven(PuctTree.ROOT)) {
                break
            }
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
     *
     * With [SOLVER] on there is a fourth step after the credit: a pass back up the same path settling
     * whatever the leaf's certainty settles. It runs only when the leaf was a finished game, because
     * that is the only thing that can have changed a node's status this pass.
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
        var settled = false
        var plies = 0

        while (true) {
            val mover = playout.toAct
            val direction = tree.selectPuct(node, exploration, FIRST_PLAY)
            if (raving) {
                // Indexed by the node moved *from*, which is the one whose AMAF set this move joins.
                playedMove[depth - 1] = direction.ordinal
                playedBy[depth - 1] = mover.index
                plies = depth
            }
            playout.advance(direction)

            // Re-read after every advance, never carried over: advancing on a stale reading is an
            // IllegalStateException, and any move can be the one that ends the game.
            val result = playout.outcome

            val child = tree.childOrCreate(node, direction, mover)
            val extended = child != PuctTree.NO_NODE && depth < path.size
            if (extended) {
                path[depth++] = child
            }

            if (result != null) {
                // A game that really ended is worth more than a judgement of it.
                outcomeValues(result, slotCount, values)
                if (solving && extended) {
                    // And it is worth more than an average of it too. Only a node the path reached
                    // can be settled, since the walk back up is what carries the finding anywhere.
                    tree.proveTerminal(child, result.winner.index)
                    settled = true
                }
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
                policy.into(playout.board, next, moves, priors)
                tree.open(child, moves, priors)

                eval.valuesInto(playout, values)
                break
            }

            node = child
        }

        for (i in 0 until depth) {
            tree.record(path[i], values)
        }

        if (raving) {
            creditRave(plies)
        }

        if (settled) {
            // Upward from the leaf's parent. A node's value follows from its children's, so the
            // first ancestor that will not settle stops the walk — nothing above it could settle
            // either, and no node off this path changed status for it to settle from.
            for (i in depth - 2 downTo 0) {
                if (!tree.proveFromChildren(path[i])) {
                    break
                }
            }
        }
        return true
    }

    /**
     * Enters this descent's [moves] into the AMAF statistics of every node it passed through.
     *
     * A move joins the AMAF set of a node when the *same snake* played it at or below that node, so
     * the inner loop skips the plies belonging to anybody else. Quadratic in the depth of one
     * descent, which is a handful of comparisons: the tree is about seven plies deep at the shipped
     * allowance, and half of those plies are the opponent's.
     *
     * The move made *at* the node is counted too, which is the textbook definition — AMAF asks what a
     * direction was worth whenever it was played from here on, and the first play is one of those.
     * It does not double-count anything: the real statistic lives on the child node and this on the
     * edge, and [PuctTree.blended] is what decides how much of each to believe.
     */
    private fun creditRave(moves: Int) {
        for (i in 0 until moves) {
            val by = playedBy[i]
            val value = values[by]
            for (j in i until moves) {
                if (playedBy[j] == by) {
                    tree.recordRave(path[i], playedMove[j], value)
                }
            }
        }
    }

    internal companion object {
        /** The same range [UctBot] declares, so the two are comparable at the same numbers. */
        val SEARCH = BotKnob.Search(min = 0, max = 10_000, step = 100)

        /**
         * The experiment, and therefore the tradeoff — six value functions rather than six
         * settings of one. Each buys a different tree at the same allowance: [MOBILITY] is near-free
         * and buys about a hundred times the search, [TERRITORY] sweeps the board once, [SURVIVAL]
         * takes the sweep apart again, [HORIZON] prices what it finds in moves rather than in
         * squares, [CHAMBER] keeps the chambers the other two sum away, and [LEARNED] takes that
         * same decomposition as a feature vector into a fitted model. There is no ordering between
         * them that holds on every board.
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
            values = listOf(TERRITORY, MOBILITY, SURVIVAL, HORIZON, CHAMBER, LEARNED),
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
         * Whether a value the search *knows* is carried as known — the MCTS-Solver.
         *
         * A plain tree averages a finished game in with everything else the leaf saw. This propagates
         * it exactly instead: [PuctTree.proveTerminal] settles the node the game ended on,
         * [PuctTree.proveFromChildren] walks it back up under max^n, selection stops paying for a
         * settled child and the root answers with a settled move ahead of a merely popular one.
         *
         * ### How often it has anything to say, which is the ceiling on what it can be worth
         *
         * Measured `puct` against `puct` over six games a side at the shipped allowance, counting what
         * the search reaches rather than what it then does with it:
         *
         * | | 8x8 | 12x12 | 20x20 |
         * |---|---|---|---|
         * | iterations landing on a finished game | 0.39% | 0.19% | 0.18% |
         * | turns settling at least one node | 44% | 33% | 29% |
         * | turns where the **root** settles | 11% | 4% | 2% |
         * | decisions the knob actually changes | 1.4% | 1.4% | 6.6% |
         *
         * **The premise this was proposed on does not hold as stated.** A rollout bot walks a whole
         * game per iteration and so ends on a terminal every time; this bot appraises the leaf
         * statically, so only the *tree* can reach one — and at a thousand iterations over a branching
         * factor near three the tree is about seven plies deep. It sees a terminal only in the last
         * few plies of a line, which is one iteration in five hundred. Two things still cut the other
         * way: those few are concentrated exactly where a game is decided, and a settled node also
         * *redirects* every later iteration rather than merely correcting one number.
         *
         * The last row moves with the opponent as much as with the board — 4.7% against `uct` on the
         * 12x12 against 1.4% against another `puct` — which is what makes the head-to-head the wrong
         * shape of test for it.
         *
         * ### What it is worth, which is not one number
         *
         * Measured at the shipped allowance of 1000 under `mirrored` openings, against the same bot
         * with the knob off:
         *
         * | | head-to-head (`ab`) | rated over a seven-bot field, 1,200 games a side |
         * |---|---|---|
         * | 12x12 | **BETTER, +33 ±19 Elo** over 420 boards | 289 (+266..+315) against 274 (+248..+300) |
         * | 20x20 | **NO BETTER, -41 ±25 Elo** over 240 boards | 236 (+213..+260) against 263 (+240..+290) |
         *
         * **It helps on the shipped board and hurts on the large one.** Both head-to-heads are about
         * three standard errors on the boards the two did not share — 91 of 142 decided boards at
         * 12x12, 24 of 76 at 20x20 — so the flip is not noise, and it runs the opposite way to the
         * last row of the table above: the knob changes nearly five times as many decisions on the
         * 20x20, and every one of those extra changes is worth points to the control. Neither
         * magnitude should
         * be quoted on its own: an `ab` stops at whichever bound it reaches first, so each number is
         * the generous end of its own interval, and only the sign survives that.
         *
         * **Against anything that is not its own control it does nothing measurable**, which is why
         * the field ratings are so much flatter than the head-to-heads. Only the direct pairing
         * moves — 0.55 at 12x12 and 0.44 at 20x20 — while against `uct` it scores 0.58 / 0.60 where
         * the knob-off bot scores 0.57 / 0.59, and against `eval=survival` 0.37 / 0.33 against
         * 0.39 / 0.35. `report` says it from the other side: on both boards every loss on both sides
         * is `TRAPPED`, at the same median length (91 / 91 and 227 / 223 moves) and the same board
         * fill. The solver changes how many games are lost, slightly, and not how they are lost.
         *
         * The untested suspect for the 20x20 sign is the early exit in [PuctBot.chooseMove]: a root
         * settled as a **loss** stops the search as readily as one settled as a win, and a line that
         * is lost against max^n is not lost against an opponent that might still err. Nobody has
         * measured that, so it is a hypothesis and not a finding.
         *
         * ### It costs nothing measurable, which is the one part that came out as expected
         *
         * `lab time`, best of five, averaged over seeds 1-6 on each board — microseconds per turn:
         *
         * | | 12x12 | 20x20 |
         * |---|---|---|
         * | off | 2181 | 5320 |
         * | on | 2166 | 5047 |
         *
         * `time` plays a real game, so the knob changes the game it is measured on and most of those
         * pairs are not the same position. On the four seeds where both configurations happened to
         * play the same number of turns the settled one is still the cheaper — two `ByteArray` writes
         * and a walk of at most seven ancestors on one iteration in five hundred, against an exit
         * that stops buying iterations the moment the root is settled.
         *
         * **Read that as "no worse", not as a measured saving.** The field's own `us/turn` column
         * disagrees, putting the settled bot's 20x20 matches 1-15% *above* the control's against the
         * same opponents — but that column is a whole match's clock over both bots' thinking, taken
         * off an eighteen-thread pool, which is the figure `time` exists to replace. Between them the
         * honest resolution is about ten percent, and nothing at that scale is what decides this knob.
         *
         * **Off by default, and that is what makes it measurable.** Shipping it on would move
         * `GoldenMoveStreamTest`'s `puct` hash and leave the head-to-head with nothing to compare
         * against. Turning it on is the experiment; the default is the control. The expectation
         * written here first — that at 1.4% of changed decisions `ab` would be the thin instrument
         * and a field the sensitive one — came out backwards. `ab` reached a verdict on both boards
         * even with two thirds of its boards splitting exactly, and the field is where the effect
         * washes out: three of its six pairings sit at 0.97 or better whichever way the knob is set,
         * so they carry no information about it at all. Adopting it is the sequence in
         * `docs/Bots.md`.
         */
        val SOLVER = BotKnob.Flag(
            name = "solver",
            label = "Solver",
            help = "Carry a value the search has proved as certain instead of averaging it away.",
            default = false,
        )

        /**
         * How much of [FillableSpace]'s chessboard cap [ChamberEval] applies to a chamber.
         *
         * A grid is bipartite, so a walk alternates colours and a chamber holding six squares of one
         * colour and one of the other is worth three of its seven to a walk that never revisits a
         * square. That cap is exact for classic Tron and too harsh at the shipped
         * `RulesConfig.growEveryNthMove` of two, where a big enough chamber is one a retracting walk
         * loops in — and it is still right in a corridor, and in the late endgame where a snake is
         * long enough that nothing clears. One number cannot be both, so this grades between the cap
         * and the raw square count.
         *
         * ### Swept, and left at the cap anyway
         *
         * `spsa puct:eval=chamber` over all three chamber weights at the shipped allowance walked
         * this one from `1.0` to **`0.1`** — nearly abandoning the cap, which is the correction the
         * retraction argument asks for and the direction [HorizonEval] was built in. **The ablation
         * refused it**, `ab` against `eval=survival` on a 12x12 at budget 1000:
         *
         * | point | verdict |
         * |---|---|
         * | `0.1` with the other two at their swept values | BETTER, +69 ±30 over 240 boards |
         * | `1.0` with the other two at their swept values | BETTER, **+85 ±32** over 220 boards |
         * | `0.1` with the other two off — this knob alone | NO BETTER, −37 ±23 over 180 boards |
         *
         * So the cap stays. The third row is the one to read carefully: 141 of its 180 boards split
         * exactly, so that test mostly could not see the knob at all — the honest claim is *the
         * relaxation buys nothing*, not *it costs 37*. What the first two rows do establish is that
         * the sweep's move was drift while the other two weights carried the objective, which is
         * what a coordinate with nothing pulling it back does. [ChamberEval] carries the whole table.
         *
         * **A knob tuned at one allowance is tuned at that allowance**, and this one was swept at
         * budget 1000 on a 12x12. The cap binds harder on a snake that is long relative to its room,
         * so a larger board is a different question and an unmeasured one.
         */
        val PARITY_WEIGHT = BotKnob.Decimal(
            name = "parityWeight",
            label = "Parity",
            help = "How much of the chessboard cap a chamber is held to, against its raw square count.",
            default = 1.0,
            min = 0.0,
            max = 1.0,
            step = 0.05,
        )

        /**
         * What [ChamberEval] takes off a chamber for the share of it sitting on a contested edge.
         *
         * [TempoOwnership] awards a square to whoever reaches it first, and on a boundary that is a
         * margin of half a step — which the next few moves can overturn. Every other leaf here counts
         * such a square exactly as it counts one in the back of a sealed pocket. This is the discount
         * that says they are not the same square, and it is applied per chamber in proportion to how
         * much of that chamber is on the boundary, so it is near nothing in an open midgame and large
         * in the endgame where the two snakes are threading past each other.
         *
         * ### `0.2`, swept — and it is a sharpener rather than a term that pays
         *
         * It shipped at `0.5` on the argument that a square awarded on half a step of tempo is about
         * half held. `spsa puct:eval=chamber` over the three chamber weights at budget 1000 on a
         * 12x12 settled on **`0.2`**, and the ablation says what that is worth, `ab` against
         * `eval=survival`:
         *
         * | point | verdict |
         * |---|---|
         * | `0.2` with [SEAL_PENALTY] at its swept `0.55` | BETTER, **+85 ±32** over 220 boards |
         * | `0.2` with the seal off — this knob alone | UNDECIDED, +9 ±9 over 2,000 boards, capped |
         *
         * **On its own it is a number inside its own error bar**, over two thousand boards, which is
         * about as flat as this instrument can say. With the seal beside it the pair is worth roughly
         * twice what the seal is worth alone. Read it as sharpening the reading the seal takes: what
         * a chamber is *worth* being cut off from is not its square count when a rival is standing on
         * its edge. [ChamberEval] carries the full table and the field.
         */
        val FRONTIER_PENALTY = BotKnob.Decimal(
            name = "frontierPenalty",
            label = "Contested",
            help = "How much a chamber is marked down for the share of it somebody else can still take.",
            default = 0.2,
            min = 0.0,
            max = 1.0,
            step = 0.05,
        )

        /**
         * What [ChamberEval] takes off for the share of its own region a snake's best chain misses.
         *
         * The reading no other leaf here has. A chain through the chambers is a max over children, so
         * a region that shatters into pockets is worth its best pocket — and [SurvivalEval] compares
         * those chains between snakes without ever asking whether one of them has just cut itself off
         * from half of what it owns. Twenty spendable squares out of twenty-two and twenty out of
         * forty are the same number to it and are not the same position.
         *
         * ### `0.55`, swept — and it is the whole of what [ChamberEval] is worth
         *
         * It shipped at `0.25`, below [TRAP_PENALTY] on the argument that ground the chain cannot
         * reach is not ground that is gone — a retracting tail can open it again — so it should claim
         * less of the reading than having nothing legal right now already does. **The sweep more than
         * doubled it, past [TRAP_PENALTY]**: `spsa puct:eval=chamber` over the three chamber weights
         * at budget 1000 on a 12x12 settled on `0.55`. `ab` against `eval=survival`, which is this
         * leaf with all three chamber terms neutral:
         *
         * | point | verdict |
         * |---|---|
         * | `0.55` with nothing else changed — this knob alone | BETTER, **+37 ±20** over 400 boards |
         * | `0.55` with [FRONTIER_PENALTY] at its swept `0.2` | BETTER, **+85 ±32** over 220 boards |
         *
         * Of the three weights this is the only one worth points on its own, which makes sense of
         * where it landed: shattering your own region is not a discount on a quantity, it is closer
         * to being trapped a few moves early, and the argument that put it below [TRAP_PENALTY] was
         * pricing the wrong thing. Both runs stopped at the sequential test's upper bound, so "at
         * least 10 Elo" is what was proven and 37 and 85 are the generous ends. [ChamberEval] carries
         * the field and the diagnosis.
         */
        val SEAL_PENALTY = BotKnob.Decimal(
            name = "sealPenalty",
            label = "Sealed",
            help = "How much a snake is marked down for the share of its own room it has cut off.",
            default = 0.55,
            min = 0.0,
            max = 1.0,
            step = 0.05,
        )

        /**
         * What one free neighbour of a destination square adds to [MovePrior]'s score.
         *
         * The reading this prior was built on and the only one whose default is not zero, so it is
         * also the scale the other three are measured against: what a penalty of `0.2` means depends
         * entirely on what a liberty is worth. Under the proportional normalisation it is a
         * **temperature** as well — only the ratio of the weights to `MovePrior.PRIOR_FLOOR` matters
         * there, so raising this sharpens the prior and lowering it flattens one.
         *
         * `0.5` is where it shipped as a constant and it has never been swept. It was deliberately
         * left out of the sweep that settled the four beside it: with [PRIOR_TEMPERATURE] above zero
         * the two are the same degree of freedom, and a gradient search handed both would split the
         * scale between them arbitrarily and report a point neither coordinate is right at.
         */
        val PRIOR_LIBERTY = BotKnob.Decimal(
            name = "priorLiberty",
            label = "Prior room",
            help = "How much a move is preferred for the free squares it lands next to.",
            default = 0.5,
            min = 0.0,
            max = 2.0,
            step = 0.05,
        )

        /**
         * What [MovePrior] takes off a destination whose free neighbours are joined only through it.
         *
         * **The seal question, asked at the prior.** [ChamberEval]'s `sealPenalty` is the one reading
         * of that leaf worth points on its own — being cut off from ground the sweep says you own is
         * closer to being trapped early than to a discount on a quantity — and a move onto a cut
         * vertex is the move that does the cutting. The leaf sees it a ply late and by taking a
         * region apart; this sees it before the move is searched, off the eight squares around the
         * destination.
         *
         * Signed rather than floored at zero, because which way it should point is a real question
         * and not one to settle by declaration: a corridor is a commitment in the midgame and is the
         * only ground left to spend in the endgame.
         *
         * ### `0.8` — the seal question does transfer, and it needs a temperature to be usable
         *
         * Rated over a 3,100-match field at the shipped allowance on a 12x12 — [MovePrior] carries
         * the whole table:
         *
         * | entrant | rating | 95% |
         * |---|---|---|
         * | this knob alone at `0.8` | 96 | +70..+125 |
         * | with [PRIOR_TAIL] at `0.8` beside it | 86 | +54..+122 |
         * | the same pair with [PRIOR_TEMPERATURE] at `0.9` | **146** | +124..+168 |
         * | `eval=chamber`, the baseline | 43 | +23..+64 |
         *
         * `+53` on the baseline on its own, so the reading `ChamberEval.sealPenalty` is built on does
         * transfer from the leaf to the prior: a move onto a square that cuts its own neighbourhood
         * really is worse, and it costs eight board reads to see instead of a decomposition.
         *
         * **The second row is the interesting one.** Two large weights in a *proportional* prior are
         * worth less than either alone, because a move collecting both penalties lands on
         * `MovePrior.PRIOR_MINIMUM` and everything above it flattens. The third row is the same pair
         * normalised as a softmax. That is the whole argument for the temperature and it could not
         * have been made before there was a second reading to spread.
         */
        val PRIOR_PINCH = BotKnob.Decimal(
            name = "priorPinch",
            label = "Prior pinch",
            help = "How much a move onto a square that would cut its own surroundings is marked down.",
            default = 0.0,
            min = -1.0,
            max = 1.0,
            step = 0.05,
        )

        /**
         * What [MovePrior] adds per board edge the destination sits against.
         *
         * [PRIOR_LIBERTY] counts a neighbour blocked by the wall and one blocked by a snake as the
         * same missing liberty, and they are not the same: a body square comes back when a tail
         * retracts and the board never does. This is the half of the blocked count that is permanent,
         * and it is a genuine second dimension rather than a rescaling — the pair of readings spans a
         * plane where the liberty count alone spans a line in it.
         *
         * Signed, because "hug the wall to keep the middle whole" and "the wall is where you run out
         * of room" are both real and neither is obviously the one that wins here.
         *
         * ### The one reading nothing here could settle
         *
         * A four-weight `spsa` over this and the three beside it walked it out to `0.45` and back and
         * finished at **exactly its starting `0.0`** — the only coordinate of the four that did not
         * move. Tested on its own anyway, because a sweep leaving a knob alone is weak evidence and a
         * `NO BETTER` is a result worth having:
         *
         * | point | verdict | boards |
         * |---|---|---|
         * | `0.45` alone, against `eval=chamber` | **UNDECIDED, +39 ±23** | 400, capped |
         *
         * Between the bounds after four hundred boards, which is the instrument saying it cannot tell
         * — not that the reading is worthless. It leans positive and stayed there, and the field that
         * settled the other three never carried it, so this is the one candidate of the four with no
         * verdict at all. Re-opening it means entering it in a field beside them rather than running
         * more of the same head-to-head; [MovePrior] carries why that distinction decided the phase.
         */
        val PRIOR_WALL = BotKnob.Decimal(
            name = "priorWall",
            label = "Prior wall",
            help = "How much a move along the edge of the board is preferred, or avoided.",
            default = 0.0,
            min = -1.0,
            max = 1.0,
            step = 0.05,
        )

        /**
         * What [MovePrior] adds to a step that closes on this snake's own tail, and takes off one
         * that does not.
         *
         * The oldest survival heuristic there is: ground your own tail is about to free is ground you
         * can always get back to, so a snake that keeps its tail near never seals itself away from
         * everything. A grid step changes a Manhattan distance by exactly one, so the reading is a
         * sign rather than a magnitude and needs no scale of its own.
         *
         * ### `0.8`, and it is the strongest single reading in the prior
         *
         * Rated over a 3,100-match field at the shipped allowance on a 12x12, against the same bot
         * with every weight at zero — [MovePrior] carries the whole table:
         *
         * | entrant | rating | 95% |
         * |---|---|---|
         * | this knob alone at `0.8` | **113** | +87..+145 |
         * | this knob alone at `0.4` | 88 | +63..+113 |
         * | `eval=chamber`, the baseline | 43 | +23..+64 |
         *
         * `+70` on the baseline with the intervals disjoint, and the larger weight is the better one
         * — which is worth stating because the head-to-head said the opposite. It is also the reading
         * no leaf here can supply: [ChamberEval] appraises a *position* and this is a fact about a
         * *move*, and about the one square on the board that is going to come free whatever anybody
         * does.
         *
         * Beside [PRIOR_PINCH] and **without** [PRIOR_TEMPERATURE] the pair rates 86, below this
         * knob alone; with the temperature the three rate 146. So the strongest point in the field
         * contains this reading and is not this reading — see [MovePrior] on why.
         *
         * **Not yet a default, and what is missing is named.** All of the above is at `eval=chamber`,
         * which is not what this bot ships at — `ab puct puct:priorTail=0.8` measured `+158 ±51` over
         * 120 boards, which says it survives `eval=territory` and no more than that. Moving this
         * default moves `GoldenMoveStreamTest`'s `puct` hash and `BotLadderTest`'s thresholds, so it
         * needs the sequence in `docs/Bots.md` run at the shipped evaluation.
         */
        val PRIOR_TAIL = BotKnob.Decimal(
            name = "priorTail",
            label = "Prior tail",
            help = "How much a move that keeps this snake near its own tail is preferred.",
            default = 0.0,
            min = -1.0,
            max = 1.0,
            step = 0.05,
        )

        /**
         * The softmax temperature [MovePrior] normalises at, or `0` for the proportional prior.
         *
         * **Zero is the control**, and it is the default for the reason [SOLVER] is off by default:
         * it reproduces the prior this bot has always played, bit for bit, so a batch across this
         * knob is a batch about the normalisation and about nothing else.
         *
         * Above zero the prior is `exp(score / t)` normalised, which is what a policy head produces.
         * The ranking is the same either way — both forms are monotone in the score — so what moves
         * is how far apart the probabilities are, and PUCT allocates its allowance in proportion to
         * exactly that. Low is sharp and near-greedy on the prior; high tends to uniform.
         *
         * The exponential comes from [portableExp] rather than `kotlin.math.exp`, so this bot stays
         * in `GoldenMoveStreamTest`'s cross-target set with a temperature in it. That is the whole
         * reason the temperature could be had at all — see `docs/Coding-Standards.md` on SW-02.
         *
         * ### `0.9` — worth nothing on its own, and worth `+60` on top of the pair that needs it
         *
         * `spsa` walked it from `0` to `0.9` beside [PRIOR_PINCH] and [PRIOR_TAIL], and the field
         * that ablated all three says this is the coordinate carrying the point — [MovePrior] has the
         * whole table:
         *
         * | entrant | rating | 95% |
         * |---|---|---|
         * | this knob alone at `0.9` | 49 | +16..+81 |
         * | `eval=chamber`, the baseline | 43 | +23..+64 |
         * | [PRIOR_PINCH] and [PRIOR_TAIL] at `0.8`, no temperature | 86 | +54..+122 |
         * | the same pair **with** this knob at `0.9` | **146** | +124..+168 |
         *
         * Nothing alone, because a one-feature score has nothing to be spread; sixty points on top of
         * a two-feature score, because that score is wide enough that the proportional form clips it
         * against `MovePrior.PRIOR_MINIMUM` and the softmax does not.
         *
         * **So the agenda's premise for this workstream holds, conditionally.** The missing
         * temperature really was a cost of the `exp` ban — but only for a prior rich enough to want
         * one, which is why its absence read as a virtue for as long as the prior was one feature.
         *
         * Read the intervals rather than the differences: alone this knob is one interval inside
         * another, and it takes the field's fourth row to say anything about it at all. Every `ab`
         * run against this knob measured it **negative**, in both directions, and was wrong — see
         * [MovePrior] on why a per-coordinate head-to-head is the wrong instrument here.
         */
        val PRIOR_TEMPERATURE = BotKnob.Decimal(
            name = "priorTemperature",
            label = "Prior temperature",
            help = "How evenly the prior is spread over the moves. Zero keeps the proportional prior.",
            default = 0.0,
            min = 0.0,
            max = 4.0,
            step = 0.05,
        )

        /**
         * RAVE's equivalence parameter in visits, or zero to leave the AMAF machinery unbuilt.
         *
         * Rapid Action Value Estimation gives an edge a second estimate off the moves played *later*
         * in the same simulation, on the assumption that a move's worth does not depend much on when
         * it is played. [PuctTree.blended] holds the schedule; this is the visit count at which the
         * two estimates carry comparable weight, so higher trusts AMAF for longer.
         *
         * ### The variant here is not the textbook one, because this bot has no rollout
         *
         * Classical RAVE harvests its AMAF set from a Monte Carlo rollout — hundreds of moves per
         * simulation, which is what makes the statistic cheap. **This bot appraises the leaf
         * statically** ([SOLVER] carries the measurement that established it), so the entire
         * simulation is the tree descent and the AMAF set of a node is the moves its own mover plays
         * further down that one path. That is a real variant and not a degenerate one — but it
         * inverts the property RAVE is *for*, and the inversion is structural rather than a matter of
         * tuning:
         *
         * - AMAF exists to give a **rarely-visited** node a usable estimate early.
         * - Here a node's AMAF supply is the number of its own mover's plies **below** it, which is
         *   largest at the root and zero at the frontier — so the statistic is richest exactly where
         *   the real visit counts already are, and absent exactly where it was wanted.
         *
         * ### And the move alphabet is four directions, which cuts the other way
         *
         * AMAF is normally keyed on a board point, and its independence assumption is that occupying
         * that point is worth about the same whenever it happens. A move here is a *direction*, so
         * the assumption becomes "heading north from around here is worth about the same two plies
         * later" — weaker, but not vacuous over a seven-ply horizon in which a head travels three
         * squares. What it *would* be is vacuous behind a rollout: over a hundred random moves every
         * snake plays every direction, every AMAF mean converges to the same number and the statistic
         * discriminates nothing. **A four-symbol alphabet saturates in a long simulation**, so the
         * rollout-free searcher is the better host for direction-AMAF rather than the worse one, and
         * the obvious reading — "RAVE needs rollouts, so put it on `uct`" — is backwards here.
         *
         * ### Progressive bias is already here, which is the other half of this settled by reading
         *
         * Chaslot's progressive bias adds `f(s,a) / (n + 1)` to a UCT score so a hand-written prior
         * fades as visits accumulate. PUCT's exploration term **is** that expression —
         * `c * P(s,a) * sqrt(N) / (1 + n)`, see [PuctTree.selectPuct] — so there is nothing to add to
         * this bot and the mechanism to add it to is `uct`, which has no prior to decay.
         *
         * ### Coverage, which bounds what any of it can be worth
         *
         * `puct:eval=chamber` against itself at the shipped allowance on a 12x12, counting what
         * [PuctTree.selectPuct] actually reads rather than what the search then does with it:
         *
         * | | figure |
         * |---|---|
         * | scored edges with an AMAF estimate behind them | **89.6%** |
         * | scored edges of a child **nobody has visited** | 10.3% |
         * | of all scored edges: unvisited child **with** AMAF — RAVE's whole purpose | **0.0%** |
         * | mean real visits at a scored edge | 64 |
         * | mean AMAF samples where there are any | 211 |
         * | mean `beta` at `rave=50` / `rave=500` | 0.42 / 0.58 |
         * | AMAF writes per iteration | 18 |
         * | decisions changed against the control, `rave=50` / `rave=500` | 4.9% / 10.8% |
         *
         * **The third row is the finding, and it is exact rather than empirical.** This search opens
         * one node per iteration and appraises it immediately, so a node's subtree is *empty* at the
         * moment it is created — and a node's AMAF set can only be filled by descents that pass
         * through it. So every edge's first real visit strictly precedes any AMAF evidence about it,
         * and the mechanism RAVE exists for — a usable estimate before a child has been tried — can
         * never fire. A rollout is what normally supplies it: one simulation from a fresh node covers
         * hundreds of moves and fills every one of that node's edges at once.
         *
         * What is left is the 89.6%, and that is RAVE running where it was never needed: on edges
         * carrying 64 real visits, moved 42% of the way toward a statistic gathered from other plies
         * of the same subtree. Prior information applied to a well-estimated quantity is noise, and
         * a fifth of a decision in twenty changes as a result.
         *
         * ### Cost
         *
         * At `rave` small enough to compute the whole mechanism and change no ranking — the probe
         * `MovePrior` uses — against `puct:eval=learned` at the shipped allowance on a 12x12, paired
         * seed by seed with `uct` carried as a control. All six seeds played the same number of
         * turns, so the game is held still and the ratio is cost alone:
         *
         * | seed | 1 | 2 | 3 | 4 | 5 | 6 |
         * |---|---|---|---|---|---|---|
         * | ratio | 1.035 | 1.020 | 1.049 | 1.042 | 1.085 | 0.941 |
         *
         * **About 3%**, or roughly 7 Elo at P3's exchange rate. Cheap, and irrelevant beside what the
         * field says.
         *
         * ### Strength — three settings and the control, entered into one field and rated together
         *
         * Which is the instrument [MovePrior] establishes: a per-coordinate head-to-head between two
         * settings of the same bot measures a style match-up, not strength. 4,200 matches at the
         * shipped allowance on a 12x12, 2,915 of them distinct, worst contested pairing 119 of 200:
         *
         * | entrant | rating | 95% |
         * |---|---|---|
         * | `rave=50` | 137 | +117..+155 |
         * | **`eval=learned`, the control** | **133** | +115..+154 |
         * | `eval=chamber` | 107 | +88..+128 |
         * | `rave=200` | 105 | +86..+124 |
         * | `rave=1000` | 24 | +7..+43 |
         * | `uct` | −13 | −34..+8 |
         *
         * **Monotone in how much the search is told to believe AMAF, and the best of it is the
         * control.** `rave=50` is four points above with the intervals almost entirely overlapping —
         * a null, and a null that still owes the 3% of clock above, which is about seven points.
         * Every larger setting is worse, and `rave=1000` by a hundred with the intervals disjoint.
         * There is no interior optimum to find: the limit of small `rave` **is** the knob switched
         * off, and the curve descends from there.
         *
         * The shape fits what the coverage says. At `rave=50` the average blend is already 42% AMAF
         * on edges carrying 64 real visits, and it neither helps nor hurts — the AMAF estimate is
         * gathered from nearby plies of the same subtree, so it behaves as a shrinkage of the real
         * mean toward its own neighbourhood rather than as new information. Shrink harder and the
         * search stops distinguishing the moves at all.
         *
         * So the honest answer to *do AMAF statistics transfer in this game* is **no**, and the
         * reason is not the four-direction alphabet the risk line predicted. It is that the only
         * simulation available to a rollout-free search is the tree itself, which supplies AMAF
         * exactly where the real statistic is already strongest and never where it is weakest.
         *
         * **Off by default**, so the two AMAF arrays are length zero, [creditRave] is never called
         * and `GoldenMoveStreamTest`'s `puct` hash is a hash of the same bot. It ships wired and off
         * for [UctBot.ROLLOUT_DEPTH]'s reason: the measurement above is worth more with the thing still
         * there to re-run, and the one setting that would change the answer — a searcher with a
         * rollout under it — is a change to this bot rather than to this knob.
         */
        val RAVE = BotKnob.Decimal(
            name = "rave",
            label = "RAVE",
            help = "Visits at which a move's record elsewhere in the search stops outweighing its own. 0 is off.",
            default = 0.0,
            min = 0.0,
            max = 10_000.0,
            step = 25.0,
        )

        /**
         * Everything this bot lets you tune, in the order a form would show it.
         *
         * The sidebar shows the first two — see [ao.snakewarz.botapi.registry.BotEntry.offered]. All the
         * rest are the ablation, and the ablation is a `:lab` batch rather than a form: [CPUCT] has
         * an optimum a sweep finds, and the four appraisal weights do nothing at all at [MOBILITY],
         * so four of the seven rows used to be dead most of the time they were on screen.
         *
         * [TerritoryEval] and [SurvivalEval] read the same four, with the same meanings — a share of
         * the board and a share of what can be filled are the same kind of quantity, so a weight
         * swept against one transfers. That is also why adding [SurvivalEval] added no knob. The
         * three after [SOLVER] are the ones [ChamberEval] needs and nothing else reads, because they
         * price a chamber and the other four evaluations have no chambers to price. The five after
         * those are [MovePrior]'s and are read at every setting of [EVAL], because every setting of
         * it searches through the same prior.
         *
         * [SOLVER] and everything after it are last because `:lab` logs an entrant as its knobs in
         * this order and `report` resolves one by a prefix of that string. Appending keeps every
         * prefix anybody has already written still naming what it named.
         *
         * **This bot is what [BotKnob.MAX_PER_BOT] is set by**, and adding to it here is the reason
         * that bound has moved once: it is a `:bot-api` constant and `ReplayCodec` reads the same one
         * when it decodes a slot, so it is a decision about what payload a decoder will accept rather
         * than a number to nudge. `ShippedBotsTest` pins the count so it fails loudly.
         */
        val KNOBS: List<BotKnob> =
            listOf(
                SEARCH, EVAL, CPUCT, TERRITORY_WEIGHT, MOBILITY_WEIGHT, TRAP_PENALTY, SEPARATION_BONUS, SOLVER,
                PARITY_WEIGHT, FRONTIER_PENALTY, SEAL_PENALTY,
                PRIOR_LIBERTY, PRIOR_PINCH, PRIOR_WALL, PRIOR_TAIL, PRIOR_TEMPERATURE,
                RAVE,
            )

        /** [TerritoryEval] — a share of the board off one sweep. Released as `expert`; see [EVAL]. */
        const val TERRITORY: String = "territory"

        /** [MobilityEval] — nearly free, so the allowance buys a far bigger tree. */
        const val MOBILITY: String = "mobility"

        /** [SurvivalEval] — what each snake could still use, rather than what it can merely see. */
        const val SURVIVAL: String = "survival"

        /** [HorizonEval] — the same regions priced in moves, which is what a retracting tail buys. */
        const val HORIZON: String = "horizon"

        /** [ChamberEval] — the same regions read chamber by chamber rather than summed to a number. */
        const val CHAMBER: String = "chamber"

        /**
         * [LearnedEval] — the same readings, weighted by a fit rather than by a sweep.
         *
         * The one value here whose behaviour is not fully determined by this file:
         * `LearnedWeights.ENCODED` is what it plays, and replacing that literal moves this bot the
         * way moving a knob's default would. `LearnedEval` carries what it was fitted on.
         */
        const val LEARNED: String = "learned"

        /**
         * `Q` for a child nobody has visited. Half is "unknown" on a `0..1` scale.
         *
         * Not a knob, because there is nothing yet to say about sweeping it. Promote it if there is.
         */
        const val FIRST_PLAY: Double = 0.5

        /** Deeper than the tree can grow at any sane allowance — [UctBot]'s figure, for its reasons. */
        const val MAX_DEPTH: Int = 512
    }
}
