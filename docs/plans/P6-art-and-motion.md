# P6 — Art, texture and motion

**Modules:** `:ui`, `:app`, `docs/`
**Depends on:** [P4](P4-maps-and-levels.md) for §6.2 only — a texture pack is keyed on a map shape, so
the catalogue has to have stopped moving. §6.0, §6.1, §6.3, §6.4 and §6.5 depend on nothing.
Names below are written as they are after [P2](P2-gauntlet-rename.md).
**Read first:** [`../UI.md`](../UI.md) §*"Themes, and the one place a colour is written"*,
§*"Faces, and the seam they arrive through"*, §*"The overlay canvas"*;
[`../Coding-Standards.md`](../Coding-Standards.md) **SW-08** (the bundle is a budget), **CC-01**, **CC-18**.

## Why

The game plays like a game after P1–P5 and still **looks like a diagram**. Six specific things:

- A snake is a run of flat squares with a one-pixel gutter between each pair, which is exactly what
  makes it read as tiles rather than as a body.
- A portrait is eight rectangles in a 32-unit box, drawn for *how the bot searches*. You cannot see
  who you are facing, and at 3rem on a tile there is nothing to see.
- The logo is `<h1>Snake Warz</h1>` in the system UI font.
- Every map is the same grey block in the same grey board. A `rooms` board and a `scatter` board feel
  identical because they are painted identically.
- **Nothing on the page moves.** `styles.css` has no `transition`, no `animation`, no `@keyframes` and
  no `prefers-reduced-motion` — verified, all four are absent from all 1012 lines.

## What this phase fixes and what it leaves to taste

An art pass is judged by eye, so this plan fixes the **constraints** and not the drawings:

- board art is **drawn in code**, never an asset — the same argument `identicon` already makes;
- portraits stay **SVG**, flat-shaded, with a per-file ceiling (§6.4);
- **every colour on the canvas still comes out of `Theme`** — `docs/UI.md`'s "the one place a colour
  is written" does not get an exception for prettier snakes;
- the **tail fade survives** — it is a rule being drawn, not decoration (§6.1);
- motion is **guarded by `prefers-reduced-motion` in the same change that introduces it** (§6.5).

---

## 6.0 The decision everything else rests on: bodies move to the overlay

**Do this first. §6.1 and §6.5 are not possible without it, and it is the only part of P6 that is
architecture rather than art.**

### The gutter is the problem

`BoardRenderer`'s central invariant (`BoardRenderer.kt:27-33`) is that a cell of side `s` owns pixels
`(c*s + 1 … c*s + s)` and the one-pixel gutter along its top and left edge belongs to the **gridline**.
No fill ever touches it, so gridlines are stroked once per `fit` and survive forever.

That gutter is the complaint. A body segment that stops one pixel short of the next segment of the
same snake has a grid line drawn through the middle of the animal. **A connected snake has to cross
the gutter**, and there are only two ways to let it:

| | Consequence |
|---|---|
| **(a)** Bodies keep the board canvas and are allowed to overwrite gridline pixels | Every path that vacates a cell must *put the gridline back* — four short strokes per cleared cell, exactly right or the board grows holes. The KDoc at `BoardRenderer.kt:27-33` becomes false and `BoardRendererTest`'s *"the gridline gutter belongs to the square below and right of it"* (line 84) is testing a rule that no longer holds |
| **(b)** Bodies stop being painted on the board at all, and move to the overlay | The gutter question disappears: nothing under a body is ever partially repainted, so a joint may span whatever it likes |

**Take (b).**

### Why it is close to free

The overlay is already `clearRect`-ed and redrawn end to end **every turn**, and `drawOverlay`
(`BoardRenderer.kt:406-423`) already walks every snake's whole body twice — once in `wash`, once in
`drawThread`. Painting the body there is `O(sum of body lengths)`: the order the overlay already
costs, and **not** the order of a `repaint`, which is `O(rows × cols)`. On a 20×20 with two snakes of
twelve squares that is twenty-four cells against four hundred.

