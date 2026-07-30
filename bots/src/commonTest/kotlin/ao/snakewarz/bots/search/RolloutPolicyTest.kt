package ao.snakewarz.bots.search

import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.scratch.BoardScratch
import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.AppraisalTape
import ao.snakewarz.bots.SHIPPED_BUDGET
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.at
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.cornerSpawns
import ao.snakewarz.bots.search.puct.MovePrior
import ao.snakewarz.bots.search.uct.UctBot
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.Rng
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rollout-policy experiment, and the rules it is an experiment on.
 *
 * [RolloutPolicy] ships wired and **off**, so this is the whole of the evidence for what turning it
 * on would buy and what it would cost. Two measurements, taken together because neither means
 * anything without the other:
 *
 * - **how often a policy would play a different move from the uniform draw** — near zero and there is
 *   no mechanism to test, whatever a batch later says;
 * - **what it costs per turn** — because `EvaluationCost.ROLLOUT` is a flat `1`, so a dearer policy
 *   buys no fewer iterations and pays for itself entirely in wall clock. A batch at equal allowance
 *   therefore *flatters* a policy, and the cost table below is what an equal-clock allowance is
 *   derived from.
 *
 * Both print like [ao.snakewarz.bots.ThroughputTest] and are read the same way:
 *
 * ```
 * ./gradlew :bots:jvmTest --tests '*RolloutPolicyTest*' -i | grep -E '\[probe\]|\[bench\]'
 * ```
 *
 * ### The measure of divergence is total variation, because that is the disagreement rate
 *
 * Two policies over one legal set have an exact answer to "how often would they differ" that does not
 * depend on how either consumes randomness: the total variation distance is the disagreement
 * probability under the best coupling of the two draws, and no implementation can do better. For a
 * policy that merely *filters* — [RolloutPolicy.LIBERTY] — it works out to the share of the legal set
 * the filter refuses, which is the intuitive reading of the same number.
 *
 * A step where the mover has one legal move or none cannot diverge at all, so those are counted
 * separately and kept out of the denominator.
 *
 * The rule is restated here rather than read off [RolloutPolicy], and deliberately: it is the oracle
 * the implementation is measured against, the way `PortableLogTest` takes `kotlin.math.ln` as one.
 * What ties the two together is the behaviour block below.
 *
 * ### Where the probe's positions come from, and why not a rollout from the opening
 *
 * A rollout starts at a leaf the tree reached from a real position, so the boards it walks are the
 * boards a *played* game passes through. A uniform game between two snakes crashes early and never
 * reaches the tight endgame where a dead end is a common square. So the probe plays real `uct` and
 * samples rollouts from every position of the real game, which is the distribution
 * [UctBot] actually rolls out from, up to the few plies of tree descent above it.
 *
 * That is what the turn loop here is for, and it is why [ao.snakewarz.bots.HeadlessMatch] is not used
 * instead: this needs the live board at every turn, and the harness hands back only the decisions.
 *
 * ### And why the cost half uses [AppraisalTape] rather than a match
 *
 * A policy changes how `uct` plays, so two policies play different games of different lengths against
 * the same opponent — and a µs/turn taken off a whole match then carries both. [AppraisalTape] holds
 * the line still by construction: the subject is seated at no slot, so every entrant is timed over
 * the same boards in the same order.
 *
 * **Every timed entrant here is [UctBot], and that is what makes the ratio survive the JVM.**
 * `ThroughputTest` records its own cross-entrant sweep being unusable on this target — seven bot
 * classes through one `Bot.chooseMove` call site, agreeing pass to pass and wrong by 4–5x. A block of
 * one class does not have that problem, so the control is the default setting read **twice**, first
 * and last, which detects the machine moving under the block without putting a second receiver type
 * at the site the block is timed through. Reaching for a foreign entrant as the control was tried
 * first and is what produced the failure: `puct` read 779 then 625 µs/turn across one 8x8 block, a
 * 25% swing in the control itself.
 *
 * ### Read the control line before the table above it
 *
 * On a quiet machine the two control readings land within a few percent of each other and every
 * matched allowance lands within ten percent of them; that is what makes the block a measurement. The
 * assertions are an order of magnitude looser than that on purpose — this runs inside `./gradlew
 * build` beside a wasm compile, and a benchmark that fails when the machine is busy is a benchmark
 * everybody learns to ignore. So a block whose control pair is far apart has not *failed*; it has
 * measured the machine, and the answer is another run.
 */
