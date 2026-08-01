# Research agenda — 2026-08-01

Seven workstreams over seven phases. The goal is to **replace the Gauntlet's long easy opening with a
real progression, find useful strength between Chase and full search, and establish the strongest
deployable opponent on an empty 8x8**.

This is the first agenda written after walls became a shipped game mechanic. Its predecessor,
[`2026-07-30_Research-Agenda.md`](2026-07-30_Research-Agenda.md), is closed and remains a record of
what was believed before and during that change. This agenda supersedes its open work; it does not
edit the old one. How phases are run and written back is in
[`../Research-Process.md`](../Research-Process.md).

The scope is intentionally narrower than the predecessor's. It does not try to improve every search
component. It asks what the player-facing opponent ladder is missing, then spends the expensive work
on the one position where the answer matters most: the final boss.

## Product decisions already made

These are inputs, not hypotheses for a phase to revisit.

| Decision | Consequence for this agenda |
|---|---|
| Walls are part of the game | Name the map; never pool wall layouts |
| Use constant per-turn effort first | No bank, trigger, saved allowance or match-level pool |
| The reactive opponents are too easy | Keep the five easy ones as instruments, not Gauntlet levels |
| `chase` is the interesting reactive bot | Make it the intended first level and strength floor |
| The jump to search is too large | Seek no-tree and fixed shallow-search rungs |
| The final boss is an empty 8x8 | Map-general work and boss work use separate corpora and make separate claims |

"Constant per-turn" means a configuration receives the same evaluation limit on every turn. A forced
move or a proof may return early, but unused work is discarded. Different Gauntlet levels may have
different fixed limits; none may move effort from an easy turn to a later one.

## Status

| Phase | Workstream | State |
|---|---|---|
| P1 | The wall-aware baseline and complete 8x8 openings | complete |
| P2 | The strongest hand-written policy without a tree | complete |
| P3 | A learned policy without a tree | complete |
| P4 | The fixed mini-search bridge | complete |
| P5 | The empty-8x8 championship | complete |
| P6 | Solve what can be solved on empty 8x8 | complete |
| P7 | Rebuild and measure the Gauntlet | complete |

The coordinator owns this table and the "What P*n* actually found" sections. Agents report; they do
not edit either.

---

## Ground truth

Read from the tree on 2026-08-01 and from the three closed agendas. Where a number predates walls, it
is labelled `empty`; it is evidence about that board and no other one.

### 1. A map is a different game, not a difficulty modifier

The strongest measured warning is not subtle. On `cross`, `uct` and `puct` inverted, the field shrank
from 979 Elo to 479, and `wallhug` gained roughly 400 Elo. On the old `double-spiral` at 16x16,
`puct@250` beat `puct@1000` 77-23; on `empty` at the same size it lost 23-77.

