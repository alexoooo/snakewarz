# Research agenda — 2026-07-30

Eight workstreams over eight phases. The goal is to **fix the policy this repository actually ships,
give the board a shape, and let a bot spend its allowance where the game is decided** — three
directions that have one thing in common: each is a premise change rather than a tuning run, and each
invalidates measurements taken before it.

Its predecessor is [`2026-07-29_Research-Agenda.md`](2026-07-29_Research-Agenda.md), closed, and its
predecessor's predecessor is [`2026-07-28_Research-Agenda.md`](2026-07-28_Research-Agenda.md). Neither
is edited — a closed agenda records what was believed at the time. Where this one corrects them, the
correction is [below](#ground-truth) with a pointer to the line it corrects.

How a phase is run, what an agent is told, and the order the instruments go in are all in
[`../Research-Process.md`](../Research-Process.md). This document is the *what*.

> ## Read first: parts of this were built rather than researched
>
> **This agenda is not edited and is not the current state of the tree.** It records what was believed
> on 2026-07-30. Some of it has since been *delivered as engineering* by
> [`../plans/README.md`](../plans/README.md) — Release 2, eighteen sessions — which is a different
> route to the same ground and leaves parts of the text below describing work that is done.
>
> | here | delivered by |
> |---|---|
> | **P3** — the header change and obstacles | whole. `MatchSetup.walls`, `ReplayCodec` v3 with a raw wall bitmap under flags bit 1, `Occupancy.wall`, and the differential test P3 asks for — `WallNeutralityTest` in `:core` and `:match`, with `SHIPPED_PAYLOAD` untouched |
> | **P4** — fair maps | the *generator* half. `match/map/` ships eight ρ-symmetric shapes with connectivity asserted at generation; [`../Maps.md`](../Maps.md) is the catalogue. Fairness came out as **construction**, not as the measurement question this agenda framed it as |
> | **P4** — does the ladder survive a map | answered, and the answer was **no**. See below |
> | **P7** — `learned` needs a refit | diagnosed, not done. The features were moved onto `BoardView.openCount` so a map leaves every reading in range; the refit and its instrument are still open, and `LearnedEval`'s KDoc states the three clauses precisely |
> | **P7** — separation, parity | untouched |
> | P1, P2, P5, P6, P8 | untouched |
>
> **Three of the ground truths below were corrected in the doing.** Each is a case of a written
> description being read instead of the code:
>
> - **Ground truth 6 overstates the damage.** It lists `TerritoryEval` among the sites reading a share
>   against `playableCount`. It does not — it normalises by `totalOwned`, the ground the sweep actually
>   handed out, which a map shrinks correctly. **It needed no change at all.**
> - **`TempoOwnership` was a KDoc sentence, not a computation.** The same list names it as a second
>   site. What it had was a *comment* saying its walkable count is read against `Grid.playableCount`;
>   the reading is the caller's, and the fix was one sentence.
> - **Graph-distance spawn placement would have broken every existing three-seat replay.** P3 says
>   `mostDistantSpawns` "has to pick *reachable* squares that are far apart in the graph rather than in
>   the plane". The first half was done; the second was not, and deliberately. Graph distance on a
>   wall-free rectangle is **Manhattan**, where the shipped metric is Euclidean — a different argmin,
>   so switching would have moved seat 3 and beyond *on an empty board*, invalidating every three-seat
>   replay header and every empty-board measurement already taken. The metric stayed Euclidean and only
>   a reachability **filter** was added. `mostDistantSpawns`' KDoc carries the escalation and the
>   condition it becomes available under.
>
> **And one thing this agenda predicted, correctly and larger than it guessed.** P4 asks whether the
> ladder survives a map and says an inversion should be recorded rather than treated as a defect. It
> does not survive: on `cross` the top pair inverts and the field compresses from 979 Elo to 479, and
> on `double-spiral` at 16x16 a quarter of an allowance beats the full one 77–23 where it loses 23–77
> on a bare board. **A map changes which bot is stronger, not merely by how much.** That result is
> live rather than historical, so it lives in
> [`../Workflow.md`](../Workflow.md#a-map-is-a-different-game-not-a-harder-one) where somebody hits it
> before running a batch — not here.

## Status

| Phase | Workstream | State |
|---|---|---|
| P1 | See the models | planned |
| P2 | Fix the policy | planned |
| P3 | The header change, and obstacles | planned |
| P4 | Fair maps, and does the ladder survive one | planned |
| P5 | A budget that carries | planned |
| P6 | Board-conditioned defaults | planned |
| P7 | What a map changes underneath | planned |
| P8 | A policy head, or simultaneity | planned |

The coordinator owns this table and the "What P*n* actually found" sections. Agents report; they do
not edit either.

---

## Ground truth

Read from the code on 2026-07-30, not from the docs' description of it. Getting any of these wrong
changes which phase is risky, and three of them were established by probe rather than by reading.

### 1. The shipped move prior is one feature, and its measured replacement was never adopted

`PuctBot.kt:661` sets `priorLiberty = 0.5`. `PRIOR_PINCH` (`:707`), `PRIOR_WALL` (`:746`),
`PRIOR_TAIL` (`:803`) and `PRIOR_TEMPERATURE` (`:855`) are all **`0.0`**. So `P(s,a)` is
`0.5 × (free orthogonal neighbours of the destination)`, normalised proportionally, and nothing else.

`MovePrior`'s own KDoc carries the field that measured the alternative: the swept point
`priorPinch=0.8, priorTail=0.8, priorTemperature=0.9` rates **146 (+124..+168)** where the shipped
prior rates **43**, with a confirming run at **+65 over 260 fresh boards**. The last agenda listed the
prior adoption in P3 and P3 adopted the eval instead. **The number is banked and unshipped**, and every
prior measurement in the repository is therefore baselined against the ablated worst case.

### 2. Ties in the prior are broken by enum declaration order, and the code knows

`Direction.kt:13-16` declares **NORTH, SOUTH, EAST, WEST**. `DirectionSet.nth` walks by
`countTrailingZeroBits`, so it enumerates in ordinal order, and `PuctTree.selectPuct` keeps a child
only on a strict `value > best`. The comment at `PuctTree.kt:232-233` states the consequence outright:

> Floored at one: `sqrt(0)` would zero the exploration term the first time a node is selected from,
> **leaving every child on exactly `firstPlay` and the choice made by ordinal.**

With a one-feature prior, *every* move into open space reads three liberties, so the scores tie
exactly and the ordinal decides. Both vertical directions sort first. That is the tall serpentine a
zero-ply `puct` draws, and it is an enum declaration order reaching the board.

**This is a defect independent of which features the prior has** — it also sets first-visit order
inside a real search, where two children carrying equal `cpuct·P` are visited north-first.

### 3. `puct:budget=1` is exactly the prior's argmax, and the `eval` knob is dead there

`PuctTree.kt:234` floors `sqrt(parentVisits)` at one, so on the first selection every child sits on
`exploit = firstPlay = 0.5` and `explore = cpuct · P(a)`. The argmax of that is the argmax of `P`.

Verified by probe: **858 decisions across twelve self-play lines and two prior configurations, zero
mismatches** against `MovePrior`'s own argmax.

And at that allowance the leaf runs once, is recorded, and is never read — `bestMoveAtRoot` has
exactly one visited child. A probe over `HeadlessMatch` move streams at `budgetPerTurn = 1` found:

| swept | distinct move streams |
|---|---|
| six `eval` values | **1 of 6** (100 moves) |
| three `priorTemperature` values | **1 of 3** |
| prior *weights* | 3 of 3 — 100 / 85 / 87 moves |

`priorTemperature` is dead for the structural reason rather than the accidental one: both
normalisations are monotone in the score, so they share an argmax. Only the weights can move a
zero-ply game.

**So `puct:budget=1` is a free, exact, already-shipped zero-ply policy bot**, reachable from the
browser by typing 1 into Budget and from `:lab` as `puct:budget=1`. `budget=0` is **not** this: it
falls through to `unbudgeted = SpaceBot` (`PuctBot.kt:181`).

### 4. `alphabeta` at a small allowance is one-ply greedy, and the allowance is an accident

Probed on a two-snake 12×12, comparing every decision against a hand-computed one-ply argmax over the
paranoid margin, recording `AlphaBetaBot.depthReached`:

| allowance | matched | depths reached |
|---|---|---|
| 1 | 210/429 | `{0: 429}` |
| 2 | 366/429 | `{0: 300, 1: 129}` |
| 3 | 420/429 | `{0: 48, 1: 379, 2: 2}` |
| **4** | **429/429** | `{1: 420, 2: 9}` |
| 6 | 427/429 | `{1: 297, 2: 132}` |
| 12 | 403/429 | `{1: 8, 2: 364, 3: 55, 4: 2}` |

Three caveats, all of which a phase quoting this must carry. The scalar is `values[me] − max(others)`
and **not** raw `values[me]`, which `LeafEval`'s KDoc argues is a materially different quantity —
roughly a doubling of the territory term's weight. The leaf is judged after **our** move only, because
the engine advances one snake at a time. And the correct allowance tracks the legal-move count, so
**4 is a property of the iterative-deepening loop on this board and not a switch** — it will not hold
at three seats or on a different geometry.

`puct` at a small allowance does **not** substitute: after the first child is evaluated,
`Q + cpuct·P/2` can beat an unvisited `0.5 + cpuct·P`, so PUCT is not guaranteed to try each root
move once.

### 5. The forced-move saving has nowhere to go

`PuctBot.kt:159-160`:

```kotlin
// Searching a forced move spends an allowance that a real choice will want later.
legal.singleOrNull()?.let { return Decision.Move(it) }
```

There is no later. `Match.kt:140` calls `budget.reset()` at the top of every turn, so the allowance is
per-turn and the saving is discarded rather than banked. **The comment states an intent the
architecture cannot express.**

Everything a bank needs is already true: the accounting is in evaluations rather than milliseconds
(`Budget`'s KDoc), so a carried allowance stays deterministic and clock-free and does not touch SW-01
at all. What it touches is fairness — "equal allowance" becomes a statement about a match rather than
about a turn.

### 6. Interior walls are nearly free on the hot path, and break six things nobody would look at

`Occupancy` already carries a `WALL` code (`-1`, `Occupancy.kt:125`) for the padded border ring, and
`freeNeighbors` tests `== EMPTY` — so an interior wall is **already** not-free, with no new branch on
the hottest path in the program. `copyFrom` copies the whole owner array, so obstacles ride into every
search arena for nothing. `Board.copy()` is how a `BoardScratch` arena is built, so the search side
needs no change at all.

What does break:

| Site | What happens |
|---|---|
| `Board.kt:131-132` — `reset()` calls `occupancy.clear()` | fills the interior `EMPTY` and **erases the map**. Its only callers today are tests, which is what makes it the one that gets missed |
| `Occupancy.kt:46` | walls are **deliberately excluded from the Zobrist hash**, correct while they never vary. Two different maps now hash identically |
| `Grid.playableCount = rows * cols` | `PositionFeatures.kt:203` computes `fill = 1.0 - walkable / playableCount`, so on a map with K obstacles `fill` starts at `K/playable` and never reaches 0. **Every learned feature shifts and `LearnedWeights` needs a refit** |
| `mostDistantSpawns.kt:33` | indexes `grid.playableCount - 1` as a corner. A spawn can land on an obstacle, or inside a pocket the map sealed |
| `TerritoryEval`, `TempoOwnership` | both read a share against `playableCount`; see their KDocs for which figures were derived from it |
| `ReplayCodec.kt:28, 57-60` | **already has the extension point** — v1 unconfigured, v2 configured, flags bit 0 — so a map is a flag bit and a block, not a redesign |

And one asset: `ChamberTreeTest.kt:244` and `SurvivalHorizonTest.kt:191` **already generate randomly
walled boards** for their brute-force oracles. The chamber and horizon primitives are proven on walled
regions today; what is not proven is everything that normalises by the board's area.

### 7. Obstacles make permanent separation a real predicate at two seats

`Separation`'s KDoc argues the conservative predicate is vacuous at two snakes on two legs: there are
never dead bodies while a two-snake match runs, and *"a rectangle minus its wall ring is connected."*

**A map breaks the second leg.** `Separation.permanent` becomes a real question at two seats, and
`SeparationTest` currently pins the vacuity — so that test is a premise, not an assertion, the moment
P3 lands.

This gives back exactly one of the four grounds on which the last agenda killed the separated-endgame
dispatch rule. **The other three still stand** and are restated in
[Considered and not ranked](#considered-and-not-ranked); nobody should read this item as reopening
that idea by itself.

### 8. Board-conditioned defaults are blocked at three contract sites, and a number is waiting

The mechanism is free — a `Grid` is available at bot construction and costs nothing per turn. The
contract is what forbids it: `entrantOf` drops knobs *"sitting at the value the registry declares
today"*, `SlotForm` renders `knob.defaultText`, and `Param.isDefault` is the codec's notion of stock.
A board-dependent effective default makes all three describe a bot that is not playing.

Two independent arguments now exist and the second brought a number. From the closed agenda's *Open at
the close*, `rolloutDepth=25` at equal clock:

| | 8×8 | 12×12 | 20×20 |
|---|---|---|---|
| value at equal clock | **−4** | **+13** | **+78** |

Monotone in board size, crossing zero between the two smallest boards. And separately,
`alphabeta:eval=territory` rates **+131** above bare `puct` at 8×8 while **losing** the head-to-head
**89–111**, where at 12×12 it wins 70.5%.

### 9. `puct` has three knob slots left, and the ceiling is a decoder contract

`PuctBot.KNOBS` declares **17**; `BotKnob.MAX_PER_BOT` is **20**. `ShippedBotsTest` pins the count so
an 18th fails loudly. The ceiling is read by `ReplayCodec` when it decodes a slot, so raising it is a
decision about what payload a decoder accepts rather than a number to nudge —
`BotKnob.kt:273-274`'s *"a bot that wants more than this wants a second bot"* is the standing answer.

**This agenda proposes to spend two of the three**, in P5. Whatever else lands must budget for that.

### 10. Neither model is observable from anywhere a person can see it

`MovePrior` and `LeafEval` are `internal` to `:bots`, and `:ui → :bots` is a forbidden edge enforced by
`checkModulePurity`. Grepping `ui/src`, `lab/src` and `app/src` finds no prior or value exposure of any
kind. So the diagnostic channel is either a `:lab` command — cheap, no purity problem — or a
`:bot-api` sink the driver reads, which is what a board overlay would need.

---

## Decisions this agenda needs from a person

Not made yet. Each names the phase it blocks, so the coordinator brings the number rather than
guessing.

**Adopting the swept prior — blocks P2.** [`../Research-Process.md`](../Research-Process.md) says a
phase never moves a default. The cost, stated correctly: it moves `puct`'s golden **and**
`alphabeta`'s, because `AlphaBetaBot.kt:240-247` reads `PuctBot.PRIOR_*.default` rather than its own
params — both in the cross-target set, so it needs a real-Chrome re-verification. And above zero
temperature `alphabeta` starts calling `portableExp` at every node, which **rewrites that golden case's
own SW-02 justification** rather than merely re-pinning it. `BotLadderTest` now seats `puct` and
`alphabeta`, so their rungs move too, and `puct` over `uct` is the narrowest rung on the ladder.

**Whether a map is a game feature or a research fixture — blocks P3's scope.** As a game feature it
needs a map identity in the replay URL, a picker in the chrome, and a fairness rule. As a research
fixture it needs none of those and is a `:lab` flag. The agenda below assumes the **game feature**,
because that is what was asked for; the cheaper reading would cut P3 roughly in half.

**Whether an equal-allowance field stays the unit — blocks P5.** A budget bank makes "1,000
evaluations a turn" into "1,000 × turns a match", and a bot that banks and then wins in fewer turns has
spent less in total. Either the field is equalised on the per-match total, or the bank is capped tightly
enough that the difference is inside the noise. **This has to be decided before the phase, not after
its first surprising number.**

---

## The workstreams

Ranked by expected Elo per session, then reordered for what makes later phases cheaper to *measure*
and for collisions at the registration site. Sizes are S/M/L in agent-sessions.

| # | Workstream | Thesis | Reuses | Size | Risk — *if this is a null, will I be able to tell?* |
|---|---|---|---|---|---|
| P1 | **See the models** | Nobody can judge a policy that cannot be read out, and the readout that matters is agreement with a deep search rather than Elo. Ground truth 3 makes the zero-ply bot free, so this is an instrument phase with no bot risk | `puct:budget=1`, the 22k-match corpus, `:lab`'s command shell | S | Yes — every deliverable is a rate |
| P2 | **Fix the policy** | The shipped prior is a single feature whose measured replacement is +100 rated and unadopted, *and* its ties are broken by enum order in both first-visit order and root selection. Those are two defects and only one of them is fixed by adopting | `MovePrior`, P1's agreement rate, the adoption ritual | M | Yes — P1's rate is the before/after |
| P3 | **The header change, and obstacles** | Maps, a per-match budget and board-conditioned defaults all touch `MatchSetup`, `ReplayCodec` and `SlotForm`. One codec bump serves three phases; three bumps is three migrations. And interior walls are nearly free where it matters and expensive in six places nobody would look | `Occupancy`'s WALL code, the v1/v2 flags mechanism, `ChamberTreeTest`'s walled fixtures | L | N/A — execution, gated by a differential test |
| P4 | **Fair maps, and does the ladder survive one** | An asymmetric map turns seat advantage into map advantage and confounds every field run on it. Fairness is a prerequisite, not a rider. Then: every rung was certified on an empty rectangle | P3's primitive, `--openings mirrored`, `play` + `rate` | M code / L batch | Yes — seat win-rate on a null map set is the probe |
| P5 | **A budget that carries** | `PuctBot.kt:159` says a saved allowance is one *"a real choice will want later"* and `Match.kt:140` discards it. Start with the stopping rule that provably cannot change a move | `Budget`, `Scratch`, P3's header, two of three free knob slots | M | Yes — utilisation is a rate, and the first rule is a pure saving |
| P6 | **Board-conditioned defaults** | Twice argued, once with a number, blocked at three contract sites rather than by any mechanism | P3's contract work, the `rolloutDepth` table | M | Yes — the +78/+13/−4 row is the check |
| P7 | **What a map changes underneath** | `fill` is normalised by `rows × cols`, separation stops being vacuous, and parity stops being a property of the geometry. Three premise changes with one cause | P3, `train`, `PositionFeatures`, `Separation`, `PhasesCommand` | M | Yes — loss on a map corpus, firing rate for separation |
| P8 | **A policy head, or simultaneity** | Either AlphaZero's other half fitted on the existing corpus, or the largest known model error in the repo. Pick with P1's number in hand | `LearnedNet`, `train`, `PositionFeatures` — or nothing, for the other one | L | Partly — see the phase |

**Why this order.** P1 first for the reason the last two agendas both gave about their own instrument
phase: it is the cheapest thing here and everything in P2 is unmeasurable without it. **P3 third and
not seventh** is the load-bearing ordering decision — it is the P3-bitboards lesson said out loud:
land the premise change early or every measurement taken before it is an empty-board measurement
somebody has to retake. P4 immediately after, because a map set nobody has checked for fairness
poisons every field that follows. P5 and P6 both need P3's header. P7 collects the three things P3
falsified. P8 last and **droppable** — it is the row with the most upside and the least certainty, and
therefore the natural passenger if the agenda runs long.

**Collisions.** P2, P5 and P6 all touch `PuctBot`'s knob list, and P5 spends two of the three
remaining slots (ground truth 9). P3, P5 and P6 all touch `MatchSetup` and `ReplayCodec`, which is
the whole argument for doing that surgery once in P3. P2, P3 and P7 all move goldens; P2's and P7's
are cross-target and need real Chrome.

---

### P1 — See the models

Two deliverables, no bot changes, and no default moves.

**The probe.** A `:lab` command that, for a given position or a replayed match, prints the prior vector
and the leaf-value vector per legal move — the numbers themselves, not the move chosen. `:lab` sees
`:bots` and `:match` together, so this costs no purity fight; a board overlay would need a `:bot-api`
sink and is **out of scope here** (ground truth 10). Take the position stream from a logged match so
the command is a diagnosis instrument and not a toy.

**The readout that matters.** Elo at budget 1 is a weak reading of a policy. The strong one is
**top-1 agreement**: how often does the prior's argmax equal the move a deep search actually played,
measured over the existing 22,452-match corpus? That number is comparable across prior settings,
across board sizes, and — after P3 — across maps. It is also the before/after P2 needs.

Report it split by game phase, because a policy that is right in the opening and wrong in the endgame
is a different problem from one that is uniformly weak, and the shipped prior's degeneracy (ground
truth 2) predicts exactly the first shape.

> **The trap to state in the brief.** Agreement with a deep search is not correctness — the search is
> not an oracle, and at 8×8 the two searchers already disagree about each other's strength. Quote it
> as agreement, never as accuracy.

**Rider, one hour.** The tie rate. What fraction of root selections have two or more children within a
rounding error of each other, on the shipped prior and on the swept one? If it is 40% of openings,
P2's second half has a firing rate before it has a design.

### P2 — Fix the policy

Two defects, and adopting the swept prior fixes only one of them.

**The adoption.** The number is in ground truth 1 and the cost is in
[Decisions this agenda needs](#decisions-this-agenda-needs-from-a-person). The coordinator brings it;
the user confirms; the phase executes the ritual in [`../Bots.md`](../Bots.md). Re-verify in real
Chrome — this is the change that puts `portableExp` on `alphabeta`'s hot path.

**The tie-break.** Independent of which features the prior carries, and it survives adoption: even a
four-feature prior ties on a symmetric board. The candidates, cheapest first — order the legal set by
prior *then* by a deterministic function of the position rather than by ordinal; or add a
position-derived perturbation below the resolution of any real difference. **No RNG**: SW-01 wants the
stream reproducible, and `Occupancy.hash` is already an O(1)-maintained per-position `Long` that costs
nothing to read. Note it excludes walls (ground truth 6), which is harmless here and would not be if
anybody keyed a table on it.

Measure the tie-break **with the prior held at the shipped one-feature setting as well as at the
swept one**. The degenerate prior is where it should matter most, and if it does not matter there it
matters nowhere.

**What P1's agreement rate is for.** Both halves report it before and after. A policy change that
raises agreement and loses Elo is the interesting result, and it is the one the last agenda's
`horizon` finding says to expect at least once per agenda.

### P3 — The header change, and obstacles

The largest phase here and the one everything after it is priced against. Three parts, and the first
is what makes the other two cheap.

**One header change.** Bump `MatchSetup` and `ReplayCodec` once, for the map, and leave room for P5's
per-match budget and P6's board-conditioned marker. The codec already writes *"the oldest version that
can express the record"* (`ReplayCodec.kt:50`), so an empty map still encodes as v1 and every shared URL
in existence keeps its length. **A default match must produce a byte-identical payload** —
`ReplayCodecTest`'s `SHIPPED_PAYLOAD` is the assertion that says so, and it is deliberately historical,
so do not update it.

**The obstacle primitive.** Reuse `Occupancy`'s existing `WALL` code rather than adding a third
occupant class: `freeNeighbors` already excludes it and no search path changes at all. The work is the
six sites in ground truth 6 — `Board.reset()` re-stamping, the Zobrist decision, the `playableCount`
normalisations, spawn placement, and the codec. **Take the `playableCount` sites as a rename, not a
patch:** the quantity every eval wants is *playable squares that are not permanently wall*, and calling
it `playableCount` on a map is how a wrong `fill` survives review.

> **The differential test is what makes this safe.** A map with zero obstacles must play byte-identical
> move streams to today's engine, on every golden, on both targets. That is the "make the neutral
> setting reproduce the incumbent" rule from
> [`../Research-Process.md`](../Research-Process.md#1-one-variable-and-make-the-neutral-setting-reproduce-the-incumbent),
> and `ChamberEval` reproducing `SurvivalEval` bit-for-bit is the worked case to copy.

**Map generation.** Standard placements and a randomiser within parameters, per the request. The
parameters that matter are obstacle *count*, minimum *clearance* from a spawn, and a **connectivity
guarantee** — a map that seals a spawn into a pocket is a forfeit generator, and a forfeit is a defect
and never a result. Verify connectivity at generation, from each spawn, and fail loudly rather than
sampling until it passes.

Spawn placement is the part that will be underestimated. `mostDistantSpawns` picks corners by index
arithmetic on an empty rectangle; on a map it has to pick *reachable* squares that are far apart in
the graph rather than in the plane.

### P4 — Fair maps, and does the ladder survive one

**Fairness first, and it is a measurement question rather than an aesthetic one.** An asymmetric map
gives one seat more room, and a field run on it measures the map. Two available answers: constrain the
generator to rotational or mirror symmetry about the spawn pair, or lean on `--openings mirrored`,
which already replays each opening with the seats swapped. The second is cheaper and does not
constrain what a map can look like; the first is what a *game* wants, because a human on the wrong side
of an unfair map does not get a rematch.

**The probe before the field**: run a null-strength pairing — two identical entrants — over the
candidate map set and read the seat win-rate. It should sit on 50%. Anything else is the map set, and
no strength number taken on it means anything until it is fixed.

**Then the field.** Every ladder rung, on empty and on maps, rated in one fit per geometry. Board-size
intransitivity is already proven here — `alphabeta` and `puct` swap order between 8×8 and 12×12 — so
**map topology is a second axis of the same phenomenon and the prior should be that it also inverts
something.** State the field composition beside every rating; a single Elo fitted over an intransitive
cycle is field-composition-dependent.

Read the distinct-games line first. A fixed map plus fixed spawns plus two bots that draw no randomness
is four distinct games however many rounds are asked for.

### P5 — A budget that carries

Ships as knobs defaulting **off**, so the blast radius is zero and the phase can be judged on its
firing rates. It spends two of `puct`'s three remaining knob slots (ground truth 9), which is the
reason it is a phase and not a rider.

**Step 0, before any strength claim: the rule that cannot change a move.** Stop the search when
`visits[best] − visits[second] > remaining`, which is arithmetically incapable of changing which move
comes back. Report the **saving** — what fraction of the allowance goes unspent, split by game phase.
If it is 2%, there is no phase here and the answer costs an afternoon. If it is 30%, everything after
this has a budget to spend and a control that proves the plumbing is sound.

**Then the bank.** A match-level pool, a per-turn cap, and a decision about what "equal allowance"
means — see [Decisions this agenda needs](#decisions-this-agenda-needs-from-a-person). Determinism is
untouched: still counted in evaluations, still no clock below `:ui`.

**Then the trigger**, and this is the research question. Four candidates, all readable off `puct`'s own
tree with no additional evaluation:

- **Visit-distribution entropy at the root** — the information-theoretic form of "is this decided".
- **The best-vs-second gap**, which is the same thing without the arithmetic and is what step 0 already
  computes.
- **Best-move stability** — has the argmax changed in the last N iterations? Stockfish's answer, and
  the one that catches a position that *looks* settled and is not.
- **Prior entropy**, which is free and available at zero cost before a single iteration — and which
  ground truth 3 says is exactly what `puct:budget=1` reads. A trigger computable before spending
  anything is worth more than one that needs a search to justify a search.

Sweep at most two of them. **A trigger that fires on 3% of turns cannot move a match**, so every
candidate reports a firing rate before it reports a win rate, and the honest failure mode is a
mechanism that fires often and buys nothing — which the last agenda's RAVE row is the template for.

### P6 — Board-conditioned defaults

The mechanism is free; the contract is the phase. Three designs, and the choice is what a replay URL
and a settings form say afterwards:

1. **`BotKnob` gains a default that is a function of the grid.** Honest, and it makes `defaultText`
   unanswerable without a board — `SlotForm` renders that string today.
2. **A sentinel value meaning "choose for me"**, so the default stays a constant and the bot resolves
   it at construction. Costs one frozen `Choice` value per knob and keeps all three contract sites
   truthful, at the price of the *stock* bot being the one that varies.
3. **Nothing in the contract; the bot reads `setup.grid` and ignores the knob when it is at default.**
   Cheapest and the worst — it is precisely the case where all three sites describe a bot that is not
   playing.

The number to check against is the `rolloutDepth` row in ground truth 8, which is the only measured
instance anybody has. **Re-measure it rather than inheriting it**: it was taken before P3's obstacle
work and before P2's prior, and a knob tuned at one allowance is tuned at that allowance.

**The trap in the win.** The closed agenda already named it: `puct` over `uct` is the narrowest rung on
the ladder — 12 of 20 against a threshold of 11 — and `rolloutDepth` conditioning makes `uct`
stronger on large boards. A phase that adopts it on the strength of a 20×20 number and pushes a ladder
rung under its threshold has made the ladder red for a reason nobody will connect to this phase.

### P7 — What a map changes underneath

Three premise changes, one cause, one phase because they share a corpus and a map set.

**`learned` needs a refit, and a train/holdout gap cannot tell you so.** `fill` is normalised by
`rows × cols` (ground truth 6), so on a map every feature vector is shifted by a constant that depends
on the map. This is P4-of-the-last-agenda's lesson arriving with a concrete cause: **a held-out number
is an in-distribution reading and cannot see a transfer failure.** The instrument is a loss measured on
a map corpus by the weights fitted on an empty one, against the same weights refitted — and then a
field, because a loss improvement has been worth zero Elo here before.

**Separation stops being vacuous at two seats.** Ground truth 7. The work is a firing rate first:
how often does `Separation.permanent` actually hold on the P4 map set, and how early? The last agenda
measured `naive` first firing at move 66 with **81% of separated matches coming apart and rejoining**;
the conservative predicate on a map is a different question with a real answer. `SeparationTest`'s
vacuity assertion becomes a map-conditioned statement rather than a theorem.

> **This does not by itself reopen the separated-endgame dispatch rule.** Three of the four grounds
> that killed it stand: the split is still taken with hindsight and a bot still cannot know which one
> it is looking at; "ahead on room" is still a raw square count where squares and spendable moves come
> apart by 1.4–2×; and `SurvivalHorizon` is still loosest exactly where a region shatters. Anybody
> reviving it answers those three, in writing, first.

**Parity stops being a property of the geometry.** `ChamberEval.parityWeight` reads a colour imbalance
that an empty rectangle makes trivially predictable. An obstacle set changes each region's balance, and
snakes growing at half speed already made one canonical Tron parity bound worthless here — so the
prior is that this weight is either newly valuable or newly wrong, and it is cheap to find out which.

### P8 — A policy head, or simultaneity

Pick one, with P1's agreement rate in hand. Both are large; neither is safe.

**A learned policy head.** `PositionFeatures`, `LearnedNet`, `train` and a 22,452-match corpus all
exist, and this is the half of AlphaZero this repository does not have. Two things have to be said in
the brief or the phase wastes itself:

- **The corpus logs the move played, not the root visit counts.** So this is behaviour cloning of a
  search's argmax and not distribution matching, and its ceiling is the search it clones. If P1's
  agreement rate says the hand-written prior already agrees with a deep search 70% of the time, the
  headroom is 30 points of agreement and not a policy revolution.
- **It runs at every expansion, which is three times per iteration where the leaf runs once.**
  `MovePrior`'s KDoc prices that argument in both directions, and the ruinous case — the same readings
  inside a rollout at 1.4–1.6× — is what killed the last agenda's P6. A paired cost probe comes before
  a strength claim, not after.

**Or simultaneity.** The engine advances one snake at a time, so both searchers model a simultaneous
game as sequential and the seat that moves second is, inside the search, reacting to a move it could
not have seen. **This is the largest known model error in the repository and nobody has measured what
it costs.** The standard answer is a matrix game at the root with a mixed strategy, which needs a
per-slot RNG at decision time — allowed, since RNG is already injected and forked per slot, but it
turns the shipped bots' move stream stochastic and every golden with it.

The honest risk on the second one: it is the row most likely to produce a deep, correct finding and no
Elo. Rate it a success if it produces a number for the size of the error.

---

## Considered and not ranked

With the reason, so nobody re-derives it.

**`SequentialTest` seat-count awareness.** Carried from the closed agenda's *Open at the close*:
`configFor` hardcodes two contestants and `HEAD_TO_HEAD`, so `ab`, `tune` and `spsa` are unavailable at
three seats and every three-seat number in this repository is a field rating. Still the single change
that turns three-seat measurement from mapping into deciding. Not ranked because nothing in this
agenda is three-seat — but if P4 or P7 grows a third seat, this becomes a prerequisite rather than a
nicety.

**`freeSquaresOf`'s word-level free mask.** 13–14% of every `SpaceOwnership` sweep, per-cell, and the
largest speed item anybody has a number for. Not a phase because it wants doing **inside P3**: a map
changes what "free" means, and building the mask twice is the avoidable version of this work.

**An opening book.** Spawns are deterministic and boards are empty, so the first several plies come
from a handful of positions. Cheap to falsify before anyone builds anything — measure what fraction of
a match sits within six plies of the start. It gets *more* interesting after P3, since a book becomes
per-map, and *less* interesting after P4 if random maps are the default.

**A transposition table.** Carried, unchanged, and now with a second reason. `BoardView.hash` keys on
`(cell, owner)` with no tail and no body ordering, so two threadings of the same occupied set collide
structurally; the fix is a per-slot tail key plus a verification field. The cheap falsification comes
first: bodies make positions path-dependent, so true transpositions should be rare. **P3 adds a
third:** walls are excluded from the hash, so two maps hash identically, and anything keyed on it must
mix the map in.

**Root-proven-lost early exit.** Still open, still specified: `chooseMove` stops when the root is
proven including proven *lost*, and selection also skips proven children, so both ends change together.
Parked for whichever phase opens `PuctTree` — which is P2, so whoever takes P2 may find it in reach.
Re-measure the +33/−41 board-size sign flip rather than inheriting it.

**`alphabeta`'s move ordering.** The top rung has two knobs and orders by `MovePrior` at its
*defaults*, which ground truth 1 says is the degenerate prior — so **P2 improves this bot's ordering
for free and without touching it**, which is the reason not to rank a separate phase for killers,
history and aspiration windows until P2's number is in.

**A fifth prior reading: the escape route.** All four current readings judge the destination square.
None asks whether the move keeps a path home to the snake's own tail, which is what the game is
actually about; `priorTail` proxies it with a Manhattan step and nothing more. Not ranked because
`MovePrior`'s own KDoc prices the honest form — flood from each destination — at three sweeps per
iteration, which is the leaf's work done three times. Somebody who finds a *local* form of it, as
`cuts` is the local form of the articulation test, has a phase.

**A distribution-valued leaf.** `LeafEval`'s KDoc names this as the prerequisite for the max^n versus
paranoid backup question, which cannot be isolated with the leaves that exist because the difference of
two of these numbers is not an affine image of one of them. Unchanged, still true, still a seventh
frozen `eval` value nobody has measured.

**The reference machine, the `TimeCommand` minimum-of-passes, the leaf-pair tolerance, P7's cut 20×20
field, and the two `learned` fits in one field.** All carried unchanged from the closed agenda's *Open
at the close*, all still measurement hygiene rather than workstreams, all still worth an hour to
whoever has a quiet machine.

---

## The protocol every agent gets

The block from [`../Research-Process.md`](../Research-Process.md#the-brief-every-agent-gets), verbatim,
plus five lines this agenda earns:

```
- Name the baseline entrant explicitly in every entrant string, spec and all. After P2 a bare
  `puct` is a different bot from the one in every pre-P2 measurement, exactly as it became a
  different bot at P3 of the last agenda.
- After P3, say which map a number was taken on -- including "empty". A rating is conditioned
  on the field, the geometry AND the map.
- A firing rate or a saving comes before any strength claim. P5 and P7 are both phases where
  the mechanism can be structurally incapable of firing, which is an afternoon's answer.
- A map that seals a spawn is a forfeit generator, and a forfeit is a defect, never a result.
  Verify connectivity at generation and fail loudly.
- A train/holdout gap is an in-distribution reading and cannot see a transfer failure. P7 is
  a transfer question by construction; do not answer it with a held-out loss.
```

---

## Open at the close

*Written when the last phase lands. Appended to as phases produce decisions that belong to a person
rather than to a phase.*
