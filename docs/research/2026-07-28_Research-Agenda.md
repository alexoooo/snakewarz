# Research agenda — 2026-07-28

Ten directions past `puct`, ordered into phases, written so a fresh session can pick up a workstream
without re-deriving the ground truth. Status lives in the table at the bottom; update it as phases land.

**Closed 2026-07-28. All eight phases landed and every defect they found is fixed.** What is still open
is at the bottom, under [Open at the close](#open-at-the-close). This document stays as it is now — the
record of what was tried, what it measured, and which of its own premises it falsified. A new agenda
gets a new dated file beside it; [`../Research-Process.md`](../Research-Process.md) is how one is
written and run, and most of what it says was learned here.

**Read first:** [`../Bots.md`](../Bots.md) (the bot contract, the registry rules, the three things that
bite), [`../Coding-Standards.md`](../Coding-Standards.md) (the rule ids a review cites),
[`../Workflow.md`](../Workflow.md#deciding-whether-a-change-helped) (`ab` vs a field, and why the
distinct-games line is read first).

---

## Why this exists

`puct` is the strongest bot in the tree and still registered as experimental. The ladder is settled and
measured, so "does search beat reaction" is no longer the open question. What is open is which *kind*
of search, which *kind* of evaluation, and whether approaches nothing here has tried — learned models,
ensembles, evolutionary search, structural perception of the board — buy anything.

---

## Ground truth every workstream needs

Six facts established by reading `:core` and `:bot-api` rather than the docs' description of them.
Getting any of these wrong wastes a session.

### 1. `FillableSpace`'s premise is false under the shipped rules

Its opening line (`bots/.../search/puct/FillableSpace.kt:12`) is *"a snake is a walk that never
revisits a square"*. True at `growEveryNthMove = 1` (classic Tron); **false at the shipped `2`**, where
the tail retracts and a square is reusable roughly `length` moves after you leave it. The repo already
states the consequence in the other direction — `SurvivalEval.kt:83-88`: *"A snake in a closed room of
n squares survives about 2n moves, because the tail frees a square every second turn."*

Both caps inside `FillableSpace` assume self-avoidance and break: the chessboard **parity bound**, and
the block-chain **DP's "a cut vertex can be crossed once"**. `SurvivalEval` — the strongest leaf in the
box — is built on this. `FillableSpace`'s own "it is an upper bound, deliberately" section names two
unmodelled over-counts and does not name the retraction, which cuts the other way.

### 2. `BoardView.hash` cannot key a transposition table

`Occupancy` keys on `(cell, owner)` only (`core/.../grid/Occupancy.kt:116-117`); `auxHash` adds to-act,
head, growth phase and liveness (`core/.../rules/Board.kt:412-419`). **No tail, no body ordering.** Two
different threadings of the same occupied set with the same head hash *identically*, and they differ in
which square frees next — precisely what matters in a filling endgame. This is a structural collision,
not a probabilistic one, and `BoardUndoTest."distinct positions get distinct hashes"` cannot catch it
because it only walks one game. The `turnIndex` wrinkle the docs warn about is the lesser problem.

If a TT is wanted anyway: key on `hash` xor a per-slot tail key (`SnakeView.tail`), and store a
verification field. Or skip it — branching is ≤3, so transpositions are rarer than in chess.

### 3. `Scratch.playout()` resets the arena

`begin()` is `paid = budget.tryConsume(cost); arena.copyFrom(source)`
(`bot-api/.../scratch/BoardScratch.kt:70-73`). So a search **cannot pay per leaf and keep its
descent** — asking for the next payment destroys the path it is standing on. That is why neither
shipped searcher calls `undo`: the payment already reset them.

A depth-first bot must choose:
- **replay the path from the root at each paid leaf** (~`2·depth` extra applies; doctrinally clean,
  keeps termination structural, handles budget 0 by itself), or
- **call `turn.budget.tryConsume` directly** (passes `BotContractTest`, but moves the accounting into
  the bot, which is exactly what SW-07 exists to prevent — expect the review).

### 4. `turn.scratch.playout(0)` is load-bearing and undocumented

`Budget.tryConsume(0)` succeeds even at a budget of zero (`0 > 0` is false, `core/.../Budget.kt:47`), so
`paid` stays true and `advance` does not throw. A bot that wants an arena *without* buying an evaluation
needs this. Without it the bot works at budget 1000 and throws at budget 0 — a case
`BotContractTest."no bot outruns its budget, even when handed none at all"` runs.

### 5. `isolated` is not a test for *permanent* separation

`SpaceOwnership.isolated` / `TempoOwnership.isolated` mean "this slot's frontier never met another's in
*this sweep*". A barrier made of a **living** snake's body erodes — its tail advances one square every
two moves — so `isolated == true` today can be false in ~`length/2` rounds. Only a **dead** snake's body
is permanent. The tests dodge this: `TempoOwnershipTest."a snake walled in behind a body still meets
nobody"` sets `growEveryNthMove = 1` with the comment *"so no tail is retracting to muddy it"*. There is
no test of separation under the shipped rules.

Conservative predicate: run the connectivity flood treating **every living snake's body square as free**
(dead bodies stay wall). If the regions still do not connect, the separation is permanent.

### 6. Cheap facts worth not re-measuring

- **The wasm bundle has 94% headroom** — 90.6 KB of the 1.5 MiB gzipped cap (SW-08). Model weights are a
  non-issue. But store them as a **`String` literal decoded in the constructor**, not `doubleArrayOf`:
  a large array literal compiles to *code* in Kotlin/Wasm, a string literal to a data segment that gzips.
- **Board geometry varies.** `BotContractTest` runs 1×1, 1×5, 2×2, 3×7, 8×8, 9×13, 10×10, 11×11, 12×12,
  14×14, 20×20, and `MatchSetup.MAX_SIDE = 256`. **Any raw-board-plane model input is dead on arrival**;
  a fixed-length hand-crafted feature vector is the only option.
- **`:lab` cannot see `:bots` internals.** Kotlin `internal` is module-scoped and `:lab`'s main
  compilation is not an associated compilation of `:bots`. A trainer needs one narrow `public`
  extractor exported from `:bots` — duplicating it in `:lab` guarantees train/inference skew, which
  produces a bot that is merely mediocre rather than visibly broken.
- **Positions are regenerable** from logged replays: `ReplayCodec` → `MatchRecord` (carrying spawns,
  turn order, rules, budgets) → `ScriptedRegistry` drives the match. But `Replays.DECISIVE` is the
  default, so a training corpus needs `--replays all`.
- **`Playout.undo` has no production consumer.** Only `BoardScratchTest` and `BoardUndoTest` call it. It
  is correct and well tested (a full 3-snake game unwound comparing signatures at every depth) but
  unexercised in anger.
- **You cannot skip a trapped snake.** It is still alive and still `toAct`, and must be handed a
  direction. `PuctTree.open` gives such a node **one** edge, because every direction from a trapped
  position produces a bit-identical board — branch 1 there, not 0 and not 4.

---

## Relaxed constraints

Two house rules are lifted **for new experimental bots only**. Both relaxations are contained; neither
touches `uct` or `puct`.

- **SW-02 (no `ln`/`exp`/`pow` in `:bots`).** The rule exists because `GoldenMoveStreamTest` re-runs in
  real Chrome and one last-place difference in a transcendental flips a move and then the match.
  Containment: a new bot that uses `exp` is simply not added to that test's cross-target set, or is
  pinned JVM-only, and says so in its KDoc. `uct`'s `portableLog` and `puct`'s softmax-free prior stay
  as they are, so their existing hashes stand. This unlocks softmax priors with a temperature, logistic
  value squashes, and real UCB1.
- **The ~8 ms frame slice.** Experimental bots may overrun it. Per-turn cost is still **reported**
  (`:lab time`) beside every win rate — SW-07's real point is that a matrix at equal iteration counts is
  not a matrix at equal clock, and the repo has the worked example (`SurvivalEval` wins per iteration,
  ties per millisecond).

Everything else stands: SW-01 determinism from `setup.rng`, SW-03 no hot-path allocation, SW-04 module
purity, **SW-05 frozen identifiers** (a new `BotId` slug, knob name or `Choice` value is permanent from
the day it ships), SW-07 honest evaluation cost.

---

## The ten workstreams

### A — Search algorithm

| # | Workstream | Thesis | Reuses | Size | Risk |
|---|---|---|---|---|---|
| **1** | **MCTS-Solver** — propagate proven win/loss up the tree as exact rather than averaging it | Endgames here are full of forced terminal lines and `puct`'s rollouts hit them constantly. Averaging a proven loss with 0.5s discards the one certain thing the search knows | `PuctTree` (+2 `ByteArray`s), `outcomeValues` | S | Low. Standard, strictly more information |
| **2** | **Iterative-deepening alpha-beta**, 2-player scope | The canonical strong Tron bot (2010 Google AI Challenge) was alpha-beta, not MCTS. Nothing here has ever run an exact search, and `Playout.undo`/`undoDepth` exist with no consumer | `Playout.advance`/`undo`, any `LeafEval`, `priorsInto` for ordering | M | Medium. Branching ≤3 buys ~11 plies at best while the decisive facts sit 100+ plies out. **`LeafEval` is a non-zero-sum vector** — the scalar must be `values[me] − values[them]`, which changes the eval's meaning. max^n does not prune (hence 2-player). No TT — finding 2 |
| **3** | **RAVE / AMAF + progressive bias** on `PuctTree` | 1000 evaluations is a *low-simulation* regime — a few hundred nodes. RAVE exists to make early estimates useful; progressive bias decays the hand-written prior instead of leaving it fixed forever | `PuctTree`, existing prior | S/M | Low-medium. RAVE's move-independence assumption is weak where a move's value depends entirely on *when* it is played |

### B — Evaluation and prior

| # | Workstream | Thesis | Reuses | Size | Risk |
|---|---|---|---|---|---|
| **4** | **Retraction-aware survival horizon** — "moves until trapped, given my length and growth phase" in place of "cells a self-avoiding walk can take" | Finding 1. The distortion is **structural, not uniform**: an open room is underestimated ~2×, a dumbbell ~4×. So the bot declines necks it could cross both ways — and whether it can is `2k > length`, i.e. exactly the mid-game where territory is decided | `FillableSpace`'s Hopcroft–Tarjan decomposition, `TempoOwnership` | S/M | Low. **Verifiable against a brute-force oracle** on tiny boards |
| **5** | **Chamber-tree territory eval** ("tree of chambers") | The winning Tron evaluation. `FillableSpace` already computes the block-cut decomposition and throws the graph away, keeping one integer. Keep it and you can ask per-chamber questions: who arrives first, is it contested, what is its parity, does taking it seal me in | `FillableSpace` internals, `TempoOwnership` | M | Medium. More state and more cost in a leaf already 1.3–5× a rollout |
| **6** | **Richer PUCT prior with a temperature** | The prior is *one* feature — destination liberties — with no softmax, and the KDoc frames the missing temperature as a virtue because `exp` was banned. It no longer is. Candidates: territory delta after the move, articulation-point penalty (does this move cut my own space?), wall adjacency, tail-following | `priorsInto`, `SpaceOwnership`, `FillableSpace` | S | Low. Drops into `priorsInto` with no other change. Needs #8 to settle the weights |

### C — Entirely different approaches

| # | Workstream | Thesis | Reuses | Size | Risk |
|---|---|---|---|---|---|
| **7** | **Learned evaluation + learned prior** (a small AlphaZero loop) | `LeafEval` is a network-shaped hole and the bundle has 94% headroom. Self-play in `:lab` → (features, visit distribution, outcome) → fit → bake weights → repeat | `LeafEval`; `ReplayCodec` + `ScriptedRegistry` to regenerate positions | L | Medium-high. Geometry varies, so the work is **feature design, not the net**. Needs one narrow `public` extractor in `:bots` (finding 6). Bake the inference squash into the training loss — fitting with a logistic and clamping online gives a miscalibrated value that reads as "the net is just weak" |
| **8** | **SPSA / evolutionary tuning in `:lab`** | `tune` is coordinate descent, which scales badly past ~3 knobs. #5, #6 and #7 all introduce 8–12 weight vectors it cannot settle. SPSA is what chess engines use for exactly this | `Sprt`, `Arena`, `KnobSpace`, `TuneJournal` | M | Low. Pure `:lab` infrastructure, no bot risk. It is what makes #5/#6/#7 tunable at all |
| **9** | **Bitboard space primitives** | Every evaluator here is a BFS sweep over a `ByteArray`. A 14×14 padded board is 196 bits = 4 longs; flood fill becomes shift-and-mask, whole board per ~4 ops. Plausibly 10–30× on the sweeps, which converts directly into iterations — and **every "per iteration vs per millisecond" verdict in the repo moves** when sweeps get cheap | A `:bots`-internal mirror maintained incrementally; no `:core` change | M | Medium. The work is keeping the mirror in sync through `advance`/`undo`. Highest leverage on everything else in this list |
| **10** | **Phase-dispatch portfolio bot** | Different bots are best in different phases and nothing measures where `puct`'s Elo is actually lost. Dispatch on the **conservative** separation test of finding 5, specialist per phase | `ChaseBot`'s embed-a-bot pattern, `SpaceOwnership` | S/M | Low as an *instrument*, medium as a *bot*. Its real value is diagnostic |

**Considered and not ranked:** max^n vs paranoid vs risk-sensitive backup. Cheap, but identical at N=2,
and `:lab`'s `ab`/`play` are head-to-head by default — it only pays in FFA.

---

## Phases

Ordered by expected gain per session, with dependencies. Each phase is one agent session unless noted.

| Phase | Workstream | Depends on | Why here |
|---|---|---|---|
| **P1** | #4 retraction-aware survival horizon | — | Corrects a false premise under the strongest leaf. Contained, and the only one with an **exact oracle** |
| **P2** | #1 MCTS-Solver | — (parallel with P1) | Smallest change with a real expected gain; strictly more information into the same tree |
| **P3** | #9 bitboard space primitives | — (parallel; conflicts with P1 on `search/puct`, so sequence after) | Makes #4/#5/#6 affordable and re-opens every per-millisecond verdict in the repo |
| **P4** | #8 SPSA tuning in `:lab` | — | Prerequisite for anything with more than ~3 weights. Pure infrastructure, zero bot risk |
| **P5** | #6 richer prior + #5 chamber-tree eval | P3 (cost), P4 (tuning) | The two evaluation upgrades, tunable only once P4 lands |
| **P6** | #2 alpha-beta | P3 helps | The genuinely different search — worth knowing even if the answer is "MCTS wins here" |
| **P7** | #7 learned eval + prior | P4, P5 (features), P3 (cost) | Highest ceiling, largest surface. Needs the feature extractor P5 forces you to design anyway |
| **P8** | #3 RAVE, #10 portfolio | — | Opportunistic. #10 is best run early as a *diagnostic* if the field results are confusing |

P1 and P2 touch different files (`search/puct/*Eval*` and `FillableSpace` vs `PuctTree`) and can run as
concurrent sessions. P3 rewrites the sweep primitives and should not overlap with P1.

---

## Shared measurement protocol

Every workstream reports the same way. The full grammar is in
[`../Workflow.md`](../Workflow.md); the parts that are not optional:

```bash
# Cost, always, beside the win rate (SW-07). Small board and large — the ratio moves with the board.
./gradlew :lab:run --args="time <entrant> --budget 1000"
./gradlew :lab:run --args="time <entrant> --budget 1000 --rows 20 --cols 20"

# Head-to-head: the decision procedure. --elo1 is 'how small a gain is worth finding' and dominates cost.
./gradlew :lab:run --args="ab <baseline> <candidate> --elo0 0 --elo1 10 --log .lab/<experiment>-ab"

# A field, because `ab` is blind to a change that alters how often a bot loses games it should not.
# The worked case: ChaseBot.roomShare measured 1 Elo +/-3 under `ab` over 260 boards, and +14 rated
# against a field over 6,600 games.
./gradlew :lab:run --args="play <candidate> <baseline> puct uct chase pressure space --rounds 200 --log .lab/<experiment>"
./gradlew :lab:run --args="rate --log .lab/<experiment>"

# Why it still loses. Fates, shape of losses, tempo, pasteable replay fragments.
# Since P4 an entrant name is a *knob subset* in any order — `puct:eval=horizon` resolves, values
# compare numerically, and an ambiguous name lists its candidates instead of guessing.
./gradlew :lab:run --args="report <candidate> --against <baseline> --worst 5 --log .lab/<experiment>"
```

Rules of reading, in order:

1. **The distinct-games line first.** Spawns do not depend on the seed, so under `--openings fixed` a
   pairing of bots that draw no randomness plays four distinct games however many rounds are asked for.
   `mirrored` is the default. A `NO BETTER` verdict on a pile of exact splits is a test that never saw
   the change — go to the field.
2. **Forfeits are a defect, never a result.** Fix before believing anything else.
3. **Never build an ordering out of one row** — these matchups are not transitive
   (`territory@770` beats `survival@470` where `territory@1000` cannot). Score against a common field.
4. **A knob tuned at one allowance is tuned at that allowance** — `puct`'s `cpuct` measured +73 at
   budget 400 and −19 at the shipped 1000.
5. **Only a confirming run over fresh boards is actionable.** `tune`'s descent is cheap and greedy;
   its journal is a record of attempts, not of findings.
6. **A golden failure is a question, never a hash to update.** Name what changed before touching it.

House rules for any session working from this document: **stage new files with `git add` the moment
they are created**; **never commit and never push** — leave the work staged and say so.

---

## Status

| Phase | Workstream | State | Result |
|---|---|---|---|
| P1 | #4 survival horizon | **landed, negative** | `eval=horizon` ships and is oracle-verified, but **loses to `survival` by 185 Elo** (55-145 on a 4,200-match field) at an equal clock. Beats the `territory` default +34 ±19 over 800 boards at 1.56× its cost. Not a default. The premise was right and the ranking did not follow — see below |
| P2 | #1 MCTS-Solver | **landed, gated off** | `puct:solver=true` is correct and **free** (2166 vs 2181 µs/turn). Its only measurable effect is against its own control and it **flips sign with the board**: +33 ±19 Elo on the shipped 12×12, **−41 ±25 on a 20×20**. Fires on 0.19% of iterations. Keep, off by default. One untested suspect below |
| P3 | #9 bitboards | **landed, positive** | `CellBits` + bitboard `SpaceOwnership`/`TempoOwnership`, byte-for-byte identical, all 13 goldens untouched, browser suite green. **1.59×/2.13× on `puct`'s turn** (12×12/20×20, paired), 1.18-1.22× on `survival`. **Not the predicted 10-30× — that estimate was structurally wrong.** Chrome gains *more* than the JVM. **Worth +105 ±38 / +205 ±65 Elo at an unchanged frame budget**; the ~8 ms slice now fits budget ≈1,180, up from ≈590 |
| P4 | #8 SPSA tuning | **landed** | `spsa` command, 2 measurements per iteration at any dimension, CRN worth ~7.5× the games, Polyak-Ruppert tail average, bound-safe. Verified on synthetic objectives with known optima. **The confirming run is structural, not advisory** — see below. Also fixed `report`'s entrant resolution and gave `tune` forfeit reporting |
| P5 | #6 prior, #5 chamber tree | **both landed, positive** | `eval=chamber` is **+85 ±32 Elo over `survival`** for +9% cost and tops the field; **the `seal` term is the whole of it**. `MovePrior` adds five knobs, all shipping as no-ops, with a rated **+103 Elo** recommendation on top of `chamber` for 1-2% cost. `portableExp` kept `puct` in the cross-target golden set, so SW-02 needed no relaxing. **Two defaults now await a shipping decision** |
| P6 | #2 alpha-beta | **landed — viable, and intransitive with MCTS** | `alphabeta` reaches **11 plies** at budget 1000 for a **1.03-1.09×** replay tax. It *beats* `puct:eval=chamber` head-to-head 108-92 yet rates below it in a broad field — a real cycle, not noise. Tripling the allowance is worth +101 Elo to it and +92 to PUCT: depth pays at the rate iterations do |
| P7 | #7 learned eval | **landed, positive** | `eval=learned` (25 features → 16 softsign → logistic, 433 weights) rates **+29 Elo over `eval=chamber` on disjoint intervals** over 10,500 matches, for +11-16% clock — so roughly **level to slightly ahead per millisecond**. Train loss == holdout loss to five places: **the ceiling is the features, not the model** |
| — | **Defects found by P1-P8** | **all closed** | Contract suite now sweeps **1-4 seats** and every `Choice` value (2,160-match probe found no bot bug — nothing in `:bots` was ever written as a duel); `alphabeta` and `eval=learned` added to the cross-target golden set, both reproducing in Chrome; `Match.playback` exhaustion now throws instead of hanging; `alphabeta` offers five evals; three stale KDocs re-derived |
| P8 | #3 RAVE, #10 portfolio | **landed — RAVE null, #10 shipped as a diagnostic** | `rave` is **monotone in how much AMAF is believed and the best of it is the control** — a null owing 3% of clock, with no interior optimum. The mechanism cannot fire: **0.0% of unvisited children ever carry AMAF**. #10 shipped as `:lab phases` instead of a bot, and bounds a portfolio at **~25 Elo** |

---

## P1 in detail — retraction-aware survival horizon

The first phase, specified to the point a session can start without re-deriving anything.

**The claim to test.** `FillableSpace` measures *cells a self-avoiding walk can take*. Under
`growEveryNthMove = 2` the quantity that decides a separated race is *moves until trapped*, and the two
differ non-uniformly — ~2× in an open room, ~4× in a dumbbell, because a neck crossed once by a
self-avoiding walk can be crossed repeatedly by a retracting one whenever the far room costs more moves
to fill than the snake is long.

### Files

| Path | Change |
|---|---|
| `bots/src/commonMain/kotlin/ao/snakewarz/bots/search/puct/SurvivalHorizon.kt` | **New.** `measure(space: TempoOwnership, slot: Int, head: Cell, length: Int, growsNext: Boolean): Int` → estimated moves until trapped |
| `bots/src/commonMain/kotlin/ao/snakewarz/bots/search/puct/HorizonEval.kt` | **New.** A `LeafEval` reading `SurvivalHorizon` where `SurvivalEval` reads `FillableSpace`. Same four weights, same shape, so the comparison is one variable |
| `bots/src/commonMain/kotlin/ao/snakewarz/bots/search/puct/PuctBot.kt` | One `when` branch **above** the `else ->` total-read fallback (`:73-96`); one new value in `EVAL.values` (`:260-267`) |
| `bots/src/commonMain/kotlin/ao/snakewarz/bots/search/EvaluationCost.kt` | One entry, with measured milliseconds in the KDoc table |
| `bots/src/commonMain/kotlin/ao/snakewarz/bots/search/puct/FillableSpace.kt` | KDoc only — close the gap named in finding 1 |
| `bots/src/commonTest/kotlin/ao/snakewarz/bots/search/puct/SurvivalHorizonTest.kt` | **New.** The oracle test |

Registration is unchanged: this is a new `Choice` **value** on an existing knob, so `ShippedBotsTest`'s
pinned knob-name list and `GoldenMoveStreamTest`'s `puct` case (which runs at the default
`eval=territory`) both stand. The value name is **frozen the day it ships** — proposing `horizon`.

