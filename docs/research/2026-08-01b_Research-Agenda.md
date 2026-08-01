# Research agenda — 2026-08-01b

Eight phases aimed at turning the machine-qualified Gauntlet into a human-qualified difficulty ramp.
The preceding agenda produced acceptable levels 1 and 2 and a good final boss, but one five-minute
human session cleared through level 6: levels 3 through 6 are materially too easy, and level 7 is the
only successful challenge.

This agenda keeps every level's current index, board size, map shape and map seed. Level-specific
optimization is explicitly allowed: the opponent at a rung may exploit its exact geometry and opening
population. Candidates remain lab-only until qualified. "Optimal" remains reserved for a terminal
proof; every empirical winner is described as best-known for its exact level.

The target is a steep human-facing ramp. Level 3 should demand attention, levels 4 and 5 should cause
retries, level 6 should approach boss difficulty, and level 7 should remain the peak.

## Product decisions already made

| Decision | Consequence for this agenda |
|---|---|
| Levels 1 and 2 are acceptable | Do not weaken, replace or retune them |
| Level 7 is good | Keep its opponent and configuration unless a regression is found |
| Levels 3 through 6 are too easy | Optimize each on its exact current board rather than seeking one map-general ordering |
| The ramp should be steep | Level 6 is allowed to be close to the boss rather than a gentle bridge |
| Specialists are Gauntlet-only | Qualified profiles remain replayable but do not clutter Custom mode |
| The revised campaign starts clean | Use v3 progress and per-level replay keys with no migration from v2 |
| UI responsiveness is a hard constraint | Dynamic effort may return only behind a measured synchronous per-turn cap |

## What success means

The agenda succeeds when all of the following are true:

1. Levels 3 through 6 each directly clear their shipped incumbent on their exact map and remain
   ordered under both established machine references.
2. The human first-clear targets form a steep ramp: level 3 in 1–2 attempts, level 4 in 2–4, level 5
   in 3–6 and level 6 in 5–10. These are product gates for the release owner's fresh v3 play, not
   population claims.
3. Level 7 remains the hardest level and is not weakened.
4. Level 3 fits the 3.5 ms tiny Chrome lane; levels 4 through 6 fit the 5.5 ms standard lane. No
   dynamic policy may exceed the applicable per-turn cap.
5. Every promoted specialist is deterministic, replayable, safe on an unexpected board and hidden
   from Custom mode without becoming unavailable to `Match`, playback or `:lab`.

## Status

| Phase | Workstream | State |
|---|---|---|
| P1 | Close, instrument and recalibrate | complete |
| P2 | Exhaust the existing configuration space | complete; human gate open |
| P3 | Adversarial opening books | planned |
| P4 | Map-control and exact endgame hybrids | planned |
| P5 | Search carried across turns | planned |
| P6 | Bounded dynamic thinking | planned |
| P7 | Level-specific finalist selection | planned |
| P8 | Promotion and campaign v3 | planned |

The coordinator owns this table and writes phase findings back into this agenda. Agents report; they
do not edit the agenda.

---

## P1 — Close, instrument and recalibrate

Close the preceding agenda with the human finding above. Mark its final play gate complete, harvest
its ledger into `Research-Process.md`, delete the ledger, and update `Research-Summary.html` before
this agenda begins accumulating findings.

Establish exact baselines for the four weak levels:

| level | incumbent | board | map | allowance |
|---:|---|---|---|---:|
| 3 | `lookahead:depth=1` | 12x12 | `arena@0` | 4 |
| 4 | `flat-monte-carlo` | 12x12 | `scatter@0` | 400 |
| 5 | `uct` | 12x12 | `islands@0` | 600 |
| 6 | `puct:eval=territory` | 12x12 | `pinwheel@0` | 600 |

