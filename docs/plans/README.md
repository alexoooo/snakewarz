# Release 2 — from testbed to game

> **Shipped. All eighteen sessions are done and this is now a record rather than a plan.** Read it to
> find out *why* something is shaped the way it is — the decision table below is where the answers
> are — and read the code, [`../../CLAUDE.md`](../../CLAUDE.md) and `docs/` for what the program does
> today. Where a session document and the tree disagree, the tree is right: a plan records what was
> intended, and several details were settled differently once the code was in front of somebody.

**For:** anyone picking up one of the sessions below. Read this file, then read only the session you
are doing and the two it depends on.
**Assumes:** [`../../CLAUDE.md`](../../CLAUDE.md) — the module graph, the forbidden dependency edges
and the four non-obvious facts. They are **not** repeated here and they do not relax for this work.

## Why

Release 1 is a research console that happens to be playable. The board is a fixed 640-device-pixel
rectangle beside a sidebar of pickers, knob grids, a seed box and a win-rate matrix; every map is an
empty rectangle; steering is the arrow keys; and there is exactly one mode, which is "configure a
match". Release 2 makes it a game without giving up any of that.

Four changes, in this order:

1. **Boards get walls.** Interior obstacles in patterns, with spawns that belong to the map. This is
   the premise change, and it lands first for the reason
   [`../research/2026-07-30_Research-Agenda.md`](../research/2026-07-30_Research-Agenda.md) gives in
   P3: every measurement taken before it is an empty-board measurement somebody has to retake.
2. **The page becomes a game shell.** Board takes the frame, dense chrome moves behind slide-out
   panels, portrait phone works.
3. **Steering becomes a drawn path.** Press the head, drag a route, the snake follows while you hold.
4. **Modes.** *Ladder* — ten levels, ten different opponents, progress in `localStorage`.
   *Custom* — everything the page does today.

## Decisions already taken — do not relitigate

| Question | Answer | Why |
|---|---|---|
| Path release | **Stops immediately.** Lift the pointer and the snake halts on the cell it is on; the rest of the path is discarded | Holding is what makes it move |
| Portraits | **Hand-authored SVG per shipped bot**, injected by slug, procedural identicon fallback | Ten characters is worth the art; the fallback keeps a contributed bot working on day one |
| Research chrome | **Kept, behind Custom mode, in panels** | Nothing is lost; it stops crowding the board |
| Map in a replay | **The wall bitmap itself, raw and bit-packed** — never a generator name, never run-length encoded | `MatchSetup`'s KDoc: geometry, rules, spawns and turn order are *recorded, never re-derived*. A shape id would freeze generator internals as a compatibility contract for every URL ever shared; because the bitmap travels instead, **a map shape can be redesigned or deleted without breaking a single shared link**. RLE was measured and rejected: a 20×20 pillar lattice is ~20 runs a row → ~400 bytes, against a raw bitmap's 50 |
| Board extent | **Fill the container, clamped by a maximum cell size**, replacing `BOARD_EXTENT = 640` | Snakes centre stage. Closer to the stated intent ("an 8×8 and a 40×40 occupy the same frame") than the constant was: both now fill the frame instead of both being capped at 640 device px |
| Where maps and the ladder live | **`:match`**, in new `map/` and `ladder/` packages | `MatchSetup` and spawn placement are already there, and `:ui`, `:app` **and `:lab`** all see `:match` while `:ui` may never see `:bots`. Putting the ladder there is what lets `:lab` *measure* that level 7 is harder than level 6 |

## Sessions

Each one ends with a green `./gradlew build` and a working app. Do them in order; the **Depends on**
line in each is the real constraint.

### Stage 1 — Walls and maps

