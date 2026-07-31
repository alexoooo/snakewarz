# S14 — Pointer and touch drag, and the clock it starts

**Module:** `:ui`
**Depends on:** [S13](S13-path-planner.md), [S09](S09-game-screen.md) (needs `touch-action: none`).
**Read first:** [`../UI.md`](../UI.md) §*"Playing, replaying, and three clocks"* and §*"The overlay
canvas"*.

## Goal

Press the head, drag a route, the snake follows it while you hold. **Let go and it stops on the cell it
is on**, discarding the rest of the path.

This session changes something [`../UI.md`](../UI.md) states as a fact: *"a match with a live player is
stepped by `GameSession.playRound` straight out of the keydown"*. It still is, for the keyboard. A
dragged path adds a fourth thing that can drive an interactive match, and it drives it through
`TurnScheduler`.

---

## Step 1 — the pointer

`ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/chrome/PathInput.kt`

Listeners on `#board`: `pointerdown`, `pointermove`, `pointerup`, `pointercancel`,
`lostpointercapture`.

- **`setPointerCapture` on `pointerdown`**, so a drag that leaves the canvas keeps steering instead of
  silently stopping. `lostpointercapture` is a release, and must be handled — otherwise a browser that
  takes capture away leaves the snake running with nothing driving it.
- `#board-overlay` already declines the pointer in CSS (`pointer-events: none`), so every event lands
  on `#board`. Keep that.
- `touch-action: none` on `.board-wrap` from [S09](S09-game-screen.md) is what stops a drag scrolling
  the page. Without it none of this works on a phone.
- **A press begins a plan only within one cell of the human's head.** The grace radius is what makes it
  work with a fingertip; exactly-on-the-head is a mouse-only interaction. A press anywhere else falls
  through to hover, unchanged.
- `pointermove` while dragging routes; `pointermove` while not dragging is hover, exactly as today.

New intents in `model/UiIntent.kt`: `PathBegan(clientX, clientY)`, `PathDragged(clientX, clientY)`,
`PathReleased`.

Client coordinates, not cells — `BoardRenderer.cellAt` is the only thing that knows the cell size, and
routing a raw coordinate through it is what `Hover` already does.

## Step 2 — where they sit in `dispatch`

**Tier 1, beside `Hover` and `Relayout`, above both batch guards.** Same reason: a drag on a board a
tournament owns changes nothing about that tournament, so it must neither be dropped nor be grounds
for taking the arena off one. `GameSession` already ignores steering when the match is not interactive;
that is the guard, and it belongs where it already is.

## Step 3 — the clock

`GameSession`:

| Intent | Does |
|---|---|
| `PathBegan` | if `match.interactive` and the press is within the grace radius of the human's head: clear the plan, set `dragging = true` |
| `PathDragged` | route from the plan's end to the cell; `input.replace(plan.directions, plan.length)`; if the plan is non-empty and the scheduler is stopped, `scheduler.start()` |
| `PathReleased` | `dragging = false`; `input.clear()`; `scheduler.stop()`; repaint. **This is the stop.** |

Three consequences to get right:

1. **The plan is anchored at the head, so it has to be consumed as the snake walks it.** `advance()`
   drops the plan's first direction when the human's slot moves. Get this wrong and the painted plan
   drifts behind the snake.
2. **A plan that empties while still held parks itself, with no new state.** `InteractiveBot` answers
   `Decision.Pending`, `Match.step` returns `AwaitingInput`, and `TurnScheduler` already handles that:
   it clamps the accumulator to 1.0 and breaks, so no debt builds up while the player thinks. Extending
   the drag refills the queue and the next frame resumes. Do not add a flag for this case.
3. **An arrow key mid-drag clears the plan first, then pushes.** Otherwise the two input methods
   interleave and the snake does something neither one asked for.

`Match.interactive` stays true throughout and must not be touched. It is `recording == null && any
interactive slot alive`, and it is what hands the ending back to the scheduler the instant the player
dies.

## Step 4 — the plan on the overlay

The overlay is **painted whole** — cleared with one `clearRect` and redrawn — so the plan is simply a
third decoration and needs no dirty-square accounting.

`BoardRenderer.paintOverlay` gains the plan's cells. Draw order inside `drawOverlay`:

```
wash  ->  plan  ->  threads
```

The wash goes under everything, as today. The plan goes **under** the threads so a snake reads on top
of its own route rather than being hidden by it.

Draw it as a translucent run of cells in the theme's accent, ending in a small target marker — quiet
enough not to compete with the snakes, legible on both schemes.

**`paintOverlay` must still follow every `paintMove`, `paintSnake` and `repaint`.**
`GameSession.refreshOverlay` is that obligation and it already exists; the plan just becomes part of
what it paints. Get this wrong and the plan vanishes the frame anything else repaints.

---

## Tests

`ui/src/wasmJsTest/`
- `TurnSchedulerTest` — its existing cases must pass untouched. It is `internal fun frame(timestamp)`
  precisely so a test can be the clock, and this session leans on `AWAITING_INPUT` clamping the
  accumulator.
- A `PathInput` case: a press outside the grace radius does not begin a plan; a release with no press
  is harmless; `lostpointercapture` releases.

Most of this session is a browser check. `requestAnimationFrame` **does not fire in a hidden tab at
all**, so an automated check against a backgrounded tab sees a frozen match — drive `Step`, or replace
`window.requestAnimationFrame` and pump synthetic timestamps.

---

## Done when

```bash
./gradlew build
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

In the browser, with a mouse and with touch emulation at 390×844:

- press the head, drag a route across the board, and the snake follows it;
- **lift, and it stops on the cell it is on, that turn** — not at the end of the route;
- hold still until the snake catches up to the pointer and it waits there; move further and it resumes;
- drag through a wall and the route goes around it;
- drag into a space that closes before the snake gets there and the snake dies there — the plan was a
  plan;
- press an arrow key mid-drag and the keyboard takes over cleanly;
- the plan is visible under the snake and survives a tournament repaint.
