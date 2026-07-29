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
| P1 | Instrument integrity and the cost floor | not started |
| P2 | The composed field, at equal clock, at three sizes | not started |
| P3 | Adopt | not started |
| P4 | The four residual features | not started |
| P5 | A rollout policy for `uct` | not started |
| P6 | A policy-rollout leaf, as its own bot | not started |
| P7 | The third seat | not started |
| P8 | Fused bitboard passes | not started |

The coordinator owns this table and the "What P*n* actually found" sections. Agents report; they do
not edit either.

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

*Corrects `PuctBot.kt:506` and `:785`, and the closed agenda at `:603` and `:781`, all four of which
say moving a `puct` default moves "`GoldenMoveStreamTest`'s hash **and** `BotLadderTest`'s
thresholds." The second half is false.* Fixing those four sentences is P1's work.

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

## Considered and not ranked

With the reason, so nobody re-derives it.

**Board-size-conditioned defaults, as a bot feature.** The mechanism is free — a `Grid` is available
at construction and costs nothing per turn — but the *contract* forbids it. `entrantOf` drops knobs
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
- Until P3 lands, the baseline is the bare entrant `puct` and it is named explicitly in every
  entrant string. "Measured against the incumbent" is what left every table baselined at a
  setting the bot does not ship.
- A field's us/turn column is not a cost measurement. Cost comes from P1's paired runs.
- `play` does not checkpoint. A field that dies loses everything unwritten; `ab`, `tune` and
  `spsa` do not. Size long batches accordingly and redirect to a file.
- Breaking changes are acceptable this agenda -- but say what moved, in the KDoc beside it.
```

---

## Open at the close

*Written when the last phase lands. Until then this section is empty on purpose.*
