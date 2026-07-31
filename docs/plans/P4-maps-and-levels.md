# P4 — Maps and levels

**Modules:** `:match`, `:app`, `:ui` (tests), `docs/`
**Depends on:** [P2](P2-gauntlet-rename.md), so the level table is written once against `Gauntlet*`.
**Read first:** [`../Maps.md`](../Maps.md) — the two guarantees, the "Adding a shape" checklist, and
*"A shape is a new question, not a new difficulty"*. [`../Bots.md`](../Bots.md) lines 55-117 for the
measured per-level table this phase invalidates.

Names below are written as they are after P2.

## Why

**Open the game up.** Closed maps are less interesting to play, and a positional board is the one a
person can actually read. Specifically: `rooms` is far too closed, `diagonals` and `double-spiral` are
tighter than they need to be, `scatter` is the best of the eight, and level 1 is a bare rectangle.

A shape travels as **squares** in a replay, never as a name, so redrawing one breaks no link anybody has
shared — `docs/Maps.md:23-28` designed for exactly this. It *does* change `mapKey`, so `:lab` runs on
the old drawing stop pooling with the new one; that is `RunHeader.comparabilityKey` doing its job and
is the intended behaviour, not a problem to work around.

Symmetry stays free throughout: `HalfBoard` draws one half and mirrors it, so no shape can get the half
turn wrong. Connectivity does not — `generateMap`'s `checkOneRegion` is a `check`, so a shape that only
*usually* leaves one region **crashes** rather than degrading.

---

## 4.1 Open up three existing shapes

Slugs are unchanged (SW-05 freezes them; the drawing is not frozen). Each is an edit to
`match/.../map/mapCatalogue.kt` plus a `minimumSide` in `MapShape.kt`.

### `rooms` — doors of at least two squares

A door today is **one skipped column**: `rooms` (`mapCatalogue.kt:141-162`) writes a wall line and skips
`col in doorCols`, where `bandMiddles` (295) returns one middle per band, `(start + end) / 2`.

Make `bandMiddles` return a door *band*:

- an **even**-length band gives its two central cells, which are ρ-images of each other;
- an **odd**-length band gives its three central cells, the middle being its own image.

Both are ρ-invariant by construction, which is why "at least 2" comes out as 2-or-3 rather than a flat 2
— a flat 2 on an odd band would break the half turn.

Raise `ROOM_SIDE` from 5 to 7 (line 346) so the rooms are worth having doors into, and **re-derive
`minimumSide`** from 11 (expect around 15): a band narrower than its own door leaves no wall at all, so
this needs a number backed by the `require` in `generateMap` and by `GenerateMapTest`'s minimum-side
case, not a guess. Levels at 16×16 and 20×20 both clear it.

### `diagonals` — thinner bars

`diagonals` (110-127) leaves a centred opening of one square on an odd-length bar and two on an even
one. Widen it to three and four by the same odd/even pairing, and lift `DIAGONAL_PERIOD` from 5 to 6
(line 344) — note `symmetricAnchor` (258) solves `2a ≡ extent - 1 (mod period)` and `error`s if no
residue survives the half turn, so the new period has to be checked at every size
`GenerateMapTest.SIZES` offers. Re-derive `minimumSide` from 9.

### `double-spiral` — wider corridors

`SPIRAL_STEP` 3 → 4 (line 352). `spiralInset(side) = SPIRAL_MARGIN + side * SPIRAL_STEP / 2`, so the
current insets run 1, 2, 4, 5, 7, 8 — corridors alternating one and two squares. A step of 4 gives
1, 3, 5, 7 and uniform two-square corridors. Re-derive `minimumSide` from 13; **if it lands above 14,
level 6's board grows with it.**

---

## 4.2 Three new shapes

Adding an enum constant is compiler-enforced in `drawShape` (`mapCatalogue.kt:13-24`, an exhaustive
`when` with no `else`), and `GenerateMapTest` sweeps `MapShape.entries` at every size — so symmetry,
one-region, ends-pair and determinism all enrol for free.

**Three edits are not free**, and the third is not in `docs/Maps.md`'s checklist:

1. the `<option>` in `index.html`, before `#map-from-replay` — a missing one is a boot `error()` naming
   the shape (`SetupPanel.kt:49-54`);
2. the catalogue table in `docs/Maps.md:32-46`, which currently says "Eight shapes";
3. **the same `<option>` in `SetupPanelTest.SKELETON`** (`ui/src/wasmJsTest/.../SetupPanelTest.kt:223-233`)
   — without it *every* case in that file fails at construction. **Add this step to the checklist.**

### `islands` — `scatter` with blocks

The requested variant: isolated blocks of mixed size (1×1, 2×1, 1×2, 2×2, 3×1, 1×3) instead of always
one square, chosen from `scatter`'s own `MAP_STREAM` so no new RNG stream is introduced. Density counts
**squares placed**, as `scatter` does, not blocks.

Needs one new `HalfBoard.placeIsolatedBlock(row, col, height, width): Boolean`, generalising
`placeIsolated` (65-78) with three conditions:

- the block **and its 8-neighbourhood** are clear;
- the block does not touch its own mirror;
- **the block keeps one free square from every board edge.**

That last clause is what makes connectivity an argument rather than a hope: a free border ring plus
pairwise 8-non-adjacent convex rectangles leaves the open squares in one region. `minimumSide` 7.

### `pinwheel` — wide lanes, rotational

Two straight arms offset from centre; the half turn supplies the other two. Open and positional, which
is the direction this phase is pushing. `minimumSide` 11.

### `arena` — a solid centre and four satellites

A block at the centre of the board — 2×2 at the small end, scaling with the side — plus four symmetric
satellite squares, everything else open.

