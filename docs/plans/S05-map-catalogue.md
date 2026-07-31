# S05 — `match/map/`: shapes, symmetry, connectivity, eight maps

**Module:** `:match`
**Depends on:** [S03](S03-match-header.md) (needs `openRegionFrom`).
**Read first:** **SW-01** and **SW-05** in [`../Coding-Standards.md`](../Coding-Standards.md).

## Goal

Named map shapes that generate fair, connected wall sets at any board size.

**A map is a materialised wall set. A shape is how one is made. Nothing outside `map/` ever sees a
shape.** That split is what makes the codec decision pay: `MatchSetup` takes an `IntArray`, the replay
carries a bitmap, and `MapShape` stays free to be redesigned or deleted without touching a single
shared link.

---

## Files

Four, in `match/src/commonMain/kotlin/ao/snakewarz/match/map/` (CC-06 comfortable, CC-15 one public
declaration each).

### `BoardMap.kt`

```kotlin
public class BoardMap(public val rows: Int, public val cols: Int, walls: IntArray) {
    public val wallCount: Int
    public fun walls(): IntArray                    // a fresh copy, matching MatchSetup.spawns()
    public fun isWall(row: Int, col: Int): Boolean
    override fun toString(): String                 // "BoardMap(12x12, 24 walls)"

    public companion object {
        public fun empty(rows: Int, cols: Int): BoardMap

        /** A hand-drawn map: `#` wall, `.` open — the alphabet `ChamberTreeTest`'s pictures use. */
        public fun of(picture: List<String>): BoardMap
    }
}
```

`init` requires ascending, in range, and nothing more. **Symmetry and connectivity are guarantees of
the generator, not of the type** — a hand-drawn test fixture is legitimately neither.

### `MapShape.kt`

The catalogue, as an enum. Each value declares its `minimumSide`.

### `generateMap.kt`

```kotlin
public fun generateMap(
    rows: Int,
    cols: Int,
    shape: MapShape,
    density: Double = 0.0,
    seed: Long = 0L,
): BoardMap
```

A file-level function named for what it does (SW-06 — no `Util`, no `Helper`). RNG is `SplitMix64`,
never `kotlin.random.Random` (SW-01), forked on a distinctive stream constant kept clear of
`MatchSetup.SETUP_STREAM = -1` and of `openingSetup`'s `0x5A17`.

`require(rows >= shape.minimumSide && cols >= shape.minimumSide)` runs **before** generating, so a
shape that cannot express itself at a size fails with its own name rather than emitting a degenerate
map.

**The universal recipe: generate the top half — plus the middle row on an odd board — and reflect
through the half-turn ρ(r, c) = (rows−1−r, cols−1−c).** Symmetry then costs nothing to guarantee and
cannot be got wrong per shape.

Four `check`s at the end, failing loudly (CC-08, and *"a forfeit is a defect and never a result"*):

1. every wall is playable, ascending, no duplicate;
2. the wall set is invariant under ρ;
3. **the open squares form exactly one region.** Stronger and simpler to state than "the spawns are
   reachable", and it forbids decorative sealed pockets — dead board that skews `openCount`;
4. the lowest and highest open indices exist and are ρ-images. This follows from 2, and is asserted
   because it *is* the fairness claim: [S03](S03-match-header.md) seats slot 0 at the lowest open
   index and slot 1 at the highest, and under ρ those two are exact images. **So the two-seat opening
   on any map from this generator is fair by construction, not by measurement.**

`lab/.../arena/openingSetup.kt:87`'s `reflectedPair` already computes exactly ρ, so `--openings
mirrored` stays fair on these maps with no change to that function's geometry.

### `mapCatalogue.kt`

The per-shape bodies, if `generateMap.kt` would otherwise pass ~200 lines.

---

## The starter catalogue, in difficulty order

| Shape | Construction | What it changes | Min side |
|---|---|---|---|
| `EMPTY` | nothing | the incumbent; the neutrality test's neutral setting | 1 |
| `PILLARS` | isolated single squares on a lattice of period 3 | barely changes the game; changes the **colour balance**, which is `ChamberEval.parityWeight`'s whole premise | 5 |
| `RING` | hollow rectangle inset from the border, one gap a side | an inside/outside topology nothing on an empty board has | 7 |
| `CROSS` | one horizontal and one vertical bar, gaps at the centre | four rooms joined at chokepoints — the first shape on which `Separation.permanent` can fire at two seats | 7 |
| `DIAGONALS` | parallel anti-diagonal bars with gaps (the anti-diagonal family is ρ-invariant) | breaks the axis-aligned assumption in `MovePrior`'s wall reading and in `TAIL_DISTANCE`'s Manhattan proxy | 9 |
| `ROOMS` | k×k chambers with ρ-symmetric doorways | where `ChamberTree`'s decomposition earns its keep | 11 |
| `DOUBLE_SPIRAL` | two interleaved arms | one long corridor; the game becomes pure space-filling and parity dominates | 13 |
| `SCATTER` | density-parameterised, half-and-reflect | the randomiser, for a field that is not four distinct games | 5 |

**`DOUBLE_SPIRAL`, not a single spiral.** A single spiral is chiral: it cannot be ρ-symmetric, so it
cannot be fair under the rule above. Two interleaved arms can.

**`SCATTER` has no resampling loop.** It places walls one at a time in the top half, reflecting each,
and **skips any placement whose eight-neighbourhood already holds a wall**. Isolated single-cell walls
with a gap between them cannot disconnect a rectangle, so connectivity is a property of the
*construction* rather than of a retry. If the requested density cannot be reached it fails loudly,
reporting the count it managed — *"fail loudly rather than sampling until it passes"*, without a
generator that is a coin flip.

Every shape is a function of `(rows, cols)`, so the same name means the same *idea* at 8×8, 12×12,
20×20 and 40×40 — which is what lets a later research phase run one field per geometry per map.

**SW-05: a `MapShape` name reaches a UI picker and a `:lab` flag.** Freeze it on release. Name shapes
for what they look like, never for lineage.

---

## Tests

`match/src/commonTest/kotlin/ao/snakewarz/match/map/`

- **Every catalogue shape at every size the game offers** — 8, 12, 16, 20 and 40 square, plus a
  non-square — is ρ-symmetric, is exactly one open region, and leaves at least a stated fraction of
  the board open.
- `generateMap` fails loudly below a shape's `minimumSide`, naming the shape.
- The same `(shape, rows, cols, seed)` produces the same map twice (SW-01).
- `SCATTER` at a density it cannot reach fails loudly rather than looping.
- `BoardMap.of(picture)` round-trips against `isWall`.
- The lowest and highest open indices are ρ-images for every generated map — the fairness claim,
  asserted rather than assumed.

---

## Done when

```bash
./gradlew :match:jvmTest
./gradlew build
```

and a hand check that each shape looks like its name — `MatchState.toString()` from
[S01](S01-core-walls.md) paints `#` for a wall, so a scratch test that prints one match per shape is
the cheapest possible review of eight maps.