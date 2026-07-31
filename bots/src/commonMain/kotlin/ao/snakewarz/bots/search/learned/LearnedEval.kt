package ao.snakewarz.bots.search.learned

import ao.snakewarz.botapi.scratch.Playout
import ao.snakewarz.bots.search.EvaluationCost
import ao.snakewarz.bots.search.puct.ChamberEval
import ao.snakewarz.bots.search.puct.LeafEval
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.snake.SnakeId

/**
 * A leaf whose weights were fitted to games rather than argued for.
 *
 * Every other [LeafEval] here is a shape somebody chose and a handful of weights a sweep settled.
 * This is the same idea taken as far as it goes: [PositionFeatures] reads twenty-nine bounded numbers
 * off the position — the readings six phases of measurement say carry the signal — and [LearnedNet]
 * turns them into a probability of winning, with every coefficient fitted by gradient descent on
 * positions replayed out of the match log. `:lab`'s `train` is the command; `LearnedWeights` is what
 * it produced and carries the fit's own numbers.
 *
 * > **The next two sections are about the fit this leaf shipped before P4** — twenty-five readings
 * > over a 12x12-only corpus — and are kept as the record of what that one was worth on the board it
 * > was fitted for. They are not a description of what is in [LearnedWeights] now. What replaced it,
 * > and why, is two sections down.
 *
 * ### It is the same sweep [ChamberEval] pays for, plus a forward pass
 *
 * One [ao.snakewarz.bots.search.puct.TempoOwnership] sweep and one chamber decomposition per live
 * snake — that leaf's whole bill — and then 400 multiply-adds and about thirty divisions per snake.
 * That is not free at this rate: an evaluation here is under four microseconds, so a few hundred
 * arithmetic operations is a tenth of it. `lab time` at the shipped allowance on a 12x12, seeds
 * interleaved within a session with `uct` carried as a control it cannot touch:
 *
 * | | µs/turn, 12 seeds |
 * |---|---|
 * | `uct`, control | 1,469 |
 * | [ChamberEval] | 3,650 |
 * | this | 4,224 |
 *
 * **1.16x [ChamberEval] pooled over every seed, and 1.11x with each pooled over its own games of
 * fifty turns or more.** Read the range rather than either end: `time` seats the subject against an
 * opponent with no allowance, so a seed pairs the *board* and not the position, and this one wins
 * faster — which leaves its warm-up amortised over fewer turns and inflates the pooled figure. At
 * P3's exchange rate, where 59% more allowance was worth +105 Elo, an eighth of the clock is about
 * **23 Elo**, and that is what the readings had to buy back.
 *
 * ### It tops the field, and by a little more than it costs
 *
 * Two batches over the same seven entrants at the shipped allowance on a 12x12, on disjoint seed
 * bases — 200 rounds and then 300 — pooled to **10,500 matches, 6,455 of them distinct games**:
 *
 * | | rating | 95% | score |
 * |---|---|---|---|
 * | **this** | **248** | +233..+265 | 75% |
 * | [ChamberEval] | 219 | +204..+234 | 72% |
 * | `SurvivalEval` | 159 | +146..+173 | 65% |
 * | `TerritoryEval`, the default | 130 | +118..+145 | 61% |
 * | `uct` | 69 | +54..+85 | 54% |
 *
 * **+29 Elo over the strongest hand-written leaf, on disjoint intervals**, and it is not a pairing
 * artefact: it beats every other entrant in the field by more than [ChamberEval] does, taking 202 of
 * 300 off `uct` where that one takes 188. Head to head it is 292 of 500. Against the ~23 Elo the
 * clock costs, the honest verdict is **ahead per iteration and roughly level to slightly ahead per
 * millisecond** — a fitted model matches the best hand-written evaluation here and edges it, rather
 * than replacing it.
 *
 * Two warnings come with that. The direct pairing repeats about half its boards — 144 of 300 distinct
 * in the second batch — because two settings of one bot answer many seeds identically, which is
 * `MovePrior`'s lesson and the reason the rating over a field is the number quoted rather than the
 * head-to-head. And the field's own `us/turn` column is not a cost measurement: it orders entrants by
 * rating, because a stronger bot plays longer games on fuller boards.
 *
 * ### Where the ceiling is — and the first answer to this was wrong
 *
 * The fit's training and holdout log-losses land on the **same** number, 0.5728 against 0.5737 at 497
 * weights, where a model that always answers even scores 0.693. Nothing there is short of capacity, of
 * data or of optimisation, and a hidden layer is worth 0.023 of loss over plain logistic regression
 * and saturates at sixteen units.
 *
 * That equality was read as *"what is short is what twenty-five readings off one sweep can say — the
 * next gain here is a reading, not a layer"*, and **the inference does not follow**. The holdout was
 * drawn from the same one-board corpus as the training rows, so the equality is a statement about
 * capacity *on that board* and cannot see a transfer failure at all — which is exactly what the leaf
 * turned out to have. P4 measured all three on the same scale, on 13,200 fresh matches per board:
 *
 * | what | worth, in log-loss |
 * |---|---|
 * | refitting the identical twenty-five readings on the board being played | 0.011 / 0.014 / **0.048** |
 * | the hidden layer, over plain logistic regression | 0.023 |
 * | the four readings P4 added off the residual | **0.0039 ± 0.0017** |
 *
 * So the binding constraint was the **corpus**, by roughly an order of magnitude over the features,
 * and it bound hardest at 20x20 where this leaf was worst. The four readings are real, consistent in
 * sign at every seed on every board, and small. [LearnedWeights] carries both tables and the fit that
 * replaced the one those sentences were written about.
 *
 * ### What the model is asked, and what it is not
 *
 * The target it was fitted on is "does this slot win", so what comes back is calibrated as a
 * probability rather than as a margin — which is exactly the scale [PuctTree] credits, and it spreads
 * 0.23 either side of even on held-out positions, which is the reading that says the search is handed
 * a real gradient rather than a well-calibrated constant. It is asked once per live snake, and a dead
 * one is [LeafEval.LOSS] without being asked at all: a corpse has no region, so its feature row is
 * zeros and the answer would be whatever the bias happens to be.
 *
 * **Not a default, and not proposed as one.** Everything above is measured at `eval=chamber`, which
 * is itself not what this bot ships at, and moving `PuctBot.EVAL` moves `GoldenMoveStreamTest`'s
 * bare `puct` hash — the one that pins whatever the default is, rather than the case beside it that
 * names `territory`. **It now moves two ladder thresholds as well**: `BotLadderTest` seated `puct`
 * and `alphabeta` when they graduated, so the `puct` over `uct` and `alphabeta` over `puct` rungs are
 * both pairings a `puct` default reaches. That was not true when this paragraph was written and the
 * claim is worth re-reading rather than inheriting. The sequence is in `docs/Bots.md`.
 *
 * **And the reason it is not proposed is the clock, which is a different reason than it used to be.**
 * P2 measured the previous fit at **−167** at equal clock on a 20x20 and **−11** even when handed
 * 4.65x of it — a collapse. P4 refitted on all three boards and re-ran the field, 4,200 matches a
 * board, as a rating against the bare baseline in the same field:
 *
 * | | 8x8 | 12x12 | 20x20 |
 * |---|---|---|---|
 * | this at **equal allowance** (3.0-5.2x the clock) | +118 | +252 | **+88** |
 * | this at **equal clock** | +82 | +4 | **−74** |
 * | the same two, at the fit P4 replaced | +92 / +40 | +81 / −7 | **−160 / −316** |
 *
 * **The collapse is gone and the verdict is not.** At equal allowance this is now first in its field
 * on all three boards, where the old fit was first on none; at equal *clock* it is level with the bare
 * baseline at 12x12 and behind it at 20x20, because the readings cost 3-5.2x a turn and the fit does
 * not buy that back. Two cautions on the table: the two fields differ in composition, and contrasts
 * that ought to be unchanged move ±30-90 between them — so the 8x8 and 12x12 rows say nothing, and
 * only the 20x20 move of about +240 is outside that. The two fits have **never been seated in one
 * field**; what is measured directly, on identical corpora, is the loss.
 *
 * ### Correct on an empty board, honest on a map, unfitted for one
 *
 * [PositionFeatures] normalises by the board's open squares, so a map leaves every reading **in
 * range and defined** — `boardFill` still runs zero to one, `regionShare` is still at most one,
 * `headWalls` still lands in `{0, .25, .5, .75, 1}` — and on a board without walls the row is the
 * one [LearnedWeights] was fitted on, to the bit. What the model has not seen is the *distribution*
 * a map produces, so it is extrapolating rather than degenerate, and nothing here claims it plays a
 * map as well as it plays a rectangle.
 *
 * The refit wants its own instrument — loss on a map corpus by these weights against the same
 * weights refitted, then a field — and one thing to know before building it. `:lab`'s `train` keys
 * its [PositionFeatures] cache on rows, columns and slot count, so two different maps of the same
 * size share a reader. That is sound exactly as long as a reader is built from a grid and a slot
 * count and nothing else; a reading that depended on the map would be answered off whichever map
 * happened to arrive first.
 */
