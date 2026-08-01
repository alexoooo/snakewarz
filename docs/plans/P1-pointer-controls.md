# P1 — Pointer controls

**Modules:** `:match`, `:ui`
**Depends on:** nothing
**Read first:** [`../UI.md`](../UI.md) lines 30-67 (the four clocks and the drag's four consequences),
[`../Match.md`](../Match.md) lines 61-93 (*"A drawn route is a plan, not a promise"*),
[`../Coding-Standards.md`](../Coding-Standards.md) SW-03, CC-08, CC-12, CC-15, CC-18.

## Why

The only pointer steering today is a freehand drag. `GameSession.pathBegan` refuses a press more than
one square from the head (`nearHead`), and every `pointermove` while held calls
`PathPlanner.extend`, which runs a breadth-first search from the end of the drawn route to the square
under the pointer. Two things are wrong with that:

- **It routes, so it jumps.** A pointer that crosses a body makes the path bolt the long way round the
  obstacle — `PathPlannerTest`'s *"a route never crosses the path already drawn"* pins an eleven-move
  detour on a 7×7 as correct behaviour. It is not what "follow the mouse" looks like.
- **There is no way to say "go over there".** You have to draw the whole way.

And the planner treats any occupied square as a wall, so a tail square that will certainly have
retracted before you reach it cannot be routed through. That is the design note this phase reverses:
`PathPlanner`'s KDoc says *"this does not try to predict the board it will actually meet"*.

## The interaction model

One press handler covers click, click-and-hold and freehand drag. Release still means stop.

| Gesture | What happens |
|---|---|
| Hover a square | The route from your head to it is previewed on the overlay, dimmer, committed to nothing |
| Press a square | **Only if that route exists.** Take hold, then play exactly **one** move along it |
| Keep holding | `TurnScheduler` walks the rest at the speed on the slider |
| Move while holding | The route **traces** the pointer — a staircase appended from the route's end, cut where it is blocked |
| Drag back along the route | It shortens to where the pointer is |
| Release | Unchanged: the rest of the route is discarded and the snake halts |
| Press your own head | A zero-length route, which counts as existing — takes hold, plays nothing. This is how a freehand drawing starts |
| Press a square with no route | Nothing at all. No hold, no step, no clock |

A quick click is one step, because the press plays a move and the release a few milliseconds later
discards what is left.

**Two sentences that belong in `docs/UI.md` because they are what make this legible:**

- *What you can see is what a press will do.* The preview and the press call the same `route()`.
- *A press says go there, a drag says go this way.*

## What "blocked" now means

A square is passable at plan index `i` (the anchor under the head is `0`) if it is free now, **or** its
owner is alive and will have retracted past it within `i - 1` of that snake's own moves.

The arithmetic comes out of `Board.growsOnNextMove` (`core/src/commonMain/kotlin/ao/snakewarz/core/rules/Board.kt:375`):

```kotlin
private fun growsOnNextMove(slot: Int): Boolean =
    (moveCounts[slot] + 1) % rules.growEveryNthMove == 0
```

and `Board.apply` popping the tail only when that is false (`Board.kt:198-212`). So walking a snake's
next moves `m = 1, 2, 3…`, move `m` retracts exactly when `(movesMade + m) % g != 0`, and the `j`-th
square from the tail is vacated on the `(j+1)`-th such move.

**Two separate reasons produce the same `i - 1`, and the KDoc must keep them apart** or the next reader
will "fix" one of them:

- **For your own body** it is an ordering rule: `Board.apply` tests `occupancy.isFree(target)` at line
  198 *before* `body.popTail()` at line 200, so legality is read before the tail retracts. That is
  non-obvious fact #4 in `AGENTS.md`.
- **For everyone else** it is a move count, not an ordering — an opponent's retraction happens inside
  its own move and is already visible once that move is done. `Board.advanceToAct` cycles from the
  current position skipping the dead, so between two consecutive player moves every living snake moves
  exactly once; an opponent has made `i - 1` or `i` moves depending on where it sits in the cyclic
  `toAct` order **from the current `board.toAct`** — which is *not* its slot index, because
  `pathBegan` can be reached mid-round. Assuming `i - 1` for everyone assumes fewer retractions, so it
  believes more squares occupied. Conservative, always.

Conservative is the only safe direction, and the reason is sharper than "a lost move": when
`InputBuffer.take` discards an illegal direction it returns *the next legal one from the same route*,
so an over-optimistic plan makes the snake skip to a later leg rather than stop.

This falls out correctly on the case that matters. Your own tail square has clearance 1, so the route
may not enter it at step 1 and may at step 2 — fact #4, reproduced rather than special-cased.

**Three stated optimisms, all of which belong in the KDoc:**

1. Opponents' future heads are never predicted — a square free now is assumed free forever.
2. A snake that dies stops retracting, and its body freezes where it fell.
3. `growEveryNthMove == 1` (classic Tron) means nothing ever clears. This needs an **explicit early
   return**: `(movesMade + m) % 1` is always `0`, so the naive loop never terminates.

## `:match` — new file

### `match/src/commonMain/kotlin/ao/snakewarz/match/human/Clearance.kt`

`internal`, its own file (CC-15). `Occupancy`'s time-aware counterpart. Two `IntArray(grid.cellCount)`
(`stamp`, `clearsAt`) and a generation counter, wrapping the way `PathPlanner.nextGeneration`
(`PathPlanner.kt:213-219`) already does.

```kotlin
internal class Clearance(private val grid: Grid) {
    fun refresh(board: BoardView)
    fun enterableAt(board: BoardView, cell: Cell, arrival: Int): Boolean
}
```

`refresh` walks each **living** snake tail-to-head accumulating retractions:

```kotlin
nextGeneration()
// A trail that never retracts gives nothing back, and an unstamped square already reads
// "never" -- which is the right answer for a wall and for a corpse too.
if (board.rules.growEveryNthMove == 1) return

for each alive snake:
    var moves = 0
    var retracted = 0
    for (j in 0 until snake.length) {
        while (retracted <= j) {
            moves++
            if ((snake.movesMade + moves) % g != 0) retracted++
        }
        stamp[snake.cellAt(j).index] = generation
        clearsAt[snake.cellAt(j).index] = moves
    }
```

`moves` is monotone across `j`, so the inner `while` is amortised — about `2 × length` iterations at
`g == 2`. Total cost is `O(sum of body lengths)`, and a cached `board.turnIndex` makes repeated calls
within one turn free. Walls and corpses need no scan at all: not free, not stamped, therefore never
enterable.

```kotlin
fun enterableAt(board: BoardView, cell: Cell, arrival: Int): Boolean =
    board.isFree(cell) || (stamp[cell.index] == generation && clearsAt[cell.index] <= arrival - 1)
```

`match/human/` goes from five files to six — comfortable under CC-06, so **keep it flat**. If routing
ever needs a third file, that is the moment to nest `human/path/` and move its tests with it (CC-13).

## `:match` — `human/PathPlanner.kt`

`extend` is **deleted**; `cameFrom: IntArray` becomes `depth: IntArray` (same memory); a `Clearance`
field is added. `begin`, `advance`, `clear`, `cellAt`, `cellCount`, `moveCount`, `isEmpty`,
`directions`, `maxCells`, the generation stamp and `directionOrdinal` are all kept as they are.

```kotlin
/** Replaces everything after the anchor with the straightest shortest route to [target]. */
public fun route(board: BoardView, target: Cell): Boolean

/** Draws the line from the path's end to [target], stopping where it is blocked. Moves appended. */
public fun trace(board: BoardView, target: Cell): Int

/** Drops everything past the first square that will still be held when the snake could reach it. */
public fun revalidate(board: BoardView): Boolean
```

### `route` — time-aware, straight-preferring shortest path

Forward BFS from the anchor where **depth is the arrival index**, so the clearance test is just
`enterableAt(board, cell, depth)`. Occupancy only ever decreases for bodies already on the board, so
the test is monotone in depth and plain BFS stays optimal with each cell dequeued once.

Two things that are easy to get wrong:

- **A rejected cell must not be stamped.** It may become enterable from a deeper frontier cell later.
- **Reconstruct backwards from the target**, at each step preferring the predecessor that continues the
  direction just taken, else the first `Direction.entries` neighbour at `depth - 1`. `error(...)` if
  none — CC-08. That gives long straight runs, an L rather than a staircase, with no second search.

Return `true` when `target` is the anchor: a zero-length route exists, and that is what lets a press on
your own head take hold.

Two honesty notes the KDoc must carry:

- With obstacles the greedy backward reconstruction is a **heuristic** and can cost one extra turn.
  The exact answer is lexicographic `(length, turns)` over `(cell, direction)` states — four times the
  state and four times the memory for a cosmetic property on a board a person is looking at. The trade
  was made deliberately.
- Because a snake **cannot wait in place**, BFS cannot express "loop around and come back once the tail
  clears". A cell whose only neighbours are all dequeued before it opens is never reached. Soundness is
  unaffected — a route that *is* found is walkable — and the failure mode is an ordinary "no route",
  which the press already treats as "do nothing".

### `trace` — the freehand line

A 4-connected DDA staircase, integer-only, re-derived from the current cell each step so it
self-corrects and survives truncation:

```kotlin
while (r != r1 || c != c1) {
    // Step whichever axis has further to go: the staircase that hugs the segment.
    if (abs(r1 - r) > abs(c1 - c)) r += sign(r1 - r) else c += sign(c1 - c)
    val next = grid.cellAt(r, c)
    if (onPath(next) || !clearance.enterableAt(board, next, cellCount)) break
    if (cellCount == maxCells) break
    append(next)
}
```

`(0,0) → (3,5)` gives `E E S E S E S E` — a true staircase, not an L. That asymmetry is the whole
point: an L would turn a diagonal drag into a right angle, which is the opposite of "precisely follow
the mouse". A press routes; a drag traces.

No search, so it can neither detour nor jump. Once cut it simply stops growing while the pointer stays
past the obstruction; bringing the pointer back into line resumes it.

**One special case, worth having:** if `target` is already on the path at index `p`, set
`cellCount = p + 1` and return. Dragging back along your own route shortens it, which is what a player
expects and what an unconditional self-block would refuse. The stamp array already knows `p`.

Returns the number of moves appended. `0` is an ordinary answer, not a fault — note the contract change
from `extend`, whose `Boolean` `pathDragged` already discards.

### `revalidate`

```kotlin
clearance.refresh(board)
for (i in 1 until cellCount) {
    if (!clearance.enterableAt(board, Cell(path[i]), i)) { cellCount = i; return true }
}
return false
```

`O(cellCount)` ≤ 512, once per turn. Truncating to a bare anchor is the "route emptied while held"
state `docs/UI.md:51-54` already describes — `InteractiveBot` answers `Pending`, the scheduler clamps
its accumulator, dragging refills. **No new flag.**

### Self-crossing

Stays unconditionally blocked, beyond the shorten-on-backtrack case above. The time-aware version is
derivable — over `V = body ++ plan`, self-occupancy at time `t` is exactly `V[r(t) … L0-1+t]` — but it
buys nothing you can do today, so record the formula in the KDoc and say the omission is deliberate.

### KDoc rewrite

The class KDoc currently asserts *"over squares that are free **now**"* and *"Breadth-first rather than
'append the square if it is adjacent'"* (`PathPlanner.kt:9-25`). The first is now false; the second
applies to `route` only. Both have to be rewritten rather than left standing.

## `:ui` — `GameSession.kt`

### `canSteer()`

```kotlin
private fun canSteer(): Boolean =
    !batch.running && batchBoard == null && match.interactive &&
        match.outcome == null && playerSeat != null
```

Shared by `pathBegan` and the preview (CC-12). **`match.outcome == null` is new**: at a turn-limit draw,
or when the human won, `interactive` is still true — it only tests `alive` — so today a route would be
planned and painted on a finished board.

### `pathBegan` (currently lines 409-429)

Drop `nearHead`. Guard on `canSteer()`. Then:

```kotlin
plan.begin(head)
if (!plan.route(match.view, renderer.cellAt(clientX, clientY))) {
    plan.clear()          // no route means no hold: the preview already said so
    return
}
dragging = true
input.clear()             // a key pressed a moment ago is not part of this route
hover(Cell.NONE)          // the tip would sit under the finger
syncQueue()
playPlayerMove()          // the one step per click
if (!plan.isEmpty) { scheduler.start(); renderChrome() }
```

Its KDoc says *"Nothing is queued and no clock starts here"*, which inverts.

**Ordering is safe without a `!scheduler.running` guard, and that deserves a comment.** `endPath()` runs
first and stops the clock, and `TurnScheduler.start()` only arms a `requestAnimationFrame`, so nothing
fires before the handler returns. A quick click's `pointerdown` and `pointerup` land in separate tasks
but both before the next frame, so `start()` then `stop()` cancels it — one step, by construction.

### `playPlayerMove()` — new, beside `playRound` (line 1056)

**`playRound` is the wrong primitive here.** It plays until an interactive slot has nothing queued, and
with a full route queued that is `slotCount + 1` turns, not one move. The new one loops `advance()`
until the player's `movesMade` changes, keeping `playRound`'s two escapes: bail on any non-`CONTINUED`,
and `if (!match.interactive) { scheduler.start(); break }` for a press that killed the player mid-round
— in which case `consumePlan` will already have called `forgetPath()`, so `pathBegan` must not then
start the clock itself.

### `pathDragged` (449-467)

`plan.extend(...)` becomes `plan.trace(...)`, and `input.replace(...)` becomes `syncQueue()`. Nothing
else changes.

### `consumePlan` (513-529) — becomes the single authority over the queue

Today it advances and re-anchors the plan but **never rewrites the queue**, so when `take` discards a
direction the plan re-anchors while the queue still holds the whole route: the snake walks a route the
overlay no longer shows, until the next pointer event — and under press-and-hold there may not be one.
That is a latent inconsistency this phase forces fixed, and fixing it buys the per-turn truncation for
free.

```kotlin
val seat = playerSeat ?: return
val snake = match.view.snake(SnakeId(seat))
if (!snake.alive) { forgetPath(); return }

if (result is StepResult.Advanced && result.id.index == seat) {
    plan.advance()
    if (dragging && (plan.cellCount == 0 || plan.cellAt(0) != snake.head)) plan.begin(snake.head)
}
// Every step, not only the player's: an opponent is what cuts a route.
if (!dragging) return
plan.revalidate(match.view)
syncQueue()
```

The `!dragging` guard is load-bearing: `steer` calls `endPath()` then `input.push`, and an unguarded
`input.replace(_, 0)` inside `playRound`'s loop would eat a second queued keypress.

**What revalidating on every step buys.** The last revalidation before the player's turn happens
immediately after the preceding snake moved, and nothing moves in between; plan index 1 is required
free *now*, which is exactly what `Board.legalMoves` tests. So **while a route is held, `take` cannot
discard.** The re-anchor branch stays as the keyboard path's safety net, and stays justified under
CC-10 because `advance()` still runs on steps the plan did not spell out.

### The preview

A second `preview: PathPlanner`, rebuilt beside `plan` in `begin()` (line 723). Re-planned inside
`refreshOverlay` (386-392) — already the "the board moved under a pointer that did not" obligation and
already called after every paint, so a ghost route cannot stay anchored on an old head.
`hovered == Cell.NONE` short-circuits it to nothing, and `pathBegan` sets `hovered` to `NONE`, so a
preview and a committed route can never both be live. `hover()` already early-returns on an unchanged
square (364-373), so the BFS runs at most once per distinct square crossed — **no other throttle is
needed and none should be added.** A full-board BFS is about `4 × cellCount` array reads; `drawOverlay`
already costs more than that on every one of those events.

### Copy and KDoc

- Status line (line 1203): `"your move — drag from your head, or the arrow keys"` becomes
  `"your move — click a square to step, hold to keep going"`.
- `dragging`'s KDoc (85-93) says *"a press that landed nowhere near the head holds nothing"* — now false.
- `endPath`'s KDoc (469-487) should name its `!dragging && plan.cellCount == 0` guard as what it now
  entirely is: the thing that stops a click pausing a bots match or a replay.

### Guards that already cover everything else

Each stays load-bearing and should be named as such:

- `batch.running || batchBoard != null` keeps a press off a tournament's board, which is what makes
  `UiIntent.PathBegan`'s `Shell` tier honest.
- `Match.interactive` is false under playback and once the human is dead.
- `#panel-scrim` (`z-index: 10`) and `#dialog-result` (`z-index: 20`) are full-viewport and above
  `#board`, so no press reaches the canvas while either is up. That is a **CSS invariant with no Kotlin
  counterpart** — the pointer has nothing like `Shell.boardHasKeys` — and belongs in `ui/CLAUDE.md`.

**The one thing that does break is the feature:** with a human seated, any click on the board costs a
move and there is no cancel. It is exactly one move, and the preview shows where it goes before you
commit — which is why the preview ships in this phase rather than after it.

## `:ui` — `render/BoardRenderer.kt`

- `paintOverlay(view: BoardView, cell: Cell, plan: PathPlanner, preview: PathPlanner)`; hold `preview`
  the way `plan` is held today (line 99).
- `drawOverlay` (406-423) order becomes **wash → preview → route → threads**.
- `drawPlan()` becomes `drawRoute(planner: PathPlanner, alpha: Double, targetAlpha: Double)`, called
  twice (CC-12).
- New `PREVIEW_ALPHA` (≈0.14) and `PREVIEW_TARGET_ALPHA` (≈0.40) beside `PLAN_ALPHA` /
  `PLAN_TARGET_ALPHA` at 636-638, each with the "why this number" note CC-01 asks for. A previewed
  route must not be mistakable for a committed one.

## Deleted

- `ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/nearHead.kt`
- `ui/src/wasmJsTest/kotlin/ao/snakewarz/ui/NearHeadTest.kt`

Every press now takes hold if a route exists, so a grace radius has nothing left to decide, and a
near-head press costs one step *toward where the finger already is* — not a wrong move. Its recorded
reasoning (a fingertip cannot hit one square) is re-homed as one sentence in `docs/UI.md` explaining why
it is gone. Net gain under CC-13: the last routing logic leaves `:ui`'s browser-gated suite for
`:match`'s `commonTest`.

## Tests

### `match/src/commonTest/kotlin/ao/snakewarz/match/human/ClearanceTest.kt` — new

The arithmetic on its own, and the file that would catch an off-by-one sliding.

- **An oracle test.** Drive a snake forward on a real `Board` move by move and assert that the move at
  which each body square actually becomes `isFree` equals `clearsAt`. Cheap, and the only test that
  would notice a `+1` drifting.
- `growEveryNthMove = 1`: nothing ever clears, and `refresh` **terminates**.
- A corpse never clears; a wall never clears.
- The tail is not enterable at arrival 1 even though the next move retracts it.

### `match/src/commonTest/.../human/PathPlannerTest.kt` — extended

`openBoard`, `assertPath` and `assertDetourAroundColumnTwo` are reusable as-is.

Kept, retargeted at `route`: the straight run, the wall detour, the body detour, the unreachable target,
the off-board and `Cell.NONE` targets, the "moves spell out the squares" invariant, the paused-and-resumed
drag, `advance()` keeping the anchor under the head, and the 5 000-route allocation loop.

New:

- **An L, not a staircase** — 9×9 open, head `(0,0)` → `(3,5)`, exactly one direction change.
- **Time-aware, both ways** — a corridor square held by a tail that clears before arrival is routed
  *through*; the same square with an arrival one turn too early is routed *around*.
- **`trace` draws the staircase** — `(0,0) → (3,5)` gives the alternating sequence, and every cell is
  4-adjacent to the one before it.
- **`trace` truncates rather than detours** — a wall on the line stops the path short and the far side
  is not appended. **This replaces `a route never crosses the path already drawn`**, whose eleven-move
  detour assertion is exactly the behaviour being removed.
- **`trace` back over the path shortens it.**
- **`revalidate` cuts** — apply an opponent move onto a plan square, assert `cellCount` drops to that
  index and the surviving `directions` still walk the surviving cells.

No new `:ui` test. **Known gap, not introduced here:** `GameSession`'s press/drag/release machine has no
test — it needs a full DOM and browser tests are off by default — so the click-to-move behaviour is
verified by hand.

## Docs

- **`docs/UI.md:36-67`** — rewrite the fourth-clock passage. Press anywhere takes hold *if a route
  exists*, plays one move and holds the clock; release still discards; hover previews the same route; a
  drag traces literally. Delete *"Press within a square of your own head"* and re-home the grace-radius
  rationale as the sentence explaining why it is gone. Add the two sentences from the top of this file.
- **`docs/UI.md` overlay-order note and `ui/CLAUDE.md`** — three decorations become four, and the order
  becomes **wash → preview → route → threads**. Add that every board press now costs a move, and that
  the scrim and dialog CSS are what stand between a stray click and a lost snake.
- **`docs/Match.md:61-83`** — the *"free **now**"* bullet becomes the time-aware rule with **both**
  reasons for `i - 1`; *"breadth-first rather than append-if-adjacent"* is scoped to the press route;
  add the truncation bullet. Keep the promise framing and say what is still unpredicted.
- **`match/CLAUDE.md`** — one biting fact in the style of its `map/` entry: the clearance off-by-one is
  `i - 1` because legality is read before the tail retracts, it is conservative by one for seats acting
  before the player in `toAct` order, and conservative is the only safe direction.

## Sequencing

`Clearance` + its test → `PathPlanner` + its tests → `BoardRenderer` signature → `GameSession` → delete
the `nearHead` pair → docs. The first two steps are pure `:match` and fully covered on the JVM before
any browser build.
