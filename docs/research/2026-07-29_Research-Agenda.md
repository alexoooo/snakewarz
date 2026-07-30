# Research agenda — 2026-07-29

Eight workstreams over eight phases. The goal is to **establish the bot defaults this repository
ships, measure the configuration nobody has measured, and turn the last agenda's findings into
something a player actually gets** — then chase what it left open and two directions it never
considered.

Its predecessor is [`2026-07-28_Research-Agenda.md`](2026-07-28_Research-Agenda.md), closed, whose
*Open at the close* is where most of this comes from. That document is **not edited** — a closed
agenda is a record of what was believed at the time, and
[`../Research-Process.md`](../Research-Process.md) says a new agenda cites the old one rather than
reopening it. Where this one corrects it, the correction is [below](#ground-truth) with a pointer to
the line it corrects.

How a phase is run, what an agent is told, and the order the instruments go in are all in
[`../Research-Process.md`](../Research-Process.md). This document is the *what*.

## Status

| Phase | Workstream | State |
|---|---|---|
| P1 | Instrument integrity and the cost floor | **landed** — [integrity](#what-p1-actually-found--the-integrity-half), [cost](#what-p1-actually-found--the-cost-half-and-the-number-that-re-prices-the-agenda) |
| P2 | The composed field, at equal clock, at three sizes | **landed** — [the rider](#what-p2s-rider-found--the-ahead-bucket-was-buying-about-half-what-it-looked-like), [the allowances](#what-p2-found-before-the-field-ran--cost-is-not-monotone-in-board-size), [the field](#what-p2-actually-found--the-phase-was-pointed-at-the-wrong-bot) |
| P3 | Adopt | **landed** — the scoped adoption was a [null](#what-p2-actually-found--the-phase-was-pointed-at-the-wrong-bot); [an unscoped one](#what-p3s-verification-found--the-right-claim-survives-and-the-headline-one-does-not) [executed](#what-p3-executing-found--a-cost-list-is-only-exhaustive-for-the-change-it-was-written-for) |
| P4 | The four residual features | **landed** — the premise was [false](#what-p4-actually-found--the-ceiling-was-the-corpus-and-six-phases-read-one-number-wrong); the features are real and small, and the corpus was the constraint |
| P5 | A rollout policy for `uct` | **landed** — [built, priced and measured](#what-p5-actually-found--the-mechanism-is-real-the-price-is-not-payable-and-step-0-nearly-killed-the-wrong-candidate); neither policy adopted, both wired and off |
| P6 | A policy-rollout leaf, as its own bot | **landed** — [closed on its own cost kill-criterion](#what-p6-actually-found--the-phase-closed-on-its-own-kill-criterion-and-the-accident-it-was-carrying-is-the-result); no bot added. The depth lead it carried is a decision for a person |
| P7 | The third seat | **landed** — [the map, and the ladder survives](#what-p7-actually-found--the-ladder-survives-a-third-seat-and-the-design-that-measures-it-nearly-didnt); two boards, no bot or default moved |
| P8 | Fused bitboard passes | **landed** — [built, proven correct, and declined](#what-p8-actually-found--the-fusion-works-is-provably-correct-and-is-half-the-speed-on-the-target-that-ships); the targets disagree in *sign* |

The coordinator owns this table and the "What P*n* actually found" sections. Agents report; they do
not edit either.

**All eight phases have landed and this agenda is closed.** What it moved, in total: `AlphaBetaBot`'s
default eval `chamber` → `territory`; `puct` and `alphabeta` graduated into the ladder with
`alphabeta` on top; `LearnedWeights.ENCODED` refitted across three board sizes; and a
`rolloutPolicy` knob on `uct` shipped wired and off. **Two of the eight phases shipped no code at
all**, and one of those produced the agenda's most reusable rule. Everything left to a person is in
[Open at the close](#open-at-the-close); the one decision that moves a shipped ladder rung is the
first entry there.

---

## Ground truth

Read from the code on 2026-07-29, not from the docs' description of it. The first five correct the
closed agenda, and getting any of them wrong changes which phase is risky.

### 1. `puct` is not out of knob slots

It declares **17**; `BotKnob.MAX_PER_BOT` is **20** (`BotKnob.kt:276`). Three spare. The 18th knob
fails exactly two pinned counts in `ShippedBotsTest` — loudly, by design — and touches nothing else;
`BotEntry`'s `require` and `ReplayCodec`'s decode check do not bite until the 21st.

*Corrects the closed agenda at `:617`, which reported the ceiling as 16 and reached.* The likely
origin is `BotEntry.params`, which is 17 minus the non-`Param` `Search` knob.

`BotKnob.kt:273-274` is still the relevant sentence, and it shapes P6: **"the ceiling is per bot, so a
bot that wants more than this wants a second bot rather than a larger payload."**

### 2. The move prior is already on

`priorLiberty` defaults to **`0.5`** and is live (`PuctBot.kt:657-665`). Its own KDoc calls it *"the
only one whose default is not zero, so it is also the scale the other three are measured against."*
Four of the five prior knobs are neutral at their defaults, not five.

*Corrects the closed agenda at `:772`.* It matters twice over: the measured **+103 Elo** prior sits
*on top of* an active liberty prior rather than replacing nothing, and `priorLiberty` **has never been
swept** — deliberately, because above zero temperature it and `priorTemperature` are the same degree
of freedom.

### 3. Moving `puct`'s default eval moves one hash and no thresholds

`BotLadderTest` never names `puct` — the bot is registered outside the ladder precisely so it asserts
nothing there (`ShippedBots.kt:69-73`). And `GoldenMoveStreamTest` pins its own private
`SEARCH_BUDGET = 20` (`:226`), independent of `MatchSetup.DEFAULT_BUDGET_PER_TURN`.

> **P3 made this whole item false, eight hours after P1 made it true.** `BotLadderTest` now seats
> `puct` and `alphabeta`, so a `puct` **default** move reaches the `puct`-over-`uct` and
> `alphabeta`-over-`puct` rungs. The sentences below were correct when written and were rewritten
> again by the phase that seated the bots. `LearnedWeights.ENCODED` is **not** one of these: it reaches
> only `eval=learned`, which after P3 is nobody's default.

*Corrects `PuctBot.kt:506` and `:785`, and the closed agenda at `:603` and `:781`, all four of which
say moving a `puct` default moves "`GoldenMoveStreamTest`'s hash **and** `BotLadderTest`'s
thresholds." The second half is false.* Fixing those four sentences is P1's work.

> **P1 corrects this paragraph's own line references, and finds a fifth sentence.** The claim is
> confirmed false — `BotLadderTest` seats `wallhug`, `random`, `space`, `pressure`, `chase`,
> `flat-monte-carlo`, `uct` and neither `puct` nor `alphabeta` — but the citations were not. There is
> no such sentence at `PuctBot.kt:506`; that line is `SOLVER`'s KDoc and it is *correct* as written.
> A repo-wide grep found the false claim in exactly two `:bots` sources: `PuctBot.kt:785` and
> **`LearnedEval.kt:88`**, which this paragraph missed. In the closed agenda the sentences are at
> `:603-604` (it breaks mid-sentence) and `:770`, not `:781` — and being a closed agenda they are
> **not edited**; this paragraph is the correction of record. A sixth stale count sat six lines from
> one of them: `LeafEval.kt:23` said *"There used to be a **fourth**"*, written when there were three
> evals.

One consequence the count hides: `GoldenMoveStreamTest.kt:153-160` justifies pinning only two of the
six evals because *"`territory` is `puct`'s own default and `chamber` is `alphabeta`'s."* **Move the
default off `territory` and `territory` is pinned by nothing, on either target.** A replacement case
is part of P3, not an afterthought.

### 4. The budget default is the move no test can see

`:bots` may not import `:match`, so the shipped allowance is **hand-typed** in every test that needs
it:

| Copy | Value | |
|---|---|---|
| `MatchSetup.kt:241` | `1_000` | the source |
| `BotLadderTest.kt:133` | `1_000` | *"which `:bots` may not import"* |
| `ThroughputTest.kt:140` | `1_000` | same comment |
| `AlphaBetaBotTest.kt:154` | `1000` | same comment, different spelling |
| `ReplayCodecTest.kt:274` | `40_000` | **deliberately historical** — what the constant was when `SHIPPED_PAYLOAD` was captured |

Nothing asserts the first four agree. Raise `DEFAULT_BUDGET_PER_TURN` and **the build stays green
while the ladder goes on certifying a rung nobody ships** — which is strictly worse than a red test.
`MatchSetup.kt:224-227` knows this and enforces nothing.

So the closed agenda had the two release decisions the wrong way round: the eval move is loud and
cheap, and the budget move is silent. The fifth row is the trap in the obvious fix — a sweep that
updates every literal named like the others breaks the replay codec's golden payload.

### 5. Adopting the prior re-tunes a second bot

```kotlin
// AlphaBetaBot.kt:240-247
private val policy = MovePrior(
    setup.grid,
    PuctBot.PRIOR_LIBERTY.default,
    PuctBot.PRIOR_PINCH.default,
    ...
```

`alphabeta` reads `puct`'s prior **defaults**, not its params. Adopting `priorPinch`/`priorTail`/
`priorTemperature` therefore moves `alphabeta`'s golden as well as `puct`'s — both in the
**cross-target** set, so it needs a real-Chrome re-verification — and falsifies that golden case's own
justification, which is that `alphabeta`'s ordering *"is `MovePrior` at a temperature of zero so no
exponential is reached."* Above zero it reaches `portableExp` at every node, and the bot's SW-02 story
changes with it.

*New. The closed agenda costed the prior adoption at one hash and one bot.*

### 6. Equal allowance is never equal milliseconds — but one field can still be equal clock

`EvaluationCost`'s seven constants are all `1`, deliberately and permanently: the calibration that
would be honest is a function of the board, not a number, and *"half a calibration would be worse than
none — it would look settled"* (`EvaluationCost.kt:61-66`). So a field mixing evals at one allowance
is a style match-up in cost as well as in play.

The escape is that **`:lab play` accepts a per-entrant `budget=N` in the entrant spec**
(`LabCommand.contestantOf`), and the duplicate guard compares whole contestants, so `puct:budget=900`
and `puct:budget=1100` are two rungs of one field. Allowances taken from paired timings therefore make
a *single* field equal-clock and rateable in one fit. That is what P1 buys P2.

`EvaluationCost`'s KDoc table already carries `chamber` (1.09×/1.07× survival) and `learned`
(1.11–1.16× chamber), with `uct`'s rollout as a carried control. The table that is stale is
`MatchSetup.DEFAULT_BUDGET_PER_TURN`'s, and only its `puct territory, JVM` column.

### 7. There is no rollout policy abstraction, and only two bots roll out

The choice is one hardcoded expression, duplicated verbatim at `randomPlayout.kt:30` and
`truncatedPlayout.kt:35`: `if (legal.isEmpty) Direction.NORTH else legal.nth(rng.nextInt(legal.size))`.
Its consumers are `UctBot` and `FlatMonteCarloBot` and nothing else — `PuctBot` has a `LeafEval`
instead, and `AlphaBetaBot` uses the arena as a make/unmake board.

What is affordable in that loop is the whole question:

| Candidate policy | Per-step cost against uniform |
|---|---|
| a local wall-avoiding rule (one extra `freeNeighbors` per candidate) | ~2–3× |
| `MovePrior` sampled | ~3–6×, and that is an estimate, not a measurement |
| a reactive bot's move | **~10–30× a *whole* rollout**, i.e. ~1,000× a step |

`MovePrior.into` needs only a `Grid` at construction and a `BoardView` + `SnakeId` + `DirectionSet` +
a caller-owned `DoubleArray(4)` per call — no `Scratch`, no `Turn`, same module. It is reachable from
a rollout with zero plumbing. A reactive bot is not, and the table says why it should not be.

**And `EvaluationCost.ROLLOUT` is a flat `1`, so a dearer policy buys no fewer iterations.** Its whole
cost lands on the wall clock, which means an equal-allowance comparison *flatters* it. That is the
central trap of P5 and P6 and it belongs in their risk column, not their thesis.

### 8. Separation is real, reversible, and not knowable at decision time

`Separation.permanent` — the conservative predicate — is **provably vacuous at two snakes**
(`Separation.kt:28-33`): a two-snake match ends at the first death, so there are never dead bodies,
and the rectangle minus its wall ring is connected. It *"becomes a real question the moment a third
snake is seated."* That is most of P7's case.

`Separation.naive`, which is what every eval means by *isolated*, first fires at move 66 and the one
that holds arrives at move 136 of 165, with **81% of separated matches coming apart and rejoining at
least once.**

And the number a dispatch rule would want does not exist online. `PhasesCommand.kt:150-154`:

> **The split is taken with hindsight, and it has to be.** … what stands in for it is the *last* time
> the free squares came apart before the game ended. … **That makes this a diagnostic and not a
> dispatch rule: a bot deciding what to play cannot know which of the two it is looking at.**

### 9. `play` is the one instrument that does not checkpoint

`ab`, `tune` and `spsa` all write per block, per decision and per iteration, and `spsa` can resume a
journal. `play` writes once, at the end. A field that dies at 90% loses everything. At 18 threads a
7-entrant 200-round 12×12 field is ~15 minutes and 300 rounds ~23, so this is survivable — but P2 and
P7 are the long ones and should be sized against it.

---

## Standing decisions

Made by the user before the work started, so no phase re-litigates them.

**Breaking changes are acceptable.** *"In general we are ok with breaking changes — this is just a
game for fun."* SW-05 still describes what the code does and why, and a phase still says what it
changed; but the bar for this agenda is **say what moved**, not *never move it*. Concretely: the
retired `eval=expert` resolves to whatever the default is, so moving the default silently re-points
every shared link carrying it — that is **accepted and documented**, not shimmed. The same relaxation
applies to freezing a new `Choice` value or a new `BotId`; the design reasons in ground truth 1 still
stand on their own.

**Adoption lands at P3**, immediately after the field that ranks the candidates rather than at the end
of the agenda. [`../Research-Process.md`](../Research-Process.md) says a phase never moves a default,
because it is a release decision belonging to a person — this is that person making it, in advance,
and the coordinator brings the number to them before P3 executes. The reason for early rather than
late is the predecessor's worst measurement defect: **every knob table in `PuctBot.kt` is baselined at
`eval=chamber`, which is not what the bot ships**, and `PRIOR_TAIL`'s KDoc says so outright. Adopting
first makes the bare entrant `puct` the baseline for P4 through P8. The price is re-pinning goldens a
second time if P4 or P5 wins, which is one command run twice.

**The third seat is in scope**, one phase, P7.

### Taken after P2, on P2's number

The two above were taken before the work. These were taken when the coordinator brought the number,
which is what the adoption paragraph above says will happen — and the number was not the one anybody
expected, so this is new authorisation rather than the pre-authorised kind.

**`AlphaBetaBot`'s default eval moves `chamber` → `territory`, conditional on P3a.** If P3a's
corrected 12×12 allowance does not hold the margin, the phase stops and reports instead of adopting.
The 8×8 (+91) and 20×20 (+41) margins already survive a 10% allowance error, so the *result* does not
hinge on the 12×12 number; the adoption was still made conditional on it, because the narrowest
margin is the one a reader will check.

**`puct` and `alphabeta` both graduate into the ladder, with `alphabeta` on top.** This is the
largest test change of the agenda — it moves `BotLadderTest`'s rung structure *and* its thresholds,
and it means the ladder stops being the instrument the shipped bots were originally ranked in. It
follows the evidence: `puct` clears `uct` by +54/+58/+62 and `alphabeta` clears `puct` on all three
boards, so seating one without the other would have been the inconsistency.

> **What the user was told, corrected.** "`alphabeta` clears `puct` on all three boards" was put to
> them as the ground for this decision, and P3a has since shown it is true of the *field ratings* and
> **false of the head-to-head at 8×8**, where `alphabeta:eval=territory` loses to bare `puct`
> **89–111** while rating +131 above it. The ladder is a 12×12 instrument and at 12×12 the ordering
> is strong — 70.5% head-to-head — so the decision stands on the board the ladder is measured on. The
> reversal is a real board-size intransitivity and belongs in `BotLadderTest`'s own KDoc rather than
> in a footnote here.

---

## The workstreams

Ranked by expected Elo per session, then reordered for what makes later phases cheaper to *measure*
and for collisions at the registration site. Sizes are S/M/L in agent-sessions.

| # | Workstream | Thesis | Reuses | Size | Risk — *if this is a null, will I be able to tell?* |
|---|---|---|---|---|---|
| P1 | **Instrument integrity and the cost floor** | Nothing downstream is believable until the four budget literals fail loudly when they disagree, `territory` is pinned by something, and an appraisal is timed in Chrome rather than derived from a JVM ratio | `ThroughputTest`'s existing Chrome path, `:lab time`, `GoldenMoveStreamTest` | M | Yes — every deliverable is a number or a red test, and both are visible on the spot |
| P2 | **The composed field, equal clock, three sizes** | `learned`, the prior and the raised budget were each measured alone, against different baselines, in different fields. One field carrying every combination at per-entrant allowances from P1 is the only instrument that ranks them | `play` + `rate`, per-entrant `budget=`, `bootstrapIntervals` | S code / L batch | Yes — `rate` prints intervals and a residual table, and the field either separates or it does not |
| P3 | **Adopt** | The blast radius is two goldens, one Chrome run and zero ladder thresholds — not the release event the closed agenda feared. Deferring is what left every knob table baselined at a setting nobody plays | the adoption ritual in [`../Bots.md`](../Bots.md) | S | N/A — this is execution, not measurement |
| P4 | **The four residual features** | `learned`'s train loss equals its holdout loss to five places, so it is bounded by its 25 features and not by capacity, data or optimisation | `PositionFeatures`, `train`, the 22,452-match corpus | M | Yes — the train/holdout gap is the readout, and it moves or it does not |
| P5 | **A rollout policy for `uct`** | Uniform-random is the least considered part of the only two bots that roll out, and `MovePrior` is reachable there with no plumbing | `MovePrior`, `randomPlayout`, `UctBot`'s knob slots | M | Only with a step-0 divergence probe — see below |
| P6 | **A policy-rollout leaf, as its own bot** | `eval=rollout` was deleted because `uct` was already its control. That is true of a *uniform* rollout; it is not true of a policy rollout under a tree that also has a prior | P5's policy, `PuctTree`, `ShippedBots` | M | Yes, if P5 lands first — P5's policy is the control |
| P7 | **The third seat** | Every measurement in this repository is 2-player. At three snakes the conservative separation predicate stops being vacuous and `alphabeta`'s paranoid backup stops being identical to `puct`'s max^n | `--format ffa`, `BotContractTest`'s 1–4 seat sweep, `Separation` | L | Partly — there is no 3-seat ladder to rate against, so P7 builds its own field |
| P8 | **Fused bitboard passes** | P3-the-bitboard-phase quantified a further 1.3–1.6× and did not take it. A cost result re-opens settled verdicts | `CellBits`, `SpaceOwnership`, the differential test | S | Yes — a paired ratio against an unaffected control |

**Why this order.** P1 is first for the reason the closed agenda gave about its own bitboard phase —
it was ranked third and should have been first, because it re-priced everything before it and made
everything after it affordable. P1 is the same shape: without a paired per-config cost there is no
equal-clock field, and without the Chrome number there is no honest budget. P2 before P3 because
adoption needs a number. P3 early by the standing decision above. P5 before P6 because P5 builds the
thing P6 reuses and is its control. P8 last and **droppable** — it is the row with the least thesis
risk and the most known answer, which makes it the natural passenger if the agenda runs long.

**Collisions.** P3, P5 and P6 all touch a bot's knob list; P4 and P3 both touch
`GoldenMoveStreamTest`. Phases run sequentially anyway, so these are re-work rather than conflict —
but they are why P3 is one phase and not three.

---

### P1 — Instrument integrity and the cost floor

Two halves, one agent each if the batches are long.

**Integrity.** Make the four budget literals of ground truth 4 fail loudly when they drift, without
touching `ReplayCodecTest`'s deliberately historical copy — `:bots` cannot import `:match`, so the fix
is an assertion, not an import. Add a golden case pinning `eval=territory` explicitly so that
ground truth 3's hole is closed *before* P3 opens it. Fix the four sentences that say a `puct` default
moves `BotLadderTest`'s thresholds. Then the stale counts: `LeafEval.kt:15` says *"the **five**"* and
lists five while `EVAL.values` holds six; `PuctBot.kt:46` says *"the **three** settings"*;
`EvaluationCost.kt:99` says `TERRITORY` is *"about **three** ROLLOUTs on a 20×20"* where `:43-44` of
the same file measures **0.9 and 1.7**.

**Cost.** `ThroughputTest`'s Chrome path is hardcoded to `entry("uct")`; extend it to the candidate
appraisals. **Nobody has ever timed an appraisal in Chrome** — the current 6.2–6.8 ms figure in
`MatchSetup`'s KDoc is a JVM measurement multiplied by `uct`'s browser tax, and the browser is the
deployment target. Then paired JVM timings per candidate config at 12×12 and 20×20, by the protocol
in [`../Research-Process.md`](../Research-Process.md#2-cost-paired-with-a-control): worktree the
baseline, interleave seeds, carry an entrant the change cannot touch.

> **A hazard in `time` itself.** It plays a *different game per entrant*, against a zero-budget
> `space` (`TimeCommand.kt:36-40`), so a stronger entrant plays a longer game on a fuller board and
> its µs/turn carries that. Derive the allowance from a **paired ratio against a fixed control at a
> fixed budget** and scale; do not read it off one entrant's block.

**Deliverable:** an allowance per candidate that fits the ~8 ms frame slice in *Chrome*, and a build
that goes red when the mirrors drift.

### P2 — The composed field, at equal clock, at three sizes

The run the closed agenda's three shipping decisions are waiting on. One field, every combination,
rated in one fit — the instrument P5b's intransitivity result demands, because a head-to-head between
two settings of one bot measures a style match-up and only a common field converts that into strength.

Entrants, each at the allowance P1 assigns it:

- `puct` bare — **the shipped baseline, and the thing nothing has been measured against**
- `puct:eval=chamber`
- `puct:eval=chamber` + the tuned prior
- `puct:eval=learned`
- `puct:eval=learned` + the tuned prior
- `puct:eval=learned` + the tuned prior + `priorWall=0.45` — the one prior coordinate with **no
  verdict at all**; it never moved off its start in the sweep that settled the others, and it costs
  one column here
- `alphabeta` at whichever leaf P1's costing favours
- `uct`, and one reactive anchor to keep the field connected

Then repeat the field at two more board sizes. **That answers board-size conditioning for the price of
a flag**: if the ordering is stable across sizes, the whole direction is a null in an afternoon; if it
inverts, that is the strongest result on this agenda and it earns its own follow-up. The known
precedent is real — the solver measured **+33 ±19 on 12×12 and −41 ±25 on 20×20**.

Read the distinct-games line before anything else, take cost from P1's paired runs and never from the
field's `µs/turn` column, and state the field composition beside any rating that will be quoted later
— a single Elo fitted over an intransitive cycle is field-composition-dependent.

**Rider, one hour, in the diagnosis step.** `PhasesCommand`'s AHEAD bucket is `mine > rival` on a raw
free-square flood, so a one-square lead buckets identically to a commanding one, and its own KDoc says
*"part of that 93% is the size of the lead rather than the quality of the fill. Nothing here separates
the two."* Add a lead-size column and separate them. This is what the separated-endgame idea reduced
to once it was checked against the code — see [Considered and not ranked](#considered-and-not-ranked).

### P3 — Adopt

Execute the adoption ritual in [`../Bots.md`](../Bots.md) for whatever P2 ranks first. The coordinator
brings the number; the user confirms; the phase executes.

What it costs, stated correctly this time: **the eval default** moves one golden hash, needs the
`territory` case P1 added so the bitmap sweep stays pinned, and re-points every shared `eval=expert`
link — accepted and recorded in `PuctBot.EVAL`'s KDoc, which already carries the sentence that will
have to change. **The prior** moves a *second* golden because `alphabeta` borrows `puct`'s prior
defaults, needs the browser suite in real Chrome, and turns `alphabeta` into a bot that reaches
`portableExp` at every node — so that golden case's SW-02 justification is rewritten, not just
re-pinned. **The budget** breaks no test at all, which is why P1 goes first.

Also open here: whether `puct` graduates out of `ShippedBots`' experimental section into the ladder.
`ShippedBots.kt:69-73` says to promote it when the number is in *or leave it and say why* — P2 is that
number. Related: `ShippedBots.kt:45-46` claims `:ui` seats the second slot from the first bot on the
list, and `Chrome.kt:335` has seated `uct` for some time. Fix the comment while the file is open.

### P4 — The four residual features

`eval=learned`'s train loss equals its holdout loss to five places (0.56825 / 0.56824), a hidden layer
buys 0.023 and saturates at 16 units, and 60 epochs equals 30. It is bounded by its **25 features**.
The four the residual named: a **tempo margin** off `TempoOwnership.distanceTo`; an **articulation
count**; the **second-best chamber's worth**; a region's **raw colour imbalance** kept separate from
the parity cap.

Cheaper than it looks: `PositionFeatures` already holds both a `TempoOwnership` and a `ChamberTree`,
so the sweeps are running. Two of the four need new accessors on `ChamberTree`, which exposes only
`chainWorth`, `chainArea`, `regionArea` and `sealed`.

> **Do not kill the articulation feature by remembering the wrong sentence.** The finding that *"a
> true articulation test is unaffordable"* was about the **prior**, which runs three sweeps per
> iteration. At the leaf, Hopcroft–Tarjan is already running and the count falls out of the same pop.

Retraining moves `LearnedWeights.ENCODED`, which `PuctBot.kt:1035-1038` says *"moves this bot the way
moving a knob's default would"* — so P4 moves the `eval=learned` golden, cross-target, Chrome. And the
ktlint trap from last time still applies: a long single-line weight literal fails the 120-column gate
with no auto-fix, and concatenated chunks fold back to one data segment.

### P5 — A rollout policy for `uct`

Ships as a knob on `uct` defaulting **off**, so the blast radius is zero. `uct` declares four knobs
against a ceiling of 20.

**Step 0, before any strength claim: a divergence probe and a paired `time`, together.** How often
does the policy pick a different move from uniform? A policy that changes the step in 3% of positions
cannot move a rollout, and that is an hour's answer. Pair it with the timing because of ground truth
7's flat `ROLLOUT = 1`: a dearer policy buys no fewer iterations, so an equal-allowance `ab` **flatters
it**, and the strength claim has to be made at equal clock or not at all.

Candidates in cost order: the local wall-avoiding rule first, `MovePrior` sampling second. Note that
`randomPlayout` is shared with `flat-monte-carlo`, so *were* this ever made a default it would move
two goldens and five ladder assertions — which is the argument for the knob, not against the work. And
`EvaluationCost.kt:88-89` names the consequence: `UctBot.ROLLOUT_DEPTH` is the first thing to
re-measure when two kinds of rollout stop costing the same.

### P6 — A policy-rollout leaf, as its own bot

`eval=rollout` existed and was deleted, for a reason that has to be answered before this row is worth
running (`LeafEval.kt:21-24`):

> It is gone: `uct` was always in the matrix beside it, and `uct` is that control — a tree with a
> random rollout at the leaf — without also being a setting nobody would choose to play against.

That reason is specific to a **uniform** rollout. `uct` is the control for uniform-random-under-a-
tree; it is not a control for a *policy* rollout under a tree that also carries a prior and PUCT
selection. If P5 finds a policy worth anything, this is a genuinely new point in the space, and P5's
policy-`uct` is its control.

Built as a **new experimental `BotId`**, not a seventh `puct` eval value — `alphabeta`'s own
precedent, and `BotKnob.kt:273-274`'s "a bot that wants more wants a second bot." The standing
decision on breaking changes lowers the bar for freezing the name; the design reason for a separate
bot stands regardless.

**If P5's step-0 probe comes back near zero, P6 does not run.** Say so and move on.

### P7 — The third seat

Everything in this repository was measured at two snakes. Seating a third un-parks three things at
once:

1. **Ground truth 5 of the closed agenda stops being vacuous.** `Separation.permanent` is provably
   never true at two snakes and is a real predicate at three. It is pinned by a test that asserts the
   vacuity, so that test is the starting point.
2. **`alphabeta` and `puct` stop agreeing on what a backup means.** `alphabeta` reduces to paranoid,
   `puct` to max^n, and at N=2 those are the same rule — which is why the closed agenda parked *"max^n
   vs paranoid vs risk-sensitive backup"* as cheap but unmeasurable. At three seats it is measurable.
3. **A dispatch portfolio gets a regime where it might pay**, having been bounded at ~25 Elo at two
   seats.

`--format ffa` exists and seats everybody; `BotContractTest` already sweeps 1–4 seats and a 2,160-match
probe found no multiplayer defect, so the bots are sound at three — nothing in `:bots` was ever
written as a duel. What does *not* exist is a 3-seat ladder, so P7 builds its own field and states
plainly that its ratings are not comparable with any 2-seat number in this repository.

The honest risk: this is the row most likely to produce an interesting map and no Elo. Rate it a
success if it produces the map.

### P8 — Fused bitboard passes

A further **1.3–1.6×** was quantified and not taken, and *"nothing beyond that."* Pure speed, which
converts at a measured exchange rate. `CellBits.settleInto` is already fused; the unfused work is one
level up, in `SpaceOwnership`'s per-layer word loops.

One subtlety worth reporting properly: `advanceAlone` — the single-frontier path — **is** the
separated case, and ground truth 8 says that is 85% of matches from around move 136 of 165. So the
payoff is phase-weighted, and quoting one board-level ratio would repeat the closed agenda's own
"structurally wrong estimate" mistake in miniature. Quote it per board **and** per phase.

---

## What the phases found

Written by the coordinator as each phase lands, in the order they landed. The workstream sections
above are what was *believed* before the work; these are what the work returned.

### What P1 actually found — the integrity half

**The three things the phase was sent to fix were all real, and every citation for them was wrong.**
That is the finding, and it generalises: this agenda's ground truth was read from the code, but its
*line numbers* were read once and not re-checked, and a line number is the part that rots first.

| The agenda said | The code said |
|---|---|
| `PuctBot.kt:506` claims a `puct` default moves `BotLadderTest`'s thresholds | It does not. `:504-506` is `SOLVER`'s KDoc and is **correct as written** — it names only the golden hash |
| Two `:bots` sentences carry the false claim | Two do: `PuctBot.kt:785` and **`LearnedEval.kt:88`**, which the agenda missed entirely |
| The closed agenda carries it at `:603` and `:781` | `:603-604` (the sentence breaks across the line) and **`:770`**. `:781` is a table row about the budget |
| `LeafEval.kt:15` is the stale count in that file | So is `:23` — *"There used to be a **fourth**"*, written when there were three evals and there are six |

The claim itself is confirmed false at the root: `BotLadderTest` seats `wallhug`, `random`, `space`,
`pressure`, `chase`, `flat-monte-carlo` and `uct`, and neither `puct` nor `alphabeta` appears in it.
`GoldenMoveStreamTest.SEARCH_BUDGET = 20` is that suite's own default for `hashOf` and is independent
of `MatchSetup.DEFAULT_BUDGET_PER_TURN`. So P3's blast radius is what ground truth 3 says it is.

**`EvaluationCost` disagrees with itself, and nothing in the file says so.** Its two KDoc tables were
taken in different sessions: `territory` at 20×20 is **1.65×** the rollout in the first (`:26-29`) and
**1.37×** in the second (`:76-79`), with only `uct`'s rollout carried as a control across them. Both
are presented as fact and the prose quotes only the first. The stale sentence at `:99` — "about three
ROLLOUTs" — matched neither, and is now the `:43-44` figures (0.9 and 1.7). Which table is right is
P1b's to settle by taking its own paired pair, and it is under instruction not to average them.

**What shipped.** Four hand-typed budget literals became **one copy plus one tripwire**: a new
`bots/src/commonTest/.../ShippedBudget.kt` holding `SHIPPED_BUDGET = 1_000` that `BotLadderTest`,
`ThroughputTest` and `AlphaBetaBotTest` all read, and a new `MatchSetupTest` case pinning
`DEFAULT_BUDGET_PER_TURN` whose failure message *is* the procedure. There is no compile-time link
available and that is by design, not omission — `:bots` → `:match` is forbidden in the test source set
too, and `BotKnob.Search` declines to carry a default on the grounds that how much a match grants is
the match's policy. The red was **verified rather than assumed**: raising the constant to `1_180`
fails the pin with `the shipped allowance moved; :bots' SHIPPED_BUDGET has to move with it`.

`ReplayCodecTest`'s `40_000` was correctly left alone, and both files now say why it is not one of
these copies.

**The asymmetry, stated rather than hidden.** The tripwire catches the drift event that matters —
raising the shipped allowance while the ladder goes on certifying a rung nobody plays — and does
**not** catch the reverse, someone lowering `:bots`' copy alone to speed the suite up. Catching that
needs a source-scanning Gradle check or a `:lab` test reading both files off disk, and the coordinator
judged both disproportionate at the failure site. It is in [Open at the close](#open-at-the-close).

**`eval=territory` is now pinned by name**, at `-900434540592784873L` — deliberately the same number
as the bare `puct` case, because `EVAL.read` returns `territory` for `BotParams.EMPTY` too, so today
it is the identical bot playing the identical match. The two part company the moment P3 moves the
default, which is the entire point: the bare case pins *whatever the default is*, and only a named
case keeps `TerritoryEval`'s bitmap sweep pinned through a release decision it has nothing to do with.
The hole ground truth 3 predicted is closed **before** P3 opens it.

**Two things P3 inherits.** `docs/Research-Process.md:307` and `docs/Bots.md:256` carry the same
sentence in generic form, where it is *true* — for a ladder bot. But `Bots.md`'s adoption ritual step
5, "re-check `BotLadderTest`'s thresholds", is a **no-op for `puct`**, and P3 is the phase that will
read it. And `ShippedBots.kt:45-46` is confirmed stale as suspected: `Chrome.kt:334-336` seats slot 1
from `DEFAULT_OPPONENT` with a fallback to `bots.firstOrNull()`, so `random` being first no longer
decides the opening opponent.

### What P1 actually found — the cost half, and the number that re-prices the agenda

**Equal allowance is not merely "not equal milliseconds". It is off by up to 4.7×, and every knob
table in the repository was measured across that gap.**

| entrant | 12×12 ×`puct` | **12×12 budget** | 20×20 ×`puct` | **20×20 budget** |
|---|---|---|---|---|
| `puct` (= `eval=territory`) | 1.00 | **1000** | 1.00 | **1000** |
| `uct` | 1.14 | **880** | 0.78 | **1280** |
| `puct:eval=chamber` | 3.45 | **290** | 4.58 | **220** |
| `puct:eval=learned` | 3.86 | **260** | 4.65 | **215** |
| `alphabeta` (default `chamber`) | 3.48 | **285** | 4.43 | **225** |
| `alphabeta:eval=territory` | 0.95 | **1000** | 1.02 | **1000** |
| `alphabeta:eval=learned` | 3.86 | **260** | 4.74 | **210** |

Chrome, `AppraisalTape`, median of 5 passes, **mean of two independent full browser runs agreeing to
within 3% on every ratio but one**. Anchored at `puct:budget=1000` so every figure is a ratio and
machine-independent.

So `eval=chamber` at the shipped allowance is **three to four and a half times `puct`'s wall clock**,
and at equal clock it gets roughly **a quarter of the evaluations**. Every published comparison
between these leaves gave the dearer one several times the clock. That does not make those numbers
wrong — it makes them answers to a different question than the one a shipping decision asks, and P2
is now the phase that asks the shipping question.

**`alphabeta`'s costing favours `eval=territory` decisively** — 0.95×/1.02× against its shipped
`chamber` at 3.48×/4.43×, which is 3.5–4.4× more allowance at equal clock. Its published strength
table is at `chamber`, so P2 carries **both** rungs rather than picking one on cost alone.

**The tuned prior is free**: 1.01× ± 0.06, 95% CI [0.95, 1.06] over 18 paired runs — `portableExp`
runs at most three times per *expansion* against a whole leaf. So P2 seats every prior variant at its
twin's allowance and the prior costs it nothing.

#### Four instrument defects, three of them in instruments this repo trusted

1. **There is no such thing as "`uct`'s browser tax."** The Chrome/JVM ratio runs **2.4×** to **3.3×**
   across five configs on one board, so carrying one bot's onto another's is up to 40% error. And the
   JVM figure it was multiplied by is an **opening**: `:lab time` seats the subject against a
   zero-allowance `space`, and on a 20×20 that match ends anywhere between turn **28 and 225** on the
   seed alone. It reads a `puct` turn at 2.6 ms where the same JVM reads **10.0 ms** over a line
   played onto a full board. The published 6.2–6.8 ms band does bracket the measured 6.0 ms — by the
   two errors cancelling, which is luck and not validation.
2. **The frame criterion was checked against the wrong statistic.** `:ui` can stop between turns and
   not inside one, so what overruns a frame is a *single* turn; 6.2–6.8 ms is a mean. The dearest
   sampled turn is **8.7 ms**. `puct` at the shipped 1,000 on a 20×20 is at the **edge** of the 8 ms
   slice, not comfortably inside it. The slice affords 920–1,350 evaluations and 1,000 sits inside at
   either end — so **no change to the shipped allowance is indicated**, which retires one of the
   closed agenda's three release decisions.
3. **`--tests` on `wasmJsBrowserTest` silently runs one test method of the named class** and reports
   `BUILD SUCCESSFUL`. Two patterns, two runs, one of `ThroughputTest`'s four each time — and it saves
   nothing, 8m09s filtered against 8m15s unfiltered, because Karma startup dominates. **Never filter
   that task.**
4. **`min`-of-passes is wrong on a machine whose clock steps.** Two cells came back bimodal — three
   passes near 49,000 µs and two near 33,000 — and the minimum put that entrant 30% off its own line
   while every other entrant stayed on. Under a **median** all 42 cells sit within 8% of a straight
   line through the origin. The minimum's argument is that noise only adds time; that holds for a slow
   pass and not for a fast one, because a fast one is not noise. `TimeCommand` still takes a minimum.

**And the phase falsified its own first instrument, which is the part worth copying.** A JVM sweep
timing seven bots in one process put `alphabeta:eval=territory` at 4.7× `puct` and `eval=learned` at
5.1× `eval=chamber` on a 12×12, where Chrome, a fresh-process `:lab time` and `EvaluationCost`'s own
published figures all say 1.0× and 1.1×. Every pass agreed, so it read as a measurement; the 20×20
half of the same run is clean, which is what made it invisible. JIT state carried between entrants in
one process is not a cost instrument.

#### The instrument that replaced them

`AppraisalTape` (`bots/src/commonTest/`) times a turn over a line of positions **that does not depend
on the bot being timed**: the subject is seated at *no slot*, asked to decide from slot 0's seat and
its answer thrown away, while slot 0 plays `space`'s move from an RNG of its own so a subject drawing
a different number of random values cannot shift the line under itself. Fixed stride over the line so
the samples spread across the whole fill sweep rather than piling into the opening. It reports the
**mean** for taking a ratio and the **worst** for reading an allowance, because only the second is the
frame criterion. Two consistency checks say it measures the leaf and not the search: `puct` bare and
`alphabeta:eval=territory` agree to 5%, and `puct:eval=chamber` and `alphabeta` agree to 1%.

#### `EvaluationCost`'s self-disagreement, settled

Toward the **second** table. `:lab time`, 6 seeds, interleaved within seed: 20×20 `puct`/`uct` =
**1.44**, sd 0.21, 95% CI **[1.22, 1.66]** — which supports `:76-79`'s 1.37 and leaves `:26-29`'s
1.65 at the very top edge, not the point estimate. At 12×12 it is 0.90, CI [0.74, 1.05], consistent
with both and settling nothing. The mechanism of the original disagreement is in the same output:
game length varied 28 to 225 turns. `MatchSetup`'s stale `puct territory, JVM` column is refreshed to
0.78 / 2.6 / 5.5 / 29 ms — **2.2× below** what it said, which is the 2.13× the bitmap sweep was
measured at, so the sweep moved and the method did not.

#### What is scaled rather than measured

Absolute Chrome milliseconds rest on a **4.1×** machine-scaling factor (range 3.4–5.0) between this
box's headless Chrome and the reference machine the published tables were taken on. **Ratios and the
allowance table do not** — they are anchored within each run. Any absolute millisecond quoted from
P1 carries that factor; one browser run on the reference machine would replace it with a measurement.

### What P2's rider found — the AHEAD bucket was buying about half what it looked like

The one-hour rider on `PhasesCommand` was scoped as a tidy-up. It halves a bound the closed agenda
published twice.

`PhasesCommand`'s AHEAD bucket is `mine > rival` on a raw free-square flood, so a one-square lead
buckets identically to a commanding one, and its own KDoc said nothing there separates the two.
Split by lead size, on `.lab/rave-field`, 12×12, 1,200 matches per leaf:

| band | `learned` n / score | `chamber` n / score |
|---|---|---|
| ahead, **clear** | 106 / **73%** | 40 / **73%** |
| ahead, **commanding** | 510 / 94% | 480 / 97% |

**The narrow band is a dead heat.** Over the whole ahead side `learned` converts 90.6% and `chamber`
94.8%; standardise `chamber` onto `learned`'s lead distribution and it converts 92.5% — so **1.9 of
the 4.2 points survive** and the rest was 90% of `chamber`'s races starting commanding against 77% of
`learned`'s. The ~3.7-points-of-score ≈ **25 Elo** conversion bound, which the closed agenda used to
price both the dispatch portfolio and the separated endgame, was priced off a sign bucket. **Halve
it.** That reprices P7's third motivation and it is the second time on this agenda that a published
number turned out to be measuring the company rather than the effect.

Cross-checked on a third population sharing no bot, board or batch — `.lab/solver-field-20`,
`eval=territory`, 20×20 — which gives 71% clear and 97% commanding. **Conversion is close to a
function of the lead alone.**

**And the confound the separated-endgame workstream was killed on is small.** That reason argued a
snake ahead on squares may be behind on spendable moves, since the two come apart by 1.4–2×
shape-dependently. Measured: the raw-flood lead and the spendable-ground lead **disagree on sign in
1%** of separated matches at 12×12 and **2%** at 20×20. The 1.4–2× figure survives — it is about
magnitude, not sign — but the inference that it contaminates the AHEAD bucket does not. At the split
it is one race in fifty. *The workstream stays unranked; the reason it stays unranked is now the
hindsight-split argument alone, which is the strong one.*

A `:lab` limit found on the way: **`FillableSpace` and `SurvivalHorizon` are `internal` to `:bots`**,
so a true spendable-moves reading is not available to `:lab` at all. `PositionFeatures` is that
module's only public class besides the registry and it hands over a *margin*, not a count — which at
the settings that vector fixes is `FillableSpace`'s parity-capped block-chain square count exactly.
That margin is what the new `usable` column is built on, and the KDoc says what `chainWorth` is not.

### What P2 found before the field ran — cost is not monotone in board size

The third board is **8×8**, chosen because it is what `index.html` opens on (`:151`) — the geometry
most matches are actually played on — and because it extends the axis downward where the two existing
points already cover mid and large.

| entrant | 8×8 ×`puct` | **8×8** | 12×12 ×`puct` | **12×12** | 20×20 ×`puct` | **20×20** |
|---|---|---|---|---|---|---|
| `puct` | 1.00 | **1000** | 1.00 | **1000** | 1.00 | **1000** |
| `uct` | 1.22 | **825** | 1.14 | **880** | 0.78 | **1280** |
| `puct:eval=chamber` | 2.52 | **395** | 3.45 | **290** | 4.58 | **220** |
| `puct:eval=learned` | 3.32 | **300** | 3.86 | **260** | 4.65 | **215** |
| `alphabeta` (`chamber`) | 2.38 | **420** | 3.48 | **285** | 4.43 | **225** |
| `alphabeta:eval=territory` | 1.13 | **1000** | 0.95 | **1000** | 1.02 | **1000** |
| `alphabeta:eval=learned` | 2.75 | **365** | 3.86 | **260** | 4.74 | **210** |

**The dear leaves get cheaper as the board shrinks and `uct` gets dearer** — `chamber` runs 2.52× at
8×8 against 4.58× at 20×20, while `uct` crosses from 1.22× to 0.78×. So cost is not even monotone in
board size across entrants, and P1b's open question 3 — *"the ratios do not transfer"* — is answered
harder than it was asked. Any future phase that wants an allowance for a board it has not swept has
to sweep it.

P1's 12×12 and 20×20 rows **stand**: the same three-run median procedure reproduced them to within
4%, which is the evidence that the 8×8 row was taken the same way. The 8×8 row is the least precise
of the three. The frame budget is a non-question there — ~2.8 ms on the worst turn.

**A third instrument defect, at the level above the one P1 fixed.** `AppraisalTape`'s median-of-passes
protects a *cell*; nothing protected a *sweep*. Two full browser runs disagreed by up to 30% at 8×8,
and the second failed a free consistency check the first passed — two entrants sharing a leaf must
cost the same, and run 1 gave 2%/0.5%/6%/2% where run 2 gave 30%/13%/62%/58%. A clock step mid-sweep,
in a run whose every individual pass list looked tight. The fix is the **median of three runs**, and
the leaf-pair check is now the gate that says whether a run is usable at all.

### What P2 actually found — the phase was pointed at the wrong bot

**Nothing in `puct`'s configuration should be adopted, and the strongest entrant in the repository is
an `alphabeta` default move nobody proposed.**

Three boards, twelve rungs, 13,200 matches each, one `rate` fit per board. Field composition is
identical on all three and every rating below is **only** quotable beside this field:
`puct@1000` bare; `chamber` and `learned` each at equal clock **and** at equal allowance; both with
the tuned prior; `priorWall`; both `alphabeta` leaves; `uct` at its allowance; `chase`.

| rung | 8×8 | 12×12 | 20×20 |
|---|---|---|---|
| **`alphabeta:eval=territory@1000`** | **177** (+161..+193) | **101** (+88..+118) | **240** (+224..+257) |
| `puct:eval=learned@1000` *(equal allowance)* | 138 | 146 | **−11** |
| `puct:eval=chamber@1000` *(equal allowance)* | 102 | 143 | 212 |
| `alphabeta@`equal clock *(its shipped `chamber`)* | 69 | 55 | 199 |
| `puct@1000` — **the shipped baseline** | 46 | 65 | 149 |
| `puct:eval=learned@`equal clock | 86 | 58 | **−167** |
| `puct:eval=chamber@`equal clock | 12 | 2 | 90 |
| `uct@`equal clock | −8 | 7 | 87 |
| `chase` | −642 | −565 | −377 |

`alphabeta:eval=territory` is first outright on **8×8 and 20×20** — ahead even of the
equal-*allowance* rungs, which were handed 2.5× to 4.6× its wall clock and still lost. On 12×12 it is
first among everything at equal clock and third behind those two subsidised rungs. Against the bare
baseline directly it scores **71%**, where the fitted ratings expect 55%.

**So P3's scoped adoption is a null and its unscoped one is the finding.** At equal clock
`eval=chamber` is **−34 / −63 / −59** against the bare baseline and `eval=learned` is **+40 / −7 /
−316**. `puct`'s eval default does not move. What the number points at instead is `AlphaBetaBot`'s
default eval, `chamber` → `territory`, worth **+108 / +46 / +41** against its own shipped leaf at
equal clock — a decision for a person, and one the standing decision did not pre-authorise because
nobody thought to propose it.

#### `eval=learned` is a 12×12 leaf, and nothing said so

`LearnedWeights.ENCODED` was fitted `--rows 12 --cols 12` (`LearnedWeights.kt:22`) and neither that
KDoc nor `LearnedEval`'s says the fit is only known at that size. It rates **−167** at equal clock on
a 20×20 and **−11** even when handed 4.65× the clock, against **+146 / +138** on the two smaller
boards. **This was in no risk column on either agenda.** It also re-scopes P4, which planned to add
four features to a fit taken on one board size: the residual may be the board and not the features.

#### The tuned prior belongs to `chamber`, not to `puct`

Equal clock, prior minus no-prior: **+24 / +13 / +19** on `eval=chamber`, **−94 / −87 / −92** on
`eval=learned`. The +103 Elo prior was measured on `chamber` — which is exactly the defect the
standing decision named, *every knob table in `PuctBot.kt` is baselined at `eval=chamber`, which is
not what the bot ships* — and it does not transfer. `phases` says the damage is contact-phase: the
never-came-apart score falls 73% → **49%**.

**`priorWall=0.45` still has no verdict, and is reported as having none.** Three boards, three signs
(0; +29 with abutting intervals; −12 overlapping), and it is the **least diverse pairing in the field
on every board** — 65/200, 114/200, 31–40/50 distinct games, so the two entrants answer most boards
identically. It was also tested on top of a prior worth −87 to −94 on that leaf, which is not a
neutral platform.

#### Board-size conditioning is the largest effect in the phase, not a null

The agenda hoped the three sizes would settle this for the price of a flag, one way or the other.
The **top is stable** and the **middle inverts violently**: `learned` swings +40 / −7 / −316 against
the baseline at equal clock, `uct` climbs monotonically with board size, and `alphabeta`'s chamber
rung goes 3rd → 6th → 2nd. Combined with P2a's finding that *cost* is not monotone in board size
either, the contract change parked in [Considered and not ranked](#considered-and-not-ranked) —
board-size-conditioned defaults — has earned the follow-up agenda that section said it would.

#### A fourth instrument defect: the printed interval is optimistic on a small board

**An opening is a pure function of the match seed**, drawn by rejection from squares clearing the
separation floor, and there are only **40 of 64** of them on an 8×8, 84 of 144 on a 12×12, 220 of 400
on a 20×20. 200 rounds is 100 seeds, so a pairing covers ~37 / ~59 / ~80 *distinct* openings while
`bootstrapIntervals` resamples 100 groups as though independent. **8×8 bars are optimistic by up to
~1.6× and 12×12 by ~1.3×**; the 20×20 is unaffected. It is also why the 8×8 distinct-games line reads
45% — *below* the 50% floor two identical entrants produce. **More rounds on an 8×8 buys no more
boards**, which is a sizing rule and not just a caveat.

The margins above survive it: 8×8 (+91 over the runner-up) and 20×20 (+41) at 1.6× and 1.0× the bar.

#### And P2a's own sizing was wrong by 2–4×, for P1's reason

Measured sustained: **50.5 ms** (8×8), **191 ms** (12×12), **734 ms** (20×20) per match against
P2a's 17 / 131 / 818. Its 110-match probe ran 1.9 seconds — at boost clock. An 18-thread batch running
eleven minutes runs at base clock. That is the *third* time on this agenda that a short measurement of
a long thing came back optimistic on this machine, after `min`-of-passes and the single-sweep browser
run. **Size a batch from a batch.**

#### Promotion

`puct` beats the top ladder rung `uct` by **+54 / +58 / +62** at equal clock, disjoint on all three
boards, so on the evidence it graduates. But `alphabeta` beats `puct` at equal clock on all three
boards as well, so admitting `puct` on this evidence admits `alphabeta` above it on stronger
evidence. **That is one decision, not two.**

### What P3's verification found — the right claim survives and the headline one does not

P3a was sent to check the narrowest margin. It came back having reversed which of the three boards is
trustworthy, and having found that the sentence the phase wanted to write is not the sentence the
evidence supports.

**"First in the field" is fragile. "Better than its own incumbent" is solid.** Those are different
claims and only the second may be written down as settled:

| the claim | 8×8 | 12×12 | 20×20 |
|---|---|---|---|
| `alphabeta:eval=territory` − `alphabeta:eval=chamber`, fresh field, disjoint seeds | — | **+46** (P2: +46), intervals **disjoint** | — |
| confirming `ab`, same pairing | **BETTER +33 ±14** | UNDECIDED **+21 ±15** (hit `--max-pairs`) | **BETTER +98 ±26** |
| `alphabeta:eval=territory` − bare `puct`, **common-opponent cut** | +20.7 pp (z 8.3) | **+1.45 pp (z 0.72)** | +8.95 pp (z 5.6) |

**At 12×12 the +36 over the baseline is the direct 70.5% head-to-head folded into one ordering** —
drop the pairing `rate` itself flags as not described by the ladder and the two are level. That is the
P5b lesson from the closed agenda arriving from the other direction: there, `ab` was wrong and the
field was right; here the field's *fitted rating* carries a pairing the fit cannot describe, and the
common-opponent cut is what separates them.

**And the 8×8 margin is the least trustworthy of the three, not the most.** The rung rating **+131
above bare `puct` loses its own head-to-head to it, 89–111.** Its +177 is bought off the middle of the
field — 83.5% / 79.5% / 74.0% against the three `learned` rungs — while it only draws with
`alphabeta@420`. 8×8 also has the worst opening coverage (72 distinct of 200) *and* the only
measurably wrong allowance: the corrected ratio is 1.228, so that rung played over-budgeted and about
23 Elo of its +91 is instrument.

**The 12×12 allowance stands at 1000.** Four fresh browser sweeps, whole suite each, two of them
discarded by the leaf-pair gate. Median of gate-passing runs **1.035**; pooled with P2a's three,
0.993. An allowance is worth **111 Elo per e-fold** on this entrant, so erasing +36 needs a **1.38×**
error and the worst gate-passing reading is 1.167. Measured directly: at budget **700** — a 1.43×
over-allowance corrected away — the candidate rates 6 against `puct@1000`'s 7. Level, not below.

**The confirming `ab`s agree in sign on all three boards and rank the boards in the opposite order to
the field.** No `blindness` note fired. So the pairing is real and its *magnitude* is
field-composition-dependent in both directions — the company effect changes sign with the board,
which is the sharpest instance of the intransitivity warning this repository has produced.

**A benefit nobody costed.** At 20×20 and the shipped allowance the change takes `alphabeta`'s dearest
turn from **~44 ms — 5.5× the frame slice — to ~8.5 ms**, where `puct` already sits. The *incumbent*
default overruns a browser frame by five times on a large board. That is a shipping defect the
strength argument was never needed for.

**The SW-02 sentence was already wrong, and the honest replacement is stronger.** `alphabeta`'s
cross-target golden is justified by "the leaf is `ChamberEval`, whose only transcendental is `sqrt`".
`TerritoryEval` and `ChamberEval` **both import no `kotlin.math` at all** — no `exp`, no `log`, no
`sqrt`. So it overclaimed before the move and named the wrong class after it. The prior clause is
untouched: `PRIOR_TEMPERATURE.default` is `0.0` and `portableExp` is reached only above zero.

**The one item in the cost list most likely to be missed**, and it is the kind that stays broken
quietly: `AppraisalTape.kt:94-100` and `ThroughputTest.CANDIDATES` spell the free leaf-pair gate as
`puct:eval=chamber` against **`alphabeta`**. After the move that pair is territory-against-chamber —
**silently disabling the one check that says a browser sweep is usable at all**, three phases after
this agenda invented it.

**And P3a re-specified that gate on its own terms.** As written its first pair is `puct` against
`alphabeta:eval=territory`, which *is* the quantity under test — circular. It now runs on two pairs
the subject is not in.

### What P3 executing found — a cost list is only exhaustive for the change it was written for

Both authorised changes landed: `AlphaBetaBot`'s default eval is `territory`, and `puct` and
`alphabeta` are ladder rungs with `alphabeta` on top.

**P3a's §15 cost list is exhaustive for the eval move and misses six sites for the graduation** —
which is the honest lesson rather than a criticism of it: it was written to cost change A and was read
as if it costed both. `TerritoryEval.kt:71` and `MatchSetup.kt:244` both described `puct` as
registered experimental; `ShippedBotsTest` is listed under *Verified unaffected*, true for A and false
for B (slug order, display name, and the `"the top of the ladder"` label on `uct` all move);
`CLAUDE.md:38` said "a seven-bot ladder topped by an MCTS bot"; `Chrome.kt:332` offered "the best bot
there is" for a slug now seventh of nine.

**And the second-order miss re-falsifies P1's own correction.** `PuctBot.kt:787` and
`LearnedEval.kt:89` said *"it moves no ladder threshold: `BotLadderTest` seats neither bot"* — the
sentences P1 was dispatched to write, correct when written and made false by the graduation eight
hours later. **Ground truth 3 of this agenda goes with them**: "moving `puct`'s default eval moves one
hash and no thresholds" is now false, and a `puct` default move reaches two rungs.

*A precision worth keeping, because the phase report blurred it and it mis-scopes P4:* that is about
moving **`PuctBot.EVAL`'s default**. `LearnedWeights.ENCODED` reaches only `eval=learned`, and after
P3 **no bot defaults to `eval=learned`** — so retraining moves that leaf's golden, cross-target, and
**no ladder threshold**.

**Every one of the six existing ladder rungs came back on its recorded figure to the match**, so no
existing threshold was touched. The two new ones:

| rung | measured of 20 | threshold |
|---|---|---|
| `puct` over `uct` | **12** | 11 |
| `alphabeta` over `puct` | **20** | 17 |

`puct` over `uct` is the narrowest rung on the ladder, one match from the floor at which it stops
asserting a majority, and it was **not loosened**. It does not rest on those twenty matches: fresh
100-match head-to-heads give 59% (100 of 100 distinct) and 75% (72 of 100), agreeing with P2's
+54/+58/+62. **The 8×8 head-to-head reversal is in `BotLadderTest`'s class KDoc**, under a heading
saying the ladder is a 12×12 instrument and an instruction not to read a regression into the top of it
coming apart on another board.

**`min >= 2` is no longer comfortable.** `alphabeta`'s depth assertion was written against a measured
8 plies; at the shipped leaf the minimum is **2 on both boards** (12×12: 231 turns, 12.6 plies mean;
20×20: 362 turns, 14.3 mean).

**Chrome verified, and exactly one hash moved on both targets** — `alpha-beta against random on 12x12`
to `-6565866919283159623L`, while `alpha-beta at chamber` holds the old `-3589698981299349624L`. P3a's
paired case did precisely the job P1 invented it for. That run's own appraisal sweep independently
reproduced the frame table: 20×20 worst turn **45.2 ms at `chamber` against 8.0 ms at `territory`**.

**The gate was fixed harder than the cost list asked** — `"alphabeta"` → `"alphabeta:eval=chamber"`,
the `CANDIDATES` list interleaved so both halves of every pair are adjacent (a mid-sweep clock step
could otherwise land *inside* the ratio the gate protects), and a third free pair added.

### What P4 actually found — the ceiling was the corpus, and six phases read one number wrong

**The phase's own premise was false, and a twenty-minute probe falsified it before any feature was
written.** The premise: *`eval=learned`'s train loss equals its holdout loss to five places, so it is
bounded by its 25 features and not by capacity, data or optimisation.* The equality is real — it
reproduces at three boards and 966,000 rows — and **it does not support the inference**. A holdout
drawn from a one-board corpus is a statement about capacity *on that board*. It cannot see a transfer
failure at all, and a transfer failure is precisely what P2 measured at −316.

Scored on 13,200 fresh matches per board, none of which existed when the shipped fit was taken:

| model, scored on that board | 8×8 | 12×12 | 20×20 |
|---|---|---|---|
| the shipped 12×12 fit, log-loss / accuracy | 0.5475 / 72.0% | 0.5822 / 67.7% | **0.6274 / 64.0%** |
| the identical 25 readings refitted on *that* board | 0.5364 / 72.3% | 0.5685 / 68.5% | **0.5798 / 67.6%** |
| **what the corpus was worth** | 0.011 | 0.014 | **0.048** |
| what the four new readings are worth | 0.0054 | 0.0059 | 0.0024 |

**A 20×20 board is not harder to call — the shipped fit could not call it.** The binding constraint
was the corpus by roughly an order of magnitude, and it bound *least* on features exactly where the
leaf was worst. Two corroborations that were not designed as such: the 12×12 native refit lands on
0.56854 where the shipped fit's own holdout was 0.56825 — an independent replication of the achievable
loss at that size, on a different corpus, three phases later — and the old fit is **under-confident
rather than biased**, spreading 0.191 on a 20×20 against a native fit's 0.220 while averaging 0.4913
where the rows average 0.5000. That is signal loss, not an offset a constant would fix.

#### The four features are real, consistent and small

All four are free — `PositionFeatures` already holds a `TempoOwnership` and a `ChamberTree`, and three
of the four are counters in a pop that was happening anyway. Three fits of each shape, same corpus,
same three seeds, paired: **0.0039 ± 0.0017**, accuracy 68.25% → 68.51%, sign negative at every seed
on every board. Against a hidden layer's 0.023 and the corpus's 0.048 that is **a sixth of a hidden
layer and a twelfth of the corpus**. Firing rates are asserted in `PositionFeaturesTest` rather than
left in a transcript — 7.3% / 52.4% / 82.7% / 74.9% over 1,194 slot-positions, and `chokepoints`
landing on 52% independently agrees with `ChamberTree`'s own KDoc figure for multi-chamber regions on
the same fixture, which is the check that says the new counter counts what it claims to.

**`ChamberEval` did not get more expensive for readings it does not use.** The two that cost work
*per square* sit behind an `allReadings` constructor flag, off by default and off for `ChamberEval`,
reporting **zero** rather than a stale value when off — so a misread is no information rather than
another position's. The `articulations` objection was a misremembered finding: *"a true articulation
test is unaffordable"* is about `MovePrior`, which runs three sweeps per iteration.

**A fifth was measured and declined.** Board scale as `playable / (playable + 144)`, which is what
would recover the 0.0015 / 0.0096 / 0.0092 mixture tax of one model over three boards. It recovers the
12×12 tax exactly and is worth 0.0017 pooled, 95% CI **[−0.0037, +0.0003]** — and the interval is the
smaller half of the reason. It is the only reading in the vector that is not a ratio, a share or a
flag, so it retires the property the vector is built on and the test that pins it; and the corpus
spans `0.31..0.74` of it where `MatchSetup.MAX_SIDE` reaches `0.999`, which would hand the softsign
units an input past everything anybody has measured — **the same failure mode this phase was sent to
fix, reintroduced deliberately.**

#### At the board: the collapse is gone, the verdict is not

Four blocks per board pooled to 4,200 matches each, seven rungs, rated against the bare baseline in
the same field. The last three 20×20 blocks are the coordinator's — the phase's own batch was cut off
mid-run and the source tree moved only in comments between, which was checked by diff rather than
asserted.

| rung, rated against the bare baseline | 8×8 | 12×12 | 20×20 |
|---|---|---|---|
| `eval=learned` at **equal allowance**, this fit | **+118** | **+252** | **+88** |
| the same, at the fit it replaces (P2) | +92 | +81 | **−160** |
| `eval=learned` at **equal clock**, this fit | +82 | +4 | **−74** |
| the same, at the fit it replaces (P2) | +40 | −7 | **−316** |

**At equal allowance it is now first in its field on all three boards, where the old fit was first on
none.** At equal *clock* it is level with the bare baseline at 12×12 and behind it at 20×20 — the
readings cost 3.0–5.2× a turn and the fit does not buy that back. So `eval=learned` remains not a
default and not proposed as one, but the reason has changed from *it collapses on a large board* to
*it costs more than it returns per millisecond*.

**Only the 20×20 row is evidence.** The two fields differ in composition (7 rungs against 12), and
contrasts that ought to be unchanged move ±30–90 between them — `eval=chamber` at equal clock moves
+38 / +83 / +10 and `alphabeta:eval=territory` moves −87 / +32 / −26. The 8×8 and 12×12 learned rows
sit inside that. The 20×20 move of about **+240** does not, and it is the board the loss table
predicted in advance. **P3's adopted `alphabeta:eval=territory` survives at equal clock on 12×12 and
20×20 and loses to the retrained leaf on 8×8** — which is the board P3a already established is the
least trustworthy of the three.

#### The instrument this needed did not exist, and its absence is what hid the error

`train` could fit and report a holdout; it had no way to ask *what is a model fitted elsewhere worth
here*. Two additions: **`train --model FILE`**, which fits nothing and scores a literal over the
**whole** corpus — a model that has never seen these games needs none held back — reporting loss,
accuracy, spread and **mean answer against mean label**, the pair that separates *mispriced here* from
*this board is harder*; and **`Corpus` now carries the board each row came off**, so a corpus spanning
more than one geometry reports its holdout **per board**. A pooled loss over a mixture is a claim
about the mixture and about no board in it, and that is the reading whose absence hid this for six
phases. The falsified sentence is fixed where it did the damage — `docs/Workflow.md`'s "read the gap
between the training and holdout columns" now says what the gap does and does not mean.

#### And the phase falsified two sentences it had itself written

`LearnedEval`'s "*the leaf is only known to work near 12×12*" and *"the next gain here is a reading,
not a layer"* were both P2's and P3's corrections, both correct when written, and both made false by
P4 — the third time on this agenda that a correction expired inside a day, after P1's two. So was
`EvaluationCost.LEARNED`'s 1.11–1.16× band, which was a 12×12-only figure for a leaf whose cost ratio
against `ChamberEval` **falls as the board grows** — 1.28× / 1.19× / 1.09×, because the fit is a fixed
cost per snake while the sweep under it grows. What the four readings cost on their own is *not*
isolated: two sessions and two instruments separate 1.16× from 1.19×, and P1 measured browser ratios
moving 3% between runs on one machine.

### What P5 actually found — the mechanism is real, the price is not payable, and step 0 nearly killed the wrong candidate

**Neither policy is adopted, and `liberty` is worth keeping wired.** Rated against the bare baseline in
one nine-rung field per board, two blocks of 200 rounds on disjoint seeds pooled per board — 14,400
matches and 3,200 games an entrant, 94–98% of them distinct, no forfeits:

| against `uct:budget=1000`, in Elo | 8×8 | 12×12 | 20×20 |
|---|---|---|---|
| `liberty` at equal **clock** | **−25** | **−17** | **+17** |
| `prior` at equal **clock** | **−33** | **−38** | **−34** |
| `liberty` at equal allowance — *the control* | +18 | +26 | **+75** |
| `prior` at equal allowance — *the control* | +27 | +31 | +23 |

`prior` is settled: disjoint below the baseline on all three boards. `liberty` loses on the two small
boards and is level-to-slightly-ahead at 20×20 — intervals **overlapping**, `ab` UNDECIDED at +18 ±17,
and about ±9 Elo of allowance slop on top. **No board supports adopting either; one board says the
mechanism is real.** The coordinator re-rated 12×12 and 20×20 off `.lab/` directly and both reproduce,
including the overlap on the one contested cell.

**Both of the agenda's cost estimates were wrong, in the same direction.** Ground truth 7 priced
`liberty` at ~2–3× uniform and `MovePrior` sampled at ~3–6×. Measured: **1.4–1.9×** and **1.4–2.4×**.
The prior is far cheaper than anyone expected because at `puct`'s shipped weights it *is* a liberty
count — `priorPinch`, `priorWall`, `priorTail` and `priorTemperature` all default to `0.0`, so it reads
four orthogonal squares rather than the eight-square ring and never reaches `portableExp`.

#### Step 0's threshold used the wrong denominator, and it would have killed the cheapest candidate

The agenda says *"a policy that changes the step in 3% of positions cannot move a rollout."*
`liberty`'s per-**step** divergence is 1.6–2.9% — at or under that line on **every** board. Its
per-**rollout** figure is 0.42–0.48 diverging steps, because a rollout takes 20–37 of them, so roughly
a third of rollouts play a different game. Read per step, the candidate that went on to be the only
one worth keeping dies at step 0. **Multiply by steps per rollout before comparing a rate to a
threshold** — the firing-rate rule is right and its denominator has to be the unit the decision is
made in.

#### Divergence predicts neither sign, nor magnitude, nor which board

The closed agenda established that divergence predicts visibility and never sign. P5 extends it twice
over: `liberty` fires on *fewer* choice steps as the board grows (6.1% → 3.5%) while its per-iteration
value **quadruples** (+18 → +75). And per *iteration* `prior` is worth **more** than `liberty` on both
small boards (+27 vs +18, +31 vs +26) — its extra divergence is not noise a tree averages out, as P5a
had guessed. It loses on price alone.

#### The number worth more than the verdict

**An e-fold of allowance is worth 80–137 Elo to `uct`** — six independent estimates, one per policy per
board, consistent inside their bars and agreeing with P3a's 111 measured on `alphabeta`. That converts
any allowance error straight into Elo: erasing `liberty`'s 12×12 deficit needs a **1.24×** allowance
error, and the allowance was verified to 1.13× at worst. It is the exchange rate every future
equal-clock claim on this bot can be checked against.

Two corollaries fell out. **`uct` is not saturated at 1,000** — every equal-allowance control rates
43/43/58 above its equal-clock twin, so `:ui`'s allowance is not re-opened. And the
**`rolloutDepth` × `rolloutPolicy` interaction is a null**: truncation plus `liberty` is worth what
`liberty` is worth alone. That row is closed.

#### An instrument defect worth more than the row it was found on

**A foreign control contaminates a JVM timing run.** `puct`, carried as the untouched control the
protocol asks for, read **779 then 625 µs/turn inside one 8×8 block** — a 25% swing in the control
itself. That is `ThroughputTest`'s documented contamination: several bot classes through one
`Bot.chooseMove` call site de-optimises it. The control has to be **the subject's own default read
twice**, keeping the site monomorphic. This cuts against `Research-Process.md`'s "carry an entrant the
change cannot touch" on this target, and the process document now has to mean *the same class at a
different setting*.

The coordinator's own re-runs then caught the phase's cost table being **too tight**: it claimed the
two larger boards agree to within 2% across runs, and at 20×20 the control swung 11% between runs while
`prior` read 2.45× on one and 1.93× on the next. **The allowance is the verified quantity and the ratio
is not** — they are not even reciprocals, since cost per turn is a tree term plus a rollout term and
only the second carries the policy. `RolloutPolicyTest.EQUAL_CLOCK` asserts allowances against the
default's clock directly, and across five runs every one landed within 94–113%.

#### And a lead nobody was looking for, which may be worth more than the phase

`uct:rolloutDepth=25` at the **shipped** allowance beats the undepthed bot **58.5%** on 12×12 and
**67.0%** on 20×20, 200 distinct games a cell. `UctBot.ROLLOUT_DEPTH`'s published table says **51.7%**
— and it is played at **100** evaluations, while `RolloutTruncationTest.BUDGET`'s KDoc said in as many
words that *the ratio does not turn on the allowance*. **The strength half of it does**, and 20×20 had
never been measured at any allowance.

It is recorded beside both constants as a **lead and not a finding**, with what it is not: nothing has
been timed at the shipped allowance and this trade is decided by cost; a head-to-head between two
settings of one bot is a style match-up; and the field it fell out of agrees in sign and not in size.
The bound that makes it worth chasing: at 116 Elo per e-fold the 20×20 margin survives a **2×** cost
ratio, against a published 1.0–1.1× at a tenth of this allowance.

### What P6 actually found — the phase closed on its own kill criterion, and the accident it was carrying is the result

**P6's premise is dead on price, and that was settled in an hour by the measurement it was sent to
take first.** The row survived P5 on the swept prior being *"the strongest lead and not reachable from
`uct` today"*. It is reachable now, it was priced, and it cannot pay:

| against a uniform rollout | 8×8 | 12×12 | 20×20 |
|---|---|---|---|
| `prior` at `puct`'s shipped weights | 1.59× | 1.97× | **2.01×** |
| `prior` at `MovePrior`'s **swept** weights | 2.26× | 2.99× | **3.25×** |

**3.25× at 20×20 against a 2.2× kill threshold.** That is 1.18 e-folds, so at 82–93 Elo per e-fold the
swept prior would have to return **97–110 Elo per iteration** to draw level with a uniform draw. The
largest per-iteration figure any policy has ever posted on `uct` is **+75**, and the shipped-weights
prior posts **+23**. The evidence is not merely silent either: P5 has `prior` beating `liberty` per
iteration on both small boards and *losing* to it at 20×20, so richer is not monotone in value, and the
swept prior is richer still. **Priced out, not switched off** — it fires on 93.4–93.9% of choice steps.

The agent declined to measure its strength per iteration, and the reason is the right one: a field
needs a reachable entrant, a reachable entrant needs a frozen `Choice` value, and **SW-05 says a value
frozen forever should be one somebody would want to play**. The cost result is what says not to freeze
it. `eval=rollout`'s deletion paragraph in `LeafEval` now answers the policy-rollout objection with a
number instead of an argument.

**It also falsified the other half of the sentence P5 had already half-falsified.**
`RolloutTruncationTest.BUDGET` said the truncation cost ratio *does not turn on the allowance*. P5
found the **strength** half does; P6 found the **cost** half does too and in the same direction —
1.0–1.1× at 100 evaluations against **1.12× / 1.32×** at the shipped 1,000.

#### And the lead P5 turned up by accident is the phase's actual finding

`uct:rolloutDepth=25`, timed properly and played at equal clock. Allowances 890 / 760, **verified
directly against the control's clock at 94% and 99%** rather than derived — the distinction P5 had to
learn the hard way. Eight rungs, two blocks of 200 rounds on disjoint seeds, 11,200 matches a board,
94–97% distinct, no forfeits:

| rating (95%) | 12×12 | 20×20 |
|---|---|---|
| `alphabeta:eval=territory@1k` | 179 (+166..+193) | 162 (+149..+173) |
| `rolloutDepth=25` at equal **allowance** | 66 | **125** (+112..+138) |
| `puct:eval=territory@1k` | 108 (+95..+120) | 77 (+63..+88) |
| **`rolloutDepth=25` at equal clock** | **64** (+52..+75) | **99** (+86..+113) |
| `uct@1000` — the baseline | 51 (+39..+63) | 21 (+10..+33) |
| `uct:rolloutPolicy=liberty` at its allowance | 13 | 35 |

**At 20×20 it is +78 over its own baseline on disjoint intervals, and +22 past `puct:eval=territory`
— a bot a rung above it on the ladder. At 12×12 it is +13 with overlapping intervals: a null.** The
paired `ab`s agree: **BETTER +93 ±33** over 260 boards at 20×20, **UNDECIDED +17 ±17** over 800 at
12×12, no blindness note either time. The equal-allowance head-to-heads pool to 65.5% / 57.8%,
**replicating P5b's 67.0% / 58.5% on seeds it never touched** — which is what makes this a measurement
rather than a lucky field. The coordinator re-rated the 20×20 field off `.lab/` and it reproduces,
disjoint intervals included.

A third independent reading of the exchange rate fell out free, the two depth rungs differing only in
allowance: **95 Elo per e-fold**, inside P5's 80–137 and beside P3a's 111 on `alphabeta`.

#### The mechanism that made pricing an unshipped policy possible

`UctBot.withRolloutPolicy`, an internal seam over a private primary constructor. It exists because
`BotKnob.Choice.read` **coerces an unknown value to the default**, so pricing a policy that does not
ship meant either freezing a `Choice` value for a setting nobody had shown was worth playing, or
putting a second bot class at the timed call site — the defect P5 measured at 25% of a control's own
reading. Neither was acceptable, so the seam is the third option, and it is `internal` and test-only.

### What P7 actually found — the ladder survives a third seat, and the design that measures it nearly didn't

**The map, which is what this row was rated on.** A composition-balanced three-seat field puts all nine
bots in the **two-seat ladder order**, under both scoring rules, on 25,200 matches of which 25,200 are
distinct games:

| bot, seated at three | rating (95%) | score | win share (95%) |
|---|---|---|---|
| `alphabeta` | 289 (+281..+298) | 80% | 69% (68–70) |
| `puct` | 279 (+271..+286) | 79% | 66% (64–67) |
| `uct` | 269 (+261..+278) | 78% | 65% (64–67) |
| `flat-monte-carlo` | 147 | 66% | 45% |
| `chase` | −79 | 41% | 22% |
| `pressure` | −118 | 37% | 18% |
| `space` | −157 | 33% | 10% |
| `wallhug` | −256 | 23% | 3% |
| `random` | −374 | 13% | 2% |

**The third seat compresses; it does not reorder.** 35 of 36 pairwise cells move toward 50% and
nothing crosses; the Elo span falls **1,215 → 663** at 12×12 and 1,102 → 540 at 8×8 — the same ladder
at about half its width. The coordinator re-rated this field off `.lab/` directly and it reproduces,
including that **`puct` over `uct` flattens to level** (279 against 269, overlapping intervals; 51.1%,
z +1.0). That is the same rung *Open at the close* flags as the narrowest on the two-seat ladder, found
independently by a different instrument.

#### Longevity and victory agree — which is the finding, because the phase was built expecting them not to

At `FREE_FOR_ALL` each pair is scored by **`outlasting`**, not by who won (`pairwiseOutcomes.kt:83-90`,
confirmed by the coordinator from the code). So the worry was that a three-seat rating rates survival
order and a bot could climb it by refusing to engage. **It does not happen.** With the company held
still — one triple, the same matches scored twice — the two rules order the entrants identically in
**79 of 84** triples, and all five flips are between entrants within 5 points of win share (se on a
difference ≈3.7). Neither rule ever disagreed about a gap either could see.

**What the outlasting rule gets wrong is the scale, not the order**, and by exactly as much as a third
snake takes the match: `pressure` against `wallhug` reads 65/35 by longevity and **90/10** by victory,
where a third snake wins 67% of those games; at the top of the ladder, where a third snake wins 8%,
the two rules agree to a point. `rate` now prints a **win column with its own bootstrap bar** and says
out loud when the two orderings disagree.

#### The methodological finding, and it is the one worth carrying forward

**A Steiner triple system is the wrong covering design for a three-seat field.** P7a proposed
`S(2,3,9)` — 12 triples covering every *pair* — and P7b ran it, got two inversions against the ladder
(`space` over `chase`, `uct` over `puct`), and then ran the **complete design, all 84 triples**, which
reproduces neither. The reason is exact: a Steiner system balances **pairs**, but an outright win is a
**three-way event**, so what has to be balanced is the *company*. **A pairwise cell moves 12.7 points
on average and up to 33.7 on the identity of the third snake alone**, monotonically in that snake's
strength.

This is visible in the artefacts rather than argued: the incomplete design rates `chase` above `space`
while it wins 11% against 20%, and `rate`'s new orderings check fires on it. The complete design's
same check passes. **A three-seat field is not a two-seat field with a spare chair, and a covering
design that balances the wrong thing produces a well-formed table that is wrong.**

#### What else fell out

- **First-mover advantage inverts.** 54.26% at two seats (z +10.2); **32.53%** against an even 33.33 at
  three (z −3.4). Moving first stops being an advantage and becomes a slight liability.
- **`chase`'s nearest-opponent reduction was expected to be punished by a second opponent and is the
  least punished thing in the field.** It loses 3.5 points of mean pairwise score where `pressure` —
  which does no reduction — loses 6.0 and `alphabeta` loses 9.1, and `chase` *widens* its rung over
  `pressure`. The mechanism argument had the sign backwards.
- **`Separation.permanent` is un-parked and near-vacuous.** It fires in 9 of 1,200 matches (0.75%), 259
  of 171,933 positions, replicating P7a's 0.8–1.2% on a population it never measured. Ground truth 8 of
  the closed agenda is now a real predicate that is sound, **late** — it cannot fire before a death —
  and too rare to build on. **The dispatch-portfolio motivation loses for the third time.**
- **Seat fairness re-confirmed on real fields**: 33.4/33.1/33.5 (χ² 0.39) and 33.7/32.7/33.6 (χ² 4.69).
  Under `--openings fixed` the same probe reads **83.4 / 0.05 / 16.6** — a seat worth more than any bot
  in the repository, which is why `mirrored` is not optional here.
- **Zero forfeits, zero suicides, zero winnerless matches across 93,300 logged matches.**

#### The `backup` knob was declined, on a reason from the code

Workstream item 2 wanted max^n against paranoid, and P7a proposed a `backup` knob on `puct` as the
clean instrument. **It is not one either.** No `LeafEval` returns a vector on the simplex —
`TerritoryEval` is `0.5 + 0.5*(ground + mobility) − trapPenalty` with `ground` antisymmetric and the
rest per-slot — so `values[by] − max(others)` is not an affine image of `values[by]`. A knob would move
the backup **plus** the leaf weighting **plus** the effective `cpuct` together: the same three-way
confound, relocated. **No `Choice` value was frozen**, and the reason is in `LeafEval`'s KDoc. This row
has now been parked by two agendas and is parked again with a mechanism rather than a shrug.

#### What a three-seat number in this repository can and cannot mean

**Can:** an ordering — it agreed with victory in 79 of 84 triples and reproduced the whole ladder.
**Cannot:** a magnitude (two thirds of the low field's range is rank among losers); one bot's number in
isolation (34 points of a cell is the company it kept); any comparison with a two-seat figure; or a
decision — **`ab`, `tune` and `spsa` do not exist at three seats.** `SequentialTest.configFor`
hardcodes two contestants and `HEAD_TO_HEAD`, so the repository's deciding instrument was unavailable
for this entire phase. Making it seat-count aware is the one change that turns three-seat work from
mapping into deciding.

**Two boards, not three.** The 20×20 field was cut with six of twelve triples run; the partial log was
deleted rather than rated. It is about an hour of arena to take properly. And the one ranking that is
board-conditioned is quoted as a lead rather than a finding: seated alone, **`uct` wins the three-way
game at 12×12** (37.2%, z +5.5 over 4,500 matches on three disjoint seed bases), nobody wins it at 8×8,
and `alphabeta` wins it at 20×20.

### What P8 actually found — the fusion works, is provably correct, and is half the speed on the target that ships

**Nothing shipped, and the reason is the most reusable thing this agenda produced.** Both fusions were
built and proven **byte-identical** — `OwnershipEquivalenceTest` and every golden passed unedited at
every stage, no test file touched — and then measured on both targets:

| fused `SpaceOwnership`, `puct` turn | 8×8 | 12×12 | 20×20 |
|---|---|---|---|
| **JVM**, 4 paired rounds, control 0.95–0.99× | 1.10× | 1.14× | **1.17×** |
| **Chrome**, 2 paired runs, control 0.96–1.01×, leaf-pair gate passing | 0.91× | 0.66× | **0.50×** |

**The two targets disagree in sign, by 2.3× at 20×20, on code proven to compute the same thing.**
Replicated exactly, with tight pass lists, and corroborated by a control that should move and did:
`uct:rolloutDepth=25` — the other `SpaceOwnership` reader — moved the same way while undepthed `uct`
did not move at all. There is no smaller subset worth salvaging either: the entrants that isolate the
single-frontier half read 0.93–0.98× in Chrome.

`:bots` deploys to wasm and nowhere else. **A hot-path rewrite is therefore settled in the browser and
the JVM run is the smoke test that precedes it** — now written into `Coding-Standards.md` as part of
**SW-03**, which is where it will be read before somebody rebuilds this.

Even the JVM figure was not the win the agenda budgeted for: 1.10–1.17× is 0.095–0.157 e-folds, which
at this agenda's own measured 80–95 Elo per e-fold is **8–15 Elo** — half the 27–32 the 1.3–1.6×
estimate implied, before the browser reverses the sign.

#### Three things the workstream said that the code did not

1. **"`advanceAlone` — the single-frontier path — *is* the separated case."** It is not. A slot leaves
   `spread()`'s active set when its frontier lands nowhere, which says *whose room runs out first* and
   not whether the rooms connect. The layer census is **70/30 multi-to-single in contested positions
   and 70/30 in separated ones** — the same split either side of the break.
2. **`TempoOwnership` never takes a multi-frontier layer at two snakes** — `advanceWaiting` is called
   **0.0** times per sweep on every board. Four of the six leaves read it, so for most of the leaf
   population the per-layer passes the agenda pointed at **did not exist**.
3. **The payoff is not phase-weighted, and the phase effect runs the other way.** The ratio is flat
   across the split (1.33/1.33, 1.22/1.19, 1.27/1.26) and a separated sweep is both shorter (7.3 layers
   against 21.3) and rarer per turn (487 sweeps against 904). **A cheaper sweep is worth *less* after
   the board comes apart** — the exact opposite of the phase-weighting this agenda instructed the phase
   to quote, and the reason it was told to quote it per phase is the reason it was caught.

The agenda's own instruction here was drawn from ground truth 8, which is about `Separation`; the
mistake was assuming a different predicate meant the same thing. **Two phases in a row have now found
that a mechanism named in the agenda was not the mechanism in the code.**

#### And one number nobody was looking for

`freeSquaresOf` is a per-**cell** loop and **13–14% of every sweep** — untouchable by any fusion above
it, and the largest item left in this area. It needs a word-level free mask in `:core`'s `Occupancy`,
which is not a fusion, so this negative says nothing about it either way.

---

## Considered and not ranked

With the reason, so nobody re-derives it.

**Board-size-conditioned defaults, as a bot feature.** *Two phases have now independently argued for
this and the second one brought a number: `rolloutDepth=25` is worth **+78 Elo at 20×20, +13 at 12×12
and −4 at 8×8**, monotone in board size and crossing zero between the two smallest boards. That is a
setting with no correct single default, which is what this row is about.* The mechanism is free — a
`Grid` is available at construction and costs nothing per turn — but the *contract* forbids it. `entrantOf` drops knobs
"sitting at the value the registry declares today", `SlotForm` renders `knob.defaultText`, and
`Param.isDefault` is the codec's notion of stock. A board-dependent effective default makes all three
describe a bot that is not playing. That is a `:bot-api` change and not worth a phase. **The question
rides inside P2's three board sizes for free**, and if the ordering inverts, the contract change earns
its own agenda.

**The separated endgame as a dispatch rule.** Killed before it was built, and the record is worth more
than the phase would have been. The idea was that after separation the game is single-agent, which is
where "moves until trapped" is the true objective rather than a proxy — so `eval=horizon`, which lost
185 Elo as a two-player comparator, becomes a candidate objective. Four things sank it:

- The split it is motivated by is taken **with hindsight** and `PhasesCommand`'s own KDoc says a bot
  cannot know which split it is looking at. The 21% is real and is measured at a quantity that does
  not exist at decision time — the same class of error as arguing from rollouts `puct` does not have.
- "Ahead on room" is a raw free-square flood with no margin, and squares and spendable moves come
  apart by 1.4–2× shape-dependently. A snake ahead on squares and behind on moves was never ahead;
  that is a contact-phase evaluation error, not a fill-execution one, and the metric cannot tell them
  apart.
- `SurvivalHorizon` **is** already single-agent-shaped, and its bound is documented as loosest exactly
  where a region shatters into pieces the snake will never re-enter — which is the separated fill.
  The thesis had the sign backwards on the property that decided the 185 Elo.
- The arithmetic was inflated roughly 2×. The honest ceiling is the observed conversion gap, already
  published as ~3.7 points of score ≈ **25 Elo** — the same bound the portfolio idea carries.

What survives is the one-hour lead-size rider on P2, which settles the confound the bound rests on.

**Root-proven-lost early exit.** Still open, still specified: `chooseMove` stops when the root is
proven including proven *lost*, and selection also skips proven children, so both ends must change
together. Parked for whichever phase opens `PuctTree`. Whoever takes it **re-measures** the +33/−41
board-size sign flip rather than inheriting it.

**A transposition table.** `BoardView.hash` keys on `(cell, owner)` with no tail and no body ordering,
so two threadings of the same occupied set collide structurally — a TT on it would be subtly wrong and
pass its tests. The fix is a per-slot tail key plus a verification field. Not ranked for a second
reason worth writing down: bodies make positions path-dependent, so true transpositions in this game
should be rare, and that claim is cheap to falsify before anyone builds anything.

**RAVE on `uct`**, argued and rejected — a four-symbol move alphabet saturates over a ~100-move
rollout, so every AMAF mean converges and discriminates nothing. **The solver on `uct`** — a rollout's
terminal proves nothing, so the firing rate would not rise. **`FloodFill` still on BFS** — its
consumers are the reactive bots at 12–50 µs/turn where the match driver dominates. **Bigger learned
models** — closed by measurement, not unexplored.

---

## The protocol every agent gets

The block from [`../Research-Process.md`](../Research-Process.md#the-brief-every-agent-gets), verbatim,
plus four lines this agenda earns:

```
- Name the baseline entrant explicitly in every entrant string, spec and all. "Measured
  against the incumbent" is what left every table baselined at a setting the bot does not
  ship. Since P3 that cuts the other way too: `alphabeta`'s default eval is now `territory`,
  so a bare `alphabeta` today is a different bot from the one in every pre-P3 measurement.
- A field's us/turn column is not a cost measurement. Cost comes from P1's paired runs.
- `play` does not checkpoint. A field that dies loses everything unwritten; `ab`, `tune` and
  `spsa` do not. Size long batches accordingly and redirect to a file.
- Breaking changes are acceptable this agenda -- but say what moved, in the KDoc beside it.
- A train/holdout gap is an in-distribution reading and cannot see a transfer failure. P4
  cost six phases on that inference; do not repeat it with any other held-out number.
```

---

## Open at the close

*Written when the last phase lands. Appended to as phases produce decisions that belong to a person
rather than to a phase.*

- **The reference machine.** P1's absolute Chrome milliseconds carry a 4.1× scaling factor (range
  3.4–5.0) between this box's headless Chrome and the machine the published tables were taken on. One
  browser run there replaces the scaling with a measurement. Ratios and the allowance table are
  unaffected.
- **The leaf-pair consistency check is documentation, not an assertion.** Two entrants sharing a leaf
  must cost the same, and that check caught a browser sweep this agenda would otherwise have
  believed. Making it a `ThroughputTest` assertion was declined for a stated reason: it needs a
  tolerance nobody has measured across machines, and a bench assertion that fires on a busy machine
  teaches everyone to ignore the suite. Somebody with a quiet machine could measure that tolerance.
- **`TimeCommand` still takes a minimum of passes**, which P1 measured to be wrong on a machine whose
  clock steps — it put one entrant 30% off its own line. `AppraisalTape` takes a median. Changing
  `:lab`'s was out of P1's brief.
- **`UctBot.ROLLOUT_DEPTH`'s default — measured on all three boards, and the answer is "not as a
  default".** The missing 8×8 row was taken after the agenda closed, on P6's own protocol, and it
  completes the picture:

  | `rolloutDepth=25` | 8×8 | 12×12 | 20×20 |
  |---|---|---|---|
  | cost ratio, median of runs | 1.05× | 1.12× | 1.32× |
  | verified equal-clock allowance | 950 | 890 | 760 |
  | **value at equal clock** | **−4** | **+13** | **+78** |
  | value per *iteration*, at equal allowance | **0** | +15 | +104 |
  | paired `ab` | UNDECIDED −1 ±16 | UNDECIDED +17 ±17 | **BETTER +93 ±33** |

  **The gain is monotone in board size and crosses zero between 8×8 and 12×12.** The 8×8 null is
  **structural rather than a pricing loss**: handed the clock back, the equal-allowance rung rates
  *level with the baseline* — 78 against 78, coordinator-verified — so there is nothing there for a
  cheaper allowance to rescue. Field and `ab` agree in sign at 8×8, so that board's known failure mode
  did not fire on the subject. The 8×8 cost ratio is also **not** on the other two boards' trend line
  by interpolation, which is P2's "cost is not monotone in board size" arriving a second time.

  So a single default cannot be right: it is worth +78 on the largest board and **nothing** on the one
  `index.html` opens on. What the evidence supports is `rolloutDepth` **staying a knob**, which is what
  it already is. A board-conditioned default is the thing the `:bot-api` contract forbids — see
  *Board-size-conditioned defaults* in [Considered and not ranked](#considered-and-not-ranked), for
  which this is now the **second independent argument**, and the first one strong enough to name a
  number. If somebody adopts it for large boards anyway, the cost list is in the KDoc, and the trap in
  it is that **`puct` over `uct` is the narrowest rung on the ladder at 12 of 20 against a threshold of
  11, and a stronger `uct` pushes it down.**
- **`freeSquaresOf` is 13–14% of every `SpaceOwnership` sweep** and is a per-*cell* loop, so nothing
  above it can reach it. It wants a word-level free mask in `:core`'s `Occupancy`. That is not a
  fusion, so P8's negative says nothing about it either way, and it is the largest speed item anybody
  has a number for.
- **Whether Kotlin/Wasm's array bounds handling is really what reversed P8's sign is unverified.** The
  evidence is a scaling law — the regression runs 1.10 / 1.52 / 2.00 against operand spans of 2 / 4 / 8
  words — plus a control that moved as predicted. It is not a disassembly. Somebody who wants to write
  fast wasm here would learn more from settling that than from any other item on this list.
- **`SequentialTest` is two-seat only, and that is what keeps three-seat work from deciding
  anything.** `configFor` hardcodes two contestants and `HEAD_TO_HEAD`, so `ab`, `tune` and `spsa` were
  all unavailable for the whole of P7 and every number it produced is a field rating. Making it
  seat-count aware is the single change that turns three-seat measurement from mapping into deciding.
- **P7's 20×20 field was cut** with six of twelve triples run, and the partial log deleted rather than
  rated. About an hour of arena to take properly. Until then the three-seat map is a two-board result,
  and the one board-conditioned ranking in it — who wins the three-way game outright — is quoted as a
  lead.
- **The two `learned` fits have never been seated in one field.** P4's Elo case rests on comparing two
  fields, and contrasts move ±30–90 between them, which is why only the ~+240 at 20×20 is quoted as
  evidence. Seating both literals at once needs two compiled in, which is a `:bots` change nobody
  should make for one measurement — but somebody who wants the direct number could take it by holding
  the old literal in a test fixture and running an `ab`. The **loss** comparison is direct and is what
  the finding actually rests on.
- **What the four new readings cost on their own is not isolated.** `EvaluationCost.LEARNED` now
  carries a fresh three-board ratio against `ChamberEval`; the difference from the old 12×12 band is
  confounded with a session and an instrument. A paired sweep of a 25-reading against a 29-reading
  `PositionFeatures` on one machine in one run would settle it, and would cost minutes.
- **P4's field ran 4.7× slower than the coordinator's re-run of the same blocks** — 2.51 ms/turn
  against 0.54, at identical turn counts. Outcomes are budget-deterministic so nothing measured is
  affected, but it is the fourth machine-state surprise on this agenda and the one with no explanation
  attached. Anything on this box that quotes wall time should say when it ran.
- **The budget tripwire is one-directional.** P1 pinned `MatchSetup.DEFAULT_BUDGET_PER_TURN` against
  `:bots`' hand-typed `SHIPPED_BUDGET`, which catches the drift that matters — the allowance rising
  while the ladder certifies a rung nobody plays. It does not catch someone lowering `:bots`' copy
  alone to speed the suite up. The only mechanisms that would are a source-scanning Gradle check or a
  `:lab` test reading both files off disk, and both were judged disproportionate at the failure site.
  Recorded rather than fixed.
