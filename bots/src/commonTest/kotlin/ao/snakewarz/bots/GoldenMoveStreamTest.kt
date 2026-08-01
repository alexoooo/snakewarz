package ao.snakewarz.bots

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fixed matches, hashed. These numbers are the canary.
 *
 * They catch the classic determinism failure — an iteration over a `HashMap`, which the legacy
 * `GameStateImpl` did and got away with only because `PlayerAvatar.hashCode()` happened to return a
 * monotonic index — along with a Kotlin codegen change, a stdlib algorithm drift, and any accidental
 * edit to a bot's behaviour. Kotlin/Wasm is Beta, so the second of those is a live concern rather
 * than a theoretical one, which is why this suite also runs in a real browser in CI.
 *
 * A failure here is not automatically a bug. It is always a question that has to be answered before
 * the number is updated.
 */
class GoldenMoveStreamTest {
    @Test
    fun `random against random on 20x20`() {
        assertEquals(4969147972122689914L, hashOf("random", "random", seed = 2005))
    }

    @Test
    fun `wall hugger against random on 20x20`() {
        assertEquals(-2193524718431092627L, hashOf("wallhug", "random", seed = 2005))
    }

    @Test
    fun `wall hugger against wall hugger is fixed with no randomness at all`() {
        // Consumes no RNG, so this one is pinned by the rules alone. If it ever moves, the engine
        // moved. Re-pinned from -6119216452350361752 when a trapped survivor began owing its fatal
        // turn; the new stream is exactly the old stream plus that final north move.
        assertEquals(-6807198478660944021L, hashOf("wallhug", "wallhug", seed = 0))
    }

    @Test
    fun `the space filler against random on 20x20`() {
        assertEquals(-2269829668146017894L, hashOf("space", "random", seed = 2005))
    }

    @Test
    fun `the pressure bot against random on 20x20`() {
        assertEquals(-8093726933972115299L, hashOf("pressure", "random", seed = 2005))
    }

    @Test
    fun `the chaser against random on 20x20`() {
        assertEquals(-836205036734502335L, hashOf("chase", "random", seed = 2005))
    }

    @Test
    fun `the cartographer against random on 20x20`() {
        // Initial canary for the full-owned P2 rule after five wall-map fields qualified it.
        assertEquals(-3361584792731511458L, hashOf("cartographer", "random", seed = 2005))
    }

    @Test
    fun `lookahead against random on 12x12`() {
        // Initial canary for P4's adopted depth-three shape. The 64-evaluation cap guarantees the
        // complete fixed tree rather than exercising its whole-policy fallback, and the suite runs
        // the same replay-and-appraise path in Chrome.
        assertEquals(
            5485164985182975229L,
            hashOf("lookahead", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = 64),
        )
    }

    @Test
    fun `the chaser against a room ranker, where its room guard refuses something`() {
        assertEquals(-4075282736796042152L, hashOf("chase", "space", seed = 1, rows = 16, cols = 16))
    }

    @Test
    fun `and that guard is what makes the stream above what it is`() {
        // The 20x20 case is blind to ChaseBot.ROOM_SHARE, and blind in the way that matters: it sat
        // unchanged straight through the change that introduced the guard. A canary that cannot see
        // a deliberate edit to a bot cannot see an accidental one either.
        //
        // Finding a case that is not blind took looking, and what the search turned up is the
        // interesting part: against `random` the guard alters about one match in twenty and against
        // `wallhug` or `pressure` none at all, because a pocket only forms where somebody is laying
        // wall in front of you deliberately. Against `space` on 16x16 it is 14 seeds in 40. That
        // rarity is not a weakness of the guard -- it is worth +14 Elo *because* the few turns it
        // changes are the ones between dying and not.
        //
        // So the pinned case above is one where it fires, and this asserts that it fires rather than
        // leaving it to be true. Re-pin the pair together: a hash, and a reason it is not a constant.
        val guarded = hashOf("chase", "space", seed = 1, rows = 16, cols = 16)
        val unguarded = hashOf(
            "chase",
            "space",
            seed = 1,
            rows = 16,
            cols = 16,
            params = listOf(BotParams(mapOf("roomShare" to "0")), BotParams.EMPTY),
        )

        assertEquals(false, guarded == unguarded)
    }