### Design

Keep `FillableSpace`'s iterative Hopcroft–Tarjan block decomposition; it is the right machinery, already
allocation-free and generation-stamped. Change what a block is *worth*:

- **Open block, `2·area > length`** — the walk can loop, the tail clears behind it, and the block is
  worth roughly `2·area` moves rather than a parity-capped cell count.
- **Block behind a neck** — chargeable both ways when the far side costs more moves to fill than the
  snake is long *at the time it arrives*; once when it does not. This is the whole correction, and it is
  a comparison against a **running** length, since the snake grows as it fills.
- **Parity cap survives, weakened** — it still binds when the snake is long relative to the block (late
  endgame, where the walk really cannot revisit) and must not bind when it is short.
- **Global clamp:** a region of `F` free squares yields at most `2F + 1` moves. The free count drops by
  exactly one every two moves — a retracting move takes one square with the head and gives one back with
  the tail; a growing move takes one and gives none. Exact, and the right bound.

### Verification

This is P1 rather than a plausible-sounding first step because it has an **exact oracle**.

```bash
# 1. Correctness. Brute-force exact "moves until trapped" by exhaustive DFS over a private board on
#    3x3..4x5 regions, compared against the estimator. Assert it is an upper bound; tight on open
#    rooms; strictly tighter than FillableSpace's on dumbbells and combs.
./gradlew :bots:jvmTest --tests '*SurvivalHorizonTest*'

# 2. The gate. A new eval value enrols in the contract suite.
./gradlew build          # jvmTest + checkModulePurity + ktlintCheck + golden hashes

# 3. Cost, then head-to-head, then a field, then a diagnosis — the shared protocol above.
./gradlew :lab:run --args="time puct:eval=horizon --budget 1000"
./gradlew :lab:run --args="ab puct:eval=survival puct:eval=horizon --elo0 0 --elo1 10 --log .lab/horizon-ab"
./gradlew :lab:run --args="ab puct puct:eval=horizon --elo0 0 --elo1 10 --log .lab/horizon-ab"
./gradlew :lab:run --args="play puct:eval=horizon puct:eval=survival puct uct chase pressure space --rounds 200 --log .lab/horizon-field"
./gradlew :lab:run --args="rate --log .lab/horizon-field"
./gradlew :lab:run --args="report puct:budget=1000,eval=horizon --against puct:budget=1000,eval=territory --worst 5 --log .lab/horizon-field"
```