Add a lab-only `2026-08-01b` candidate table so experimental profiles can be measured without
changing persistent Gauntlet configuration. Reproduce the shipped `uct@100` and `puct@250` profiles,
then build one exact-map field per level containing its incumbent, serious existing search families
and the level-7 boss as an anchor where affordable.

During later human gates, retain attempt count, first-clear time, survival length and death shape in
the WIP ledger. This is local research evidence, not production telemetry and not training data unless
a later phase explicitly declares how it will avoid overfitting one player.

**Kill criterion.** A missing opening block, a forfeit or a mismatch between the candidate table and
the shipped board voids the run. No algorithm phase starts before all four baselines reproduce.

### What P1 actually found

The preceding agenda is closed. Its human gate falsified the machine-qualified middle: one roughly
five-minute session cleared levels 1 through 6, while level 7 alone supplied the intended challenge.
The finding and open questions now live in that agenda, the process lessons are in
`Research-Process.md`, its WIP ledger is deleted, and `Research-Summary.html` includes the closed run.

The lab-only table is named `2026-08-01b` and initially reproduces all seven shipped rows exactly.
Tests pin every opponent spec, allowance, geometry, shape, map seed and materialised wall array. UCT@100
and PUCT@250 then reproduced the shipped reference profiles exactly over 200 mirrored matches per
level, seed 81001, with no forfeit:

| reference | scores, levels 1 through 7 | distinct games, levels 1 through 7 |
|---|---|---|
| UCT@100 | 69/66/61/63/25/25/11% | 200/200 on every level |
| PUCT@250 | 98/99/95/82/55/45/17% | 107/161/121/200/200/100/74 |

The general `play`, `time`, `ab`, `tune` and `spsa` instruments could not previously vary a tournament
seed without redrawing `scatter` or `islands`: `--seed` drove both. P1 added strict `--map-seed`,
defaulting to the old coupled behaviour but allowing the shipped `@0` walls to remain fixed while
fresh opening blocks use another seed. A map seed without `--map` is refused rather than ignored.

One common eight-entrant field then ran separately on each weak level's exact board: the incumbent,
the other serious 600-allowance search variants, and territory alpha-beta@1700 as the boss anchor.
Each field completed 5,600 matches, every requested mirrored block and zero forfeits. Maps were never
pooled:

| level | exact board | distinct | incumbent rating (95%) | highest 600-allowance rating (95%) | boss anchor |
|---:|---|---:|---:|---:|---:|
| 3 | arena 12x12 | 4,410/5,600 | Lookahead depth 1 −259 [−282,−240] | chamber alpha-beta +108 [+92,+126] | +160 [+143,+177] |
| 4 | scatter 12x12 @0 | 4,312/5,600 | FMC@400 −302 [−327,−280] | chamber alpha-beta +173 [+156,+193] | +199 [+181,+218] |
| 5 | islands 12x12 @0 | 4,353/5,600 | UCT@600 −75 [−98,−55] | chamber alpha-beta +179 [+161,+200] | +252 [+235,+273] |
| 6 | pinwheel 12x12 | 4,370/5,600 | territory PUCT@600 +78 [+60,+95] | territory alpha-beta +93 [+78,+108] | +167 [+148,+185] |

These fields establish headroom, not qualifiers: their `us/turn` columns are not Chrome cost and the
incumbent direct cells still need shared-opening intervals. P2 therefore prices candidates before
selecting allowances and runs direct incumbent comparisons. The retained logs are
`.lab/2026-08-01b-p1-level{3-arena,4-scatter0,5-islands0,6-pinwheel0}`.

## P2 — Exhaust the existing configuration space

Buy the cheap answer before inventing a new bot. Measure serious existing configurations separately
on `arena12`, `scatter12@0`, `islands12@0` and `pinwheel12`.

- Level 3 starts with Lookahead depths 2 and 3, then complete depth-4 and depth-5 research variants
  if the existing depth cap leaves visible headroom.
