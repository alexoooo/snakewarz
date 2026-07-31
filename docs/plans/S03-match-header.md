# S03 — `MatchSetup` carries a map; spawns and stats follow

**Module:** `:match`
**Depends on:** [S01](S01-core-walls.md), [S02](S02-bots-geometry.md).
**Read first:** [`../Match.md`](../Match.md).

## Goal

The map becomes part of the recorded match description, beside the geometry, the rules, the spawns and
the turn order — the things `MatchSetup`'s KDoc says are *recorded, never re-derived*. The codec is
**not** touched in this session, so `SHIPPED_PAYLOAD` proves the header change by staying still.

---

## Step 1 — `MatchSetup`

`match/src/commonMain/kotlin/ao/snakewarz/match/MatchSetup.kt`

**Parameter position: after `spawns`, before `budgets`.** It is a layout field and belongs with the
layout fields the KDoc already groups. This breaks exactly one positional call site —
`ReplayCodec.kt:210`, which passes all ten arguments positionally — and converting that one to named
arguments is a strict improvement, not drive-by. `MatchSetupTest`'s eight-argument positional calls
stop before the new parameter and compile unchanged.

```kotlin
/** Permanently impassable squares, as **playable** indices `row * cols + col`, strictly ascending. */
walls: IntArray = IntArray(0),
```

Private copy beside `starts`: `private val map: IntArray = walls.copyOf()`.

Validation in `init`, after the existing spawn loop so `playableCount` is in scope:

```kotlin
var previous = -1
for (i in map.indices) {
    require(map[i] in 0 until playableCount) {
        "wall $i is at ${map[i]}, which is off a ${rows}x$cols board"
    }
    require(map[i] > previous) { "walls must ascend and not repeat; ${map[i]} follows $previous" }
    previous = map[i]
}
for (slot in 0 until slotCount) {
    require(!holdsSorted(map, starts[slot])) { "slot $slot spawns on a wall of the map" }
}
```

**Ascending is required here rather than in `:core`** because this is where a stranger's payload lands.
It buys three things at once: O(n) duplicate detection, a canonical form so `equals` is honest, and a
binary-searchable array for the spawn test. `holdsSorted` is a four-line private binary search — no
`java.util.Arrays` in common code.

Accessors, mirroring the existing idiom:

```kotlin
/** The playable wall indices, as a fresh array. */
public fun walls(): IntArray = map.copyOf()

public val wallCount: Int get() = map.size

/** Whether this match is played on a map at all — the codec's version selector. */
public val mapped: Boolean get() = map.isNotEmpty()

/** The walls translated into [grid]'s padded address space, which is what [Board] wants. */
internal fun wallCells(grid: Grid): IntArray =
    IntArray(map.size) { grid.cellAt(map[it] / cols, map[it] % cols).index }
```

**The three hand-written members at `:127-154` must all learn about it**, or every round-trip test
passes vacuously:
- `equals` → `+ map.contentEquals(other.map)`
- `hashCode` → `+ 31 * result + map.contentHashCode()`
- `toString` → append `", walls=${map.size}"`. **The count, never the indices** — this string is
  embedded in `Match.step()`'s playback failure message at `Match.kt:163`.

`create(...)` gains `walls: IntArray = IntArray(0)`, passes it through, and its spawn call becomes
`mostDistantSpawns(grid, walls, slots.size)`.

## Step 2 — `Match` wires it to the board

`match/src/commonMain/kotlin/ao/snakewarz/match/Match.kt:57` — the single construction site:

```kotlin
Board(grid, setup.spawnCells(grid), setup.rules, setup.turnOrder(), setup.wallCells(grid))
```

---

## Step 3 — spawn placement, and the trap in it

`match/src/commonMain/kotlin/ao/snakewarz/match/mostDistantSpawns.kt`

```kotlin
internal fun mostDistantSpawns(grid: Grid, walls: IntArray, count: Int): IntArray
```

> **Do not switch the metric to graph distance in this session.** The current score is
> `1/(sqrt(dRow² + dCol²) + 1)` — Euclidean. Graph distance on a wall-free 4-connected rectangle is
> **Manhattan**: a different metric, a different argmin, so **seat 3+ would move on an empty board**,
> invalidating every three-seat replay header, the logged corpus's geometry, and the whole neutrality
> argument. The plane metric stays; only a reachability *filter* is added.

Rules, in the order that preserves the pins:

