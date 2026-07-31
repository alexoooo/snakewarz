# S02 — The two bot sites that read geometry instead of occupancy

**Module:** `:bots`
**Depends on:** [S01](S01-core-walls.md) — needs `BoardView.isWall` and `BoardView.openCount`.
**Read first:** [`../Bots.md`](../Bots.md), [`../Coding-Standards.md`](../Coding-Standards.md).

## Goal

Every bot primitive already routes through `isFree`/`freeNeighbors` and therefore handles interior
walls with no change at all. **Two sites do not**: they decide "is this square against a wall" by
comparing row and column against the border, and an interior wall is invisible to both. A third site
family normalises by board *area*, which stops being the right denominator.

Each fix must be a **no-op on a wall-free board**, byte for byte.

## What is already safe — do not touch it

`FloodFill`, `ShortestPaths`, `nearestOpponent`, `RolloutPolicy`, `SpaceOwnership`, `TempoOwnership`'s
computation, `ChamberTree`, `SurvivalHorizon`, `FillableSpace`, and `CellBits.freeSquaresOf` all read
`board.isFree`, so the wall byte falls out for free.

`TerritoryEval` is safe too, and the research agenda overstates this: it normalises by `totalOwned`
(`:219`) and by the two-snake pool (`:213`). The only `playableCount` in the file is `:52`, a
historical KDoc note about what `cost` used to be. **No change.**

Do **not** fold the "word-level free mask" idea into this session. The wall byte is already inside
`isFree`, nothing about `CellBits` changes, and a hot-path rewrite inside a premise change is exactly
what makes a neutrality argument unreadable (CC-07).

---

## Step 1 — `MovePrior`: count walls, not coordinates

`bots/src/commonMain/kotlin/ao/snakewarz/bots/search/puct/MovePrior.kt:241-250`

Today it compares `row`/`col` against `0`/`lastRow`/`lastCol`. Replace with a question the board can
answer:

```kotlin
if (walling) {
    var edges = 0
    var at = 0
    while (at < RING) {                       // orthogonals only, same stride as today
        if (board.isWall(Cell(destination + ring[at]))) edges++
        at += 2
    }
    if (edges != 0) score += wallBonus * edges
}
```

**On a wall-free board this counts exactly the border-ring neighbours — exactly what the coordinate
comparison counted** — so it is a no-op on every existing golden.

Do **not** be tempted to count blocked neighbours generally. That would fold snake bodies into the
reading and move the game on an empty board. The KDoc at `:40-44` already says which reading is
wanted: *"How many of the destination's blocked neighbours are the wall rather than a snake… a body
square clears when a tail retracts and the board never does."* The rewrite is that sentence,
finally computed the way it is written.

`private val lastRow` / `lastCol` (`:187-188`) then have no reader. Delete them (CC-10).

**The goldens cannot catch a mistake here**, because `PuctBot.PRIOR_WALL` defaults to `0.0` and the
branch is dead at every shipped setting. That is why this fix needs a test of its own — below.

---

## Step 2 — `PositionFeatures`: `wallsAt`, and the normalisations

`bots/src/commonMain/kotlin/ao/snakewarz/bots/search/learned/PositionFeatures.kt`

**`wallsAt` (`:305-312`)** becomes the same four-neighbour `isWall` count. But it is called from
`into()`, which has no board in hand — so the *reading* moves into `measure()`'s per-slot loop, where
the board is, and is stored exactly as `fill` and `progress` already are:

- `private val headWalls = IntArray(slotCount)`, written at `:197`, read at `:275`.
- `headRow`/`headCol` stay: `RIVAL_DISTANCE` (`:245`) still needs them.
- `HEAD_WALLS`'s KDoc changes from *"Board edges the head sits against"* to *"impassable squares
  beside the head"* — a strictly better reading, and the same number on an empty board.

**The denominators:**
- `private var open = 0`, set from `board.openCount` at the top of `measure()`.
- `:203` → `fill = 1.0 - space.walkableCount().toDouble() / open`. This is the change that makes
  `BOARD_FILL` reach `0.0` on a fresh walled board instead of starting at `K/playable` and never
  getting there.
- `:252` → `val open = this.open.toDouble()`, and `:261`/`:262` divide by it. **Rename the local from
  `playable` to `open`.** The research agenda is explicit: *"calling it `playableCount` on a map is
  how a wrong `fill` survives review."*