- Levels 4 through 6 receive frozen allowance curves for UCT, territory/chamber PUCT and
  territory/chamber alpha-beta. Include already measured prior and leaf configurations only at the
  allowance where they were qualified.
- Browser cost is measured before a result-dependent allowance is selected. No interpolation and no
  larger unplanned allowance after strength is visible.

A configuration qualifies for a level only if its shared-opening 95% lower bound against that
level's incumbent is above 50%, it fits the level's Chrome lane, and the full two-reference profile
does not introduce an adjacent rise beyond five percentage points.

**Kill criterion.** Stop algorithm research for any level whose cheapest qualifying existing
configuration also passes its human target. A new concept does not receive a phase merely because it
was listed here.

### What P2 actually found

Existing search configurations machine-qualify on all four weak levels, so no new algorithm has yet
earned a phase. P2 first extended the unreleased fixed-depth research seam to depths four and five;
the released Lookahead range and default remain unchanged. It then froze Chrome points before seeing
strength. All four arena depths fit the 3.5 ms lane, and only configurations whose exact-board row fit
5.5 ms entered the other fields. The direct-cell instrument now reports shared-opening 95% intervals
from the same experimental blocks as `rate`, rather than substituting a fitted rating for a direct
qualification.

The cheapest complete table satisfying the direct, Chrome and two-reference machine gates is:

| level | P2 finalist | exact Chrome worst | direct score over incumbent (shared-opening 95%) | UCT@100 | PUCT@250 |
|---:|---|---:|---:|---:|---:|
| 3 | Lookahead depth 2 @16 | 0.3 ms | 75% [67,82] | 62% | 90% |
| 4 | territory PUCT@600 | 2.7 ms | 92% [89,96] | 18% | 40% |
| 5 | territory PUCT@600 | 3.4 ms | 66% [58,71] | 14% | 37% |
| 6 | territory alpha-beta@600 | 4.7 ms | 64% [57,70] | 15% | 33% |

The reference profiles introduce no adjacent rise above five points. Lower points were not silently
dropped: territory PUCT@400 on islands clears UCT@600 at 69% [63,74], but PUCT@250 scores 60% against
it, a twenty-point reversal after level 4, so it fails the ramp gate. Territory alpha-beta@400 clears
the pinwheel incumbent at 63% [56,70], but UCT@100 rises roughly ten points after level 5; @600 is the
cheapest measured point that preserves both profiles.

Depth is the human choice P2 cannot make. Arena depth 2 already clears the incumbent, while depth 5
scores 87% [83,91] and still costs only 2.0 ms. The lab-only `2026-08-01b` table now carries the
cheapest machine-qualified four profiles, with levels 1, 2 and 7 unchanged. P3 is conditional on the
release owner's fresh attempt bands: a level that passes stops here; a level that remains too easy is
unresolved and may receive exact-map algorithm work.

One cost disagreement remains recorded rather than averaged. Pinwheel territory PUCT@600 read 6.9 ms
in P2 after its retained P7 qualification read 3.3 ms, despite stable within-pair controls. P2 does
not select it; territory alpha-beta@600 had a stable 4.7 ms row. Any later reuse of the PUCT incumbent
must price it again rather than choosing either reading.

## P3 — Adversarial opening books

For each unresolved level, generate a separate contingency book for the exact current walls, spawns
and both possible turn orders. At a human turn, branch over every legal human move; at the opponent's
turn, retain the move selected by the strongest offline level-specific search. This produces a book
that answers adversarial legal play rather than memorising one winning or losing replay.

Book entries use a complete structural state key: ordered bodies, growth phase, liveness, actor to
move and the asserted wall layout. `BoardView.hash` is not an exact key. Every label is generated by
the primary offline search and disagreements are cross-checked with a second search or a materially
larger allowance. The book never claims proof unless every descendant reaches a terminal result.