class RolloutPolicyTest {
    @Test
    fun `a candidate rollout policy diverges from uniform often enough to be worth building`() {
        for (side in SIDES) {
            val tallies = Array(CANDIDATES.size) { Tally() }
            for (seed in 1L..SEEDS) {
                probe(side, seed, tallies)
            }

            for (i in CANDIDATES.indices) {
                println("[probe] ${side}x$side ${CANDIDATES[i]}: ${tallies[i].report()}")
            }

            // The liberty rule is the cheapest candidate and the one whose rate decides whether
            // there is a mechanism here at all, so it is what carries the assertion. A band rather
            // than a floor, and a wide one: at zero there would be nothing to build, and at the top
            // the rule would be refusing most of the legal set, which is a different bot rather than
            // a rollout policy.
            val liberty = tallies[LIBERTY].meanDivergence()
            assertTrue(
                liberty in PLAUSIBLE_DIVERGENCE,
                "the liberty rule diverges from uniform on $liberty of ${side}x$side choice steps, " +
                    "which is outside anything this has measured",
            )

            // And the ordering, which is what the cost table has to be read against: the dearer
            // policy is the one that does more. It came out the same way on all three boards and by
            // a factor of two or better, so this asserts a gap rather than a sign.
            assertTrue(
                tallies[PRIOR].meanDivergence() > liberty,
                "the prior diverges less than the rule that costs a fraction of it on ${side}x$side",
            )
        }
    }

    @Test
    fun `what a policy costs a turn is what its strength has to be read against`() {
        for (side in SIDES) {
            val tape = AppraisalTape(side, side)

            // Every setting several times before any of them is timed. The first passes are
            // interpreted on the JVM and the tiering is what the control below would otherwise read
            // as the machine drifting.
            repeat(WARMUPS) {
                for (value in RolloutPolicy.VALUES) {
                    tape.time(uct, paramsFor(value), SHIPPED_BUDGET, passes = 1)
                }
            }

            val opening = median(tape, BotParams.EMPTY, SHIPPED_BUDGET)
            val matched = mutableListOf<Long>()
            for (value in RolloutPolicy.VALUES) {
                val params = paramsFor(value)
                val allowance = equalClock(side, value)
                val mean = median(tape, params, SHIPPED_BUDGET)
                val atAllowance = median(tape, params, allowance)
                matched += atAllowance

                println(
                    "[bench] uct:${UctBot.ROLLOUT_POLICY.name}=$value ${side}x$side: " +
                        "$mean us/turn at $SHIPPED_BUDGET, $atAllowance us/turn at $allowance, " +
                        "over ${tape.appraisals} of ${tape.lineTurns} turns",
                )
            }
            val closing = median(tape, BotParams.EMPTY, SHIPPED_BUDGET)
            val control = (opening + closing) / 2

            println(
                "[bench] uct control ${side}x$side budget $SHIPPED_BUDGET: $opening then $closing us/turn, " +
                    "matched allowances ${matched.joinToString("/") { percentOf(it, control) }} of it",
            )

            assertTrue(
                closing * LOADED_MACHINE > opening && opening * LOADED_MACHINE > closing,
                "the default read $opening us/turn before the block and $closing after, so nothing " +
                    "in this block is a measurement of anything",
            )
            for (i in RolloutPolicy.VALUES.indices) {
                assertTrue(
                    matched[i] * LOADED_MACHINE > control && control * LOADED_MACHINE > matched[i],
                    "${RolloutPolicy.VALUES[i]} at its ${equalClock(side, RolloutPolicy.VALUES[i])} " +
                        "allowance costs ${matched[i]} us/turn against the default's $control on " +
                        "${side}x$side, which no equal-clock table could be behind",
                )
            }
        }
    }