What it buys beyond the gutter:

- **`paintMove`, `paintOwner`, `paintSnake` and the `heads` array all go** — about ninety lines,
  including the head-handoff tracking whose entire existence (`BoardRenderer.kt:101-109`) is that the
  engine does not report a head becoming ordinary body. Nothing needs to know that if the body is
  redrawn whole.
- **`fill`'s composite-over-background dance goes** (`BoardRenderer.kt:550-569`). It exists because a
  translucent fill applied twice to the same board pixel deepens; on a freshly cleared overlay alpha
  composites over transparency, which is what `wash` already documents at lines 528-531.
- **It is the only thing that makes §6.5 possible.** An animated body needs a per-frame repaint, and a
  dirty-rectangle board cannot give one.

### What is honestly lost

`repaint` keeps its name and loses its snake loop; the board canvas becomes background + walls +
gridlines and is painted **only** from `fit` — resize and theme change. The per-turn board cost goes
from three `fillRect`s to zero, and the per-turn overlay cost roughly doubles. In the endgame of a
20×20 that is a few hundred strokes a turn rather than a few dozen.

Bound it, do not assume it: `TurnScheduler` already breaks out of its loop at
`FRAME_BUDGET = 8.milliseconds` (`TurnScheduler.kt:132-136`) and caps at `MAX_TURNS_PER_FRAME = 256`,
so the failure mode is a visibly slower match rather than a frozen page — which is the guarantee that
file already makes. Measure it anyway with a `[bench]` run before and after, on the largest board the
picker still offers after [P3](P3-screens-and-setup.md) §3.7 (28 × 28).

### Two things that must not change with it

- **`#board` carries `aria-label="Game board"` and `#board-overlay` is `aria-hidden`**
  (`index.html:221-222`). Moving the snakes onto the aria-hidden canvas takes nothing away: both
  canvases are decoration, and what a screen reader is given is `#board-tip`, the scoreboard and the
  status line. Say so in `docs/UI.md` rather than leaving it to be rediscovered.
- **`paintOverlay` still has to follow every board paint.** That obligation (`GameSession.refreshOverlay`)
  gets *stronger*, not weaker: it is now the only thing that draws a snake at all. A missed call used
  to lose a decoration; now it loses the game.

### Docs this falsifies

`docs/UI.md` §*"The overlay canvas"* — "three decorations" and "`paintMove` repaints only the two or
three squares a turn dirtied" both stop being true. `ui/CLAUDE.md`'s overlay paragraph and its
decoration-order line change with it. `BoardRenderer`'s class KDoc lines 20-51 is mostly about a
split that no longer exists and is rewritten, not patched.

**Overlay order becomes: wash → route → bodies → heads.** The wash still goes down first for the
reason at `BoardRenderer.kt:393-395`, and the route still goes under the bodies so a snake reads on
top of its own plan. With [P1](P1-pointer-controls.md) the preview joins it:
**wash → preview → route → bodies → heads**.

---

## 6.1 Snakes that look like snakes

A body becomes **a stroked polyline through the cell centres**, which is what `drawThread`
(`BoardRenderer.kt:477-520`) already does at `THREAD_WIDTH = 0.16`. The change is width, colour and
what sits on the ends — not new machinery.

- **The body:** one segment-by-segment stroke in `Theme.body(slot)` at roughly `0.78` of the cell,
  `lineJoin = ROUND`, `lineCap = ROUND`. Round joins are what turn a right-angle corner into a bend.
- **The spine:** the existing thread, kept, drawn *over* the body — so the two become one drawing at
  two widths rather than two decorations. `drawThread`'s per-segment `beginPath` loop already exists
  to ramp alpha along the length; ramp `lineWidth` in the same loop and the tail tapers.