**Expected signature if the thesis holds:** fewer `TRAPPED` eliminations at high board fill, and a
higher median board fill at the point of loss — the bot should stop declining necks into space it can
actually spend. `report`'s "shape of its losses" section reads both directly.

### Done means

`SurvivalHorizon` verified against the oracle; `eval=horizon` registered and passing the contract suite;
a measured table in `HorizonEval`'s KDoc giving the win rate **and** the per-turn cost, in the house
style (`TerritoryEval`, `UctBot.ROLLOUT_DEPTH`); `FillableSpace`'s KDoc gap closed. Whether `horizon`
becomes the default — or `puct` moves into the ladder — is a separate decision needing its own number,
per the note at `ShippedBots.kt:68-72`.

### What P1 actually found — read this before P5

All of it delivered and staged. The estimator is correct and the bot is worse, which is the
interesting part.

**Four of the design bullets above were wrong.** Each was caught by the oracle and corrected, so the
shipped `SurvivalHorizon` does not match the four bullets as written:

1. The neck is chargeable both ways after `2·length − 1` **moves**, not `2k > length`. The bullet is
   twice as permissive; used literally a 3×3 wishbone read 10 against a true 4.
2. The running length must come from **distance to the head**, not from the order blocks are charged
   in. The accumulating version is order-dependent and *violated the upper bound* — 6 against a true 7.
   This is what forced the one out-of-scope accessor, `TempoOwnership.distanceTo`.