At runtime the lookup is deterministic, allocation-free and legality-checked. A miss or rejected key
falls through to the profile's live search. Primitive arrays are preferred over object graphs, and
the production distribution must remain inside the existing 1.5 MiB gzipped limit.

Report reachable-state coverage, lookup hit rate by turn, disagreement rate between labelers,
corrections over the incumbent, exact-map strength, Chrome cost and bundle delta before increasing
the opening horizon.

**Kill criterion.** Stop extending a book when another horizon does not materially increase live hit
coverage, when corrected decisions fail to improve the exact-map field, or when bundle cost prevents
a production build from passing.

## P4 — Map-control and exact endgame hybrids

The four weak levels have walls, so reopen exact and graph-based ideas that did not pay on the empty
8x8 boss. Begin with diagnostics rather than implementation:

- articulation and doorway races: who can occupy a cut before the opponent crosses it;
- chamber ownership and escape routes rather than only total flood-filled territory;
- permanent separation and the remaining survival time of each isolated snake; and
- small exact continuations after a book or search reaches a fragmented late position.

Measure firing time, firing rate and correction rate over P1's exact-level corpus. Reuse the JVM exact
solver as a verifier, but do not transplant its 728 MiB table into the browser. A browser hybrid solves
only isolated regions or small remaining positions inside predeclared node and memory caps, unwinds
wholesale on refusal, and verifies any claimed proof by replaying the chosen continuation on a fresh
board.

**Kill criterion.** A diagnostic that rarely fires or never corrects a P2/P3 finalist ends the phase.
No strength batch is run for a mechanism that cannot first show coverage.

## P5 — Search carried across turns

Current searchers discard all work after choosing a move. Test two deterministic ways to retain
useful information without increasing the current turn's allowance:

1. Alpha-beta carries its principal variation and previous best reply into the next turn's move
   ordering.
2. PUCT reroots its existing subtree through the move it chose and the human move later observed.

The candidate must reconstruct the actual intervening move from the live board and verify the
prospective root structurally. If the expected path, board state or wall key does not match, it resets
the tree. Stale statistics are never used on a merely similar position.

Report valid reroot coverage, retained nodes or variation hits, saved evaluations to equal completed
depth, exact-map strength at equal allowance and paired Chrome cost. Compare the stateful candidate
against a state-cleared control using the same opening blocks.

**Kill criterion.** Less work is not a win by itself. Reject reuse that does not improve strength at
equal cost or that depends on accepting an unverified state.

## P6 — Bounded dynamic thinking

Reintroduce effort redistribution inside the level specialist rather than changing `Match`, `Budget`
or the synchronous bot contract. A profile receives:

- a base credit added once per turn;
- a bounded bank holding credit not spent on forced, proven or confidently settled turns; and
- a hard per-turn consumption cap equal to the allowance recorded in the replay.

The first stopping rule is the arithmetic control:

```text
visits[best] - visits[second] > remaining
```

It cannot change which child finishes with the most visits. Report its savings split by opening,
middle and endgame before adding a trigger. If it creates spendable credit, test one best-move
stability trigger; do not open an entropy sweep at the same time.

The bank and trigger use evaluation counts, injected deterministic state and no wall clock. Search
remains one synchronous `chooseMove` call. The hard cap stays inside 3.5 ms at level 3 and 5.5 ms at
levels 4–6, so saving work may make hard positions stronger but can never create an unbounded UI
stall.

Equal-strength comparisons grant the same total base credit over a match and report actual consumed
work. The bank size, cap and trigger are frozen before the field.

**Kill criterion.** End the phase if safe early stopping saves too little to fund hard turns, if the
trigger rarely fires, or if redistribution fails to improve an exact-level common field.

## P7 — Level-specific finalist selection

Choose one finalist per exact level from P2 through P6. Never pool maps. Each finalist field uses
shared opening blocks, complete stochastic replications where required, zero forfeits and expanded
specifications. Report direct cells, shared-opening bootstrap intervals, common-opponent ratings,
residuals, actual consumed effort and exact Chrome worst turns.