- **The head:** `Theme.head(slot)`, and **facing**. `SnakeView.lastDirection` is already on the view.
  Two eye dots offset perpendicular to it is the single highest-value change on this list — a snake
  with eyes reads as an animal at any size.
- **The tail:** tapers to a point and **keeps its fade**.
- **A corpse:** `Theme.CORPSE_ALPHA`, no spine highlight, no eyes. A snake that is out is scenery, which
  is the argument `paintSnake` and `drawThread` already make in two places.

### Three constraints, each of which an art pass could quietly break

1. **The tail fade is the rules being drawn, not decoration.** `tailAlpha`
   (`BoardRenderer.kt:373-380`) fades the oldest square through `AGING_ALPHA` then `DYING_ALPHA`
   because `growEveryNthMove = 2` makes the square a snake is about to give back knowable a move
   ahead — non-obvious fact #1. Losing it in a repaint is a **gameplay** regression that no test will
   catch. It also has two carve-outs that must survive: a corpse never fades, and
   `growEveryNthMove < 2` has nothing to fade because nothing ever retracts.
2. **No new colour enters `BoardRenderer`.** `docs/UI.md` §*Themes* is explicit that every colour comes
   from `Theme` and that `styles.css` holds none of a theme's numbers. A highlight is
   `Theme.head(slot)` drawn over `Theme.body(slot)` — the pair the theme already provides for exactly
   this ("this snake, but readable against itself", `Theme.kt:80-81`). Adding a third array per theme
   means six new palettes, and that is the cost `Theme.kt:26-31` already declined to pay.
3. **Everything gets a floor.** `THREAD_MIN_WIDTH = 2.0` exists because `MIN_CELL = 6` at
   `bootRatio` can make a cell about fifteen device pixels. Every new radius needs one, and the eyes
   need a **cut-off** rather than a floor — the same shape of decision as `WALL_EDGE_MIN_CELL = 8`
   (`BoardRenderer.kt:602-609`), and its sibling constant should carry the same kind of note. P3 §3.7
   removes 40 × 40 from the picker, but `MatchSetup.MAX_SIDE = 256` and `MIN_CELL` both stand, and
   `BoardRendererTest`'s `Grid(40, 40)` case is deliberately kept to pin the extreme.

---

## 6.2 Texture packs

**Depends on P4**, because a pack is chosen per map shape and the catalogue changes there.

### The finding that decides the design: a shape never reaches a match

`MatchOptions` carries `walls: IntArray` and no shape, and its own KDoc says why — *"Already drawn
rather than named by its shape, because that is what `MatchSetup` takes: a shape never reaches a
match, and it never reaches a replay."* `GauntletLevel.shape` is the only place a shape survives, and
it lives on the **level**, not on the match.

So **a pack cannot be derived from the board and must not try.** Two shapes can draw identical walls
at some size, and a shared `#r=` link carries no shape at all. Deriving one would be a picture that
changes when a link is reopened.

### The design

A `TexturePack` is a `:ui` enum chosen by **whoever starts the match**, with one plain default:

| Entry point | How it gets a pack |
|---|---|
| `GameSession.startLevel(n)` | `Gauntlet.levelAt(n).shape` → the table below. `:ui` may read `MapShape`: it is in `:match`, which `:ui` already depends on, and `SetupPanel` already enumerates `MapShape.entries` |
| `GameSession.startCustom()` / Start match | `MatchOptions` gains `shape: MapShape?`, written by `SetupPanel` from the picker |
| A `#r=` link, or a map taken from a replay | `null` → the plain pack |

**`shape` on `MatchOptions` is a decoration hint and nothing else.** `setupFrom` keeps building a
`MatchSetup` out of `walls` and never reads it. Its KDoc has to say that in those words, or the next
reader will thread it into the match and undo the property `docs/Maps.md:23-28` was designed for.

### What a pack may vary, and what it may never