| # | Session | Module |
|---|---|---|
| [S01](S01-core-walls.md) | The neutrality tests, then interior walls in the engine | `:core` |
| [S02](S02-bots-geometry.md) | The two bot sites that read geometry instead of occupancy | `:bots` |
| [S03](S03-match-header.md) | `MatchSetup` carries a map; spawns and stats follow | `:match` |
| [S04](S04-replay-codec-v3.md) | `ReplayCodec` v3, and the bound that has to move | `:match` |
| [S05](S05-map-catalogue.md) | `match/map/` — shapes, symmetry, connectivity, eight maps | `:match` |
| [S06](S06-lab-maps.md) | Tournaments and `:lab` learn about maps; the fairness probe | `:match`, `:lab` |
| [S07](S07-walls-on-screen.md) | Walls painted, and a map picker | `:ui`, `:app` |

**The order is the risk order.** `:core` and `:bots` come first because both are gated by hashes that
already exist — `GoldenMoveStreamTest`'s sixteen, and especially `-6119216452350361752`
(`wallhug`×`wallhug`, *"pinned by the rules alone. If it ever moves, the engine moved"*). The codec is
S04 and not S03 so that the header change can be proved against an untouched `SHIPPED_PAYLOAD` first.

### Stage 2 — The game shell

| # | Session | Module |
|---|---|---|
| [S08](S08-screen-shell.md) | Screens, panels, navigation, focus | `:ui`, `:app` |
| [S09](S09-game-screen.md) | The board takes the frame; mobile | `:ui`, `:app` |
| [S10](S10-panels.md) | Setup, tournament and share move into panels | `:ui`, `:app` |
| [S11](S11-themes.md) | `Theme` replaces `Palette`; walls get painted | `:ui`, `:app` |
| [S12](S12-portraits.md) | The `Portraits` seam and eleven SVGs | `:ui`, `:app` |

### Stage 3 — Drawn-path steering

| # | Session | Module |
|---|---|---|
| [S13](S13-path-planner.md) | `PathPlanner` and a path-sized input queue | `:match` |
| [S14](S14-path-input.md) | Pointer and touch drag, and the clock it starts | `:ui` |
| [S15](S15-keyboard.md) | Everything, without a mouse | `:ui`, `:app` |

### Stage 4 — Ladder

| # | Session | Module |
|---|---|---|
| [S16](S16-ladder-table.md) | The ten levels, and `:lab` proving they get harder | `:match`, `:lab` |
| [S17](S17-ladder-screen.md) | Level select, progress, Continue | `:ui`, `:app` |

### Stage 5

| # | Session | Module |
|---|---|---|
| [S18](S18-docs.md) | The docs that now describe a different program | `docs/` |

## Standing rules for every session

- **Read the row in [`../../CLAUDE.md`](../../CLAUDE.md) that matches what you are about to touch**,
  and [`../Coding-Standards.md`](../Coding-Standards.md) before the first change, not after the first
  review.
- **Stage a new source file the moment you create it** — `git add <path>`. Never commit, never push.
- **`./gradlew build` is the gate**, and it runs `checkModulePurity` and `ktlintCheck`. The browser
  goldens need `./gradlew allTests -PbrowserTests=true` and real Chrome.
- **Five rules bite in this work specifically:**
  - **SW-05** — a `MapId` slug ends up in a shared replay URL. Freeze it on release, same charset
    discipline as `BotId`.
  - **SW-09** — a decoded wall bitmap arrives from a stranger. Bound it *before* allocating from it,
    and fail with `IllegalArgumentException`, never `OutOfMemoryError`.
  - **SW-03** — `PathPlanner` is in a pure module: primitive arrays, constructor-allocated buffers.
  - **SW-08** — CI fails above 1.5 MiB gzipped. Check with
    `./gradlew :app:wasmJsBrowserDistribution` before Stage 2 closes.
  - **CC-06 / CC-15** — `match/map/` and `match/ladder/` stay a handful of files; one public
    declaration per file.
- **Never background `wasmJsBrowserDevelopmentRun`.** To see the app, build a static bundle and serve
  it yourself on the reserved port:
  ```bash
  ./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
  py -m http.server 8099 --bind 127.0.0.1 \
     --directory app/build/dist/wasmJs/developmentExecutable
  ```
  Kill it when done — `Get-NetTCPConnection -State Listen -LocalPort 8099 | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }`.