3. The parity cap is **graded**, not a step. A step is a cliff in a leaf.
4. The global clamp is **`2F`**, and `2F − 1` when the next move grows — not `2F + 1`. A walk needs a
   free square *before* each move, so `2F + 1` is unreachable. Both bounds are exact.

**And one ground-truth figure.** "~4× in a dumbbell" is a large-bell limit. Measured, a 3×5 dumbbell is
12 squares against 17 true moves and a 4×5 is 16 against 23 — **1.4×**, because the snake grows too
fast relative to a small bell to collect the second crossing. Only the open-room **2×** is flat, and it
is exact (2×4 → 14/14, 3×4 → 22/22).

**Why the corrected leaf still loses.** A leaf is read as a *comparison*, never as a quantity. The
doubling that is exact in an open room is generous everywhere the walk cannot really loop, so a move
that shatters a region into pieces the snake will never re-enter still reads as worth two moves a
square. Right about one region's physics, wrong about the ranking of two. The predicted signature came
out **inverted**: `horizon` dies *earlier* (median 85 moves vs 96) and in a *more open* board (60% fill
vs 68%), with every loss on both sides `TRAPPED`.

**The lesson P5 inherits:** a more accurate absolute quantity is not automatically a better ranking
function, and the fix for that is calibration against the shape being compared — which is what #5's
per-chamber questions and #6's tunable weights are actually for. Do not assume a truer number wins.

### What P2 actually found — read this before P3 and P5

**The thesis row for #1 is wrong as written.** It argues from *"`puct`'s rollouts hit forced terminal
lines constantly"*. **`puct` has no rollouts** — it appraises the leaf statically, so only the tree can
reach a terminal, and at 1000 iterations over a branching factor near three that tree is ~7 plies deep.
Measured firing rate: **0.19% of iterations** on a 12×12. This cuts the same way for `uct`: a rollout
ends on a terminal every time, but a rollout's terminal is one random line and proves *nothing* — only
a tree-descent terminal is a proof — so porting the solver to `uct` would not raise the rate either.

**And its risk line, "Low. Standard, strictly more information", is falsified.** More information into
the tree is not monotone in playing strength here: the same knob is worth **+33 ±19 Elo on a 12×12 and
−41 ±25 on a 20×20**, both ~3σ on the boards that were not exact splits.