A pack is a second axis over the **board** the way a scheme is a second axis over a theme — the same
argument `Theme.kt:15-22` makes, and worth stating in those terms.

- **May vary:** the wall fill, the wall edge, and the board's own ground (flat, or a faint per-cell
  figure). All three are `Theme.wall`, `Theme.wallEdge` and `Theme.background` *shaded* — a pack picks
  a treatment, the theme still picks the colour, so all three themes × both schemes keep working
  without a pack knowing any of them.
- **May never vary:** `Theme.body`, `Theme.head`, `Theme.accent`. A trail is what a snake **is** and a
  route is the player's; a texture that moved either would make the board's one reliable colour
  channel depend on which level you are on.

### The one hard rule

**A pack's per-cell variation is a pure function of `(row, col)` and the cell size — never an RNG.**
After §6.0 walls are laid down only from `fit`, so a resize redraws them, and a pattern that shuffled
on resize is the kind of bug that gets reported as "the map changed". Reuse the FNV-1a already written
out in `identicon.kt:65-71` rather than writing a second hash — CC-12 — but **lift it without touching
its arithmetic**: `IdenticonTest`'s *"a known slug still draws the mark it always drew"* (line 65)
pins a literal against it.

### The table

Four or five packs across eleven levels, not eleven. A pack is a *feeling*, and eleven feelings is
eleven times the drawing for a difference nobody can name. Map shape → pack, so `empty` and `arena`
share one. The table is a `when` over `MapShape` with **no `else`**, so P4 adding a twelfth shape is a
compile error rather than a silently plain board — the same enforcement `drawShape` already relies on
(`mapCatalogue.kt:13-24`).

No assets, so SW-08 is untouched.

---

## 6.3 The logo

### The trap: there are two "Snake Warz" on this page and only one of them is a brand

- `index.html:59` — `<h1>Snake Warz</h1>` on the home screen. This one is the logo.
- `index.html:212` — `<span class="wordmark" id="wordmark">Snake Warz</span>` in the game bar.
  **`Chrome.kt:160` overwrites its `textContent` on every render** with `"Level 7 — The Gambler"` or
  the game name. It is a status line wearing the game's name when it has nothing better to say, and
  any SVG put inside it is destroyed by the first frame.

**The logo goes in the home `<h1>` and nowhere else.** The game bar keeps text, and its
`@media (max-width: 30rem)` rule that hides it entirely (`styles.css:397-400`) keeps working for the
reason stated there — the board is what somebody came for.

### Inline SVG, not `<img>`

An `<img src="logo.svg">` cannot read `--accent` or `--ink`, so it would be one fixed picture under
three themes and two schemes. An **inline** `<svg>` using `currentColor` and `var(--accent)` follows
all six for free. It lives in `index.html` as static markup, which is not a third exception to
*"Kotlin never constructs structure"* — Kotlin never touches it.

### Accessibility

```html
<h1 class="lockup">
    <svg class="lockup-mark" viewBox="0 0 …" aria-hidden="true" focusable="false">…</svg>
    <span class="visually-hidden">Snake Warz</span>
</h1>
```

The heading keeps real text, so the document outline is unchanged and no new ARIA semantics are
introduced. `.visually-hidden` does not exist in the stylesheet yet and has to be added — the standard
clip-rect utility, **not** `display: none`, which would take it out of the accessibility tree too.
Note that `[hidden] { display: none !important }` (`styles.css:56-58`) means `hidden` is not an option
here.

### What to draw

The favicon is already the right idea and the right root: a five-block staircase in the snake ramp on
a rounded `#16191d` tile — a snake turning a corner, which is the game. Make the home lockup that
mark at real size beside the words.

**There is no web font on this page** (`styles.css:64` is a system stack) and adding one is a network
request plus SW-08. So the words are either the system stack styled hard — tight tracking, heavy
weight, two-tone across the two words — or **drawn as paths**. Recommend the styled-text version
first: it is a few lines of CSS against a few hundred bytes of hand-authored path data, and it can be
replaced by paths later without anything else moving. If paths win, they belong in the same inline
`<svg>`, and `<title>` is still not a substitute for the `<span>`.