    @Test
    fun `flat Monte Carlo against random on 12x12`() {
        // A smaller board than the rest, on purpose: this one simulates, and the suite it belongs to
        // also runs in a real browser, where the engine is slower. Twenty rollouts a turn is still
        // hundreds of thousands of simulated moves over a match, which is plenty to pin.
        // Re-pinned from 6424283122996719906 when a trapped survivor began owing its fatal turn;
        // that changes the endings scored inside the search as well as the live match's final move.
        assertEquals(
            2935789030642645579L,
            hashOf("flat-monte-carlo", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = SEARCH_BUDGET),
        )
    }

    @Test
    fun `UCT against random on 12x12`() {
        // The one that would catch a cross-target divergence in UCB1, which is why its logarithm
        // comes from `portableLog` and not from `kotlin.math`. This suite runs in Chrome too.
        //
        // Re-pinned from 4446294306891950002 when `UctBot.EXPLORATION` moved from 5.0 to 3.0. The
        // question a golden failure asks, answered: the divisor is read on every selection past a
        // child's first visit, so every stream that searches at all is expected to move, and the
        // three that do not search did not. The move was `play` against a field of `chase`,
        // `flat-monte-carlo` and `puct` over 9,450 then 6,720 games on disjoint seed bases, then
        // three sequential tests at `elo0=0, elo1=10` -- 3.5 at +21 ±14 over 1,100 boards, 2.5 at
        // +20 ±14 over 1,240, and the adopted 3.0 at +24 ±15 over 960 boards from a seed base
        // neither sweep had touched. `UctBot.EXPLORATION` carries both tables.
        // Re-pinned from 7247267489204944759 for the last-snake-moving ending rule, which changes
        // the rollout results the tree learns from as well as the live match's final move.
        assertEquals(
            3233546072208393327L,
            hashOf("uct", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = SEARCH_BUDGET),
        )
    }

    @Test
    fun `PUCT against random on 12x12`() {
        // The same allowance as the other two searchers now, and that is the point: an allowance is
        // a count of evaluations, so twenty means twenty iterations here as well — where it used to
        // mean twenty simulated moves and buy this bot next to nothing.
        assertEquals(
            -900434540592784873L,
            hashOf("puct", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = SEARCH_BUDGET),
        )
    }

    @Test
    fun `PUCT at territory, named rather than inherited`() {
        // The same bot as the case above, and the same number, because `territory` is what
        // `PuctBot.EVAL` defaults to. What this case buys is the day that stops being true: the
        // bare one pins whatever the default *is*, so moving the default would leave `TerritoryEval`
        // pinned by nothing on either target. Naming the value is what keeps it pinned through a
        // release decision it has nothing to do with.
        //
        // So this hash is expected to *survive* a default move that moves the bare one, and the two
        // are worth reading together: parting company is a default moving, and one moving alone is
        // arithmetic or search order.
        assertEquals(
            -900434540592784873L,
            hashOf(
                "puct",
                "random",
                seed = 2005,
                rows = 12,
                cols = 12,
                budgetPerTurn = SEARCH_BUDGET,
                params = listOf(BotParams(mapOf("eval" to "territory")), BotParams.EMPTY),
            ),
        )
    }