The engine representation makes walls cheap and the interpretation expensive. An interior wall uses
the border ring's `WALL` byte, so normal legality already rejects it. The traps are readings that do
not ask `BoardView` and normalisations against `Grid.playableCount` rather than
`BoardView.openCount`. [`../Bots.md`](../Bots.md#a-bot-on-a-map-two-readings-and-the-one-denominator)
records the two fixes already made. Every new feature in P2-P4 has to obey the same rule.

The eleven shapes are not eleven samples from one population. `arena`, `cross`, `rooms` and
`double-spiral` express different games; `scatter` and `islands` add a map seed on top. Ratings are
reported separately by `rows x cols`, shape and, for the two randomised shapes, density.

### 2. The current Gauntlet spends six levels below the search gap

[`Gauntlet.kt`](../../match/src/commonMain/kotlin/ao/snakewarz/match/gauntlet/Gauntlet.kt) currently
seats, in order:

1. `random`
2. `burninhell`
3. `wallhug`
4. `space`
5. `pressure`
6. `chase`
7. `flat-monte-carlo@400`
8. `uct@600`
9. `puct:eval=territory@1000`
10. `alphabeta:eval=territory@1000`
11. `alphabeta:eval=chamber@1000` on `empty` 8x8

The first six consume no allowance. The first five are retained in `ShippedBots` for reasons such as
an Elo floor, deterministic move streams and clean ablations; that does not make them good campaign
opponents. Removing them from the Gauntlet does **not** imply retiring their frozen slugs.

The table also has not been re-measured since its maps changed. The old agenda left that as a cheap
follow-up. It is deliberately not P1 here: measuring the exact order of a table already selected for
replacement spends a batch on a result this agenda cannot use. P7 measures the replacement.

### 3. `chase` is the right floor and still far below search on `empty`

The user's play is the product finding: the first five current levels are not interesting and
`chase` may be suitable for level 1. The old machine result explains the scale without overruling the
play finding. In a seven-entrant field on `empty` 12x12, `chase` scored 13% and rated about 400 Elo
below the leading PUCT configurations. Alpha-beta took 200 of 200 from both `chase` and `pressure`.

Walls can move that margin by hundreds of Elo, so the number is not imported into a map. The durable
claim is only that the current roster has no measured rung in the large interval between the best
reactive bot and the cheapest full search.

### 4. The engine already implements the constant-per-turn experiment

`Match` calls `Budget.reset()` at the start of every turn. A search bot may consume up to that turn's
limit and nothing carries. `MatchSetup` can grant each slot its own fixed limit, and the replay header
records it. No format or budget architecture work is needed to run this agenda.

An equal evaluation allowance is not an equal wall clock. A `territory` leaf, a chamber decomposition
and a rollout have different prices, and those prices move with the board. Therefore every strength
result carries two readings:

- the fixed evaluation limit actually used; and
- a paired Chrome cost measurement for that exact configuration and board.

This agenda does not "correct" the allowance from turn to turn. If a level needs a smaller constant
to fit the UI frame, it receives that smaller constant on every turn.

### 5. Two existing seams bound the missing middle

`puct:budget=1` is exactly `MovePrior`'s argmax. The leaf is evaluated once and never read, so the
`eval` knob is dead; the prior weights alone can change the move. That is an already-shipped zero-ply
policy instrument, although it wastes one charged evaluation and its tied scores still expose enum
order.

At the other edge, `alphabeta` at allowance 4 matched an explicit one-ply greedy move on all 429
decisions of one `empty` 12x12 probe. That is an accident of branching and iterative deepening, not a
portable mode: the matching allowance changed with legal-move count and geometry. A dedicated
one-reply bot can guarantee that it examined every candidate; a tiny allowance on the full search
cannot.

Together these say the bridge can be built without inventing a new engine:

```text
chase -> scored policy -> every own move -> every immediate reply -> full tree search
```

### 6. The present final boss is a hypothesis, not the known 8x8 champion

Level 11 uses `alphabeta:eval=chamber@1000` on `empty` 8x8. `alphabeta` now defaults to the cheaper
`territory` leaf because that change beat its chamber incumbent at all three measured sizes. On the
old `empty` 8x8 field, `alphabeta:eval=territory@1000` rated first, but it lost its direct pairing to
bare `puct` 89-111. The field, the head-to-head and the cost correction did not identify a total
ordering.

That is enough to reject "top registry slug plus dearest leaf" as a proof of a boss. It is not enough
to select the replacement. P5 reopens the question on a complete opening schedule and P6 separates
"strongest measured" from "proved optimal".

### 7. Random rounds do not buy independent 8x8 openings forever

The previous 8x8 fields sampled only a small finite set of legal openings. Two hundred rounds covered
roughly 37 of 40 eligible openings, while the bootstrap treated the groups as independent and printed
intervals up to about 1.6 times too optimistic. More rounds mostly replayed the same boards.

The boss track therefore needs an opening enumerator, not a larger `--rounds` number. Deterministic
finalists play every legal opening in both seatings exactly once. Stochastic finalists repeat that
complete schedule under declared, disjoint bot-RNG seeds. The opening is the block resampled for an
interval.

### 8. An exact solver cannot use `BoardView.hash` as its state key

The maintained hash records occupied `(cell, owner)` pairs plus heads, liveness, growth and turn state.
It does not record body ordering. Two snakes with the same occupied cells and head but different body
threading free different tails next and can hash identically. This is a structural collision, not a
64-bit probability.

Walls add another caveat: `Occupancy.hash` excludes them and `Board.hash` mixes the map's `wallKey`.
P6 is locked to one `empty` 8x8, so the map can be an asserted constant, but body order still cannot.
Any transposition table needs a complete key or a full verification record.

### 9. Identifiers are persistent even when the campaign changes

A released `BotId`, knob value and Gauntlet index is stored data. Candidate implementations stay
behind internal or test-only experiment seams until one qualifies; registering five speculative bots
would freeze five names and add five permanent picker rows.

Gauntlet indices 1 through 11 also remain the saved-progress keys. P7 may replace the configuration at
an index, but it cannot casually renumber the table. Whether existing cleared bits carry into a
substantially rebuilt campaign is a release decision, not a research-agent assumption.

---

## What success means

The agenda closes successfully if it produces all three of these, even if some individual candidate
phases are negative:

1. **A measured middle.** At least one no-tree or tiny-tree opponent rates above `chase` and below the
   first full search on enough shipped maps to form a real rung. Two distinct rungs are preferred.
2. **An honest empty-8x8 boss claim.** Either every legal opening is solved and the policy is optimal,
   or the shipped description says "strongest measured" and names the finalist set, fixed allowance,
   opening schedule and browser cost that support it.
3. **A measured Gauntlet.** Level 1 starts at `chase`, the final level uses the boss, the fixed
   reference score falls through the table within the declared tolerance, and every search level fits
   its browser time envelope.

A negative P2 or P3 is still useful: it establishes that the missing interval begins with search. A
negative P6 is also useful if it puts a measured resource bound on the word "possible".

## The workstreams

Ranked by dependency, then by expected gain per session. Sizes are S/M/L in agent-sessions; batch size
is called out separately because no two timing batches may overlap.

| # | Workstream | Size |
|---|---|---|
| P1 | **Wall-aware baseline and complete openings** | M code / M batch |
| P2 | **Hand-written no-tree policy** | M |
| P3 | **Learned no-tree policy** | L |
| P4 | **Fixed mini-search bridge** | M |
| P5 | **Empty-8x8 championship** | S code / L batch |
| P6 | **Solve what can be solved** | L/XL |
| P7 | **Rebuild the Gauntlet** | M code / L batch |

Each row's case, in the format required by the research process:

- **P1 — thesis:** the old instruments can answer the new question once map identity and finite 8x8
  openings are explicit. **Reuses:** `play`, `rate`, `time`, map flags and mirrored openings.
  **Null risk:** low; schedule coverage and separate map tables are direct checks.
- **P2 — thesis:** Chase leaves cheap wall-aware spatial information unused; scoring it once per move
  can buy part of the gap without a tree. **Reuses:** `FloodFill`, `ShortestPaths`, `MovePrior`,
  ownership and chamber primitives. **Null risk:** low; agreement, cost and strength have stock controls.
- **P3 — thesis:** expert imitation can combine those readings better when inference runs once per
  real turn rather than per expansion. **Reuses:** `PositionFeatures`, `LearnedNet`, `train` and P1's
  positions. **Null risk:** low; held-out-map agreement distinguishes transfer from fit.
- **P4 — thesis:** exhausting all own moves and immediate replies removes tactical blunders at tens,
  not thousands, of evaluations. **Reuses:** `Scratch`, alpha-beta's replay-and-undo pattern,
  `LeafEval` and the P2/P3 policy. **Null risk:** low; completed depth and fallback are observable.
- **P5 — thesis:** complete openings and a robust finalist criterion can identify a deployable champion
  where the old field could not. **Reuses:** P1's scheduler, shipped searches and P2-P4 winners.
  **Null risk:** low for "strongest measured"; no tournament proves optimality.
- **P6 — thesis:** empty 8x8 is small and symmetric enough for exact late-game search, possibly every
  opening. **Reuses:** alpha-beta, undo, terminal outcomes and P5 positions. **Null risk:** moderate;
  solved coverage, node growth and a predeclared resource cap bound the result.
- **P7 — thesis:** Chase, the qualified middle and a real boss can turn eleven novelty matches into a
  difficulty curve. **Reuses:** `Gauntlet`, `gauntlet` and P2-P6 winners. **Null risk:** low; two
  references, per-level timing and a player pass test the claim.

**Why this order.** P1 fixes the two measurement defects every later phase would otherwise inherit:
map pooling and incomplete 8x8 openings. P2 then asks how far hand-written reaction can go before P3
pays for training. P4 consumes the best policy either phase produced. P5 names the empirical champion
before P6 asks whether it can be proved or improved by exact endgame knowledge. P7 waits for the whole
candidate pool; editing the level table earlier would make every preceding null a campaign rewrite.

**Phase gates.** P3 still runs if P2 wins, because a learned no-tree bot is a distinct possible rung.
P3 may stop after its agreement-and-cost probe if it cannot plausibly clear P2. P6 may stop at the
feasibility gate and still report a valid bound. P7 is never skipped: a negative middle still requires
an honest, shorter progression made from what exists.

---

### P1 — The wall-aware baseline and complete 8x8 openings

Two instruments and one baseline, with no bot default changes.

#### A qualification suite, not a pooled map corpus

Use six named boards chosen to expose distinct mechanics:

| Board | What it asks |
|---|---|
| `empty` 8x8 | the boss track and small-board tactics |
| `arena` 12x12 | open positional play with interior walls |
| `cross` 12x12 | rooms joined through a central contest |
| `rooms` 16x16 | chamber and doorway reading |
| `double-spiral` 16x16 | corridor filling, where more search has inverted before |
| `islands` 16x16, default density | transfer across a fresh seeded wall layout |

This is a qualification suite, not a claim that six ratings average into general strength. Each board
gets its own fit. For `islands`, the map seed is part of the block and new phases use disjoint seeds.
If a candidate is intended for one Gauntlet map only, it still has to say so rather than hiding a loss
inside a pooled number.

The baseline field is `chase`, `flat-monte-carlo@400`, `uct@600`, `uct@1000`, `puct@1000` and
`alphabeta:eval=territory@1000`. Add the current boss,
`alphabeta:eval=chamber@1000`, on `empty` 8x8. The two UCT allowances show the lower edge of full
search without pretending allowance and clock are interchangeable.

#### Enumerate 8x8 openings

Add a `:lab` schedule that enumerates every oriented start in the normal mirrored opening rule, pairs
it with its half-turn mirror, and reports coverage as `N of N`, not "distinct games" inferred after
the run. The schedule must preserve the normal spawn rule; hand-placing arbitrary starts merely
accepted by the low-level `MatchSetup` constructor would define a different boss.

For a deterministic pairing, one pass of both seatings is the population. For a stochastic pairing,
repeat the population under declared bot-RNG seeds and bootstrap whole openings. Verify that the old
seed-driven sampler's set is a subset of the enumerated set before trusting either.

#### Fix the cost lanes once

Run the baseline at the literal allowances above, then measure each entrant in Chrome on every board
where it may ship. From those readings define a small set of **fixed** player-facing lanes, for example
"free", "tiny", "standard" and "boss". The exact numbers are P1's result; they are not guessed here.
Every later phase uses a lane unchanged for the whole match.

**Deliverable.** Six separate baseline tables, complete-opening support for `empty` 8x8, and a cost
table from which P2-P7 can choose fixed limits. Do not run the current `gauntlet` table merely to
confirm that the first five rows are easy.

#### What P1 actually found

**The measurement defects are closed.** `--openings complete` now covers the empty-8x8 mirrored
population directly: 40 oriented starts, both contestant seatings, 80 matches per pairing and
optional whole-population replications. It rejects walls, another geometry, FFA and an explicit
round count. Opening identity is logged in `matches-v2.tsv`, old logs remain readable, and rating
intervals resample an opening with all pairings, seatings and replications attached. Coverage and
forfeits print before the strength matrix.

The planned phrase "every legal opening accepted by `MatchSetup`" was falsified. That constructor can
represent 4,032 ordered pairs of distinct squares on empty 8x8, including placements the game never
spawns. The shipped mirrored rule plus its separation predicate has 40 oriented starts, or 20 pairs
under the half turn. The old seeded sampler was proved to be a subset of exactly that population.

The baseline ran on six separately fitted boards with no forfeits. Empty 8x8 used seed 11,001 and
three complete bot-RNG replications; each entrant played 1,440 games. The five wall boards used 200
mirrored rounds per pairing and 1,000 games per entrant. Ratings and 95% opening/seed-blocked
intervals were:

| Entrant | empty 8x8 | arena 12 | cross 12 | rooms 16 | double spiral 16 | islands 16, seed 61,001 |
|---|---:|---:|---:|---:|---:|---:|
| `chase` | -403 [-437,-373] | -358 [-396,-330] | -330 [-365,-301] | -383 [-421,-356] | -384 [-420,-349] | -445 [-483,-409] |
| `flat-monte-carlo@400` | -250 [-272,-231] | -146 [-170,-124] | +29 [+10,+49] | -167 [-189,-145] | -311 [-342,-284] | -141 [-165,-120] |
| `uct@600` | +7 [-14,+30] | +86 [+66,+107] | +132 [+113,+152] | +48 [+27,+69] | -162 [-193,-137] | +42 [+17,+61] |
| `uct@1000` | +81 [+66,+97] | +111 [+90,+132] | +164 [+145,+184] | +105 [+84,+126] | -102 [-131,-78] | +97 [+74,+121] |
| `puct@1000` | +142 [+125,+160] | +136 [+116,+157] | -50 [-71,-30] | +190 [+169,+213] | +569 [+520,+625] | +223 [+199,+246] |
| `alphabeta:eval=territory@1000` | +143 [+120,+166] | +171 [+148,+192] | +55 [+38,+73] | +207 [+183,+233] | +391 [+355,+431] | +222 [+199,+244] |
| `alphabeta:eval=chamber@1000` | +278 [+256,+301] | — | — | — | — | — |

Integrity preceded those fits: empty covered 40/40 openings in every pairing and produced
4,133/5,040 distinct matches; `arena`, `cross`, `rooms`, `double-spiral` and `islands` produced
2,734, 2,810, 2,862, 2,775 and 2,858 distinct matches out of 3,000. All replays were retained as the
P3 position stream. The result strongly falsifies a single map-general ordering: UCT@1000 leads on
`cross`, territory alpha-beta leads `arena` and `rooms`, PUCT dominates `double-spiral`, and PUCT and
territory alpha-beta are tied on the seeded `islands` board.

Chrome 151 priced the exact configurations on a fixed Space-v-Space position tape, with 24 or more
positions per board and five passes bracketed by fresh `uct@600` controls. A cell required at least
four before/after controls within 15% of their pair mean. Values below are median mean / largest raw
turn in milliseconds; two `rooms` cells come from a declared retry. The `arena` PUCT cell stayed
control-unstable through two retries and is not treated as precise.

| Entrant | empty 8x8 | arena 12 | cross 12 | rooms 16 | double spiral 16 | islands 16 |
|---|---:|---:|---:|---:|---:|---:|
| `chase` | 0.008 / 0.1 | 0.010 / 0.1 | 0.016 / 0.1 | 0.026 / 0.2 | 0.017 / 0.1 | 0.032 / 0.2 |
| `flat-monte-carlo@400` | 0.708 / 1.9 | 0.941 / 2.3 | 0.700 / 1.8 | 0.776 / 3.4 | 0.661 / 2.5 | 1.000 / 2.6 |
| `uct@600` | 1.583 / 3.8 | 1.993 / 4.1 | 1.608 / 3.4 | 1.869 / 3.5 | 1.564 / 4.8 | 2.307 / 5.4 |
| `uct@1000` | 2.735 / 6.0 | 3.282 / 6.7 | 2.825 / 6.5 | 3.223 / 5.6 | 2.558 / 7.0 | 3.867 / 23.1 |
| `puct@1000` | 2.210 / 4.5 | unstable | 2.712 / 5.5 | 4.034 / 6.2 | 3.267 / 9.2 | 4.200 / 7.7 |
| `alphabeta:eval=territory@1000` | 2.283 / 4.0 | 2.820 / 6.6 | 2.862 / 6.2 | 4.200 / 7.9 | 3.420 / 9.2 | 4.328 / 8.6 |
| `alphabeta:eval=chamber@1000` | 5.775 / 10.3 | — | — | — | — | — |

Those clusters fix four player-facing envelopes for the rest of the agenda: **free** means zero
charged evaluations and at most 0.25 ms observed; **tiny** is 3.5 ms; **standard** is 5.5 ms; and
**boss** is 10.5 ms on empty 8x8 only. They classify a configuration after measurement; they never
change its allowance during a match. UCT@1000 is outside every map-general envelope because of the
23.1 ms islands turn, although it fits the empty-only boss envelope.

Shipped in P1: the complete-opening CLI/log/bootstrap instrument, its JVM tests, the six-board Chrome
cost harness and the workflow documentation. No bot, default, Gauntlet level or gameplay rule changed.
The full build and the Chrome browser suite passed after the harness was split into per-board tests;
the first one-test attempt was voided when Karma received no completion heartbeat for ten minutes.

### P2 — The strongest hand-written policy without a tree

"Without a tree" is strict in this phase: observe one live board, score legal destinations and return.
No playout, no simulated opponent reply and no budget consumption. A whole-board flood or chamber
decomposition is allowed; looking ahead through a transition is not.

Start with four readings already in the tree rather than a new bag of heuristics:

- `chase`'s shortest path and room-share guard;
- `MovePrior`'s liberty, pinch, wall and tail readings, with a deterministic position-derived
  tie-break rather than direction declaration order;
- reachable room and opponent proximity from the candidate destination; and
- wall-aware ownership or chamber facts that can be computed once and attributed to a legal move.

Test a small ablation family behind an internal experiment seam. Do **not** register one bot per weight
vector. First measure:

1. tie rate and top-1 agreement with P1's strongest map-local tested fixed-budget search, split by
   map and game phase (the teacher's cost does not constrain the free-lane student);
2. cost per turn, including the worst wall layout; and
3. a field with stock `chase`, every ablation and P1's cheapest search anchor.

Agreement predicts visibility, not strength. A candidate that copies the search more often and loses
more is a finding, not a reason to relabel agreement as accuracy.

The qualification target is deliberately about the gap: the winner should remove at least one third
of the map-local rating interval from `chase` to the cheapest full search on three of the five general
boards, and its interval must not lie wholly below `chase` on either remaining board. If none does,
keep the best diagnostic internally and freeze no slug.

**Deliverable.** One hand-written no-tree candidate worth presenting as a Gauntlet opponent, or a
measured ceiling on this class of bot.

#### What P2 actually found

**A free wall-aware policy qualified, but it is a map specialist rather than an empty-board ladder
rung.** One preallocated scorer tested six rules: guarded path; local liberty/pinch/tail; local plus
the half-room guard; full path/local/room; full plus the unresolved wall bonus; and full plus
mover-owned component area. It never applied a transition, asked for scratch, consumed an evaluation
or allocated per choice. A length-one mover's retracting head is correctly free; every other old head
remains the neck. Exact raw ties use board, turn, seat and destination hashes.

The agreement instrument read one dedicated P1 run per map, verified every replay's seed, turn order
and move-stream hash, and sampled 1,000 choice positions in each completed-game third. Replay
selection and within-replay turn selection use independent hashes, so a longer match gets no extra
chance to enter the cap. The expert was persistent per seat and called on every recorded turn. Every
rate below resampled the same complete-opening or mirrored-pair blocks as P1.

| Map and fixed teacher | full-owned unique top-1, early / middle / late | guarded-path late ceiling |
|---|---:|---:|
| empty 8x8, chamber alpha-beta@1000 | 43.9% / 55.0% / 55.6% | 87.9% |
| arena 12, territory alpha-beta@1000 | 46.6% / 56.6% / 56.8% | 85.4% |
| cross 12, UCT@1000 | 40.2% / 41.3% / 40.5% | 91.6% |
| rooms 16, territory alpha-beta@1000 | 57.6% / 54.5% / 53.8% | 84.9% |
| double spiral 16, PUCT@1000 | 46.3% / 59.5% / 57.1% | 88.5% |
| islands 16 seed 61,001, PUCT@1000 | 47.6% / 53.4% / 51.8% | 80.8% |
| same islands positions, territory alpha-beta@1000 | 50.7% / 60.2% / 55.5% | 85.5% |

That last pair falsifies teacher-independent “accuracy”: changing only the teacher moves full-owned
agreement by 3–7 points. Guarded path's very high ceiling on `cross` also mostly describes ties—its
middle tie rate is 83.1%—not a selected action. Most importantly, owned area barely changes top-1
agreement yet materially changes corridor strength below. Agreement saw visibility, not value, just
as the phase brief warned.

The first Chrome cost pass was void because single-call Chase controls were below the 100 µs clock
tick. Two calibration passes then exposed integer truncation in the repaired control mean; their
classifications were void too. The final predeclared harness retained one raw call at each of the
same 24–37 positions, timed 100 extra calls as a batch, and compared full batch rates by integer
cross-multiplication. All 36 cells had at least four of five stable control pairs and zero consumed
evaluations:

| Rule | median mean range over six maps | largest raw turn |
|---|---:|---:|
| guarded path | 7–28 µs | 0.20 ms |
| local | <1 µs | 0.10 ms |
| local plus room | 3–11 µs | 0.20 ms |
| full | 7–29 µs | 0.20 ms |
| full plus wall | 7–30 µs | 0.20 ms |
| full plus owned | 9–37 µs | 0.20 ms |

Every rule therefore fit P1's fixed 0.25 ms free lane and entered strength. Five fresh-seed fields
used 200 mirrored rounds, Chase, all six rules and UCT@600; no temporary id retained a replay. There
were no forfeits. Each entrant played 1,400 games per map, and the fields produced 2,836, 3,399,
3,494, 3,126 and 3,496 distinct games out of 5,600:

| Entrant | arena 12 | cross 12 | rooms 16 | double spiral 16 | islands 16, seed 62,001 |
|---|---:|---:|---:|---:|---:|
| Chase | -55 [-73,-37] | -96 [-114,-81] | -8 [-27,+13] | +16 [-3,+37] | +22 [+4,+40] |
| guarded path | -63 [-79,-50] | +24 [+6,+40] | +21 [+2,+38] | +40 [+23,+58] | +67 [+51,+86] |
| local | -261 [-282,-243] | -270 [-292,-251] | -520 [-568,-483] | -370 [-404,-344] | -479 [-519,-449] |
| local plus room | -34 [-50,-18] | +21 [+2,+39] | -166 [-184,-148] | +17 [+1,+34] | -93 [-112,-73] |
| full | -26 [-39,-13] | -84 [-99,-68] | +82 [+67,+99] | +40 [+22,+55] | -1 [-18,+16] |
| full plus wall | +3 [-11,+17] | -33 [-51,-16] | +50 [+33,+68] | +26 [+8,+41] | +6 [-13,+23] |
| **full plus owned** | **+27 [+10,+41]** | **-21 [-38,-4]** | **+111 [+96,+127]** | **+90 [+73,+107]** | **+53 [+35,+70]** |
| UCT@600 | +409 [+378,+441] | +459 [+427,+495] | +429 [+402,+460] | +140 [+124,+159] | +424 [+395,+463] |

Guarded path, full, full-wall and full-owned clear the predeclared qualification rule. Local alone is
decisively harmful. Local-plus-room clears the point thresholds but is wholly below Chase on `rooms`
and `islands`, so it fails the remaining-board safeguard. The wall bonus never wins a field and is
not adopted. Full-owned leads its siblings on `arena`, `rooms` and `double-spiral`; guarded path leads
on `cross` and `islands`, but loses their direct pairing badly on the other three. Full-owned is the
robust winner.

The separate empty-12 adoption field was deliberately not pooled with those wall maps. Full-owned
beat Chase directly 106-91 and lost to FMC@400 27-171, but common-opponent rating put it below Chase;
the exact twenty-game `BotLadderTest` tape split 10-10. That falsifies a monotone empty-board rung.
The scoring rule nevertheless ships under the frozen slug **`cartographer`**, in the registry's
explicit map-specialist section rather than its empty-12 ladder. The other five `p2-` labels remain
JVM-lab-only ablations and cannot enter replay payloads. P2 shipped the bot, portrait, golden canary,
contract coverage, Chrome timing harness, agreement command and workflow documentation; it changed
no default, allowance, rule or Gauntlet level.

The permanent surface then passed the complete `:bots` and `:app` Chrome targets and the repository's
full `build` gate. The roster, portrait table and public documentation now distinguish nine empty-12
ladder bots, Cartographer as a wall-map specialist, and the one contributed bot.

### P3 — A learned policy without a tree

The old learned work fitted a **value** at a search leaf. This phase fits an **action policy** called
once per real turn. That cost distinction is the thesis: an inference too expensive at every PUCT
expansion can still be cheap beside a human-visible turn.

Generate examples from P1's position stream. Each row contains the live position, one feature vector
per legal move and the move selected by the deepest fixed-budget expert available on that board. If
root visit counts can be logged without changing the expert, retain them as soft targets; otherwise
state plainly that this is behaviour cloning of an argmax and cannot exceed its teacher by imitation
alone.

The split is by **map shape and match**, not by shuffled positions:

- train on `empty`, `arena`, `cross`, `rooms` and one seeded-wall family;
- hold out at least one fixed topology and disjoint `islands` seeds; and
- report `empty` 8x8 separately, because a boss specialist and a map-general policy are different
  models even if they share features.

Every action feature asks `BoardView` about walls and normalises by `openCount` where it represents a
share. A map-conditioned input must be rebuilt for each board; it must not reuse the existing trainer
cache key of only rows, columns and slot count if the reader itself depends on walls.

Run the cheap gates in this order: held-out top-1 agreement, paired Chrome cost, then strength. Stop
before a field if the model does not beat P2's policy agreement outside the training shapes or if its
worst turn does not fit P1's no-tree lane.

The strength target is P2's target again, against P2 in the same field. Better loss or agreement with
no Elo gain does not earn a slug. If both policies qualify, their different failure profiles may make
two useful rungs; if the learned one merely replaces the hand-written one, ship only the winner.

**Deliverable.** A portable, deterministic no-tree policy with a held-out-map result, or a direct
measurement that learning does not buy enough beyond P2 to justify a permanent bot.

#### What P3 actually found

**The learned linear action policy did not transfer beyond Cartographer, so P3 stopped before Chrome
cost and strength.** The new shared `action-policy-v1` extractor exposes ten bounded, action-varying
wall-aware readings. A grouped JVM trainer calls each fixed 1,000-evaluation teacher on every replay
turn, fits a shared multiclass linear score on Q8 inputs, selects L2 on disjoint experimental blocks,
then encodes and decodes the exact portable integer model before any holdout directory is opened.
The instrument is deterministic across one and four labeling workers and retains no released bot id.

The development corpus retained 11,242 unique training inputs and 2,922 validation inputs. Exact Q8
deduplication reported 608 repeated training inputs with 203 conflicting expert labels; validation
dropped 197 inputs already seen in training. L2 selected zero, with train/validation log loss
0.831527/0.834765. Validation unique top-1 agreement was 1,490/2,922 (50.99%) for the learned model
and 1,549/2,922 (53.01%) for Cartographer. Empty 8x8 improved by 2.82 points and cross by 2.26, but
arena lost 5.11, rooms 6.44 and the two unseen development islands 3.80 and 0.82 points.

The primary holdout retained 5,833 inputs after 151 duplicate inputs, 21 conflicting labels and 16
development overlaps were removed. Learned unique top-1 agreement was **2,884/5,833 (49.44%)** against
Cartographer's **3,100/5,833 (53.15%)**, a loss of 3.70 percentage points. On `double-spiral` alone it
was 1,372/2,894 (47.41%) against 1,603/2,894 (55.39%), **-7.98 points** in every progress third. Across
eight disjoint islands layouts it was 1,512/2,939 (51.45%) against 1,497/2,939 (50.94%), +0.51 points
overall but ranging from -4.11 to +7.80 by layout. The aggregate failed the required win and the fixed
topology exceeded the two-point family safeguard. A hidden model was therefore not tried, no browser
cost was claimed, no strength field ran and no weights, slug, portrait or replay identity shipped.

The negative is narrower than "learning cannot help": one linear combination of ten local/spatial
facts cannot replace Cartographer's lexicographic safeguards across maps. The reusable result is the
wall-safe action corpus/trainer and a measured boundary saying the next useful middle must add search,
not merely another no-tree combination of the same facts. The complete retained output is
`.lab/p3-policy-train.log`.

### P4 — The fixed mini-search bridge

Build the missing search depths explicitly instead of approximating them with a tiny allowance on
`alphabeta`. Three candidates, all using the best P2/P3 policy for ordering and fallback:

1. **Greedy one-ply:** apply every legal own move and evaluate the resulting state.
2. **Reply guard:** for every own move, exhaust every legal reply by the next opponent and keep the
   worst resulting value.
3. **Three-ply shallow alpha-beta:** complete the next own move after the reply, with no iterative
   deepening and no partially searched root move allowed to win by accident.

Branching is at most four, so fixed caps of 4, 16 and 64 leaf evaluations are sufficient upper bounds
for the three shapes. The implementation still charges through `Scratch.playout`; it never bypasses
the central budget. A terminal leaf is the game's answer. A nonterminal leaf begins with the cheapest
wall-aware appraisal that P1 prices, and richer leaves are tried only as equal-clock alternatives.

Record completed-depth coverage anyway. Dead snakes, forced moves and terminal replies reduce the
actual count; the saving is discarded rather than banked. A turn that cannot complete the configured
shape returns the P2/P3 policy move, and the fallback rate is reported before Elo.

Run every depth in one map-separated field with `chase`, the policy, `flat-monte-carlo@400` and
`uct@600`. The desired ordering is not assumed. A useful bridge is one that clears its policy parent,
takes a nontrivial score from the full search anchors, and occupies a stable interval on multiple map
types. If reply search helps on `empty` and hurts in corridors, that is a specialist and must be named
as one.

**Deliverable.** One or more fixed-cost configurations that fill the measured interval, plus the
smallest depth at which the gain appears. Register a new algorithm only after that result; depth is a
configuration, not a reason for three slugs.

#### Result

The bridge qualified at every depth. Exact fixtures pin complete 4/16/64-leaf searches and exact
Cartographer fallback at 3/15/63; all six P1 tapes then completed every searchable root with zero
fallback. Paired Chrome pricing put every depth in the 3.5 ms tiny lane. The largest accepted raw
turn was 1.4 ms, depth 3 on `rooms`; one unstable `arena` depth-2 control bracket was replaced by the
single declared full-target repeat, while valid first-pass rows were retained.

The seven-entrant field used 80 matches per pairing on each map, complete openings on empty 8x8 and
fresh mirrored wall runs elsewhere. Depth 1 was the smallest useful search: it beat Cartographer on
all six boards, narrowly on `cross` (41 wins, 38 losses, one draw) and decisively on the other five.
Depth 2 also cleared the parent everywhere. Depth 3 was the stable winner of the family, scoring
100%, 90%, 61%, 98%, 86% and 98% directly against Cartographer on empty, arena, cross, rooms,
double-spiral and islands respectively.

Depth 3 was not a disguised full search. Against FMC@400 it won five boards but scored only 27% on
`cross`; against UCT@600 it scored 24%, 33%, 16%, 38%, 90% and 48%. That is the desired middle: much
stronger than the no-tree parent, normally below UCT, and a real corridor specialist rather than a
monotone allowance ladder. It ships once as `lookahead`; depths 1, 2 and 3 remain configurations of
that algorithm. The smallest gain is depth 1 and the empty-8 P5 qualifier is depth 3. Retained field,
rating and browser artifacts are under `.lab/p4-*`.

### P5 — The empty-8x8 championship

This phase is deliberately `empty`-only. A wall result neither helps nor hurts a finalist here.

#### The field

Play P2-P4's winners, the current final boss, and the serious shipped search configurations:

- `puct` and `alphabeta` at `territory`, `chamber` and any already-measured policy setting that
  transfers to 8x8;
- `uct`, `puct` and `alphabeta` at fixed allowances from P1's standard through boss lanes; and
- any P4 shallow search that remains competitive on this board.

Use the complete opening schedule. Deterministic entrants play the population, both seatings;
stochastic entrants add declared RNG replications rather than more sampled openings. Rate all
entrants in one field, inspect residuals, then run direct finalist pairings on the same population.

#### "Strongest measured" has a precise meaning

Intransitivity already exists on this board, so "highest Elo" alone is insufficient. Select the pure
configuration with the highest minimum score against the other finalists. Break a practical tie by
common-opponent rating, then by lower worst-turn Chrome cost. Report the whole finalist matrix beside
the winner.

Measure an allowance curve for each finalist. Extra fixed effort that fails to improve the complete
population is saturation; extra effort that makes a corridor or style matchup worse is not silently
averaged away. The deployable boss uses the strongest point that remains inside the predeclared
browser envelope, not simply the largest integer accepted by a knob.

This phase may conclude **strongest measured on empty 8x8 under this finalist set and time envelope**.
It may not say "optimal" or "unbeatable". That word belongs to P6.

**Deliverable.** The empirical boss, runner-up, full opening-by-finalist matrix, allowance curve and
Chrome cost. It must directly clear the current `alphabeta:eval=chamber@1000` boss or the current boss
stays until P6 changes the answer.

#### What P5 actually found

Complete-opening strength was strongly non-monotone in allowance, so the largest frame-safe integer
was not the champion. Seven frozen family curves selected a common deployable field. In its 8,400
games, five complete-opening replications and zero forfeits, `alphabeta:eval=territory@1700` ranked
first at 45.6% maximin (37.5-52.5%). `alphabeta:eval=chamber@800` ranked second at 41.1%
(36.1-45.8%); territory@1700 won their direct pairing 195-124 with 81 draws, 58.9% and a
53.1-63.9% shared-opening interval. It also cleared the old chamber@1000 boss in its disjoint curve,
68.3% with a 58.1-77.9% interval.

The winner's accepted Chrome worst turn was 8.4 ms in the championship grid and 9.3 ms in P7's exact
level check, both inside the frozen 10.5 ms boss lane. Chamber@1000 won the original unrestricted
finalist field but its authoritative 10.8 ms reading missed that lane. P5 therefore names territory
alpha-beta@1700 the **strongest measured deployable empty-8x8 boss** under the declared field,
complete-opening population, fixed allowance and browser envelope. It does not call it optimal.

Retained evidence is under `.lab/p5-primary-field`, `.lab/p5-finalists`, the seven
`.lab/p5-curve-*` directories and `.lab/p5-deployable-finalists`. The `championship`, `allowance`
and read-only `allowance-report` commands reproduce the paired-bootstrap reports.

### P6 — Solve what can be solved on empty 8x8

There are two possible wins: prove every opening, or make P5's champion exact often enough to improve
it. Begin with a feasibility probe so the phase cannot disappear into an unbounded solver project.

#### Correct state identity first

An exact key includes:

- the ordered body of every snake, or an equivalent release schedule that determines future tails;
- head, tail, liveness and growth phase per snake;
- the slot to act and round state; and
- an assertion that the geometry is `empty` 8x8.

Do not key on `BoardView.hash` plus a tail and call it exact. Verify stored states structurally on a
hit until a collision-free encoding is proven. Then probe the true transposition rate before building
a large table; path-dependent bodies may make it too small to pay.

The square empty board admits rotations and reflections. Canonicalise under a symmetry only after
tests prove that bodies, directions, seat identity and to-act transform together. A faster wrong proof
is worse than no solver.

#### Work backward from positions that can finish

Sample P5 games by remaining open cells and solve them to terminal with no heuristic cutoff. Increase
the threshold only while node growth and memory remain inside a cap declared in the phase brief. The
default cap for planning is one machine-day and 8 GiB; changing it is a coordinator decision recorded
before the run, not after an almost-result.

Report, per threshold:

- positions solved and independently replay-verified;
- median and worst nodes, depth and transposition rate;
- how often P5's champion chose a move outside the proved-optimal set; and
- projected cost of reaching the complete initial-opening population.

If the projection is affordable, solve every legal opening in both seatings. If it is not, stop and
retain the largest verified endgame table or solver threshold that fits the browser. A proof hit may
return early, but its unused allowance is discarded and the fixed per-turn cap does not change.

A compact table of proved opening moves is allowed because it is knowledge, not dynamically moved
thinking time. An unproved high-budget opening book is considered only after the exact route is closed
and must compete with P5 under the same boss envelope.

**Deliverable A:** a perfect-play policy over every legal starting state, in which case it is the
strongest possible boss under the rules. **Deliverable B:** a measured exact-endgame hybrid and an
explicit unsolved region, in which case P5's wording remains "strongest measured".

#### What P6 actually found

The exact JVM verifier structurally keys every ordered body and turn field, exhaustively solves by
paranoid minimax and independently replays each proof. Under the frozen 1,024 MiB, 5,000,000 nodes
per position and 200,000,000 total-visit caps, it solved all eight sampled positions through 24
remaining open cells. At threshold 28 it solved six positions and two reached the per-position cap,
so the declared extension stopped before 32. It used 12,377,986 exact visits and 728.31 MiB of
declared arrays.

There were no usable transposition hits at any threshold. Full-key checks rejected the observed
structural-hash collisions, confirming why `BoardView.hash` alone could not support a proof. The P5
champion's recorded move remained in the exact tied-optimal set on every verified position through
the largest complete tier, so an endgame hybrid had zero observed corrections and was not promoted.

P6 closes at the feasibility gate: a complete opening proof is not supported by the measured growth
or memory behaviour, and no browser table or dynamic effort policy ships. The strongest possible AI
on empty 8x8 remains unknown; the honest final-boss claim remains P5's strongest measured result.
The `solve-endgame` command and retained solver summaries make that negative bound reproducible.

### P7 — Rebuild and measure the Gauntlet

Only winners enter this phase. The intended eleven-level shape is a candidate pool, not a promise that
every preceding phase produced a shippable bot:

| Region | Intended opponent |
|---|---|
| 1 | stock `chase`, as the first opponent worth playing |
| 2-3 | qualified hand-written and learned no-tree policies |
| 4-6 | completed fixed mini-search depths or distinct fixed-cost hybrids |
| 7 | flat Monte Carlo, if it still sits above the hybrids on its assigned map |
| 8-10 | UCT, PUCT and alpha-beta configurations at fixed per-turn limits |
| 11 | P5's strongest measured boss, improved or proved by P6, on `empty` 8x8 |

If P2 or P3 is negative, do not keep an easy incumbent to preserve eleven labels. Reuse a qualified
algorithm at a materially different fixed depth or map only when the measured matchup is different;
otherwise a shorter Gauntlet is more honest. Because existing indices are persistent, changing the
count or semantics requires the progress decision below before code moves.

Maps are chosen for readable strategic changes, then measured. They are not difficulty multipliers:
`double-spiral` cannot sit under a deeper search merely because it looks advanced when that map has
made deeper search weaker. Run the candidate table through `gauntlet` with two fixed references whose
saturation regions differ. The reference's score should fall level by level; a rise outside a
five-point practical band reorders or reconfigures the table rather than triggering a larger sample by
default.

Every level also gets a paired Chrome time reading on its own board. The allowance is constant for
the match and must fit the level's declared frame envelope at the worst observed turn. Read the
distinct-games line first, especially for deterministic policies on fixed maps.

Machine ordering is necessary and not sufficient for human difficulty. The user plays at least the
first level, one middle hybrid and the boss before the table is called done. The checks are simple:
level 1 is interesting but beatable, the middle introduces recognisably stronger replies rather than
only longer pauses, and the boss feels qualitatively harder than the preceding full-search level.

**Deliverable.** A rebuilt level table, its two-reference score profile, per-level browser cost and a
manual play note. Update `Gauntlet`'s KDoc and [`../Bots.md`](../Bots.md) with the new measurements;
remove their stale notices only after the exact shipped configurations have been run.

#### What P7 actually found

The shipped campaign is seven levels, not eleven. After the machine-qualified opponent curve was
selected, the release owner retired the four enclosed shapes `cross`, `ring`, `diagonals` and
`double-spiral`. The seven surviving shapes now appear exactly once:

| # | Opponent | Board | Fixed allowance | Accepted Chrome worst |
|---:|---|---|---:|---:|
| 1 | `chase` | `pillars` 12x12 | 0 | 0.1 ms |
| 2 | `cartographer` | `rooms` 16x16 | 0 | 0.2 ms |
| 3 | `lookahead:depth=1` | `arena` 12x12 | 4 | 0.2 ms |
| 4 | `flat-monte-carlo` | `scatter@0` 12x12 | 400 | 2.8 ms |
| 5 | `uct` | `islands@0` 12x12 | 600 | 4.9 ms |
| 6 | `puct:eval=territory` | `pinwheel` 12x12 | 600 | 3.3 ms |
| 7 | `alphabeta:eval=territory` | `empty` 8x8 | 1,700 | 7.2 ms |

P3 produced no learned rung, P4 depth 3 could not hold a consistent adjacent band under both P7
references, and P6 produced no exact hybrid. A provisional territory alpha-beta@900 rooms rung fit
the strength curve but its stable 6.0 ms worst turn failed the 5.5 ms standard lane. Those rows were
removed instead of preserving labels or changing their rules after measurement.

The exact **shipped** seven-map reruns each completed 1,400 games with no forfeit. UCT@100 scored
69/66/61/63/25/25/11%; PUCT@250 scored 98/99/95/82/55/45/17%. Every adjacent rise is inside the
five-point practical band. UCT produced 200/200 distinct games at every level; PUCT produced
107, 161, 121, 200, 200, 100 and 74 distinct games. Machine qualification is retained in
`.lab/p7-seven-map-final-uct100` and `.lab/p7-seven-map-final-puct250`; exact Chrome evidence is
`.lab/p7-exact-seven-map-app-browser-cost.{log,xml}`.

Persistent `Gauntlet`, UI and progress code now use this exact table. Each level pins `mapSeed=0`
separately from the fresh match seed, so a retry changes turn order and bot randomness without
redrawing `scatter` or `islands`. The rebuilt campaign starts fresh under
`snakewarz.gauntlet.v2`; old development progress and per-level runs remain unread under their old
keys, while ordinary replay links remain compatible because they carry wall squares rather than a
shape name.

The human gate falsified the machine-qualified ramp. One roughly five-minute session cleared levels
1 through 6; only level 7 supplied the intended challenge. The first two levels remain acceptable and
the empty-8 boss remains successful, but levels 3 through 6 are materially too easy for their product
roles. Machine monotonicity under UCT@100 and PUCT@250 was therefore necessary and not sufficient.
That result closes this agenda and is the starting condition of
[`2026-08-01b_Research-Agenda.md`](2026-08-01b_Research-Agenda.md).

---

## Decisions resolved during the program

### What happens to existing Gauntlet progress

The seven-level table is a new `snakewarz.gauntlet.v2` campaign identity with no migration from the
retired development table. This was the release owner's choice while the game remains in testing.
Ordinary replays are unaffected because they carry their complete setup.

### Whether both policy winners deserve public bot identities

P2's hand-written `cartographer` qualified and ships. P3's learned no-tree policy did not clear the
agreement and cost gate, so it received no public identifier and no campaign rung.

### How large the boss's browser envelope is

The boss lane remains P1's 10.5 ms worst-turn limit. Territory alpha-beta@1700 is the common-field
winner and its exact shipped-board check was 7.2 ms, so no larger envelope or exception was needed.

---

## Explicitly not on this agenda

**Dynamic thinking time.** The predecessor's budget bank, early-stop saving, entropy trigger,
best-move stability trigger and match-level pool are removed, not postponed inside P4 or P6. Reopen
them only after the constant-budget allowance curves show a plateau worth redistributing.

**Tuning the five easy reactive bots into campaign levels.** Keep them as instruments and Custom
opponents. P2 is allowed to reuse their primitives; it is not another `pressure` knob sweep.

**Re-measuring the current Gauntlet before replacing it.** P1 measures the anchors and P7 measures the
new table. The stale table's exact internal order has no decision left to inform.

**A general map-solving boss.** The boss is deliberately `empty` 8x8. P6 may exploit that geometry.
General-strength candidates still have to survive the P1 suite because they occupy earlier mapped
levels.

**Board-conditioned defaults, three-seat work and simultaneity.** Each has evidence behind it and none
closes the player-facing gap this agenda is for. They remain in the old agendas rather than travelling
as unranked passengers here.

**A broad refit of the learned value leaf.** Map transfer is a real open question, but P3 trains an
action policy because it directly targets the missing no-tree rung. Refit `eval=learned` only if a
phase establishes that the value leaf is the bottleneck in a qualified candidate.

---

## Open at the close

- Levels 3 through 6 need exact-map specialists or stronger qualified configurations; preserving
  their indices, boards, shapes and seeds is a product constraint for the next agenda.
- The two machine references establish ordering but do not predict human first-clear difficulty.
  Future gates must retain attempt count, elapsed first-clear time, survival length and death shape.
- Dynamic effort is worth reopening only behind a deterministic evaluation bank and a measured hard
  synchronous-turn cap. The constant-effort campaign proved responsive but left strength unused on
  easy turns.
- Empty 8x8 remains unsolved. The final boss is strongest measured under the complete-opening field
  and browser envelope, never optimal.

---

## Protocol additions for this agenda

The full protocol is in [`../Research-Process.md`](../Research-Process.md). Every phase brief also
carries these agenda-specific rules:

```text
- Name rows, columns and map beside every result. For scatter/islands, name density and seed range.
- Never pool different map shapes into one rating. A cross-map summary may show six separate cells.
- A configuration's evaluation limit is constant per turn. Unused work is discarded, never banked.
- Quote Chrome cost beside strength, but do not infer cost from a field's µs/turn column.
- On empty 8x8, enumerate the complete opening population; do not buy duplicates with --rounds.
- Read distinct games and forfeits before rating. A sealed or unreachable spawn is a defect.
- Report tie/agreement, completed-depth/fallback or proof-coverage rates before an Elo claim.
- Keep candidates internal until measured. Registering a BotId freezes it.
- "Strongest measured" names the field, opening population, fixed allowance and cost envelope.
- Only a terminal exhaustive proof earns "optimal", "perfect" or "strongest possible".
```

The usual finish still applies: both targets green, module purity and ktlint green, Chrome verification
for portable move arithmetic, golden changes explained rather than accepted, new source files staged,
and no commit or push without the user's request.

---

## Open at the close

*Written when P7 lands. It will contain the progress decision, any candidate that qualified but did
not ship, the unsolved portion of empty 8x8, and the first question the constant-budget curves make
worth asking next.*