    @Test
    fun `a prior at its swept weights is priced before anything is built on it`() {
        val block = sweptBlock()

        for (side in SIDES) {
            val tape = AppraisalTape(side, side)

            // Every subject several times before any of them is timed, for the reason the block
            // above gives: the first passes are interpreted and the tiering would read as drift.
            repeat(WARMUPS) {
                for ((_, entry) in block) {
                    tape.time(entry, BotParams.EMPTY, SHIPPED_BUDGET, passes = 1)
                }
            }

            val opening = median(tape, block[UNIFORM_SEAT].second, SHIPPED_BUDGET)
            val figures = block.map { median(tape, it.second, SHIPPED_BUDGET) }
            val closing = median(tape, block[UNIFORM_SEAT].second, SHIPPED_BUDGET)
            val control = (opening + closing) / 2

            for (i in block.indices) {
                println(
                    "[bench] uct rollout ${block[i].first} ${side}x$side: ${figures[i]} us/turn at " +
                        "$SHIPPED_BUDGET, ${percentOf(figures[i], control)} of the uniform control, " +
                        "over ${tape.appraisals} of ${tape.lineTurns} turns",
                )
            }
            println(
                "[bench] uct rollout control ${side}x$side budget $SHIPPED_BUDGET: " +
                    "$opening then $closing us/turn",
            )

            assertTrue(
                closing * LOADED_MACHINE > opening && opening * LOADED_MACHINE > closing,
                "the uniform rollout read $opening us/turn before the block and $closing after, so " +
                    "nothing in this block is a measurement of anything",
            )
            assertTrue(
                figures[SWEPT_SEAT] > figures[UNIFORM_SEAT],
                "the swept prior read ${figures[SWEPT_SEAT]} us/turn against a uniform draw's " +
                    "${figures[UNIFORM_SEAT]} on ${side}x$side, so the weights are not reaching it",
            )
        }
    }

    @Test
    fun `the swept prior is a distribution no knob on this bot can name`() {
        val board = deadEndBoard()
        val legal = board.legalMoves(SnakeId(0))

        val shipped = draws(RolloutPolicy(RolloutPolicy.PRIOR, board.grid), board, legal)
        val swept = draws(sweptPrior(board.grid), board, legal)

        // Deterministic rather than statistical: both walk one SplitMix64(1) over the same legal
        // set, so this is a fixed computation and its answer cannot wobble. What it guards is the
        // seam -- a weight dropped on the way through would make these two identical and every
        // figure in the block above would be the shipped prior timed twice.
        assertTrue(
            shipped != swept,
            "the swept weights draw exactly what the shipped ones draw, so they are not reaching " +
                "MovePrior at all",
        )
    }

    @Test
    fun `the liberty rule refuses a step with no way on`() {
        // Classic Tron growth, so a body can be drawn as a wall.
        //
        //   . 0 0      slot 0's head is at (0,1) and may go west into the corner or south into the
        //   1 . .      board. The corner is enclosed by two walls, slot 0's own head and slot 1's
        //   1 . .      body, so a snake that enters it has nowhere to go on its next turn.
        val board = deadEndBoard()
        val legal = board.legalMoves(SnakeId(0))
        assertEquals(DirectionSet.of(Direction.SOUTH, Direction.WEST), legal, "both steps are legal")
        assertTrue(board.isFree(board.at(0, 0)), "and the corner really is empty")

        val policy = RolloutPolicy(RolloutPolicy.LIBERTY, board.grid)
        val rng = SplitMix64(1)
        repeat(DRAWS) {
            assertEquals(
                Direction.SOUTH,
                policy.pick(board, SnakeId(0), legal, rng),
                "the corner is a hole of one square, so this rule never draws it",
            )
        }
    }

