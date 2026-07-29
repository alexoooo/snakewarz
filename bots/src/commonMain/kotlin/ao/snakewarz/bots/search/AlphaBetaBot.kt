package ao.snakewarz.bots.search

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.botapi.scratch.Scratch
import ao.snakewarz.bots.reactive.space.SpaceBot
import ao.snakewarz.bots.search.puct.ChamberEval
import ao.snakewarz.bots.search.puct.LeafEval
import ao.snakewarz.bots.search.puct.MobilityEval
import ao.snakewarz.bots.search.puct.MovePrior
import ao.snakewarz.bots.search.puct.PuctBot
import ao.snakewarz.bots.search.puct.SurvivalEval
import ao.snakewarz.bots.search.puct.TerritoryEval
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.rules.MatchOutcome

/**
 * Iterative-deepening alpha-beta over the same leaf [PuctBot] appraises with — an exact search where
 * everything else here averages.
 *
 * The canonical strong Tron bot of the 2010 Google AI Challenge was alpha-beta rather than MCTS, and
 * nothing in this tree had ever run an exact search: both shipped searchers sample lines and keep a
 * mean. This searches every line to a fixed depth and keeps the minimax value, which is a different
 * claim about where a thousand evaluations are best spent — a wide shallow certainty against a narrow
 * deep estimate.
 *
 * It is also the first production consumer of [Playout.undo], which existed with no caller at all.
 *
 * ### The budget is what shapes this, and the shape is not the textbook one
 *
 * [Scratch.playout] **resets the arena**, because it copies the live board over it on every call. So
 * a depth-first search cannot pay for a leaf and keep the descent it is standing on: asking for the
 * next payment destroys the path. That is why neither shipped searcher calls [Playout.undo] — the
 * payment already unwound them — and it is the one structural fact this bot is built around.
 *
 * The answer here is to **replay the path from the root at every paid leaf**: pay, then re-apply the
 * `ply` moves on the way down, then appraise. It costs one arena copy and `ply` extra applies per
 * leaf, which is very nearly what [PuctBot] pays anyway — that bot also copies the arena once and
 * descends from the root once per iteration. What it buys is that the accounting stays in the engine.
 * The alternative was to call `turn.budget.tryConsume` directly and keep the descent; it passes the
 * contract suite and moves the bound into the bot, which is the thing SW-07 exists to prevent.
 *
 * ### Two players, because max^n does not prune
 *
 * Alpha-beta needs a scalar and [LeafEval] is a **vector, one value per slot, and not zero-sum**.
 * The scalar is `values[me] − max(values[everybody else])`, which at two snakes is
 * `values[me] − values[them]` and above two is the **paranoid** reduction: every other snake is
 * treated as one opponent minimising this bot's margin. Paranoid rather than max^n because max^n has
 * no cutoff — every node has to be fully expanded to know each player's own best — and a search that
 * cannot prune buys nothing over the tree that is already here.
 *
 * **This changes what the leaf means**, and the comparison against `puct` at the same `eval` is
 * therefore not quite one variable. A share and a difference of shares are not the same reading: the
 * terms that are antisymmetric between two snakes (the territory share, the separated margin) double
 * in the difference, while the terms each snake owns alone (mobility, the seal, the trap penalty) do
 * not — so the effective weighting shifts about twofold toward territory. And every slot's value is
 * clamped to `0..1` *before* the subtraction, so a position far past decided reads the same as one
 * barely decided. A leaf tuned as a share is not a leaf tuned as a difference.
 *
 * ### Termination, with terminal leaves costing nothing
 *
 * A leaf that is a **finished game** is not an evaluation and is not charged for: the outcome is the
 * game's own answer, read off the board. That leaves the honest question of what bounds a search
 * whose leaves may all be free, and three things do, in order:
 *
 * - A deepening pass that spends **nothing** is the last one. Its tree is entirely terminal, so a
 *   deeper pass would walk the identical tree — terminals have no children — and return the identical
 *   value.
 * - Free work is bounded by paid work one ply above it. The non-terminal nodes at ply `d−1` are what
 *   the previous pass paid for, so they are bounded by the allowance, and each has at most four
 *   terminal children. A pass cannot do exponentially more free work than the pass before it did paid.
 * - [MAX_PLY] caps the deepening loop outright.
 *
 * ### What deepening buys, and what carries between passes
 *
 * Each pass re-searches from the root at one more ply. That is not the waste it looks like at a
 * branching factor near three — the last pass is most of the tree — and it pays for itself twice:
 * the search always has a complete answer to return, and each pass leaves a **killer move** per ply
 * behind for the next one to try first, which is most of what makes the cutoffs land. Moves after the
 * killer are ordered by [MovePrior], the same prior `puct` searches through.
 *
 * A pass the allowance cuts short is used anyway **when at least one root move finished**, because
 * the killer ordering means the first root move searched is the previous pass's answer: a partial
 * pass is "last pass's move, plus anything that beat it in a completed search".
 *
 * **Handed no allowance it costs exactly nothing** and answers from [SpaceBot]'s flood fill, as every
 * searcher here does. Getting an arena to descend on at all is `turn.scratch.playout(0)`, which is
 * free — `Budget.tryConsume(0)` succeeds at a budget of zero, which is what makes an arena available
 * without buying an evaluation.
 *
 * ### How deep it gets, which is the number that decides whether any of this can work
 *
 * `AlphaBetaBotTest` plays this bot against itself at the shipped allowance and reports the plies
 * each pass completed:
 *
 * | board | searched turns | mean | min | max |
 * |---|---|---|---|---|
 * | 12x12 | 138 | **11.2 plies** | 8 | 16 |
 * | 20x20 | 362 | **11.6 plies** | 8 | 27 |
 *
 * At a tenth of the allowance the 12x12 mean is 6.0, so the depth is roughly logarithmic in the
 * budget as a branching factor near three demands: **ten times the evaluations buy five more plies.**
 *
 * Eleven plies is five and a half moves each. What decides a filling endgame here is whether a region
 * can be spent, and that is a hundred moves out — so this search is exact about a horizon that is not
 * where the game is decided, and everything past it is [ChamberEval]'s guess, the same guess `puct`
 * makes. That is the honest statement of what an exact search can be in this game, and it is the
 * finding rather than a caveat: depth is not the scarce resource a chess intuition expects it to be.
 *
 * ### What the replay costs, which is the design's own bill
 *
 * `lab time`, best of three, six seeds a figure, entrants interleaved within a seed so a drift would
 * move all three together, with `uct` carried as a control neither entrant can touch:
 *
 * | board | `uct`, control | `puct:eval=chamber` | this | ratio |
 * |---|---|---|---|---|
 * | 12x12 | 1.61 ms | 3.85 ms | **4.19 ms** | **1.09x** |
 * | 20x20 | 1.85 ms | 10.05 ms | **10.33 ms** | **1.03x** |
 *
 * The control lands within 5% and 1.4% of [EvaluationCost]'s own [ChamberEval] session, which is what
 * says these figures may be read beside that table rather than merely near it.
 *
 * **So the arena copy and the `ply` re-applies cost under a tenth of a leaf, and less of one on the
 * larger board.** That is the whole answer to whether replaying the path was affordable: a
 * [ChamberEval] leaf sweeps two hundred squares and takes every region apart, and eleven extra board
 * applies beside it do not show. The ratio *falls* with board size for the same reason — the leaf
 * grows with the squares and the replay grows with the depth.
 *
 * `time` plays a different game per entrant, so a seed pairs the board and not the position; the
 * ratio is what to quote and the control is what makes the session readable at all.
 *
 * ### What it is worth, and the intransitivity is the finding
 *
 * 200 rounds over seven entrants at the shipped allowance on a 12x12 — 4,200 matches, **3,219 of them
 * distinct games**, worst pairing 120 of 200 distinct, and no forfeits:
 *
 * | entrant | rating | 95% | score |
 * |---|---|---|---|
 * | `puct:eval=chamber` | 223 | +200..+249 | 72% |
 * | `puct:eval=survival` | 215 | +191..+238 | 71% |
 * | `puct` | 176 | +155..+200 | 66% |
 * | **this, at `eval=chamber`** | **161** | +143..+184 | 64% |
 * | `uct` | 83 | +55..+110 | 55% |
 * | `chase` | −405 | −446..−372 | 13% |
 * | `pressure` | −454 | −501..−415 | 9% |
 *
 * Sixty-two Elo behind the PUCT it shares a leaf with, on intervals that overlap by a point — and
 * **the head-to-head between exactly those two says the opposite**, 108-92 to this bot over 200
 * boards. `rate` prints the residual rather than leaving it to be found: this scores 54% off
 * `puct:eval=chamber` where its own rating expects 41%, and 31% off `puct:eval=territory` where the
 * rating expects 48%. Those two are the largest residuals in the field.
 *
 * **That is `MovePrior`'s instrument lesson arriving from the other direction.** There a head-to-head
 * flattered a knob a field demoted; here one flatters a whole search. A head-to-head between two
 * shapes of the same bot measures a style match-up and only a common field converts that into
 * strength — so the 108-92 is not the number, the 161 is.
 *
 * **The profile is what an exact search actually buys.** This is the only entrant in that field to
 * take 200 of 200 from both `chase` and `pressure`, where `puct:eval=chamber` drops five to
 * `pressure` and `puct` drops one to `chase`. Eleven plies of full-width search do not walk into a
 * pocket a reactive bot can build. What they cannot do is out-plan a bot guessing about the other
 * ninety plies — every one of the 92 losses to `puct:eval=chamber` is `TRAPPED` at 70% board fill,
 * lasting 99 moves at the median against 76 when it wins.
 *
 * ### Depth pays at exactly the rate iterations do, which is the answer to the phase's own worry
 *
 * The fear this bot was built to test is that the plies it reaches are past the point where anything
 * changes, so buying more of them would buy nothing. A second field says otherwise — 150 rounds over
 * five entrants, 1,500 matches, 1,224 distinct:
 *
 * | entrant | rating | 95% |
 * |---|---|---|
 * | this at an allowance of 3,000 | **105** | +81..+131 |
 * | `puct:eval=chamber` at 3,000 | 48 | +25..+72 |
 * | this at 1,000 | 4 | −19..+29 |
 * | `puct:eval=chamber` at 1,000 | −44 | −66..−21 |
 * | `uct` at 1,000 | −114 | −140..−92 |
 *
 * **Tripling the allowance is worth +101 Elo here and +92 to the PUCT beside it.** Two more plies buy
 * what three times the iterations buy, so neither search is the one that saturates first, and there
 * is no depth here past which the extra ply stops mattering.
 *
 * **And the ratings invert against the seven-entrant field, which is the caution to carry.** In this
 * one this bot rates 48 above `puct:eval=chamber` on disjoint intervals; in that one it rates 62
 * below. The head-to-head between the pair agrees with itself across both — 108-92 and 77-73 to this
 * bot — so what moved is the *company*, not the pairing. It loses to `eval=survival` and
 * `eval=territory`, which both lose to `eval=chamber`, which loses to this: a genuine cycle, and a
 * rating is a single ordering fitted over one. Neither number is the bot's strength; the pair of them
 * is what this bot is.
 *
 * ### Registered experimental, and what it is for
 *
 * `PuctBot.SEARCH`'s range and this one's are the same numbers so the two are comparable at an equal
 * allowance, and the leaf weights are read off `puct`'s own knob declarations rather than
 * re-declared, so `alphabeta` against `puct:eval=chamber` is a batch about the *search*. Whether an
 * exact search is worth its depth here is a measurement; [MAX_PLY] and [depthReached] are how it is
 * taken.
 */
