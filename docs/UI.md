# UI

**For:** changing anything in `ui/`, `app/.../index.html`, `app/.../styles.css`, the boot path, or
the GitHub Pages deployment.
**Assumes:** [`../CLAUDE.md`](../CLAUDE.md) — the module graph, the forbidden dependency edges and
the four non-obvious facts live there and are **not repeated here**.
**Enforced elsewhere:** `checkModulePurity` fails the build on `:ui → :bots`, so nothing here can
tell a wall hugger from a human. It does not check anything below that.

## Working on the UI

`:ui` exposes exactly two things — `GameSession` and `ReplayLink`. Everything else is `internal`, and
should stay that way; `:app` builds a session and is otherwise sixty lines of wiring.

### One-way data flow, and two cadences

Inside, it is a one-way data flow with no virtual DOM. State goes down through `Chrome.render(model)`,
everything a person does comes back up as a `UiIntent` into `GameSession.dispatch`, and the board is
painted separately per turn because painting two rectangles is nearly free while writing text is not.
Keep those two cadences apart: `UiModel` is built once per *frame*, not once per turn.

### Playing, replaying, and three clocks

**Playing and replaying are one code path.** A replay is a match whose slots already know what they
are going to do, so play, pause, step, restart and the scoreboard work on both without a branch. Only
seeking is replay-specific, and it is implemented by rebuilding the playback match and stepping to the
target — microseconds, and nothing to keep consistent.

What *does* branch is which clock runs, and it branches on `Match.interactive` rather than on a mode
flag: `TurnScheduler` paces bots and replays, while a match with a live player is stepped by
`GameSession.playRound` straight out of the keydown. `TournamentRunner` is the third clock and the
only one with no speed at all — a batch is not something you watch at a rate, it is something you
wait for, so it runs flat out on an 8ms-per-frame guard and reports progress instead.

While a batch runs it **owns the arena**: `GameSession` paints its current match and builds the whole
`UiModel` from that match, so the board, the scoreboard and the stats cannot disagree. The transport
is greyed, and `dispatch` drops transport intents outright — the space bar does not read the DOM's
disabled flags. Touching the transport afterwards hands the arena back with a full `fit`, because the
renderer paints one square at a time and would otherwise step a match onto somebody else's board.

**Hover is answered above both of those guards**, and that placement is the whole of it: asking what
is under the pointer changes nothing, so it neither has to be dropped while a batch owns the board
nor is grounds for taking the board back off one. Put the branch below either guard and moving the
mouse across a finished tournament's last position silently swaps it for the player's own game.

### The board is a fixed rectangle

**The board is a fixed rectangle of device pixels** — `BoardRenderer.BOARD_EXTENT`, anchored to the
`devicePixelRatio` the page opened at. The grid decides only how finely that rectangle is divided, so
an 8x8 and a 40x40 occupy the same frame and zooming the page moves the text around a board that
stays put. The container and the viewport height are clamps for a window it will not fit in, not
inputs to the size. There is deliberately **no maximum cell size**: one is what used to make a small
board small, and it would fight the extent at every size the picker offers.

### The overlay canvas

**The decorations live on a second canvas, and are painted whole.** `paintMove` repaints only the two
or three squares a turn dirtied, so a decoration sharing that bitmap would have to be understood by
every one of those paints — and a full `repaint`, which a batch triggers every frame, would wipe it.
The overlay is cleared with one `clearRect` and is sized off the same integers as the board, never
measured, so the two cannot drift. `BoardRenderer` owns both, so the cell size and the grid still
have one home.

There are two decorations on it and they answer to different things. The **thread** through each
body — plus the marker on the head — is drawn for **every snake, every turn**, because a body that
moved one square has a thread that moved along its whole length; there is no dirty square for it, and
nothing about it was ever a question about the pointer. The **wash** picks one snake out of the
others, which only a pointer asks, so it stays with the pointer and goes down first, under every
thread. That is why `BoardRenderer.paintOverlay` has to follow every `paintMove`, `paintSnake` and
`repaint` — `GameSession.refreshOverlay` is that obligation, not a pointer handler. A corpse keeps
`Palette.CORPSE_ALPHA` and loses its head marker, because `paintSnake` already says both.

`GameSession` remembers the hovered **square**, never the snake, so a restart, a seek and a batch
moving on to its next match all resolve to whoever holds it now — the same rule every colour on this
board already follows.

### Naming seats, and building DOM

**Seats are named by `SlotLabels`, not by the registry directly.** A seat is a *configured* bot, so
two of them can be the same bot at two allowances, and the display name alone cannot say so. The
qualifier is `Contestant.suffix` from `:match` — the very string the win-rate matrix uses — so the
sidebar, the hover label, the winner line and the table cannot start disagreeing about what `@4k`
means. The numbering does differ on purpose: `TournamentTable` leaves the first of a repeated column
bare because it has a legend under it, while a list of four rows reads better as `Random ·1` and
`Random ·2`.

The static skeleton lives in `app/.../index.html`. Kotlin looks elements up by id once and then only
writes text, values and `hidden`; do not start constructing structure there. The win-rate matrix is
the case that most invites breaking that rule and does not: `TournamentTable.toString()` lays it out
in `:match` and the chrome writes the text into one `<pre>`.