    @Test
    fun `the liberty rule refuses nothing when every step is a dead end`() {
        // A corridor of three with the snake in the middle: both ends are holes of one square, so a
        // rule that refused them would be left with nothing to draw from.
        val board = boardOf(1, 3, 0 to 1)
        val legal = board.legalMoves(SnakeId(0))
        assertEquals(DirectionSet.of(Direction.EAST, Direction.WEST), legal)

        val policy = RolloutPolicy(RolloutPolicy.LIBERTY, board.grid)
        val rng = SplitMix64(1)
        val drawn = mutableSetOf<Direction>()
        repeat(DRAWS) {
            drawn += policy.pick(board, SnakeId(0), legal, rng)
        }

        assertEquals(setOf(Direction.EAST, Direction.WEST), drawn, "it falls back on the whole legal set")
    }

    @Test
    fun `the prior favours the roomier step without ever refusing the other`() {
        val board = deadEndBoard()
        val legal = board.legalMoves(SnakeId(0))
        val policy = RolloutPolicy(RolloutPolicy.PRIOR, board.grid)
        val rng = SplitMix64(1)

        var south = 0
        var west = 0
        repeat(DRAWS) {
            when (policy.pick(board, SnakeId(0), legal, rng)) {
                Direction.SOUTH -> south++
                Direction.WEST -> west++
                else -> error("the prior drew a move that was never legal")
            }
        }

        // `puct` ships a prior that is a liberty count and nothing else, so the shares here are
        // exactly 3:2 -- the open step has one free neighbour and the corner has none, over a floor
        // of one. This is a wide band around a distribution rather than around noise.
        assertTrue(south > west, "the step into the board is the one with a free neighbour")
        assertTrue(west > 0, "and a prior never freezes a legal move out entirely")
    }

    @Test
    fun `a trapped snake draws nothing, whatever the policy`() {
        val board = boardOf(3, 3, 0 to 0)

        for (name in RolloutPolicy.VALUES) {
            val policy = RolloutPolicy(name, board.grid)
            val spent = SplitMix64(1)
            val untouched = SplitMix64(1)

            assertEquals(Direction.NORTH, policy.pick(board, SnakeId(0), DirectionSet.EMPTY, spent), name)
            assertEquals(untouched.nextLong(), spent.nextLong(), "$name consumed randomness for a doomed move")
        }
    }

    // -- internals

    /** One candidate's running counts. Rates are only ever reported with their denominator. */
    private class Tally {
        var rollouts = 0L
        var forced = 0L
        var choices = 0L
        var firing = 0L
        var divergence = 0.0

        fun meanDivergence(): Double = divergence / choices

        fun report(): String {
            val choicesPerRollout = choices.toDouble() / rollouts
            return "${firing * 100.0 / choices}% of $choices choice steps fire, " +
                "mean divergence ${meanDivergence()}, " +
                "$choicesPerRollout choices/rollout, " +
                "${meanDivergence() * choicesPerRollout} diverging/rollout, " +
                "$forced forced steps excluded"
        }
    }

