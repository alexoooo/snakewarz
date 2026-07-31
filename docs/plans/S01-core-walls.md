# S01 — The neutrality tests, then interior walls in the engine

**Module:** `:core`
**Depends on:** nothing
**Read first:** [`../Coding-Standards.md`](../Coding-Standards.md), and the non-obvious facts in
[`../../CLAUDE.md`](../../CLAUDE.md).

## Goal

`Board` accepts a set of permanently impassable interior squares, and a board with none of them plays
**bit-identically** to today.

`Occupancy` already carries `WALL: Byte = -1` for the padded border ring, and `freeNeighbors` tests
`== EMPTY` — so an interior wall blocks legality with **no new branch on the hottest path in the
program**, and rides into every search arena for free through `copyFrom`. Reuse that code. Do not add
a third occupant class.

**Vocabulary: `wall`, one word, everywhere in code.** `Board`'s own KDoc already uses *obstacle* for a
corpse — *"A dead snake's body stays on the board as an obstacle"* — and the two must not be
confusable. "Obstacle map" stays as prose in `docs/`.

---

## Step 0 — write the neutrality tests first, against today's code

They must exist and pass **before** anything changes, so they cannot later be written to fit the
outcome. Both are trivially green today.

`core/src/commonTest/kotlin/ao/snakewarz/core/rules/WallNeutralityTest.kt`

Play a full game with `chosenMove` over a spread of geometries (8×8, 12×12, 20×20, 13×17) and seat
counts 1..4, once through the old constructor and once through the new one with an explicitly empty
wall array, comparing `Board.signature()` (the existing helper at `core/.../rules/boardOf.kt:44`)
turn by turn. `signature()` already folds `hash` and `occupancyHash`, so this proves the wall-key
claim in step 3 directly. Until step 2 lands, both arms call the same constructor — that is fine and
is the point.

---

## Step 1 — `Occupancy` gains one method, and stops erasing the map

`core/src/commonMain/kotlin/ao/snakewarz/core/grid/Occupancy.kt`

```kotlin
/**
 * Marks [cell] permanently impassable, the way the border ring is.
 *
 * Stamped once, before any snake is placed, and never taken back — which is why it is outside
 * [hash] exactly as the ring is, and why [clear] leaves it alone.
 */
public fun wall(cell: Cell) {
    require(owner[cell.index] == EMPTY) {
        "$cell is already ${owner[cell.index]}, so it cannot become wall"
    }
    owner[cell.index] = WALL
}
```

That one `require` earns three things at once: it refuses a duplicate wall, it refuses walling an
occupied square, and it removes any need for callers to hand in an ordered array.

Then `clear()` (`:108-114`), today `owner.fill(EMPTY, rowStart + 1, rowStart + cols + 1)`:

```kotlin
/** Clears every snake, leaving every wall — the padded ring and the map alike — in place. */
public fun clear() {
    for (row in 1..grid.rows) {
        val rowStart = row * grid.stride
        for (index in rowStart + 1..rowStart + grid.cols) {
            if (owner[index] != WALL) owner[index] = EMPTY
        }
    }
    hash = 0L
}
```

**Why this rather than re-stamping in `Board.reset()`.** `clear()`'s only caller is `Board.reset()`,
whose only callers are `Board`'s init and tests — it is not a hot path (`copyFrom` is, and it is
untouched). Re-stamping would leave an ordering bug permanently available: any future path that
clears without re-stamping silently plays a different game. This makes the invariant a property of the
type that owns the bytes.

`vacate()` (`:74-78`) stays unguarded and stays correct: its only caller is `Board.apply` popping its
own tail, which is never a wall.

---

## Step 2 — the Zobrist decision

**`Occupancy.hash` does not change.** Two reasons, and neither is "it costs a xor":

- `OccupancyTest.kt:25` pins `assertEquals(0L, occupancy.hash)` for a fresh board, and `Board.reset()`
  builds `auxHash` from scratch assuming the occupancy half restarts at zero. Folding walls in moves
  both.
- The hash's stated job (`BoardView.hash` KDoc) is the undo journal: *"a search descends and backs up
  millions of times a turn and is correct only if the board it returns to is bit-for-bit the board it
  left"*. Both sides of every such comparison are the same board on the same map. A constant added to
  both changes nothing.

**The map enters `Board.hash` instead**, so anything keyed on `BoardView.hash` — a transposition
table, `:lab`'s corpus, a position-derived tie-break — cannot conflate two maps:

```kotlin
// A fifth key family, folded in once. Xor is order-independent, so two orderings of the same map
// key identically -- which is what makes this usable as copyFrom's guard as well as as a key.
private val wallKey: Long = walls.fold(0L) { key, cell -> key xor stateKey(FAMILY_WALL, 0, cell) }
```

xor'd into `auxHash` inside `reset()`, beside `toActKey()`. **An empty wall array folds to `0L`**, so
`Board.hash` on a mapless board is byte-identical to today — which is what keeps `BoardUndoTest` and
`BoardScratchTest` green and what the whole neutrality argument rests on.

Amend `Occupancy`'s *"### Zobrist hashing"* KDoc section with one sentence: the map is fingerprinted
by `Board`, not here, so a key derived from `BoardView.hash` distinguishes two maps while the
incremental occupancy half stays a pure function of the occupied squares.

---

## Step 3 — `Board`

`core/src/commonMain/kotlin/ao/snakewarz/core/rules/Board.kt`