---

## Step 3 — one KDoc sentence

`bots/src/commonMain/kotlin/ao/snakewarz/bots/search/puct/TempoOwnership.kt:177`

*"Against `Grid.playableCount` that is how full the board is"* → against the board's **open** squares.
`walkableCount()` returns `open.count()`; the computation is already right and its only reader is
`PositionFeatures.kt:203`. Doc-only.

---

## Step 4 — `eval=learned` is not refitted in this session, and here is the honest statement

Two questions, and only one of them is this session's.

**Is it still correct on an empty board?** Yes, byte-identically — `openCount == playableCount` there
and the `wallsAt` rewrite is equivalent — so `LearnedWeights.ENCODED` stays and
`GoldenMoveStreamTest`'s `6798631882534688247` holds. That is a *requirement*, not an aspiration:
refitting here would move a cross-target golden and destroy the neutrality argument.

**Is it *good* on a map?** No, and it does not need to be yet. Every reading is a ratio, a share or a
flag by design (the class KDoc: *"the same number means the same thing on a 3x7 and on a 200x200"*),
so on a map every feature stays **in range and defined** — `fill` still runs 0→1, `REGION_SHARE` is
still ≤ 1, `HEAD_WALLS` still lands in {0, .25, .5, .75, 1}. The model is extrapolating to a
distribution it never saw; it is not degenerate.

**Correct on empty, honest on maps, unfitted for maps.** Say exactly that in the KDoc.

The refit belongs to a later research phase with a proper instrument — loss on a map corpus by
empty-fitted weights against the same weights refitted, then a field. One consequence to record for
whoever does it: `lab/.../train/Corpus.kt:169` keys its `PositionFeatures` cache on
`"${rows}x${cols}x$slots"`, which on a map corpus conflates two different maps of the same size. The
reader is map-agnostic so nothing breaks today, but a map refit must key on the map or it will pool.

---

## Tests

`bots/src/commonTest/kotlin/ao/snakewarz/bots/search/puct/MovePriorTest.kt`
- At `priorWall != 0`, the destination-wall count equals the old coordinate count for **every cell** of
  a wall-free board. This is the equivalence the goldens cannot see, because the branch is dead at
  the shipped default.
- It reads correctly beside an interior wall.

`bots/src/commonTest/kotlin/ao/snakewarz/bots/search/learned/PositionFeaturesTest.kt`
- `HEAD_WALLS` unchanged on an empty board (add this case **before** the rewrite).
- `BOARD_FILL` is exactly `0.0` on a fresh walled board — the agenda's named failure mode.
- `REGION_SHARE` ≤ 1 on a walled board.

`bots/src/commonTest/kotlin/ao/snakewarz/bots/BotContractTest.kt`
- A **walled sweep**: every registry entry, on a hand-drawn walled board, never returns an illegal
  move while a legal one exists, never overruns its budget, and every match ends. Ten bots' worth of
  proof that "interior walls are free on the hot path" is true, for the cost of one fixture.
- `HeadlessMatch` gains `walls: IntArray = IntArray(0)`, and a neutrality case asserts
  `hashOf(..., walls = IntArray(0)) == hashOf(...)` for each searcher.

`ChamberTreeTest` / `SurvivalHorizonTest`
- Add a **real-wall** case beside the existing corpse-as-wall `Region` fixture. Do not rewrite the
  existing one (CC-07): the corpse path is a rule and still wants testing. These two already generate
  randomly walled boards for their brute-force oracles, so the primitives are proven on walled regions
  today — what was never proven is anything that normalises by board area.

---

## Done when

```bash
./gradlew :bots:jvmTest          # a couple of minutes; BotLadderTest plays several hundred matches
./gradlew build
```

**All sixteen `GoldenMoveStreamTest` hashes hold on the JVM**, then in real Chrome:

```bash
./gradlew :bots:wasmJsBrowserTest -PbrowserTests=true --rerun -i
```

Never with `--tests` — it silently runs one method and reports success.

`BotLadderTest`'s thresholds are *measurements*, and nothing about an empty-board game changes here,
so **none of them may need re-measuring**. If one moves, the change is not neutral: find the cause
before touching the number.