    @Test
    fun `alpha-beta against random on 12x12`() {
        // The fourth searcher, and the last entry in the registry with no case here. It belongs in
        // the cross-target set on SW-02's own terms rather than by exception: the descent is
        // `advance`/`undo` over the arena, the ordering is `MovePrior` at a temperature of zero so no
        // exponential is reached, and **the leaf reaches no transcendental at all** -- neither
        // `TerritoryEval` nor `ChamberEval` imports `kotlin.math`, and across `bots/src/commonMain`
        // `sqrt` appears in three files (`PressureBot`, `PuctTree`, `UctTree`) and `portableExp` in
        // two (`MovePrior`, `LearnedNet`), none of which this bot reaches at its defaults. That
        // sentence used to read "the leaf is `ChamberEval`, whose only transcendental is `sqrt`",
        // which named a `sqrt` that was never there. So a hash that moves here is arithmetic or
        // search order, never a platform's `log`.
        //
        // What this pins that the other three do not is the replay: a paid leaf re-applies the whole
        // path onto the arena its own payment reset, so an off-by-one there is a wrong position
        // appraised rather than a crash, and it would show up here and nowhere else.
        //
        // Re-pinned from -3589698981299349624 when `AlphaBetaBot.EVAL` moved from `chamber` to
        // `territory`. The question a golden failure asks, answered: the leaf is read at every paid
        // node of every pass, so every stream this bot searches at all is expected to move, and it
        // is the *only* hash in the repository that did -- the `alpha-beta at chamber` case below
        // holds at the old number, which is what says a leaf changed and not the search. The move
        // was P2's three twelve-rung equal-clock fields (13,200 matches a board) putting this bot at
        // `territory` **+108 / +46 / +41** over its own `chamber` rung at 8x8 / 12x12 / 20x20, a
        // fresh-seed seven-rung field reproducing the 12x12 +46 on disjoint intervals, and three
        // sequential tests at `elo0=0, elo1=5` -- +33 ±14 over 1,120 boards at 8x8, +21 ±15 over
        // 1,200 at 12x12, +98 ±26 over 360 at 20x20. `AlphaBetaBot.EVAL` carries the tables and the
        // one claim in them that does not survive its own residuals.
        assertEquals(
            -6565866919283159623L,
            hashOf("alphabeta", "random", seed = 2005, rows = 12, cols = 12, budgetPerTurn = SEARCH_BUDGET),
        )
    }

    @Test
    fun `alpha-beta at chamber, named rather than inherited`() {
        // **This case has now done the job it was added for.** It was written while `chamber` was
        // still `AlphaBetaBot.EVAL`'s default, carrying the same number as the bare case above
        // because `BotContractTest`'s "every knob at its declared default plays the match no knobs
        // at all plays" made those two one match. P3 then moved that default to `territory`: the
        // bare case moved to -6565866919283159623 and this one did not, which is exactly the
        // parting-company the pair was built to make legible — and `ChamberEval`'s block
        // decomposition and parity cap stayed pinned, on either target, through a release decision
        // they had nothing to do with.
        //
        // So the number below is no longer transferred from anywhere. It is the only thing pinning
        // this leaf, and a move in it is arithmetic or search order in `ChamberEval` alone.
        //
        // The note under `PUCT at its fitted leaf` leaves four of the six evaluations unpinned and
        // used to justify that by saying `territory` is `puct`'s default and `chamber` is
        // `alphabeta`'s. Half of that is now false — both defaults are `territory` — and it does not
        // matter, because neither leaf is pinned by a default any more.
        // Re-pinned from -3589698981299349624 when a trapped survivor began owing its fatal turn;
        // the new stream is exactly the old stream plus that final north move.
        assertEquals(
            7757850650334854603L,
            hashOf(
                "alphabeta",
                "random",
                seed = 2005,
                rows = 12,
                cols = 12,
                budgetPerTurn = SEARCH_BUDGET,
                params = listOf(BotParams(mapOf("eval" to "chamber")), BotParams.EMPTY),
            ),
        )
    }