Selection order is:

1. highest point maximin score in the level-local finalist field;
2. within five percentage points, common-opponent rating;
3. lower Chrome worst-turn cost; and
4. smaller shipped book/data footprint.

Each selected profile must directly clear its shipped incumbent with a shared-opening 95% lower bound
above 50%. Then run the full candidate table against `uct@100` and `puct@250`, 200 mirrored matches per
level, seed 81001, with no adjacent reference-score rise over five points.

The release owner then plays a fresh campaign twice and applies the target bands from
[What success means](#what-success-means). A machine-qualified profile that misses its human band is
not promoted; the finding is written back and the next-best finalist is tested without enlarging its
allowance after seeing the result.

## P8 — Promotion and campaign v3

If one or more specialist profiles qualify, ship them behind one frozen Gauntlet-only bot identity:

```text
BotId: warden
knob: profile
values: arena, scatter, islands, pinwheel
```

Only qualified values enter the released `Choice`; failed experiments remain internal and freeze no
identifier. The profile asserts its expected geometry before using a book or map-specific evaluator.
On an unexpected board it falls back to a safe general policy so the complete bot contract still
passes on arbitrary supported geometries.

Add `BotEntry.customSelectable: Boolean = true`. `ShippedBots.entries` and lookup continue to include
every entry, so matches, replays, tests and `:lab` can resolve `warden`; Custom-mode selectors filter
entries where the value is false. Do not add a second registry, expose `:bots` to `:ui`, or change
`Bot.chooseMove`.

Keep levels 1, 2 and 7 unchanged. Replace only the opponent/configuration at indices 3 through 6;
their board sizes, shapes and map seeds remain exactly as measured. Start
`snakewarz.gauntlet.v3` and `snakewarz.gauntlet.replay.<n>.v3` with no migration. Leave every v2 value
unread and recoverable. Ordinary replay links remain compatible because they carry the full setup and
the registered specialist identity remains resolvable.

Update the Gauntlet KDoc, `Bots.md`, `Match.md`, `UI.md`, the research summary and this agenda's final
findings with the selected profiles, exact maps, evidence and human result.

---

## Protocol additions for this agenda

The full protocol remains in [`../Research-Process.md`](../Research-Process.md). Every phase brief also
carries these rules:

```text
- Name the level, rows, columns, shape and map seed beside every result. Never pool maps.
- Read distinct games and forfeits before strength. A forfeit voids the run.
- Report firing, hit, reroot, completed-depth or proof coverage before an Elo claim.
- A book label is best-known unless a terminal exhaustive proof establishes optimality.
- Verify every retained state against the live board; uncertainty resets to the safe fallback.
- Measure Chrome cost before selecting an allowance and bundle size before promoting book data.
- Dynamic effort is evaluation-counted and hard-capped per synchronous chooseMove call.
- Keep candidates lab-only until P8. Registering a BotId or Choice value freezes it.
- Do not edit the agenda's Status table. Report; the coordinator writes.
```

## Verification

Tests added by the eventual phases cover:

- exact candidate-table rows and preservation of all four level geometries;
- book structural keys, legal hits, both turn orders, misses and safe fallback;
- map-control and solver refusal unwinding without a partial result;
- principal-variation and subtree reuse accepting only an exact live state;
- budget-bank accounting, deterministic triggers and absolute per-turn caps;
- `customSelectable = false` hiding a bot only from Custom selection;
- `warden` resolution through live matches, ordinary replay and Gauntlet replay;
- v3 progress and winning-run isolation with v2 values left untouched; and
- final move-stream determinism on JVM and Chrome.

The final gate is the scoped JVM suite, browser suite, module purity, ktlint, the production
distribution and its 1.5 MiB gzip limit, explained golden changes, both reference profiles and the
two-session human play pass. No commit or push follows without an explicit request.
