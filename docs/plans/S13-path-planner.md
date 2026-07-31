# S13 — `PathPlanner` and a path-sized input queue

**Module:** `:match`
**Depends on:** [S03](S03-match-header.md).
**Read first:** [`../Match.md`](../Match.md) §*"A match with a person in it runs no clock"*, and
**SW-03** in [`../Coding-Standards.md`](../Coding-Standards.md).

## Goal

The engine side of drag-to-steer: turn "the player pointed at that square" into a queue of directions,
in a module with no DOM and a JVM test target.

Nothing in `:ui` changes here. [S14](S14-path-input.md) is the half that touches a pointer.

---

## Step 1 — `InputBuffer` learns to take a whole path

`match/src/commonMain/kotlin/ao/snakewarz/match/human/InputBuffer.kt`

**Keep `push`, `take` and `clear` exactly as they are.** Their two odd rules exist for the keyboard and
are still right there: drop the *newest* when full, because a human wants their latest intent and not a
backlog; and collapse a repeat of the direction queued last, because auto-repeat is the keyboard
talking. Do not generalise them away.

Add one method:

```kotlin
/**
 * Replaces everything queued with [count] directions from [directions], as one swap.
 *
 * A drawn path is a single intent, not a run of key presses — so it neither collapses its repeats
 * (five squares east is five moves, not one) nor drops its newest end when it outgrows what was
 * queued before it. Replacing rather than appending is what makes redrawing mid-drag cheap and what
 * makes letting go mean *stop*.
 */
public fun replace(directions: IntArray, count: Int)
```

Capacity: `:app` constructs the buffer once in `main()`, before any board exists, so it cannot be
board-sized. Add

```kotlin
/** Room for a path drawn across a large board. A person does not draw a longer one. */
public const val PATH_CAPACITY: Int = 512
```

and have `:app` use it. Two kilobytes, once. Keyboard behaviour is unaffected: the collapse rule is on
`push`, so a held arrow still queues at most one pending move whatever the capacity.

`take(legal)` is unchanged and is the reason a path can be a *plan* rather than a promise: it discards
queued directions that have become illegal and returns the first legal one. Humans die by being
trapped, not by input lag.

---

## Step 2 — `PathPlanner`

`match/src/commonMain/kotlin/ao/snakewarz/match/human/PathPlanner.kt`

```kotlin
/**
 * Routes a drawn path across the board: breadth-first from where the path currently ends to the
 * square the player is pointing at, over squares that are free *now* and are not already on the path.
 *
 * A plan, never a promise. Tails retract and opponents move, so a route that was clear when it was
 * drawn can kill you by the time it is walked — which is the game, and is why this does not try to
 * predict the board it will actually meet.
 */
public class PathPlanner(grid: Grid) {
    /**
     * Extends [path] to reach [target], returning the new length, or [length] unchanged when no
     * route exists.
     */
    public fun extend(board: BoardView, from: Cell, path: IntArray, length: Int, target: Cell): Int
}
```

SW-03 applies — it is in a pure module and the shape should match everything around it, even though
this runs a handful of times a second rather than millions:

- every buffer is a constructor-allocated instance field sized off `grid.cellCount`;
- primitive arrays only, no `Sequence`, no `List<Cell>`, no `data class`;
- no allocation per call. A visited stamp is an `IntArray` of generation counters, bumped per call,
  rather than an array cleared each time.

Why BFS rather than "append the cell if it is adjacent": a finger jumps several cells between
`pointermove` events, and a mouse dragged quickly does the same. Requiring adjacency would make the
path stutter and would make touch nearly unusable. Routing means the player sketches and the planner
draws.

Path cells are stored as **padded cell indices**, and the directions handed to `InputBuffer.replace`
are derived from consecutive pairs. Keep the two representations in one place: `:ui` should never see a
direction array it has to reconcile with a cell array.

The planner writes a **cell** array; `:ui` needs those cells to paint the plan on the overlay and needs
the directions to feed the buffer. Expose both off the same object rather than recomputing either.

---

## Tests

`match/src/commonTest/kotlin/ao/snakewarz/match/human/`

**`InputBufferTest`** additions
- `replace` swaps the whole queue and does not collapse repeats — five easts stay five moves;
- `replace` beyond capacity is refused or truncated (pick one, document it, test it);
- `push` still collapses a repeat of the direction queued last, and still drops the newest when full;
- `clear` empties a replaced path.

**`PathPlannerTest`** (new)
- a straight run on an empty board is the obvious cells, in order;
- a route around a snake body is found;
- a route around a **wall** is found — the reason this session depends on S03;
- an unreachable target leaves the path unchanged and returns the old length;
- the path never revisits one of its own cells;
- extending twice from the same start is the same as extending once to the second target, where a
  route exists (so a drag that pauses and resumes does not produce a different path);
- **no allocation per call**: extend a few thousand times in a loop and assert it completes — this is
  a shape assertion more than a measurement, and it is what stops somebody adding a `List<Cell>` later.

---

## Done when

```bash
./gradlew :match:jvmTest
./gradlew build
```

`InputBufferTest`'s existing cases pass untouched. If one needed changing, the keyboard semantics moved
and they should not have.