**Open follow-up, flagged and deliberately not chased.** `PuctBot.chooseMove` exits the search whenever
the root is proven — and that fires for a root proven **lost** as readily as one proven won. A line lost
against max^n is not lost against an opponent that might still err, so the bot stops buying iterations
in exactly the positions where the remaining allowance could still be spent maximising the chance of a
mistake. Note it is **not** a one-line fix: selection also skips proven children, so at an all-losing
root there is nothing left to descend into. Making the bot play on in a lost position needs both ends
changed together. Good P8 material.

**A doctrine correction that P3 and P5 both inherit: `ab` is not always the blinder instrument.**
`ChaseBot.roomShare`'s lesson was "`ab` blind, field sensitive". Here the reverse held — `ab` reached a
verdict on both boards while both fields washed out inside their error bars, because three of six field
pairings sit saturated at 0.97–1.00 whichever way the knob is set. **A field is the more sensitive
instrument only when its pairings are actually contested.** Check that before trusting one, in either
direction.

**And a warning about predicting sensitivity from divergence.** P2's per-decision divergence table
(1.4% on 12×12, 6.6% on 20×20) correctly predicted *where the knob changes more decisions* and got the
*sign* exactly backwards: the board where it changed ~5× more decisions is the board where it lost.
Divergence measures whether a test can see a change, never whether the change is good.

### What P3 actually found — the sweep numbers everything else is priced against

`CellBits` (one bit per **padded** cell, `LongArray`, margin words so a neighbour step needs no bounds
test) now backs `SpaceOwnership` and `TempoOwnership`. Output is byte-for-byte identical, all 13 golden
hashes pass **unedited**, and the browser suite is green in real Chrome.

**Both of workstream #9's estimates were wrong, in opposite directions.**

- **"Plausibly 10-30× on the sweeps" is structurally wrong, not merely optimistic.** A bitboard flood is
  `O(words × layers)`, not `O(words)`. Measured layers: 13 on a 12×12, 23 on a 20×20 — against 4 and 8
  words. The word count is only ~2.5× below the cell count and a word-step costs several times a cell
  visit. Honest figure: **1.8-2.1× on the sweep, 1.5-1.9× on `puct`'s turn**, growing with board size
  because layers grow as √N while words grow as N. Another 1.3-1.6× is available by fusing the ~9 short
  passes per layer; nothing beyond that.
- **"Medium risk. The work is keeping the mirror in sync" — there is no mirror to keep in sync.** A leaf
  evaluation happens once per iteration at one position, so there is no second sweep to amortise over
  and no incremental path exists *or is needed*. The real risk was elsewhere entirely: the `isolated`
  predicate, whose definition is subtler than the sweep it comes off — a rival's **head** beside my
  ground is contact, and a head is not free. The differential test caught it on a 1×5 at turn 0; the
  shipped unit tests missed it.

**The hazard is not board size, it is `stride % 64 == 0`** (reachable at 62, 126 and 190 columns under
`MAX_SIDE = 256`), where both targets mask a `Long` shift to six bits and "shift by 64" silently becomes
"shift by 0", dragging in a whole foreign word.

**Padding is stronger than the agenda's hint.** `Occupancy.init` walls the entire one-cell ring, so a ±1
step from any set bit stays in its row: wrap-around is **impossible by construction**, not masked away.

**`FloodFill` was deliberately left on BFS.** Its only consumers are `SpaceBot`, `PressureBot` and
`ChaseBot` — all reactive, all 12-50 µs/turn where the match driver dominates. CLAUDE.md's "read by
nearly everything" is true of the *concept*, not of the call graph.

**What this re-opens, and it is the point of the phase.** At the shipped 12×12 a board-wide ownership
sweep is now **cheaper than a `uct` random rollout** (1.50 vs 1.68 ms/turn at 1000 evals) where
`EvaluationCost` recorded it at 1.3 rollouts. Because `territory` gained 1.49× and `survival` only
1.16×, the repo's worked per-millisecond example — *"`SurvivalEval` wins per iteration, ties per
millisecond"* — has moved in `territory`'s favour and needs re-deriving.

**Unrelated, pre-existing — since fixed, and it was the table that was wrong.** `RolloutTruncationTest`
printed `24/40` at `rolloutDepth=25` where `UctBot.ROLLOUT_DEPTH`'s table recorded `20`. Re-derived at
1,000 rounds a depth: 61.9% / 54.1% / 48.0% at depths 10 / 25 / 60. **A 40-match column carries ±8
points, which is more than the effect it was read for** — "cutting hard is ahead" survives, "cutting
late is behind" does not (depth 60 is *level*, not 17 of 40). The suspect condition change was ruled
out rather than assumed: a CRN-paired run at the pre-P0 `exploration=5.0` gave 602/550/500, every one
inside a sigma, so **that knob change is a null on this trade**. Both numbers re-recorded with their
conditions.

### What P3's conversion found — and a timing method the whole repo should adopt

**Pair each seed across two builds. Do not compare timing blocks.** The conversion agent rebuilt
pre-P3 HEAD in a detached worktree and timed the same seed back-to-back on both builds — legitimate
here because the rewrite is byte-identical, so turn counts match exactly per seed and the ratio is pure
cost with the game held still. Its first *unpaired* 20×20 block reproduced P3-like numbers **and put
the `uct` control at 0.82×**: it was reading machine drift as signal. The machine degraded ~50% across
that session. **Absolute µs from a single block are worthless; only paired ratios with a control
survive.** P3's own six-seeds-best-of-five understated its speedup for exactly this reason —
1.49 → **1.59×** on 12×12 and 1.93 → **2.13×** on 20×20.

**The headline, in the currency that counts.** At an unchanged frame budget the extra iterations are
worth **+105 Elo ±38 on a 12×12** and **+205 Elo ±65 on a 20×20**. Both stopped at the SPRT upper
bound, so the sign is solid, "at least 10 Elo" is what was proven, and those magnitudes are the
generous end.

**The worked per-millisecond example did *not* flip, and P3's claim that it had is false.** Only the
exchange rate moved — 470 → **415** survival iterations for one territory-at-1000 clock. The outcome
held: `survival@415` vs `territory@1000` is **+7 Elo ±10 over 2,000 boards**. Per iteration survival
still wins (+81 ±33). What changed is precision, and that is the stronger result: the old "48-52 over a
hundred rounds" was ±35 Elo of nothing, while ±10 over 2,000 boards is a null you can act on — *level*
is now a property of the two evaluations rather than of one lucky allowance.

**What now fits the ~8 ms slice: budget ≈1,180, up from ≈590.** Reported, not applied — it is a
shipping decision. Conservative, since Chrome gains more from the bitboards than the JVM does.

**Two stale claims this left behind — both since fixed.**
`MatchSetup.DEFAULT_BUDGET_PER_TURN`'s KDoc said *"`puct` overruns the slice at this figure at either of
its two appraisals"*; at `eval=territory` it no longer does, and there are now six eval values, not two.
Its KDoc now states the 6-7 ms band **with its derivation shown** and says outright that nobody has
timed an appraisal in Chrome. `ThroughputTest.MEASURED_BOARD`'s KDoc called 20 "the board `:ui` opens
on" when `index.html` selects 8 — the constant was right and only the justification was wrong, so the
reasoning was replaced and the number kept.

**Two budgets, and they are different questions — do not conflate them when pricing P5 onward.**
**1,590** is `1000 × 1.59`: the iterations that now cost what budget 1000 used to cost, which is the
right control for "what did the speedup buy". **1,180** is what fits the ~8 ms frame slice, which is
the right number for what should ship. They are not interchangeable.

### What P4 actually found — why the confirming run is structural

