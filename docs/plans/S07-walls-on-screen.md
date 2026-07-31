# S07 — Walls painted, and a map picker

**Modules:** `:ui`, `:app`
**Depends on:** [S05](S05-map-catalogue.md).
**Read first:** [`../UI.md`](../UI.md).

## Goal

Stage 1 ends with a walled match you can actually play in a browser. This session is deliberately the
**smallest possible** `:ui` change: paint the walls, name them honestly, offer a picker in the existing
sidebar. The shell rewrite is [S08](S08-screen-shell.md) and later — do not start it here.

---

## Step 1 — paint them

`ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/render/Palette.kt` gains a `wall` colour per scheme. It is a
board colour, not a snake colour, so it sits beside `background` and `gridline` and is **not** in the
theme-independent `BODIES` array.

Pick it so a wall reads as *structure*: a step or two off `background` towards `ink`, distinctly darker
than `gridline` and unmistakably not a corpse (which keeps `CORPSE_ALPHA = 0.28` of a snake hue).

`ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/render/BoardRenderer.kt`

`repaint` (`:199`) currently fills the background, strokes the gridlines and paints every snake. Add a
wall pass **between** the background and the gridlines, so a gridline reads across a wall exactly as it
reads across the board.

`paintOwner` (`:268-269`) paints purely off `ownerOf`, which returns `SnakeId.NONE` for a wall as well
as for an empty square — so a square repainted by `paintMove` would erase its wall. It must consult
`view.isWall(cell)` first. **This is the bug that will bite:** a wall only disappears where a snake
recently moved, which looks like a rendering glitch rather than a missing branch.

Walls are constant for the life of a match, so nothing about the overlay changes.

## Step 2 — say what it is

`ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/GameSession.kt:257` — the hover label. A wall currently
resolves through `SlotLabels.of(SnakeId.NONE)` to `"nobody"`, so the board says a wall is empty.
CC-18: user-facing text speaks the player's language. Check `isWall` first and say `"wall"`.

## Step 3 — a picker

`app/src/wasmJsMain/resources/index.html` — one `<select id="map">` in the existing `.setup` grid,
beside `#size`. Static markup with static options: **`MapShape` is not a `BotRegistry`**, so this is
not a third exception to *"Kotlin never constructs structure"* — the option list is written in HTML
like every other one on the page.

`ui/.../model/MatchOptions.kt` gains `walls: IntArray`. `Chrome.readOptions()` resolves the picked
shape at the chosen size and seed.

**`:ui` may not see `:bots`, but `:match` is fine** — `MapShape` and `generateMap` are in `:match`, so
this needs no new seam and no injected interface.

`Chrome.applySetup(setup)` (`:201`) points the form at a loaded replay. A replay carries a *bitmap*,
not a shape, so there is generally no option to select. Add a `"— from replay —"` option that is
selected when `setup.mapped` and the bitmap matches no shape at the setup's size, so a rematch replays
the same board rather than silently regenerating a different one. **This is the same half-built
feature `applySetup` already exists to prevent** for allowances and knobs.

`GameSession.setupFrom(options)` passes `walls` into `MatchSetup.create`.

---

## Tests

`ui/src/wasmJsTest/.../render/BoardRendererTest.kt` — the three existing tests are all `cellAt`
hit-testing and are unaffected.

Add:
- `PaletteTest` — the wall colour differs from `background`, from `gridline` and from every body hue,
  under both schemes.
- A `SlotLabels`/hover case asserting a wall reads as `"wall"` and not `"nobody"`.

The rest is a browser check, below. `Chrome` has no test today and this session does not add one —
that is [S10](S10-panels.md)'s problem, when the setup form moves.

---

## Done when

```bash
./gradlew build
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

In the browser: pick each shape at each board size, start a match, and check that

- walls paint, and **stay painted where snakes have moved over the neighbouring squares**;
- hovering a wall says "wall";
- a batch tournament runs on the map and the matrix is sane;
- copying the replay link, pasting it into a fresh tab, and playing it back reproduces the same board.

Kill the server when done:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8099 |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```