1. `require(count <= openCount)` where `openCount = grid.playableCount - walls.size`.
   `MatchSetupTest."a board too small for the field is refused"` still refuses `Grid(1,2)` with 3.
2. **Placed 0 → the lowest open playable index.** On an empty map that is `0`.
   (Pins: `MatchSetupTest.kt:21, 84, 252`.)
3. **Placed 1 → the highest open playable index.** On an empty map that is `playableCount - 1`.
4. **Placed 2+ → the existing Euclidean score, over candidates restricted to open squares reachable
   from placed 0.** One BFS from spawn 0 gives the reachable mask; the scan at `:44-63` skips anything
   outside it. On an empty map every square is open and reachable, so **the filter is a no-op and the
   function is byte-for-byte the incumbent**.

Rules 2 and 3 are not arbitrary. Under the half-turn ρ(r, c) = (rows−1−r, cols−1−c), a row-major index
`i` maps to `playableCount − 1 − i` — so **"lowest open index" and "highest open index" are exact
images of each other**, and the two-seat opening on a ρ-symmetric map (S05) is fair *by construction*
rather than by measurement. A vertical mirror maps (0,0) to `cols−1`, not `playableCount−1`, and the
corner rule would then not be fair; this is why S05 ships the half-turn and nothing else.

Record the deliberate order of operations in the KDoc: the plane metric is kept because changing it
moves every three-seat opening on an empty board; the graph metric is what a maze wants; and the two
seats a match and a ladder are measured at never reach the scored branch, so the escalation is
available and unspent. When three-seat maps are eventually measured, the metric becomes graph distance
**for non-empty maps only** — which re-pins nothing, because no three-seat map replay will exist.

New helper at the `:match` root beside it (CC-06 — nearest enclosing package, since `map/` will read
it too):

```kotlin
/** Which open squares a walk from [from] can reach, as playable indices. Walls only — no snakes yet. */
internal fun openRegionFrom(rows: Int, cols: Int, walls: IntArray, from: Int): BooleanArray
```

`:match` writes its own thirty-line BFS. `FloodFill` lives in `:bots` and `:match → :bots` is a
forbidden edge.

---

## Step 4 — `MatchStats` stops over-counting

`match/src/commonMain/kotlin/ao/snakewarz/match/stats/MatchStats.kt:29,35`

```kotlin
/** Squares a snake could ever stand on: the board, less the wall ring and less the map. */
public val openCells: Int = setup.rows * setup.cols - setup.wallCount

public val fillRate: Double = occupiedCells.toDouble() / openCells
```

`playableCells` → `openCells` is a **rename, not a patch**. No division by zero: a spawn may not be a
wall, so `openCells >= slotCount >= 1`. Only two readers exist — `MatchStats.toString` and
`MatchStatsTest.kt:29,31` — and nothing in `:ui` reads either.

---

## Tests

**New — `match/src/commonTest/kotlin/ao/snakewarz/match/WallNeutralityTest.kt`**

Over a spread of geometries (8×8, 12×12, 20×20, 13×17), seat counts 1..4 and seeds, assert that

```kotlin
MatchSetup.create(rows, cols, slots, seed)                        // no walls argument at all
MatchSetup.create(rows, cols, slots, seed, walls = IntArray(0))   // explicitly empty
```

are `equals`, and that the matches they drive produce **equal `MatchRecord`s and equal
`ReplayCodec.encode` payloads**. This is *"make the neutral setting reproduce the incumbent"* from
[`../Research-Process.md`](../Research-Process.md), with `ChamberEval` reproducing `SurvivalEval`
bit-for-bit as the worked case to copy. Write it before the change.

**`MatchSetupTest`**
- walls must ascend; a wall off the board is refused; a spawn on a wall is refused.
- `equals`/`hashCode` see the map. The existing case at `:153` — *"a new field that nobody added there
  would make two different matches compare the same and quietly break every round trip"* — gains a
  walls arm.
- The three pins at `:21, 84-85, 252` are unchanged and must stay that way.

**New — `mostDistantSpawnsTest`**
- on a map, every spawn is open and every spawn lies in one region;
- on the empty map the output is byte-identical to today at 1..4 seats.

**`MatchStatsTest.kt:29,31`** — rename, plus a map case where `openCells` and `rows * cols` differ.

---

## Done when

```bash
./gradlew :match:jvmTest
./gradlew build
```

and **`ReplayCodecTest`'s `SHIPPED_PAYLOAD` still holds in both directions** — the codec has not been
touched, so a header change that moved it moved something it should not have.