    @Test
    fun `PUCT at its fitted leaf against random on 12x12`() {
        // The one `eval` value in the set, and the case for it is that this is the only evaluation
        // here whose arithmetic nobody designed. Four hundred multiply-adds off a baked literal,
        // softsign, and a logistic — so it is also the only place `portableExp` is reached inside a
        // *composed* evaluation rather than on its own. `PortableExpTest` already pins that series
        // to the raw bits in Chrome; what was unpinned is everything downstream of it, including
        // the decode of `LearnedWeights` from a string into fixed point.
        //
        // The other three values are deliberately not here. `territory` and `chamber` each have a
        // case of their own above, both naming the value rather than inheriting it from a bot's
        // default, so between them the bitmap sweep, the block decomposition and the parity cap are
        // pinned whatever either default does later. `survival` and `horizon` reach those through
        // `FillableSpace` and `SurvivalHorizon`, which hold no floating point at all, and
        // `mobility` is a liberty count over a divide — integer arithmetic is exact on every target
        // and `/` is specified, so a case for any of the three would buy codegen coverage the four
        // searchers already give and nothing else. A browser case is minutes of somebody's CI, so
        // the bar is a divergence it could actually catch.
        //
        // Re-pinned from -128377200664409204 when P4 replaced `LearnedWeights.ENCODED`. The question
        // a golden failure asks, answered: **both halves of this leaf moved and nothing else did.**
        // `PositionFeatures` went from 25 readings to 29 -- a runner-up chain, a chokepoint count, a
        // raw colour imbalance and a tempo margin, all four off sweeps the leaf was already paying
        // for -- and the model was refitted from a 12x12-only corpus onto 39,600 matches across 8x8,
        // 12x12 and 20x20. Either alone would move this hash; `LearnedNet.decode` refuses a literal
        // whose shape does not match the `PositionFeatures` beside it, so the two cannot move apart.
        // This is the **only** hash in the repository that moved: the other fifteen cases, including
        // both `alphabeta` ones and the bare `puct` one, hold at their recorded numbers, which is
        // what says a leaf changed and not the search or the arithmetic under it.
        //
        // What moved it was **not** the four readings. Scored on 13,200 fresh matches per board, the
        // fit this replaces reads 0.5475 / 0.5822 / 0.6274 of log-loss at 8x8 / 12x12 / 20x20 where a
        // refit of its own twenty-five readings on the board in question reads 0.5364 / 0.5685 /
        // 0.5798 -- so 0.048 of the 20x20 gap was corpus. The four readings are worth 0.0039 ± 0.0017
        // pooled over three seeds. `LearnedWeights` carries both tables.
        assertEquals(
            6798631882534688247L,
            hashOf(
                "puct",
                "random",
                seed = 2005,
                rows = 12,
                cols = 12,
                budgetPerTurn = SEARCH_BUDGET,
                params = listOf(BotParams(mapOf("eval" to "learned")), BotParams.EMPTY),
            ),
        )
    }

    @Test
    fun `the serpentine sweeper against random on 20x20`() {
        assertEquals(5564294816982454802L, hashOf("burninhell", "random", seed = 2005))
    }

    @Test
    fun `the growth cadence reaches the bots, not just the engine`() {
        // Classic Tron is a materially different game; a bot suite that cannot tell the two apart
        // would not notice `growEveryNthMove` being wired up wrong.
        val halfSpeed = hashOf("random", "random", seed = 11)
        val tron = hashOf(
            "random",
            "random",
            seed = 11,
            rules = ao.snakewarz.core.rules.RulesConfig(growEveryNthMove = 1),
        )

        assertEquals(false, halfSpeed == tron)
    }

    private fun hashOf(
        first: String,
        second: String,
        seed: Long,
        rows: Int = 20,
        cols: Int = 20,
        budgetPerTurn: Int = SEARCH_BUDGET,
        rules: ao.snakewarz.core.rules.RulesConfig = ao.snakewarz.core.rules.RulesConfig(),
        params: List<BotParams> = listOf(BotParams.EMPTY, BotParams.EMPTY),
    ): Long {
        val match = HeadlessMatch(
            listOf(ShippedBots.entryOf(BotId(first)), ShippedBots.entryOf(BotId(second))),
            rows = rows,
            cols = cols,
            seed = seed,
            budgetPerTurn = budgetPerTurn,
            rules = rules,
            paramsPerSlot = params,
        )
        match.run()
        return moveStreamHash(match.moves())
    }

    private companion object {
        /**
         * Evaluations a turn — twenty, which is twenty rollouts or twenty appraisals.
         *
         * One figure for all three searchers, which is new: an allowance used to be counted in
         * simulated moves, so `puct` needed ten times the number the other two got to run a
         * comparable search. It buys the same thing for all of them now.
         */
        const val SEARCH_BUDGET = 20
    }
}