`spsa` estimates a gradient in **two** measurements regardless of dimension. Common random numbers —
the two arms playing each other on one board set, so a board's own difficulty cancels inside the
difference — are worth about **7.5× the games**. The answer is the Polyak–Ruppert mean of the last
quarter of the trajectory, never the last iterate and **never the best one**. At a bound the
perturbation slides inward *as a rigid body* so the arms stay exactly `2c` apart; clamping one arm
shortens the chord and reads a gradient biased toward the wall.

**The finding that matters most.** A real `spsa` run on `cpuct` at budget 1000 walked the knob to the
floor of its range and the confirming run said **−21 Elo**. `tune` had already produced the mirror-image
failure from the other direction — accepted 2.3 at +112, confirmed at −19. **Both search shapes
manufacture a confident-looking wrong point on a flat knob, and only fresh boards catch it.** That is
why the confirmation is performed by the tool on a disjoint seed base with no flag to skip it, and why
every run prints *"a record of attempts, not of findings"* whatever it found.

**A lying instrument, caught before it shipped** — recorded in `Spsa.parked`'s KDoc so nobody re-adds
it. A statistical "was there a gradient" test *loses power as a search converges*: past arrival every
iteration adds noise variance and no signal, so a long successful run reads "nothing there". Replaced
with the exact structural question — did the recommendation land on a declared bound?

**Qualify workstream #8's promise.** SPSA makes 8–12 weights *searchable*; it does not settle them. At
1,200 boards it localises a knob to ~7% of its declared range on a synthetic objective of realistic
curvature, and the confirming run is still doing the deciding. On **one** knob it is not better than
`tune` — its whole advantage is dimension, which is exactly what P5 needs it for.

Also fixed: `tune` had no forfeit reporting at all, so a sweep could run hundreds of matches through a
throwing bot silently, against protocol rule 2.

### What P5's first half found — and the trick worth copying

**Build the new thing so that its defaults reproduce the old thing exactly.** `ChamberEval` at
`parityWeight=1, frontierPenalty=0, sealPenalty=0` is **bit-identical to `SurvivalEval`**, verified on
five boards, and `ChamberTree`'s chain equals `FillableSpace`'s integer on twelve hand-drawn shapes and
400 generated regions. So the decomposition is provably *retained* rather than reimplemented, and any
later batch between the two settings is a batch about three weights and nothing else. This is the
cleanest one-variable setup any phase here has managed — copy it.

**The seal term is the reading that exists nowhere else in the box.** `SurvivalEval`'s chain is a max
over children, so 20 spendable squares out of 22 and 20 out of 40 are *the same number* to it — and the
second is a snake that has just cut itself off from half its ground.

**Cost: +9% on `survival`, not a new tier — workstream #5's risk line overstates it.** The DFS is the
same DFS; the extra work is one `Long`-and-mask per rejected edge and one accumulator per chamber pop.
What is expensive was already being paid. **This reframes the bar entirely:** since `survival@415` is
level with `territory@1000` (+7 ±10 over 2,000 boards), `chamber` does not have to beat `territory` by
~200 Elo — it has to beat `survival` by more than a tenth of a leaf of iterations, roughly **20 Elo**.

**Half of #5's stated framing is redundant.** `TempoOwnership` already answers *who arrives first* per
square at half-step resolution, finer than any chamber can. Only *is it contested* is genuinely a
chamber-level question — and only because a wall square and an unreached square are indistinguishable
from the owner array alone.

**Structure is non-trivial in 52% of positions** (>1 chamber) with the seal term firing in 14%. Not
P2's 0.19%. And where a region *is* one chamber the reading degenerates to `SurvivalEval`, the
strongest shipped leaf — not to `territory`.

**A gap P4 left, blocking the tuning:** `spsaOf`/`tuneOf` took a bare **slug**, so `spsa
puct:eval=chamber` failed outright, and `SpsaCommand.settings` wrote only the *searched* knobs — leaving
`eval` at its default, where the weights being tuned do nothing at all. **Fixed:** both commands now
take a full entrant spec whose knobs are pinned into both arms, the confirming run's *baseline as well
as its candidate*, and the pasteable re-run.

### The result — and the ablation lesson that changes how a tuned point is read

**`eval=chamber` is +85 ±32 Elo over `survival` for +9% cost**, four times the ~20 Elo bar, and tops
the field at 350 against `survival`'s 303 and `territory`'s 259 over 4,200 matches (3,441 distinct,
worst pairing 120/200 — contested, not saturated). All 73 losses `TRAPPED` at 68% fill: the same
endgame, entered from a better position more often.

**The `seal` term is the entire finding.** Per-weight ablation, all against `eval=survival`:

| term | worth |
|---|---|
| seal alone (`sealPenalty` 0.55) | **+37 ±20** over 400 boards |
| frontier alone | UNDECIDED, +9 ±9 over 2,000 — a sharpener, not a term that pays |
| parity relaxation alone | **NO BETTER, −37 ±23** |

**Ground truth #1 has now lost the same bet twice.** "`FillableSpace`'s parity premise is false under
the shipped rules" is true as *physics* and worth nothing as an *evaluation*. `HorizonEval` relaxed it
by argument and lost 185 Elo; SPSA relaxed it by search and the ablation says the move buys nothing
(+85 with the cap **on** against +69 with it off). Workstream #4 and the parity third of #5 were the
same bet. Stop making it.

**Workstream #5's framing scored 1-for-3.** *Who arrives first* was already redundant, *parity* is
worth nothing, *contested* is worth ~nothing alone — and **`seal`, the one question no other leaf in
the box could ask, is the whole gain.**

**Qualifying P4 for P6 and P7: a confirming run is necessary and not sufficient above one dimension.**
This sweep's confirmation passed at **+54 Elo over 400 fresh boards while one of its three coordinates
was pure drift**, and only a per-coordinate ablation caught it. Read a confirmed multi-weight SPSA
point as *"this point is better"* — **never** as *"each of these weights is right"*. Ablate before you
adopt.

**Workflow hazard:** a background shell reaped at ~60 minutes lost its piped stdout while the Gradle
daemon ran on to completion. `spsa`'s journal resume recovered the run for the cost of re-running only
the confirmation. **Redirect to a file rather than piping for anything over an hour.**

### What P5's second half found — read this before trusting any `ab` again

**The instrument correction, which supersedes P5a's "ablate before you adopt".** A per-coordinate
ablation **run as `ab`** is exactly the "ordering built out of one row" that protocol rule 3 already
forbids. P5b's came out **intransitive and put the wrong sign on the coordinate the field says carries
the point**: `priorTail=0.8` beat baseline by +250, `0.4` beat `0.8` by +66, and `0.4` *lost* to
baseline by −35. **The instrument is one field containing every ablation, rated together.** Ablate —
but ablate in a field.

**And this re-inverts P2's correction.** P2 concluded `ab` was the sharper tool because its fields were
saturated. Here the pairings were **contested** and `ab` was still wrong — because the knob changes how
`puct` plays *itself*, so a mirrored head-to-head amplifies a style clash into a strength claim. `rate`
printed the tell: `priorTail=0.8` scored 84% off the baseline where its own rating expects 60%. The
durable rule is not "prefer `ab`" or "prefer the field" but: **a head-to-head between two settings of
the same bot measures a style match-up, and only a common field converts that into strength.**

**`spsa`'s confirming run inherits the blind spot** — it *is* an `ab`. P5b's passed at +65 with the
right sign by luck, while the same run printed *"1217 of 2400 boards split exactly"*: the warning
firing. Confirmations remain necessary; they are not a field.

