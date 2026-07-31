# S18 — The docs that now describe a different program

**Module:** `docs/`, plus the per-module `CLAUDE.md` files.
**Depends on:** everything.

## Goal

`docs/` is the reason this codebase can be picked up cold, and seventeen sessions have falsified parts
of it. A doc that describes the program as it was is worse than no doc, because it is trusted.

This is not a tidy-up pass. **Each item below is a specific sentence that is now false.**

---

## `CLAUDE.md`

- **The "Current state" table** gains `match/map/`, `match/ladder/`, the new `:ui` packages, and the
  portrait assets in `app/`.
- **"Release 1 is feature-complete, so there is no remaining plan"** — no longer true. Point at
  [`docs/plans/README.md`](README.md).
- **The four non-obvious facts become five.** Interior walls belong there: `Occupancy.clear()` never
  overwrites a `WALL` byte, walls stay outside `Occupancy.hash` but enter `Board.hash` through
  `wallKey`, and `Grid.playableCount` is **not** the denominator any evaluation wants —
  `BoardView.openCount` is. Every one of those breaks the game silently if got wrong.
- **The "Before you touch anything" table** gains a row for maps and one for the plans directory.
- **Commands** gains `--map`, `ladder`, and the note that the map a number was taken on is now part of
  the log's comparability key.

## `docs/UI.md`

The most-falsified document here.

- **"The board is a fixed rectangle"** — rewrite. `BOARD_EXTENT` is gone and a maximum cell size is in.
  The section currently argues *against* a maximum, and that argument was in service of the extent;
  say so, say what replaced it, and keep the "an 8×8 and a 40×40 occupy the same frame" intent, which
  is more true now than it was.
- **"One page, and the one thing that scrolls"** — the sidebar is not the scroller any more; a panel
  is. The `@media (max-width: 52rem)` block that reverted the shell to a scrolling document is gone.
- **"Playing, replaying, and three clocks"** — a dragged path now drives an interactive match through
  `TurnScheduler`, which is a fourth driver and the first time an interactive match has run that
  clock. The sentence *"a match with a live player is stepped by `GameSession.playRound` straight out
  of the keydown"* is now the keyboard's half only.
- **"The overlay canvas"** — three decorations now, and the draw order is wash → plan → threads.
- **"Naming seats, and building DOM"** — still exactly two registry-driven exceptions. Say explicitly
  that screens, panels, map options, theme options and ladder tiles are **not** further exceptions, and
  why: none of them comes off a registry.
- New sections for screens and modes, themes, portraits and the `Portraits` seam, and pointer/touch
  input.
- The **"Browser gotchas"** list gains `touch-action: none`, `100dvh` over `100vh`, and
  `setPointerCapture` with `lostpointercapture`.

## `docs/Match.md`

- Maps: what a `MatchSetup` now carries, and that the bitmap travels so a shape can be redesigned or
  deleted without breaking a shared link.
- The codec's version and flag scheme, and that the geometry bound now runs at the read site.
- `InputBuffer.replace` and `PathPlanner`, and the rule that a drawn route is a **plan, not a
  promise**.
- The ladder table lives here and `:lab` measures it.
- **"A match with a person in it runs no clock"** needs qualifying: it runs one while a path is held.

## `docs/Bots.md`

- The `playableCount` → `openCount` rename and where it landed.
- `MovePrior` counts wall neighbours off the board now, not off coordinates, and `PRIOR_WALL`'s
  default of `0.0` is why no golden could have caught the old reading.
- **`eval=learned` is correct on empty, honest on maps, unfitted for maps** — state it in exactly those
  terms, with the pointer to what a refit would need.
- The measured ladder run from [S16](S16-ladder-table.md) step 4, beside the existing rungs, so the
  level ordering has visible evidence under it.

## `docs/Workflow.md`

- `--map` on `play`, `time`, `ab`, `tune`, `spsa`, and as a `rate` filter.
- The `ladder` command.
- **The fairness probe as a standing procedure**: before any strength number on a new map, run two
  identical entrants and read the seat win-rate.
- The distinct-games warning gains its map case — a fixed map plus fixed spawns plus two bots that
  draw no randomness is four distinct games however many rounds are asked for, and the ladder's lower
  levels are entirely such bots.

## New — `docs/Maps.md`

The catalogue, what each shape is *for*, the ρ-symmetry rule and why it is the half-turn and not a
mirror, the one-region guarantee, and how to add a shape. This is the file somebody reads before
adding the ninth map.

## Per-module `CLAUDE.md`

`ui/CLAUDE.md` (three public declarations now, not two; the overlay's third decoration),
`app/CLAUDE.md` (the boot path still reveals `#app` before measuring; the `Portraits` implementation
lives here), `match/CLAUDE.md` (maps and the ladder), `core/CLAUDE.md` and `bots/CLAUDE.md` if they
carry anything the wall change falsified.

## `docs/research/`

The 2026-07-30 agenda's **P3, P4 and P7 are now partly executed**. Do not rewrite a closed agenda —
they are historical records. Add a short note at the top of that file saying which workstreams
`docs/plans/` delivered and which of its ground truths were corrected in the doing. Three were:

- **ground truth 6 overstates the damage** — `TerritoryEval` normalises by `totalOwned`, not by
  `playableCount`, and needed no change at all;
- `TempoOwnership` was a KDoc sentence, not a computation;
- graph-distance spawn placement, which P3 proposes, **would have moved every three-seat opening on
  an empty board** — the metric stayed Euclidean and only a reachability filter was added.

---

## Done when

```bash
./gradlew build
```

and a read-through of each file against the code it describes. The test for this session is not a
command: pick three claims at random from each document and check them. **A doc nobody has checked
against the program is a doc that is already wrong.**