`favicon.svg` gets the mark reduced, and if it moves then `docs/UI.md`'s *"The art follows
`favicon.svg`"* line and `identicon.kt`'s tile note (lines 81-88) move with it — those two are what
keep the whole visual language pointing at one source.

---

## 6.4 Portraits you can actually see

### What is there now

Eleven files, `viewBox="0 0 32 32"`, about eight flat rects on a rounded `#16191d` tile in one green
ramp. `alphabeta.svg` is literally two bounds closing on a search window. `docs/UI.md` states the
house style outright: *"chunky flat rectangles on a rounded `#16191d` tile in the snake ramp, square
`viewBox`, no gradients, no text, no external references — and each one is drawn for **how the bot
plays**"*. Displayed at 1.55rem in the scoreboard, 3rem on a level tile, 4.5rem on the verdict card.

### The change is to the house style, and that is the decision to record

- **`viewBox` 32 → 96.** Three times the linear budget: at 32 a face is eight rects, at 96 it is a
  face with structure. Integer coordinates throughout, and the existing 6/32 corner ratio scales to
  18/96, so the frame is unchanged in proportion.
- **From "how the bot plays" to "who the bot is", without losing the first.** A *character* whose
  design still gestures at the algorithm — alpha-beta keeps its two converging bounds, as a visor.
  That line in `docs/UI.md` is rewritten to say both.
- **Flat tones, still no gradients, no text, no external references.** Cel shading is the look being
  asked for anyway, and gradients are where SVG size and cross-browser rendering differences come from.
- **One tile colour and one frame across all eleven**, because an identicon has to sit beside a
  hand-drawn face on the same card without reading as a different kind of thing —
  `identicon.kt:81-88` is the statement of that and it stays true.
- **No seat colour.** `styles.css:839-841` and `docs/UI.md` both say the swatch is the only thing tying
  a card to a trail on the board. Portraits keep their own palette; the swatch stays.

### Sizes

| Surface | Now | After | Why |
|---|---|---|---|
| Verdict card `#result-portrait` | 4.5rem | ~7rem | The "you just beat them" moment, and the card is centred text with nothing competing |
| Level tile `.level .portrait` | 3rem | ~4.5rem | The one screen where you choose who to face |
| Scoreboard `.slot .portrait` | 1.55rem | unchanged | The reason at `styles.css:836-838` still holds |
| Scoreboard under 30rem | 1.15rem | unchanged | Same |

**The tile is the one that needs re-checking, not just re-numbering.** `.level` is a four-row grid with
the picture spanning all four (`styles.css:269-273`), `.levels` is `minmax(16rem, 1fr)`
(`styles.css:243-251`), and `.level-blurb` is clamped to two lines *specifically* so that the tiles
are an even height (`styles.css:290`). A taller picture against the same four text rows changes which
of the two decides the tile's height. Check at one, two and three columns.

**A bigger picture in the game bar is out of scope, and for a stated reason:** `Chrome.kt:157-159` says
the bar is one line either way because *"the bar's height is what the board's track is measured
against"*. A versus strip there takes a row off the board.

### Budget

**SVG only, and a ceiling of about 4 KB raw per file.** Eleven at that size is roughly 13 KB gzipped
against SW-08's 1.5 MiB — noise — but the rule is what stops somebody reaching for a PNG, which would
not be noise. CI walks `portrait/` with `find` precisely so assets under it cannot escape the budget
(`.github/workflows/ci.yml:39-41`); check the arithmetic locally with
`./gradlew :app:wasmJsBrowserDistribution` before adding all eleven.

`PortraitUrlTest` needs no change — it tests the slug set against `ShippedBots`, not the art. P4's
level 11 reuses `alphabeta`, which is already in `SHIPPED_PORTRAITS`.