**SW-02 did not need relaxing after all.** Branching ≤3 means at most three numbers to exponentiate, so
`portableExp` (`2^k · exp(r)`, fdlibm's split `ln 2`, 15-term Taylor, `+ − * /` and integer bit ops
only) keeps `puct` in the cross-target golden set. It is pinned by `toRawBits()` **in real Chrome**,
which is stronger evidence than a JVM-only test, and that is now the documented bar in SW-02 for the
next transcendental.

**Result: a +103 Elo recommendation, deliberately not adopted.** `puct:eval=chamber,priorPinch=0.8,
priorTail=0.8,priorTemperature=0.9` rates +103 over `eval=chamber` with disjoint intervals for 1-2%
cost. All five knobs ship as no-ops because the measurement sits at `eval=chamber` rather than the
shipped `eval=territory`, and moving a default moves `GoldenMoveStreamTest`'s `puct` hash *and*
`BotLadderTest`'s thresholds. **The temperature is worth nothing alone (49 vs a 43 baseline) and +60 on
top of the pair** — two large weights in a *proportional* prior clip against the score floor, and the
softmax is what makes them usable together. `priorWall` never moved off its start and has no verdict.

**Two features were not built, for a stated reason:** a true articulation test and territory-delta both
need a flood or an ownership sweep **per candidate per expansion** — `ChamberEval`'s own work done three
times per iteration. `priorPinch`'s 8-ring is the local form of the same question for four extra board
reads.

**A cost confound worth knowing:** a field's `µs/turn` column ordered seven entrants almost exactly by
rating (+13% across the ladder). Stronger bot → longer game → fuller board → dearer leaf. It is not a
cost measurement; only paired `time` runs are.

**`puct` now declares exactly `BotKnob.MAX_PER_BOT` (16) knobs.** The next one needs that `:bot-api`
constant raised, and `ReplayCodec` reads the same one. Pinned by a `ShippedBotsTest` case so it fails
loudly rather than at a `require`. The ceiling is **per bot**, so a new bot starts fresh.

### What P6 actually found — exact search is viable, and the ladder is not a ladder

**Ground truth 3 overstates the cost of the clean option.** Replay-from-root is `ply` applies plus one
arena copy per leaf — not "~2·depth extra applies" — and measures at **1.03-1.09× the leaf it wraps**,
*less* on the bigger board. PUCT already pays one `copyFrom` plus a root descent per iteration, so it is
the same per-leaf shape. **There is no trade to agonise over: the doctrinally clean option is also the
cheap one**, and `turn.budget.tryConsume` never needs to be called from a bot.

**`Playout.undo` is correct in anger** — it had no production consumer until now. Verified across two
full self-play matches with a live-board hash check either side of every decision, plus mixed
advance/undo/reset-and-replay to 27 plies. One mechanism worth recording: `copyFrom` sets
`journalTop = 0`, so an `undo()` after a payment throws *"there is nothing to undo"* rather than
silently restoring a wrong position. **Ground truth 3's hazard fails fast**, which is what makes it safe
to build a depth-first search on.

**Depth: 11.2 plies mean on a 12×12 at budget 1000** (min 8, max 16), 11.6 on a 20×20. The agenda's
"~11 plies at best" was right as a number and wrong as a ceiling — 11 is *typical*. At budget 100 the
mean is 6.0, so **10× the evaluations buys 5 more plies**.

**`values[me] − values[them]` is not a like-for-like comparison, and the agenda understates why.** Terms
antisymmetric between two snakes (territory share, separated margin) **double** in the difference; terms
a snake owns alone (mobility, seal, trap penalty) do not — so the effective weighting shifts ~2× toward
territory relative to the share `ChamberEval`'s weights were swept for. And each slot's value is
`coerceIn(0,1)` *before* the subtraction, so a position far past decided reads identically to one barely
decided.

**The headline: a genuine intransitive cycle.** At an equal leaf, equal allowance and +9% clock,
`alphabeta` **beats `puct:eval=chamber` head-to-head 108-92** while rating 62 below it in a broad field
— and *48 above* it in a narrower one, on disjoint intervals both times, with the pairing itself stable
(108-92, 77-73). The company moved, not the match-up. **A single Elo fitted over a cycle is
field-composition-dependent**, which is the sharpest possible illustration of the protocol's rule 3.
`alphabeta` is also the only entrant to take 200 of 200 from *both* `chase` and `pressure`.

**Depth pays at exactly the rate iterations do.** Tripling the allowance is worth +101 Elo to
`alphabeta` and +92 to PUCT; neither saturates. Eleven plies makes the bot tactically flawless against
reactive opponents while leaving the other ninety plies to the same leaf guess PUCT makes. What exact
search does not buy here is a better long-run plan.

**A real gap in the suite: `BotContractTest` seated at most 2 snakes**, where CLAUDE.md and this
document both read as though it covered 3- and 4-snake matches. `AlphaBetaBotTest` carries that
coverage for one bot. P7 found the sibling gap: it never seated `puct` at a non-default `eval` either,
so every `eval` value was covered by `PuctBotTest` or nowhere.

**Both are closed, and closing them found nothing.** The suite sweeps every registry entry at every
value of every `BotKnob.Choice` it declares — eighteen settings against ten entries — across one to
four seats, which `docs/Bots.md` now states. A wider throwaway probe over the same eighteen at three
and four seats, five geometries to 20×20, two seeds and six allowances from zero to sixty (2,160
matches) turned up no illegal move, no forfeit, no overspend and no throw.

**The bots were already right, and the reason is that nothing here was ever written as a duel.**
`nearestOpponent` scans `0 until snakeCount` and reads liveness off the board, `PressureBot` means
over the living heads, `UctTree` credits per actor rather than negamax, `PositionFeatures` summarises
the field as a strongest challenger, and every search buffer is sized from `opponentCount + 1`. Note
also that the gap was narrower than the P6 note above reads: `uct` and `puct` each already carried a
three-way case of their own (`UctBotTest`, `PuctBotTest`), so what was genuinely unpinned was the
reactive bots, `flat-monte-carlo`, `burninhell`, every non-default `eval` and **every four-seat case**.

The suite costs 9.5s against 0.6s, about +8% on `:bots`. `alphabeta` also joined
`GoldenMoveStreamTest`'s cross-target set, verified in real Chrome, which leaves no registry entry
without a case there.

### What P7 actually found — the ceiling is the features, and it is measurable

**`eval=learned`: 25 shares/margins/flags → 16 softsign → logistic, 433 weights, +29 Elo over
`eval=chamber`** on disjoint intervals over 10,500 matches (6,455 distinct), better from **both** seats.
Net of the ~23 Elo its clock costs at P3's exchange rate, that is **level to slightly ahead per
millisecond** — a fitted model *matches and edges* the best hand-written leaf rather than replacing it.

**The diagnosis is unusually clean: train loss == holdout loss to five places (0.56825 / 0.56824).**
No overfitting at all; a hidden layer buys 0.023 and saturates at 16 units; 60 epochs equals 30. **The
fit is bounded by the features, not by capacity, data or optimisation.** Named follow-ups nobody has
tried: a tempo margin off `TempoOwnership.distanceTo`, an articulation count, the second-best chamber's
worth, and a region's raw colour imbalance separate from the parity cap.