    /**
     * Plays one real match and samples [ROLLOUTS_PER_TURN] rollouts from every position in it.
     *
     * The probe's own randomness is forked from a stream no slot uses, so sampling cannot shift what
     * either bot plays and the real game is the same game with the probe removed.
     */
    private fun probe(side: Int, seed: Long, tallies: Array<Tally>) {
        val grid = Grid(side, side)
        val rules = RulesConfig()
        val board = Board(grid, cornerSpawns(grid, SEATS), rules)
        val matchRng = SplitMix64(seed)

        val budgets = Array(SEATS) { Budget(ROOT_BUDGET) }
        val scratches = Array(SEATS) { BoardScratch(board, budgets[it]) }
        val bots = Array(SEATS) { slot ->
            uct.factory.create(
                BotSetup(
                    self = SnakeId(slot),
                    grid = grid,
                    rules = rules,
                    opponents = IntArray(SEATS - 1) { if (it < slot) it else it + 1 },
                    rng = matchRng.fork(slot),
                    params = BotParams.EMPTY,
                ),
            )
        }

        val samplingBudget = Budget(ROLLOUTS_PER_TURN)
        val sampling = BoardScratch(board, samplingBudget)
        val probeRng = SplitMix64(seed).fork(PROBE_STREAM)
        val priors = Array(CANDIDATES.size) { DoubleArray(Direction.entries.size) }
        val movePriors = arrayOf(
            null,
            MovePrior(grid, SHIPPED_LIBERTY, 0.0, 0.0, 0.0, 0.0),
            MovePrior(grid, SHIPPED_LIBERTY, SWEPT_PINCH, 0.0, SWEPT_TAIL, SWEPT_TEMPERATURE),
        )

        while (board.outcome == null) {
            samplingBudget.reset()
            repeat(ROLLOUTS_PER_TURN) {
                sample(sampling.playout(), probeRng, grid, movePriors, priors, tallies)
            }

            val id = board.toAct
            budgets[id.index].reset()
            val decision = bots[id.index].chooseMove(
                Turn(board, id, board.legalMoves(id), budgets[id.index], scratches[id.index]),
            )
            board.apply(id, (decision as Decision.Move).direction)
        }
    }

    /** Walks one uniform rollout to its end, scoring every step of it against each candidate. */
    private fun sample(
        playout: Playout,
        rng: Rng,
        grid: Grid,
        movePriors: Array<MovePrior?>,
        priors: Array<DoubleArray>,
        tallies: Array<Tally>,
    ) {
        var result = playout.outcome
        if (result != null) {
            return
        }
        for (tally in tallies) {
            tally.rollouts++
        }

        while (result == null) {
            val mover = playout.toAct
            val legal = playout.board.legalMoves(mover)

            if (legal.size < 2) {
                for (tally in tallies) {
                    tally.forced++
                }
            } else {
                score(playout.board, mover, legal, grid, movePriors, priors, tallies)
            }

            playout.advance(if (legal.isEmpty) Direction.NORTH else legal.nth(rng.nextInt(legal.size)))
            result = playout.outcome
        }
    }

    private fun score(
        board: BoardView,
        mover: SnakeId,
        legal: DirectionSet,
        grid: Grid,
        movePriors: Array<MovePrior?>,
        priors: Array<DoubleArray>,
        tallies: Array<Tally>,
    ) {
        val head = board.snake(mover).head
        val uniform = 1.0 / legal.size

        var survivors = 0
        for (i in 0 until legal.size) {
            if (freeNeighbours(board, grid, head.index + grid.offsetOf(legal.nth(i))) > 0) {
                survivors++
            }
        }
        val refused = if (survivors == 0) 0 else legal.size - survivors
        tallies[LIBERTY].record(refused.toDouble() / legal.size)

        for (i in 1 until tallies.size) {
            val prior = movePriors[i] ?: error("candidate $i has no prior to sample")
            prior.into(board, mover, legal, priors[i])

            var apart = 0.0
            for (j in 0 until legal.size) {
                apart += abs(priors[i][legal.nth(j).ordinal] - uniform)
            }
            tallies[i].record(apart / 2.0)
        }
    }

    private fun Tally.record(divergence: Double) {
        choices++
        this.divergence += divergence
        if (divergence > NEGLIGIBLE) {
            firing++
        }
    }

    private fun freeNeighbours(board: BoardView, grid: Grid, cell: Int): Int {
        var free = 0
        for (direction in Direction.entries) {
            if (board.isFree(Cell(cell + grid.offsetOf(direction)))) {
                free++
            }
        }
        return free
    }

    /** The median pass, for [AppraisalTape]'s reason — a fast pass is not noise and a minimum keeps it. */
    private fun median(tape: AppraisalTape, params: BotParams, budget: Int): Long =
        tape.time(uct, params, budget, PASSES).map { it.mean }.sorted()[PASSES / 2]

    private fun median(tape: AppraisalTape, subject: BotEntry, budget: Int): Long =
        tape.time(subject, BotParams.EMPTY, budget, PASSES).map { it.mean }.sorted()[PASSES / 2]