---

## 6.5 Motion

### Nothing on this page moves today

Verified: `transition`, `animation`, `@keyframes` and `prefers-reduced-motion` are all absent from
`styles.css`. This phase introduces the first motion into the project, so **the reduced-motion guard
arrives in the same change, not after it.**

### Tier 1 — CSS, no canvas

Screen entrance, tile press, verdict-card entrance, panel slide, the cleared badge. Costs nothing and
risks nothing. Two things it must respect:

```css
@media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
        animation-duration: 0.01ms !important;
        transition-duration: 0.01ms !important;
    }
}
```

beside the existing `prefers-color-scheme` block at `styles.css:31`.

- **Entrance only.** A screen is hidden with `hidden`, and `[hidden] { display: none !important }`
  (`styles.css:56-58`) is binary — there is no frame on which an outgoing screen is still painted. So
  the incoming screen animates and the outgoing one simply goes. That is the honest limit; do not
  reach for a state machine to beat it.
- **Focus must not wait for a transition.** `Shell.focusInto` moves focus on the same task; a CSS
  transition does not block it. Do not add a `transitionend` handler that makes it.

### Tier 2 — the overlay, on frames

Three effects, all on the overlay, which after §6.0 is where everything living already is:

- **A death.** The loser's body flashes and settles to `CORPSE_ALPHA` over about 0.3 s.
- **The route.** `setLineDash` with an offset driven by the frame — marching ants along a held route,
  only while the pointer is down or hovering.
- **The head.** A slow pulse on the marker.

All three need frames the turn clock does not provide. `TurnScheduler` calls `onFrame()` only while
`running`, and `Progress.FINISHED` **stops it** (`TurnScheduler.kt:121-126`) — which is exactly the
moment the death effect needs frames.

So: **one new `internal class Ticker` in `ui/schedule/`** — a rAF loop that runs while at least one
effect is live and stops itself when none is.

- It is **not** a second scheduler, and the KDoc should borrow `KeyRepeat`'s framing
  (`TurnScheduler.kt:9-13`): it produces **paints**, never turns, so it cannot affect a result.
- It reads the wall clock, which `:ui` may and nothing below it may — SW-01. Nothing it computes may
  reach `step()`, and that sentence belongs in the KDoc.
- Mirror `TurnScheduler`'s testability: `frame(timestamp)` is `internal` rather than `private` there
  *specifically* so `TurnSchedulerTest` can drive the clock (`TurnScheduler.kt:81-86`). Do the same and
  `TickerTest` is a unit test rather than a browser test.
- `schedule/` goes from two files to three, comfortably inside CC-06.

### Tier 3 — smooth movement between cells. Out of scope, and here is why

Stated so it is not re-proposed as an obvious win:

- The renderer is handed a **position**, never a time. A sub-turn phase means handing it
  `TurnScheduler`'s accumulator and coupling the two clocks.
- It only reads well at one speed. At `DEFAULT_TURNS_PER_SECOND = 12` a tween is 83 ms; the speed
  slider goes far higher and `MAX_TURNS_PER_FRAME = 256` allows many turns in a single frame, where a
  tween is a smear.
- A replay **seek** and a batch repaint have no previous position to tween from, so both would need a
  second code path — CC-05.
- It is not needed. A snake moving twelve times a second with eyes, a taper and a death flash is
  already motion; interpolation buys smoothness, not life.

---

## Files touched