**This is also what level 1 becomes.** A middle 2×2 and a handful of others on an 8×8 is exactly this
shape at its minimum, so it is one shape rather than a one-level special case. The most open thing in
the catalogue after `empty`, and the one that makes a level about position rather than corridors.
`minimumSide` 7.

Eight shapes become eleven. **This is the trimmable part of the plan** if that is too many.

---

## 4.3 Eleven levels, and a Final Boss

`GauntletProgress` already allows it: the cleared set is a bitmask over one `Int` behind a
`check(levels < Int.SIZE_BITS)`. The level tiles are **static markup**, so an eleventh
`<li>` / `#level-11` goes into `index.html` beside the ten (lines 98-200).

| # | title | opponent | params | budget | board | map |
|---|---|---|---|---|---|---|
| 1 | Static | `random` | — | 0 | 8×8 | **arena** |
| 2 | The Sweeper | `burninhell` | — | 0 | 10×10 | cross |
| 3 | The Hugger | `wallhug` | — | 0 | 10×10 | pillars |
| 4 | Room Reader | `space` | — | 0 | 12×12 | scatter |
| 5 | The Crowder | `pressure` | — | 0 | 12×12 | ring |
| 6 | The Hunter | `chase` | — | 0 | 14×14 | double-spiral |
| 7 | The Gambler | `flat-monte-carlo` | — | 400 | 14×14 | diagonals |
| 8 | The Student | `uct` | — | 600 | 16×16 | **islands** |
| 9 | The Planner | `puct` | `eval=territory` | 1000 | 16×16 | **pinwheel** |
| 10 | The Oracle | `alphabeta` | `eval=territory` | 1000 | 20×20 | rooms |
| 11 | **Final Boss** | `alphabeta` | `eval=chamber` | 1000 | 8×8 | empty |

`empty` now appears exactly once, at the top. That is the arc: you open on a board with a few obstacles
and finish on a bare one against the strongest thing in the registry.

**The boss is the top search bot running its dearest appraisal.** `Gauntlet.kt`'s KDoc records that
`eval=chamber` costs about 4.6× `territory` per evaluation and overruns `:ui`'s frame slice on a 20×20
— on an 8×8 it is affordable, and a small empty board is a pure tactical duel with nowhere to hide. So
eleven levels, ten algorithms, no repeated configuration.

If 4.1 pushes `double-spiral`'s minimum above 14, level 6's board grows to 16×16 and level 7 keeps
14×14 — board size stops being monotone, which nothing tests and nobody sees.

### `GauntletTest`

- `EXPECTED_LEVELS` 10 → 11.
- *"ten different opponents, which is the whole claim the ladder makes"* is **restated, not weakened**:
  unique `(slug, params)` pairs and unique titles. `alphabeta` appearing twice with different `eval` is
  the point of the boss, not an exception to the rule.
- Its other cases pass unchanged: boards clear their shape's minimum, non-`EMPTY` shapes draw walls, the
  player is seated first, and the budget column is a zero prefix then non-decreasing
  (0,0,0,0,0,0,400,600,1000,1000,1000 ✓).

---

## 4.4 This table is a hypothesis, and must say so

`Gauntlet.kt`'s KDoc places `cross` at level 2 and `double-spiral` at level 6 **by measurement**, and
`docs/Bots.md:74-112` carries the per-level reference scores. Redrawing three maps and adding three
invalidates all of it. Per the decision in [`README.md`](README.md), the table ships unmeasured and the
measurement goes on the next research agenda:

- rewrite `Gauntlet.kt`'s placement rationale to say plainly which placements are now guesses, keeping
  the two worked examples as *why placement is measured at all*;
- mark `docs/Bots.md`'s per-level table stale, with the date and the reason;
- add the re-measurement to `docs/research/*_Research-Agenda.md` — `:lab gauntlet --rounds 200`, the
  ordering being right when the reference's score falls level by level;
- `docs/Maps.md:150-162`'s measured numbers for `cross` and `double-spiral` were taken on the **old**
  drawings, so they get the same note.

---

## Files touched

| File | What |
|---|---|
| `match/.../map/MapShape.kt` | three new constants with slugs and minimums; three revised minimums |
| `match/.../map/mapCatalogue.kt` | three redraws, three new draw functions, three `drawShape` branches, revised constants |
| `match/.../map/HalfBoard.kt` | `placeIsolatedBlock` |
| `match/.../gauntlet/Gauntlet.kt` | the eleven-level table and its rewritten KDoc |
| `match/src/commonTest/.../map/GenerateMapTest.kt` | nothing structural — the sweep enrols the new shapes; check `SIZES` still covers every minimum |
| `match/src/commonTest/.../gauntlet/GauntletTest.kt` | `EXPECTED_LEVELS`, the restated uniqueness claim |
| `app/.../resources/index.html` | three `<option>`s, one `<li>` for level 11 |
| `ui/src/wasmJsTest/.../panel/SetupPanelTest.kt` | the same three `<option>`s in `SKELETON` |
| `docs/Maps.md` | catalogue table, shape count, and the missing checklist step |
| `docs/Bots.md`, `docs/research/*-Research-Agenda.md` | staleness notes and the re-measurement item |

## Verification

```bash
./gradlew jvmTest --tests "*GenerateMap*" --tests "*BoardMap*" --tests "*Gauntlet*" --tests "*OpeningSetup*"
./gradlew allTests -PbrowserTests=true                  # SetupPanelTest's SKELETON
./gradlew :lab:run --args="gauntlet --rounds 40"        # draws every level's map for real
./gradlew :lab:run --args="play uct puct --map islands" # and each new shape on its own
```

By hand: items 15 of the release checklist in [`README.md`](README.md), and confirm at 8×8 that `rooms`
is now refused with its new minimum in the message.