**Skew is prevented structurally, not by discipline.** `PositionFeatures` is the one `public` definition
and `:lab` imports it; `LearnedNet.forward` *is* the trainer's forward pass, with `ValueFit` writing only
the backward pass derived from the layout accessors; and log-loss over the logistic gives `dL/dz = p − y`,
so the trainer needs no derivative of the squash and `portableExp` cannot diverge from `kotlin.math.exp`.
Quantisation is *measured* — the decoded literal re-scores identically on the holdout.

**Three corrections to finding 6.** (a) **"A training corpus needs `--replays all`" does not apply at
12×12** — there are *zero* drawn matches in ~90k logged games, so `DECISIVE` discards nothing. The
corpus was regenerated from 22,452 existing logged matches; nothing had to be generated. (b) Weights are
a non-issue for *size*, but the **code** constraint bites a second way: a 2,644-character single-line
literal fails ktlint's 120-column gate and nothing can auto-fix it — concatenated chunks fold back to
one data segment. (c) **"The forward pass is lost in the noise" is false**: 400 multiply-adds plus ~30
divisions is 11–16% of an evaluation, because an evaluation here is under four microseconds. A learned
leaf is not free.

**A latent hang — since fixed at the source.** `Match.playback` could spin, because a scripted stand-in
that runs out of moves *parks* (`AwaitingInput`) rather than forfeiting, so a caller looping on
`outcome == null` never returned on a truncated recording. The **first** park is unchanged — it is how
a partial replay says "this is the end of what was recorded" — but a **second** `step()` now throws,
naming the recording and the turn it ran out at. `Match.playbackExhausted` lets a transport ask without
stepping, and `:ui`'s `StepOnce` (the one live path that would have stepped past the park) is guarded
by it.

### What P8 actually found — a structural null, and the first map of where Elo goes

**RAVE cannot fire here, and the reason is structural rather than empirical.** Tree-descent AMAF is the
only source available, since `puct` has no rollouts. Measured coverage: 89.6% of scored edges carry AMAF
— but **0.0% of *unvisited* children do, which is the entire mechanism RAVE exists for.** This search
opens one node per iteration and appraises it immediately, so a fresh node's subtree is empty and its
AMAF can only be filled by *later* descents through it: every edge's first real visit strictly precedes
any AMAF evidence about it. What runs instead is a 42% blend applied to edges already carrying 64 real
visits. The field is **monotone in how much AMAF is believed** (`rave=50` 137, `rave=200` 105,
`rave=1000` 24) with the control at 133 — the limit of small `rave` *is* the knob off, so there is no
interior optimum to find and no sweep worth running.

**Both halves of workstream #3's row are wrong.** Its risk line blames RAVE's move-independence
assumption; the real obstacle is visit ordering. And **progressive bias is already shipped** — Chaslot's
`f(s,a)/(n+1)` *is* PUCT's `c·P·sqrt(N)/(1+n)`. The bot lacking it is `uct`, which has no prior to decay.

**Hosting it on `uct` was argued and not built**, on a structural claim worth recording: a **four-symbol
move alphabet saturates in a long simulation**. Over a ~100-move random rollout every snake plays every
direction, every AMAF mean converges, and the statistic discriminates nothing. `puct`'s ~7-ply
rollout-free descent is the only regime where direction-AMAF separates moves at all.

**Ground truth 5's conservative predicate is correct and *vacuous at two snakes*.** Passable means the
rectangle minus **dead** bodies — and a 2-snake match ends at the first death, so there is never a
corpse. Proven structurally, measured at 0 of ~200k positions, pinned by a test. It becomes a real
question only with a third snake seated.

**Ground truth 5 quantified for the first time under the shipped rules:** first separation at move 66,
the one that *holds* at move 136 of 165, and **81% of separated matches came apart and rejoined at least
once**. `isolated` is a statement about *this move*, four times in five.

**The first map of where the strongest bot's Elo goes.** Of `eval=learned`'s 420 losses: **67% were
already behind on room when the board split**, 21% were ahead and lost the fill, 8% never split. Against
`eval=chamber`: learned enters **64%** of races ahead (chamber 54%) but converts only **87%** (chamber
93%). **The fitted leaf is better at contact and worse at converting** — which bounds a phase-dispatch
portfolio at **~3.7 points of score ≈ 25 Elo**, the only number anyone has put on #10. Stated confound:
a more conservative leaf may enter races *further* ahead, and nothing here separates lead size from fill
quality.

**Shipped as `:lab phases` + `report/Separation.kt`, not as a bot** — the agenda said #10's value was
diagnostic and the measurement agreed.

**P5b's cost confound does not generalise:** here the *weakest* entrant had the *highest* µs/turn. A
field's `µs/turn` column is unreliable in both directions; only paired `time` runs measure cost.

---

## Open at the close

What this agenda finished without settling. Each is stated as the *next* thing someone does about it,
so a later agenda can lift a line and start.

**Three shipping decisions, all the user's.** Nothing in eight phases became a default, deliberately:
moving one moves `GoldenMoveStreamTest`'s hash and `BotLadderTest`'s thresholds, which is a release
decision rather than a measurement.

| Decision | What is measured | What is not |
|---|---|---|
| `puct`'s default `eval` — `territory` today, `chamber` +85 and `learned` +29 over that | Each against the incumbent, in a field, on disjoint intervals | Either against the *shipped* `territory` at the *shipped* budget, composed with the prior |
| `MovePrior`'s five knobs, all shipping as no-ops | `priorPinch=0.8,priorTail=0.8,priorTemperature=0.9` rates **+103** on top of `eval=chamber` for 1-2% cost | The same weights on top of `eval=learned`, or at `eval=territory`. `priorWall` never moved and has **no verdict** |
| `MatchSetup.DEFAULT_BUDGET_PER_TURN`, left at 1,000 | The ~8 ms slice now affords **≈1,180** after P3, up from ≈590 | Whether the ladder thresholds and the human-vs-bot feel survive the raise |

**The composed configuration is unmeasured and is plausibly the strongest thing in the tree.**
`eval=learned` + the tuned prior + the raised budget have each been measured *alone*, against
different baselines, in different fields. Nobody has rated them together. P5b's rule says the way to
do it is one field carrying every combination — `learned` alone, `chamber` alone, `chamber`+prior,
`learned`+prior, `alphabeta`, `uct` and the reactive tail — rated in one fit, with cost taken by
paired `time` runs and never off the field's `µs/turn` column. **That is the run the three decisions
above are waiting on.**

**Root-proven-lost early exit (from P2).** `chooseMove` stops searching when the root is proven,
including proven **lost** — but a line lost against max^n is not lost against an opponent who may err.
Not a one-liner: selection also skips proven children, so an all-losing root has nothing left to
descend into. Both ends change together, and the phase that does it should re-measure P2's sign flip
(+33 ±19 on 12×12, −41 ±25 on 20×20) rather than inherit it.

**Four features nobody has tried, named by P7's residual.** Train loss equals holdout loss to five
places, so `eval=learned` is bounded by its 25 features and not by its capacity: a tempo margin off
`TempoOwnership.distanceTo`, an articulation count, the second-best chamber's worth, and a region's
raw colour imbalance kept separate from the parity cap. This is the cheapest known Elo in the tree
*if* the diagnosis holds — and the diagnosis is a measurement, not a hope.

**One instrument gap.** The `puct territory, JVM` column in `MatchSetup.DEFAULT_BUDGET_PER_TURN`'s
table is pre-bitboard by ~2.13×. Re-deriving it needs P3's six-seeds-interleaved-with-a-control
protocol, not a single `time` run, so the KDoc dates the figure and points at `EvaluationCost`
instead.