    /**
     * The three rollouts the swept-prior block times, in cost order, each as its own [BotEntry].
     *
     * Built through [UctBot.withRolloutPolicy] rather than off `rolloutPolicy=`, because the third
     * of them is not a value that knob offers — see that function. All three are the same class, so
     * the timed call site stays monomorphic; only the policy object differs.
     */
    private fun sweptBlock(): List<Pair<String, BotEntry>> = listOf(
        "uniform" to seat("uct-uniform") { RolloutPolicy(RolloutPolicy.UNIFORM, it) },
        "prior" to seat("uct-prior") { RolloutPolicy(RolloutPolicy.PRIOR, it) },
        "prior swept" to seat("uct-prior-swept") { sweptPrior(it) },
    )

    private fun seat(slug: String, policy: (Grid) -> RolloutPolicy): BotEntry =
        BotEntry(BotId(slug), slug, BotFactory { setup -> UctBot.withRolloutPolicy(setup, policy(setup.grid)) })

    /** `MovePrior`'s own swept point, which `puct` does not ship and `uct` cannot name. */
    private fun sweptPrior(grid: Grid): RolloutPolicy = RolloutPolicy(
        RolloutPolicy.PRIOR,
        grid,
        SHIPPED_LIBERTY,
        SWEPT_PINCH,
        SHIPPED_WALL,
        SWEPT_TAIL,
        SWEPT_TEMPERATURE,
    )

    /** [DRAWS] draws off one fixed stream, as a count per direction. */
    private fun draws(policy: RolloutPolicy, board: Board, legal: DirectionSet): Map<Direction, Int> {
        val rng = SplitMix64(1)
        val counts = mutableMapOf<Direction, Int>()
        repeat(DRAWS) {
            val direction = policy.pick(board, SnakeId(0), legal, rng)
            counts[direction] = (counts[direction] ?: 0) + 1
        }
        return counts
    }

    private fun equalClock(side: Int, value: String): Int =
        EQUAL_CLOCK[side to value] ?: error("no equal-clock allowance for $value on ${side}x$side")

    private fun paramsFor(value: String): BotParams =
        BotParams(mapOf(UctBot.ROLLOUT_POLICY.name to value))

    private fun percentOf(figure: Long, reference: Long): String = "${figure * 100 / reference}%"

    private fun deadEndBoard(): Board {
        val board = boardOf(3, 3, 0 to 2, 2 to 0, rules = RulesConfig(growEveryNthMove = 1))
        board.apply(SnakeId(0), Direction.WEST)
        board.apply(SnakeId(1), Direction.NORTH)
        return board
    }