**There are exactly two exceptions, and both come off `BotRegistry.entries`**: the `<option>` list in
each picker, and the knob rows inside each seat's `<details class="knobs">`. Both exist to keep
"fork, add a file, register it, open a PR" from also meaning "and edit the markup". A pre-written pool
of rows would have been the doctrinal answer and is the wrong one — the day a bot declares one knob
more than the pool holds, it silently loses it, which is the exact coupling the rule is there to
prevent. The *containers* are still static, and adding a third exception needs a better reason than
either of these had. The overlay canvas and the hover label are **not** a third one — they are static
markup like everything else, and Kotlin only ever writes their size, text and position.

`SlotForm` owns all of that, one per seat, and nothing in it dispatches a `UiIntent`. Which bot is
picked and what its knobs are set to is **form state**, like the reseed button writing `#seed`; it
becomes app state only when Start match calls `read()`. Two things there are load-bearing:

- **A value is corrected in the field, not just in the read.** `SlotForm` runs `BotKnob.reject`
  first, falls back to the declared default, and writes the correction back — a match that quietly
  played at a number nobody typed would be worse than one that refused to start.
- **Values equal to the declared default are omitted**, so an untouched seat yields
  `BotParams.EMPTY`, `MatchSetup.configured` stays false, and the replay URL of a stock match is
  byte-identical to the one the codec produced before any of this existed.

## Deployment

GitHub Pages, static files, no backend. GitHub Pages serves `.wasm` with the correct `application/wasm`
MIME type on live sites. A `.nojekyll` file is required. Replays travel in the URL **hash** (`#r=<payload>`)
because Pages has no server-side routing and a hash change causes no reload.

Kotlin/Wasm is Beta and needs WasmGC: Chrome 119+, Firefox 120+, Safari 18.2+. `index.html` already
handles this by watching for a boot *failure* (a thrown error, a rejected promise, a failed script
load, or a 15s timeout) rather than probing wasm features — a byte-level WasmGC probe is easy to get
subtly wrong and would then lock out perfectly good browsers. Kotlin signals success by adding
`booted` to `<body>`.

Keep the four pure modules platform-free so that adding a Kotlin/JS fallback target later is a
build-config change, not a rewrite.

## Browser gotchas already hit — don't rediscover these

- **Reveal `#app` before the first paint.** It starts `display: none`, and a hidden element reports
  `clientWidth == 0`, so measuring the board container first sizes every board to the minimum cell
  size. `document.body.classList.add("booted")` must stay ahead of `session.start()` in `Main.kt`.
- **The board container's width must not depend on the canvas.** The canvas measures the container to
  find out how much room it has; with `flex: 1 1 auto` that was circular and the board came out a
  different size on each load. `.arena` is a CSS grid with `minmax(0, 1fr)` so the track width is
  definite, and `.board-wrap` is a one-cell grid that centres the canvas without shrink-wrapping it.
  Don't switch either back to flexbox, and don't put a shrink-to-fit box around the canvas.
- **`#board` carries an `outline`, not a `border`, and that is load-bearing twice.**
  `box-sizing: border-box` makes a border eat into the width Kotlin wrote, so a backing store of N
  device pixels was being squeezed into N-2 pixels' worth of CSS and every gridline resampled; and
  `getBoundingClientRect` reports the *border* box, which the hover hit-test would then be a pixel out
  on at every ratio. An outline is painted outside the box and changes neither.
- **`[hidden] { display: none !important; }` is load-bearing.** The chrome hides things by setting
  `hidden`, and an author `display: flex`/`grid` outranks the user agent's `[hidden]` rule — so
  hidden rows stayed on screen while reporting `hidden == true`. Kotlin cannot see that; the fix
  belongs in `styles.css` and it is already there.
- **`BoardRenderer` draws in device pixels and never scales the context.** The backing store is
  `cellSize * cols + 1` device pixels with a *fractional* CSS size, rather than a CSS-pixel size with
  `context.scale(dpr, dpr)`. On a fractional `devicePixelRatio` — 1.25, 1.35 and 1.5 are all ordinary
  on Windows — the scaled version puts every coordinate between two device pixels and a 1px gridline
  antialiases into a two-pixel smear. Verified: sampling the backing store now yields exactly two
  colours across a row. If you do re-introduce `scale`, note that setting `canvas.width` resets the
  transform, so it must come *after* the resize.
- In `wasmJs`, `fillStyle`/`strokeStyle` take `JsAny?`, so a Kotlin `String` needs `.toJsString()`.
  `snakewarz.browser` opts into `kotlin.js.ExperimentalWasmJsInterop` once so this is not a warning
  at every call site.
- **There is no `console` in Kotlin/Wasm.** Use `println`, which lands in the browser console.
- **`requestAnimationFrame` does not fire in a hidden tab at all** — which is exactly why the
  scheduler uses it. Automated checks against a backgrounded tab will see a frozen match; drive
  `Step`, or replace `window.requestAnimationFrame` and pump the callback with synthetic timestamps.
- A harmless configure-time warning — `Kotlin does not yet support 26 JDK target, falling back to
  Kotlin JVM_25` — comes from `:app`, which emits no JVM bytecode. `:core` correctly compiles to Java
  21 bytecode via `jvmToolchain`.