public class AlphaBetaBot(setup: BotSetup) : Bot {
    private val unbudgeted = SpaceBot(setup)
    private val self = setup.self.index
    private val slotCount = setup.opponentCount + 1

    /** One value per slot, refilled at every leaf and reduced to a scalar by [paranoidMargin]. */
    private val values = DoubleArray(slotCount)

    /** One node's four priors, refilled at every node and consumed by [orderInto]. */
    private val priors = DoubleArray(DIRECTIONS)

    /** Each ply's moves in the order they are tried, best first. */
    private val ordered = IntArray(MAX_PLY * DIRECTIONS)

    /** The moves applied from the root, which is what a paid leaf replays. */
    private val path = IntArray(MAX_PLY)

    /** The move that last cut off at each ply, carried between deepening passes. */
    private val killer = IntArray(MAX_PLY)

    /** How deep the applied moves currently run. [appraise] reads it as the replay length. */
    private var ply = 0

    /** Set the moment the allowance refuses a leaf, and read by every frame on the way out. */
    private var aborted = false

    /** Root moves fully searched in the current pass, which is what makes a cut-short pass usable. */
    private var rootSearched = 0

    private var rootValue = 0.0

    /** Plies the last completed deepening pass reached. A test seam — `AlphaBetaBotTest` reads it. */
    internal var depthReached: Int = 0
        private set

