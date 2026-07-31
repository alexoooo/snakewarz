# S09 — The board takes the frame; mobile

**Modules:** `:ui`, `:app`
**Depends on:** [S08](S08-screen-shell.md).
**Read first:** [`../UI.md`](../UI.md) §*"The board is a fixed rectangle"* and §*"One page, and the one
thing that scrolls"*. **This session deliberately replaces the first of those.**

## Goal

Snakes centre stage. The board uses the room it has, on a 4K monitor and on a phone in portrait, and
the chrome around it costs two thin bars.

---

## Step 1 — the board extent, and why it changes

Today `BoardRenderer.fit` (`:115`) computes

```kotlin
val wanted = (BOARD_EXTENT * bootRatio - 1) / span + 0.5   // BOARD_EXTENT = 640.0
val fits   = (room - 1) / span
cellSize   = minOf(wanted, fits).toInt().coerceAtLeast(...)
```

so the board is **at most 640 device pixels** on its longer side — 320 CSS pixels at a ratio of 2.
That was right for a research console with a sidebar. It is wrong for a game.

Replace with: fill the room, clamped by a maximum cell size.

```kotlin
val fits = (room - 1) / span
cellSize = fits.toInt()
    .coerceAtMost((MAX_CELL * bootRatio).toInt())
    .coerceAtLeast((MIN_CELL * bootRatio).toInt().coerceAtLeast(1))
```

`BOARD_EXTENT` is deleted. `MAX_CELL` (≈44 CSS px) is new and stops an 8×8 becoming absurd in a large
window.

**`docs/UI.md` currently argues against a maximum cell size** — *"one is what used to make a small
board small, and it would fight the extent at every size the picker offers."* That argument was in
service of the fixed extent, and with the extent gone it no longer holds: nothing is left for a maximum
to fight. Rewrite the section rather than leaving it contradicting the code. The stated intent — *"an
8×8 and a 40×40 occupy the same frame"* — is **more** true afterwards, because both now fill the frame
instead of both being capped at 640.

Everything else in `fit` stays: device pixels, no `context.scale`, the overlay sized off the same
integers and never measured, `repaint` then `drawOverlay`. All of that was paid for once already.

## Step 2 — the layout

`#screen-game` is a `100dvh` flex column:

```
top bar      mode, opponent or level, a menu button      auto
board                                                    minmax(0, 1fr)
bottom bar   player cards, transport                     auto
```

**Every `min-height: 0` down the chain to `.board-wrap` stays load-bearing**, and `.board-wrap` stays a
one-cell grid with both canvases in `grid-area: 1 / 1`. The vertical circularity `docs/UI.md` describes
is unchanged: the track has a height of its own that the canvas inside cannot influence. Do not switch
anything here to flexbox and do not put a shrink-to-fit box around the canvas.

`#board` keeps its `outline` and must not gain a `border` — load-bearing twice, for the backing store
width and for `getBoundingClientRect` in `cellAt`.

Player cards replace the `<ol id="scoreboard">` rows: colour, name, length, alive or how they died.
Compact and horizontal, and the same row in portrait.

The transport (play/pause/step/restart/speed) and the scrub row move into the bottom bar. Speed can
move to `#panel-settings` if the bar is tight; the scrub row appears only in replay mode, which
**changes the board's box**, so `begin()` must keep rendering the chrome before it measures.

## Step 3 — mobile

- `100dvh`, not `100vh` — mobile browser chrome makes them differ, and `vh` puts the bottom bar under
  the address bar.
- `padding: env(safe-area-inset-*)` on the two bars, for notches and home indicators.
- **`touch-action: none` and `overscroll-behavior: contain` on `.board-wrap`.** Without the first, a
  drag across the board scrolls the page instead of steering — [S14](S14-path-input.md) depends on this
  being in place.
- `<meta name="viewport" content="width=device-width, initial-scale=1">` is already correct. Leave it;
  do **not** add `user-scalable=no`.
- **The current `@media (max-width: 52rem)` block must go.** It reverts the whole shell to an ordinary
  scrolling document, which was right when the sidebar was a column that could not share a viewport
  with the board. Now the sidebar *is* an overlay panel, so there is nothing to stack: the game screen
  stays a fixed-height column at every width, and only a panel's own content scrolls.
- Portrait and landscape both need checking. A phone in landscape has almost no vertical room, so the
  two bars must be genuinely thin — that is what "dense information kept to a minimum" buys.

Keep `[hidden] { display: none !important }`. It is load-bearing: an author `display: flex`/`grid`
outranks the user agent's `[hidden]` rule, so hidden rows stay on screen while reporting
`hidden == true`, and Kotlin cannot see that.

---

## Tests

`ui/src/wasmJsTest/.../render/BoardRendererTest.kt` — the three `cellAt` tests must still pass; they
are the hit-test that [S14](S14-path-input.md) builds the drag on, and a wrong cell size is exactly what
would break them.

Add a `fit` case: cell size is clamped by `MAX_CELL` in a large box and by the room in a small one.

---

## Done when

```bash
./gradlew build
./gradlew :app:wasmJsBrowserDistribution   # SW-08: check the gzipped size
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

In the browser, at a desktop viewport and at 390×844 portrait and 844×390 landscape:

- the board fills the frame at 8×8 and at 40×40, and neither overflows nor leaves the page scrolling;
- opening a panel does not resize the board;
- entering and leaving replay mode reveals and hides the scrub row and the board resizes correctly;
- nothing scrolls except a panel's own content;
- a batch tournament still repaints every frame without decorations vanishing.