    private companion object {
        val uct: BotEntry = ShippedBots.entryOf(BotId("uct"))

        val CANDIDATES = listOf("liberty", "prior", "prior swept")
        const val LIBERTY = 0
        const val PRIOR = 1

        /** The three sizes every field on the 2026-07-29 agenda was played at. */
        val SIDES = listOf(8, 12, 20)

        const val SEATS = 2

        /**
         * Enough distinct games that a rate is not one game's shape, and small enough that the whole
         * probe is a few seconds: every seed contributes a couple of hundred positions.
         */
        const val SEEDS = 4L

        /**
         * A tenth of the shipped allowance, for
         * [RolloutTruncationTest][ao.snakewarz.bots.search.uct.RolloutTruncationTest]'s reason — what
         * is wanted here is *played* positions rather than the strongest available ones, and the rate
         * this measures is a property of the board rather than of the search above it.
         */
        const val ROOT_BUDGET = 100

        const val ROLLOUTS_PER_TURN = 4

        /** A stream index no slot forks, so the probe cannot shift the game it is sampling. */
        const val PROBE_STREAM = 64

        /** `PuctBot.PRIOR_LIBERTY`'s default — the whole of the prior that bot ships. */
        const val SHIPPED_LIBERTY = 0.5

        /** `PuctBot.PRIOR_WALL`'s default, and the one coordinate the sweep left where it started. */
        const val SHIPPED_WALL = 0.0

        /** Where the uniform draw sits in the swept-prior block, and where the swept prior does. */
        const val UNIFORM_SEAT = 0
        const val SWEPT_SEAT = 2

        /** The point `MovePrior`'s own sweep settled on, which its KDoc records. */
        const val SWEPT_PINCH = 0.8
        const val SWEPT_TAIL = 0.8
        const val SWEPT_TEMPERATURE = 0.9

        /** Below this a distribution is the uniform one and rounding is the only difference. */
        const val NEGLIGIBLE = 1e-12

        /**
         * Wide, because what it has to catch is a mechanism that cannot fire and a rule that has
         * stopped being a rollout policy — not a rate landing on a particular number.
         *
         * Measured at 0.029 / 0.021 / 0.016 across the three boards, and falling with the board
         * because a dead end is rarer on an emptier one. [RolloutPolicy] carries the whole table.
         */
        val PLAUSIBLE_DIVERGENCE = 0.005..0.20

        /** `ThroughputTest.APPRAISAL_PASSES`, for its reason. */
        const val PASSES = 5

        /**
         * Passes of every setting before any of them is timed.
         *
         * Four rather than one, and measured rather than guessed: at one the default's two readings
         * came back 15% apart on the 8x8, which is the tiering finishing inside the timed block and
         * is indistinguishable from the machine drifting.
         */
        const val WARMUPS = 4

        /**
         * The allowance each setting buys the default's [SHIPPED_BUDGET] of wall clock at, per board.
         *
         * **This is the table a field between these settings has to be played at.** An allowance is
         * counted in evaluations and every entry of `EvaluationCost` is `1`, so a dearer rollout buys
         * the same thousand iterations and pays for them in time nobody charged it — a batch at an
         * equal allowance is therefore a handicap match in the policy's favour and says nothing about
         * strength. [RolloutPolicy] carries the ratios these are the reciprocal of.
         *
         * Derived from three runs and then *asserted* rather than left as arithmetic, because the
         * failure it guards against is silent: an allowance that has drifted turns an equal-clock
         * field back into an equal-allowance one and nothing in the field says so.
         *
         * **The 8x8 row is the weak one.** Its line is 47 turns where the 20x20's is 210, so a
         * reading there is over a fifth of the samples, and the three runs behind it spread 1.38x to
         * 1.62x where the two larger boards agreed to within 2%.
         */
        val EQUAL_CLOCK: Map<Pair<Int, String>, Int> = mapOf(
            (8 to RolloutPolicy.UNIFORM) to SHIPPED_BUDGET,
            (8 to RolloutPolicy.LIBERTY) to 690,
            (8 to RolloutPolicy.PRIOR) to 645,
            (12 to RolloutPolicy.UNIFORM) to SHIPPED_BUDGET,
            (12 to RolloutPolicy.LIBERTY) to 585,
            (12 to RolloutPolicy.PRIOR) to 545,
            (20 to RolloutPolicy.UNIFORM) to SHIPPED_BUDGET,
            (20 to RolloutPolicy.LIBERTY) to 535,
            (20 to RolloutPolicy.PRIOR) to 500,
        )

        /**
         * How far a figure may sit from the control before this stops being a benchmark at all.
         *
         * **Deliberately far looser than what the table is read at**, for
         * [ao.snakewarz.bots.ThroughputTest]'s stated reason: a benchmark that fails when the machine
         * is busy teaches everyone to ignore it, and this one runs inside `./gradlew build` beside a
         * wasm compile. A tight bound was tried and it failed exactly there and nowhere else.
         *
         * So the assertions catch a regression nobody could argue with, and the **printed figures**
         * are what a decision is made from. What to read is in the test's own KDoc: the control pair
         * within a few percent, and the matched allowances within ten of it.
         */
        const val LOADED_MACHINE = 1.8

        /** Enough draws that a distribution shows and few enough that a behaviour test is instant. */
        const val DRAWS = 200
    }
}