    private val policy = MovePrior(
        setup.grid,
        PuctBot.PRIOR_LIBERTY.default,
        PuctBot.PRIOR_PINCH.default,
        PuctBot.PRIOR_WALL.default,
        PuctBot.PRIOR_TAIL.default,
        PuctBot.PRIOR_TEMPERATURE.default,
    )

    /**
     * The leaf, at `puct`'s own declared weights.
     *
     * Read off that bot's knobs rather than re-declared here, so the two bots appraise a position
     * identically and a batch between them is about the search rather than about seven numbers that
     * drifted apart. The `else` branch is the default for `PuctBot.EVAL`'s reason: `Choice.read` is
     * total, so a value from a mangled `#r=` fragment arrives here, and this is a field initializer
     * with nothing above it to catch a throw.
     */
    private val eval: LeafEval = when (EVAL.read(setup.params)) {
        PuctBot.MOBILITY -> MobilityEval(slotCount)

        PuctBot.TERRITORY -> TerritoryEval(
            setup.grid,
            slotCount,
            PuctBot.TERRITORY_WEIGHT.default,
            PuctBot.MOBILITY_WEIGHT.default,
            PuctBot.TRAP_PENALTY.default,
            PuctBot.SEPARATION_BONUS.default,
        )

        PuctBot.SURVIVAL -> SurvivalEval(
            setup.grid,
            slotCount,
            PuctBot.TERRITORY_WEIGHT.default,
            PuctBot.MOBILITY_WEIGHT.default,
            PuctBot.TRAP_PENALTY.default,
            PuctBot.SEPARATION_BONUS.default,
        )

        else -> ChamberEval(
            setup.grid,
            slotCount,
            PuctBot.TERRITORY_WEIGHT.default,
            PuctBot.MOBILITY_WEIGHT.default,
            PuctBot.TRAP_PENALTY.default,
            PuctBot.SEPARATION_BONUS.default,
            PuctBot.PARITY_WEIGHT.default,
            PuctBot.FRONTIER_PENALTY.default,
            PuctBot.SEAL_PENALTY.default,
        )
    }

    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        if (legal.isEmpty) {
            // Doomed: every direction is the same death, and the engine will record it as TRAPPED.
            return Decision.Move(Direction.NORTH)
        }