| File | What |
|---|---|
| `ui/.../render/BoardRenderer.kt` | §6.0 the split, §6.1 the drawing, §6.2 the wall treatment. Class KDoc rewritten, not patched |
| `ui/.../render/Theme.kt` | Nothing, if §6.1 holds its line. If a pack needs a shade rather than a colour, that is a derivation here, not six new palettes |
| `ui/.../render/TexturePack.kt` | New. The enum, the `MapShape` → pack `when`, the per-cell figure |
| `ui/.../render/identicon.kt` | The FNV lift for §6.2, arithmetic untouched; the tile ratio if §6.3 moves the favicon |
| `ui/.../schedule/Ticker.kt` | New. §6.5 tier 2 |
| `ui/.../GameSession.kt` | Passes a pack at `startLevel` / `startCustom` / `load`; owns the `Ticker` |
| `ui/.../model/MatchOptions.kt` | `shape: MapShape?`, decoration only, with the KDoc that says so |
| `ui/.../chrome/panel/SetupPanel.kt` | Writes `shape` into the options it already builds |
| `app/.../resources/portrait/*.svg` | Eleven redraws at `viewBox 0 0 96 96` |
| `app/.../resources/favicon.svg` | The reduced mark, if §6.3 moves it |
| `app/.../resources/index.html` | The home `<h1>` lockup. **Not** `#wordmark` |
| `app/.../resources/styles.css` | `.lockup`, `.visually-hidden`, the portrait sizes, tier-1 motion, the reduced-motion block |
| `docs/UI.md` | §*The overlay canvas* rewritten for §6.0; §*Faces* for the new house style; §*Themes* for what a pack may and may not touch |
| `ui/CLAUDE.md` | The overlay paragraph and the decoration-order line |

## Tests

Canvas output is not unit-testable here — browser tests are off by default and pixel assertions are
brittle. So the tests cover the parts that are **not** drawings:

- **`TexturePackTest`** (new) — the `MapShape` → pack map is total; the per-cell figure is a pure
  function of `(row, col)` and returns the same answer twice for the same input.
- **`TickerTest`** (new, beside `TurnSchedulerTest`) — driven by handed-in timestamps; it stops itself
  when the last effect ends, and it never calls anything that plays a turn.
- **`BoardRendererTest`** — the four existing cases are about `cellAt` and sizing and survive §6.0
  unchanged. *"the gridline gutter belongs to the square below and right of it"* (line 84) still holds,
  because the **board** still owns the gutter; it is the overlay that ignores it.
- **`IdenticonTest`** — unchanged unless §6.3 moves the tile radius, in which case the pinned literal
  at line 65 is updated **deliberately**, as a golden being re-baselined and not a break.
- **`PortraitUrlTest`** — unchanged.
- **`ThemeTest`** — unchanged, and if a change here would move it, §6.1's second constraint has been
  broken.

## Sequencing

§6.0 → §6.1 → §6.5 is one thread and has to run in that order. §6.3 and §6.4 are markup and assets,
independent of the thread and of each other, and can be done at any point. §6.2 waits on P4.

**§6.5 is the part to cut if the release is running long** — it is the only one whose absence leaves
nothing broken.

## Verification

```bash
./gradlew build
./gradlew allTests -PbrowserTests=true
./gradlew :app:wasmJsBrowserDistribution     # then check the CI budget arithmetic locally
```

By hand, on a served bundle:

1. A snake reads as one connected body with a visible head that **faces where it is going**, at 8 × 8
   and at 28 × 28.
2. The tail still fades through two steps before it clears, and a corpse still does not fade.
3. Kill a snake — it flashes and settles; the board does not stutter.
4. Switch theme and scheme while a match is running: every snake, wall and route recolours, and no
   texture pack survives into a colour it should not.
5. Walk three Gauntlet levels with different shapes — the boards feel different, and the trail colours
   do not.
6. Open a shared `#r=` link — plain pack, nothing broken, nothing missing.
7. Resize the window twice — the wall pattern is identical each time.
8. Turn on OS reduced motion and confirm the page still works and simply does not move.
9. The home screen carries the lockup; the game bar still says `Level 7 — The Gambler` as text.
10. A portrait at 7rem on the verdict card holds up, and an identicon beside one on the Gauntlet screen
    still reads as the same kind of object.