internal class LearnedEval(
    grid: Grid,
    private val slotCount: Int,
    private val net: LearnedNet = LearnedNet.decode(LearnedWeights.ENCODED),
) : LeafEval {
    private val features = PositionFeatures(grid, slotCount)
    private val row = DoubleArray(PositionFeatures.LENGTH)

    init {
        require(net.inputs == PositionFeatures.LENGTH) {
            "the baked model reads ${net.inputs} features and PositionFeatures produces " +
                "${PositionFeatures.LENGTH} -- one of the two was changed without the other"
        }
    }

    /** A sweep, every region taken apart, and a forward pass — one unit, like everything else. */
    override val cost: Int get() = EvaluationCost.LEARNED

    override fun valuesInto(playout: Playout, into: DoubleArray) {
        val board = playout.board

        var live = 0
        for (slot in 0 until slotCount) {
            if (board.snake(SnakeId(slot)).alive) {
                live++
            }
        }

        if (live <= 1) {
            // PuctBot never asks -- the board's own outcome is non-null the moment one snake is left,
            // and a real result is read directly. A unit test can call this, and an answer beats
            // handing the survivor whatever the model makes of an empty board.
            for (slot in 0 until slotCount) {
                into[slot] = if (board.snake(SnakeId(slot)).alive) LeafEval.WIN else LeafEval.LOSS
            }
            return
        }

        features.measure(board)
        for (slot in 0 until slotCount) {
            if (!board.snake(SnakeId(slot)).alive) {
                into[slot] = LeafEval.LOSS
                continue
            }
            features.into(slot, row)
            into[slot] = net.value(row)
        }
    }

    override fun toString(): String = "LearnedEval($net)"
}