        // Searching a forced move spends an allowance that a real choice will want later.
        legal.singleOrNull()?.let { return Decision.Move(it) }

        for (at in killer.indices) {
            killer[at] = NO_MOVE
        }
        depthReached = 0
        var chosen: Direction? = null
        var depth = 1

        while (depth <= MAX_PLY) {
            // Free, and it is what puts the arena back at the live position with an empty journal
            // whatever the previous pass ended on.
            val arena = turn.scratch.playout(FREE)
            aborted = false
            ply = 0
            rootSearched = 0
            val spent = turn.budget.consumed

            val best = searchRoot(turn, arena, legal, depth)

            if (aborted) {
                if (rootSearched > 0) {
                    chosen = best
                }
                break
            }

            chosen = best
            depthReached = depth

            // Nothing deeper to find: the value is a forced result, or every line inside this depth
            // already ended, in which case one more ply walks the same tree.
            if (rootValue >= MATE_FOUND || rootValue <= -MATE_FOUND || turn.budget.consumed == spent) {
                break
            }
            depth++
        }

        return if (chosen != null) Decision.Move(chosen) else unbudgeted.chooseMove(turn)
    }

    override fun toString(): String = "AlphaBetaBot($eval)"

    // -- internals

    /**
     * One deepening pass, returning the move it settles on and leaving its value in [rootValue].
     *
     * Separate from [search] rather than a `ply == 0` branch inside it because the root answers with
     * a *move* where every other node answers with a value, and because a cut-short root is usable
     * and a cut-short interior node is not.
     */
    private fun searchRoot(turn: Turn, arena: Playout, legal: DirectionSet, depth: Int): Direction {
        val count = orderInto(arena, 0, legal)
        var best = Direction.entries[ordered[0]]
        var alpha = -INFINITE

        for (i in 0 until count) {
            val direction = Direction.entries[ordered[i]]
            arena.advance(direction)
            path[0] = direction.ordinal
            ply = 1

            // Re-read after every advance, never carried over: any move can be the one that ends the
            // game, and advancing on a stale reading throws.
            val result = arena.outcome
            val value = if (result != null) terminalValue(result) else search(turn, arena, depth - 1, alpha, INFINITE)

            ply = 0
            if (aborted) {
                return best
            }
            arena.undo()
            rootSearched++

            if (value > alpha) {
                alpha = value
                best = direction
                killer[0] = direction.ordinal
            }
        }

        rootValue = alpha
        return best
    }

    /**
     * The minimax value of the position the arena is standing on, to [depth] more plies.
     *
     * Fail-soft: the value returned may sit outside the window, which costs nothing here and leaves a
     * usable number at the root. The mover being this bot is what makes a node a maximiser; every
     * other snake minimises, which is the paranoid reduction [paranoidMargin] scores.
     */
    private fun search(turn: Turn, arena: Playout, depth: Int, alphaIn: Double, betaIn: Double): Double {
        if (depth == 0) {
            return appraise(turn)
        }

        val mover = arena.toAct
        val maximizing = mover.index == self
        val here = ply
        val base = here * DIRECTIONS
        val count = orderInto(arena, here, arena.board.legalMoves(mover))

        var alpha = alphaIn
        var beta = betaIn
        var best = if (maximizing) -INFINITE else INFINITE

        for (i in 0 until count) {
            val direction = Direction.entries[ordered[base + i]]
            arena.advance(direction)
            path[here] = direction.ordinal
            ply = here + 1

            val result = arena.outcome
            val value = if (result != null) terminalValue(result) else search(turn, arena, depth - 1, alpha, beta)

            ply = here
            if (aborted) {
                // The arena was reset out from under this descent, so there is nothing to unwind and
                // nothing this pass returns is worth reading.
                return DRAW
            }
            arena.undo()

            if (maximizing) {
                if (value > best) {
                    best = value
                    if (value > alpha) {
                        alpha = value
                    }
                }
            } else {
                if (value < best) {
                    best = value
                    if (value < beta) {
                        beta = value
                    }
                }
            }

            if (alpha >= beta) {
                killer[here] = direction.ordinal
                break
            }
        }

        return best
    }

    /**
     * Pays for one leaf, replays the descent onto the arena the payment reset, and appraises it.
     *
     * The replay is the whole design: [Scratch.playout] copies the live board over the arena, so the
     * `ply` moves that led here are gone the moment the charge lands and have to be put back before
     * the caller can carry on unwinding. Charging before the work is what makes the allowance a bound
     * — a refused playout comes back already reporting an outcome, and that is the budget check.
     */
    private fun appraise(turn: Turn): Double {
        val arena = turn.scratch.playout(eval.cost)
        if (arena.outcome != null) {
            aborted = true
            return DRAW
        }

        for (i in 0 until ply) {
            arena.advance(Direction.entries[path[i]])
        }

        eval.valuesInto(arena, values)
        return paranoidMargin()
    }

    /**
     * This bot's value less the best of everybody else's — the scalar alpha-beta needs.
     *
     * At two snakes it is `values[me] − values[them]`. Above two it is the paranoid reading: whoever
     * is doing best against this bot is the opponent, and the others' moves are searched as that
     * opponent's. In a solo match there is no rival and the margin is this bot's own value.
     */
    private fun paranoidMargin(): Double {
        var rival = LeafEval.LOSS
        for (slot in 0 until slotCount) {
            if (slot != self && values[slot] > rival) {
                rival = values[slot]
            }
        }
        return values[self] - rival
    }

    /**
     * A finished game, scored so that a win sooner beats a win later.
     *
     * Subtracting the ply is what makes the bot finish a won game instead of circling in it, and on
     * the other side it makes a lost one last as long as possible — a line lost against a perfect
     * opponent is not lost against one that might still err.
     */
    private fun terminalValue(outcome: MatchOutcome): Double = when {
        outcome.isDraw -> DRAW
        outcome.winner.index == self -> MATE - ply
        else -> -(MATE - ply)
    }

    /**
     * Writes the mover's moves into [ordered] at [at], best first, and returns how many there are.
     *
     * The killer goes first when it is still legal — a move that cut off at this ply one pass ago is
     * the cheapest guess there is — and the rest follow [MovePrior] descending. A selection sort over
     * at most four elements, which is why there is no scratch array to sort into.
     */
    private fun orderInto(arena: Playout, at: Int, legal: DirectionSet): Int {
        val base = at * DIRECTIONS

        if (legal.isEmpty) {
            // A trapped snake is alive and still to act, so it must be handed a direction. Every one
            // of them produces the same board, so it gets one edge here rather than four or none.
            ordered[base] = Direction.NORTH.ordinal
            return 1
        }

        policy.into(arena.board, arena.toAct, legal, priors)

        val preferred = killer[at]
        var remaining = legal
        var count = 0

        while (remaining.isNotEmpty) {
            var pick = remaining.nth(0)
            var top = scoreOf(pick, preferred)
            for (i in 1 until remaining.size) {
                val candidate = remaining.nth(i)
                val score = scoreOf(candidate, preferred)
                if (score > top) {
                    top = score
                    pick = candidate
                }
            }
            ordered[base + count++] = pick.ordinal
            remaining -= pick
        }

        return count
    }

    private fun scoreOf(direction: Direction, preferred: Int): Double =
        if (direction.ordinal == preferred) KILLER_SCORE else priors[direction.ordinal]

    internal companion object {
        /** The same range [PuctBot] declares, so the two are comparable at the same numbers. */
        val SEARCH = BotKnob.Search(min = 0, max = 10_000, step = 100)

        /**
         * Which [LeafEval] the search bottoms out in — the same four `puct` offers, at its weights.
         *
         * Defaulting to `chamber` rather than to `territory`: nothing is pinned to this bot's
         * defaults yet, and the strongest leaf in the box is the one that makes the search the
         * variable. `horizon` is deliberately absent — it is measured 185 Elo behind `survival` and a
         * value offered here is frozen from the day it ships.
         */
        val EVAL = BotKnob.Choice(
            name = "eval",
            label = "Evaluation",
            help = "How a leaf is judged: liberties, a share of the board, or how long each snake can last.",
            default = PuctBot.CHAMBER,
            values = listOf(PuctBot.CHAMBER, PuctBot.TERRITORY, PuctBot.SURVIVAL, PuctBot.MOBILITY),
            tradeoff = true,
        )

        val KNOBS: List<BotKnob> = listOf(SEARCH, EVAL)

        /**
         * Plies the deepening loop may reach.
         *
         * A cap rather than a target: at a branching factor near three a thousand evaluations buy
         * about ten plies even with the cutoffs landing perfectly, so this is the guard that keeps a
         * position whose every line has already ended from deepening forever, not a number the search
         * is expected to approach. It also sizes [ordered], [path] and [killer].
         */
        const val MAX_PLY: Int = 64

        /** Ways out of a square, and the stride of [ordered]. */
        private val DIRECTIONS: Int = Direction.entries.size

        /** What an arena costs when no evaluation is being bought — see `Budget.tryConsume`. */
        private const val FREE: Int = 0

        private const val NO_MOVE: Int = -1

        /** Beats any prior, which are shares of one and so never reach it. */
        private const val KILLER_SCORE: Double = 2.0

        /** A finished game, against a leaf margin that lives in `-1.0..1.0`. */
        private const val MATE: Double = 1000.0

        /** Wider than any value the search can produce, so it is a usable opening window. */
        private const val INFINITE: Double = 2000.0

        /** Below this a value is an appraisal; at or above it, it is a forced result. */
        private const val MATE_FOUND: Double = MATE - MAX_PLY

        private const val DRAW: Double = 0.0
    }
}