```kotlin
public class Board(
    override val grid: Grid,
    spawnCells: IntArray,
    override val rules: RulesConfig = RulesConfig(),
    turnOrder: IntArray = IntArray(spawnCells.size) { it },
    /** Permanently impassable squares, as padded [Cell] indices — the same address space as [spawnCells]. */
    wallCells: IntArray = IntArray(0),
) : BoardView
```

Appended with a default, so all eight existing `Board(grid, cells, rules, turnOrder)` call sites
compile unchanged.

- Field beside `spawns` (`:66`): `private val walls: IntArray = wallCells.copyOf()`.
- Validation in the second `init` block, **before** the spawn loop at `:106-112` and before
  `reset()`:
  ```kotlin
  for (i in walls.indices) {
      val cell = Cell(walls[i])
      require(grid.isPlayable(cell)) { "wall $i is not a playable square of $grid" }
      occupancy.wall(cell)          // refuses a duplicate, in the type that owns the byte
  }
  ```
- One line added inside the existing spawn loop:
  ```kotlin
  require(!occupancy.isWall(cell)) { "spawn for slot $slot stands on a wall of the map" }
  ```
- `copy()` (`:323`) → `Board(grid, spawns, rules, order, walls)`.
- `copyFrom` (`:289`) gains an **O(1)** guard beside the `rules` one:
  ```kotlin
  require(other.wallKey == wallKey) { "cannot copy a board played on a different map into this one" }
  ```
  O(1) is the requirement, not a nicety: `copyFrom` runs per rollout reset, and an array compare on a
  20×20 with a hundred walls would be a hundred int compares per rollout (SW-03). The occupancy array
  copy already carries the walls' *bytes*; the guard exists because `this.walls` — what `reset()`
  re-stamps from and what `copy()` hands on — is not copied.

**No connectivity check here.** `:core` validates what makes the *rules* well-defined. Whether a map
seals a spawn into a pocket is a fairness question belonging to the generator (S05), and refusing it
here would make a legitimately-recorded weird match un-replayable.

---

## Step 4 — `BoardView` gains two declarations

`core/src/commonMain/kotlin/ao/snakewarz/core/rules/BoardView.kt`

```kotlin
/**
 * Whether [cell] can never be entered — the padded border ring, or a wall of the map.
 *
 * What [ownerOf] cannot say: an empty square and a wall both read [SnakeId.NONE], and the renderer
 * needs to tell them apart. Constant for the life of a match.
 */
public fun isWall(cell: Cell): Boolean

/**
 * Playable squares that are not permanently wall — what a share of the board is a share *of*.
 *
 * `Grid.playableCount` is `rows * cols` and stays pure geometry; this is the quantity every
 * evaluation normalising by "the board" actually wants, and on a map the two differ.
 */
public val openCount: Int
```

`Board` implements `isWall` as `occupancy.isWall(cell)`, and `openCount` as a property initialised
once to `grid.playableCount - walls.size`.

`Grid` itself does **not** change. It stays pure immutable geometry shared between the live match and
every search arena; `playableCount` keeps meaning `rows * cols`, which is what `SnakeBody` is sized
from.

---

## Step 5 — `MatchState` must not lie

`MatchState.toString()` (`:34-54`) paints `.` for everything that is not a body, so on a map the ASCII
picture a developer stares at when a walled test fails would show the walls as empty board. The
constructor is `internal`, so this is free:

- `MatchState internal constructor(..., private val walls: IntArray)`. The array is `Board`'s own
  private and is never mutated after construction, so share it rather than copy it and `snapshot()`
  stays O(total snake length).
- `toString()` stamps `'#'` at each wall's `(row, col)` before the snakes, matching the `#`/`.`/`@`
  picture alphabet `ChamberTreeTest` already uses.
- Keep the array private. Its only consumer is this `toString`; `:ui` renders off `BoardView` (CC-10).

---

## Tests

**New — `core/src/commonTest/kotlin/ao/snakewarz/core/grid/OccupancyTest.kt` additions**
- `wall()` stamps; refuses an already-occupied square; refuses a duplicate.
- `hash` stays `0L` after walling.
- `clear()` preserves an interior wall (extend the existing `:127` case, which today covers the ring).
- `freeNeighbors` treats an interior wall exactly like the ring.

**New — `core/src/commonTest/kotlin/ao/snakewarz/core/rules/BoardStateTest.kt` additions**
- a wall off the board is refused; a spawn on a wall is refused.
- `copy()` carries the map; `copyFrom` from a differently-mapped board is refused.
- a spawn walled in on all four sides is `TRAPPED` on its first move, not an error.

**New — `BoardUndoTest`**: re-run the existing full-match property test on a walled fixture.
`signature()` covers a field added later the moment it appears in the helper.

**`WallNeutralityTest`** from step 0, now genuinely comparing two code paths.

---

## Done when

```bash
./gradlew :core:jvmTest :bot-api:jvmTest
./gradlew build
```

and, critically:

```bash
./gradlew :bots:jvmTest --tests '*GoldenMoveStreamTest*'
```

**All sixteen golden hashes hold**, especially `-6119216452350361752` — `wallhug`×`wallhug`, *"pinned
by the rules alone. If it ever moves, the engine moved"*. If one moves, stop and name the cause before
touching anything.

`OccupancyTest.kt:25` (`assertEquals(0L, occupancy.hash, "walls are … deliberately outside the
hash")`) must still pass unchanged.