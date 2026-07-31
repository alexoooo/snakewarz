# Release 3 — the game gets its hands on

> **This is an open plan.** Unlike the Release 2 record it replaces, it describes what is *left*, not
> why something shipped. When a phase lands, fold its reasoning into the code and into `docs/`, and
> strike the phase from the table below.

**For:** anyone picking up one of the phases. Read this file, then read only the phase you are doing
and whatever it depends on.
**Assumes:** [`../../CLAUDE.md`](../../CLAUDE.md) — the module graph, the forbidden dependency edges
and the five non-obvious facts. They are **not** repeated here and they do not relax for this work.

Release 3 is gameplay feel and how the thing looks. Nothing in it changes the engine, the bot contract
or the replay codec.

## Why

Release 2 made a game out of a research console. Release 3 makes it a game you can play without being
told how, and one that looks like a game rather than like a diagram of one.

- **The pointer is a drawing tool and nothing else.** A press must land within one square of your head,
  and dragging runs a breadth-first search that routes *around* obstacles — so the path bolts the long
  way round instead of following the mouse. There is no way to say "go over there".
- **A route is planned against the board as it is now**, so a tail square that will certainly have
  cleared before you reach it is treated as a wall.
- **Nothing on the home screen says how to play.**
- **Backing out of a level drops you to the front page** rather than to the level select.
- **`Custom` on top of a running game shows that game**, stale verdict card and all.
- **The setup panel is a form you submit blind** — you cannot see the board you picked until you start it.
- **Several level maps are too closed to be interesting**, and level 1 is a bare rectangle.
- **A run you were pleased with is gone** the moment you start another.
- **"Ladder" names three different things in this repo**, only one of which a player ever sees.
- **A snake is a run of flat squares with a gridline through the middle of it**, a portrait is eight
  rectangles you cannot read at 3rem, the logo is the system font, every map is painted identically,
  and **nothing on the page moves** — `styles.css` has no `transition`, no `animation` and no
  `@keyframes` in 1012 lines.

## The phases

| # | Phase | What it delivers | Depends on |
|---|---|---|---|
| 1 | [Pointer controls](P1-pointer-controls.md) | Click to step, hold to keep going, hover to preview, drag traces the mouse exactly, and "blocked" becomes time-aware | — |
| 2 | [Gauntlet rename](P2-gauntlet-rename.md) | The campaign stops being called the Ladder, everywhere but its storage key | — |
| 3 | [Screens and setup](P3-screens-and-setup.md) | Controls on the home screen, Replay on the verdict card, `← Gauntlet`, Custom starts fresh, a live setup preview, map options that say what they need, 40×40 gone | 3.1 needs phase 1 |
| 4 | [Maps and levels](P4-maps-and-levels.md) | Three shapes opened up, three new ones, eleven levels and a Final Boss | phase 2 |
| 5 | [Level replays](P5-level-replays.md) | Every cleared level keeps the run that cleared it | phase 4 |
| 6 | [Art and motion](P6-art-and-motion.md) | Snakes with heads and joints, texture packs per map, a real logo, portraits three times the size, and the page's first motion | 6.2 needs phase 4 |

**Ordering.** Phases 1 and 2 are independent of everything and of each other. Phase 2 lands before
phase 4 so the level work is written once against `Gauntlet*` rather than twice. Phase 5 lands after
phase 4 so no stored replay ever describes a level table that has since changed. Phase 3 is seven
independent items; only 3.1 waits on phase 1, because it is the page that documents those controls.
Phase 6 is last: §6.2 needs phase 4's catalogue, and §6.0 rewrites how `BoardRenderer` splits its two
canvases — a rewrite that is cheaper once the pointer work in phase 1 has stopped moving the overlay
around. Its §6.3 and §6.4 are markup and assets and can be picked up at any time.

## Decisions already taken — do not relitigate

| Question | Answer | Why |
|---|---|---|
| Click semantics | **One step per click; holding keeps going.** Release still stops the snake dead | It preserves Release 2's "holding is what makes it move" instead of inverting it, and a click is then just the shortest possible hold |
| A press with no route | **Does nothing at all** — no hold, no step, no clock | The hover preview and the press call the same `route()`, so *what you can see is what a press will do*. A square with no visible route is a square a press ignores |
| Press vs drag | **A press says go there, a drag says go this way** | The press routes (shortest, straight-preferring, around obstacles); the drag traces the pointer literally and cuts where it is blocked. Two verbs, not one with a mode flag |
| A blocked route | **Truncates and stops.** Never re-routes | "A plan, never a promise" survives: the snake stops where the plan died rather than making moves nobody drew |
| Tail prediction | **Retraction is predicted; opponents' heads are not** | Retraction is arithmetic off `growEveryNthMove` and is exact. A head is a guess, and a guess that kills you is worse than a wall |
| The campaign's name | **Gauntlet** | `Ladder` also names `BotLadderTest`'s bot-strength ordering and `:lab`'s ratings table. Renaming the one a player sees removes a three-way overload rather than adding a fourth |
| The progress key | **`snakewarz.ladder.v1` never changes** | It is somebody's saved place. SW-05 freezes it, and a rename would silently reset every player |
| The Final Boss | **An eleventh level: `alphabeta:eval=chamber` on an empty 8×8** | The dearest appraisal in the registry, unaffordable on a 20×20 and perfectly affordable here. Eleven levels, ten algorithms, no repeated configuration |
| The new level table | **Ships unmeasured, and says so** | Redrawing maps invalidates the measured ordering. The re-measurement goes on the next research agenda rather than blocking the release |
| Where snake bodies are painted | **The overlay canvas, not the board** | A connected body has to cross the one-pixel gridline gutter the board's whole paint model is built on. The overlay is already cleared and redrawn whole every turn and already walks every body twice, so this costs a constant factor rather than an order — and it is what makes any animation possible |
| Smooth movement between cells | **Out of scope, permanently for this release** | It would hand the renderer the scheduler's accumulator, it only reads well at one speed, and a seek has no previous position to tween from |
| What a texture pack keys on | **The map shape, carried as a `:ui` decoration hint** | A shape never reaches a `MatchSetup` and never reaches a replay — by design. So a pack is chosen where the match is *started*, and a shared `#r=` link gets the plain pack |
| What a pack may recolour | **Board things only** — wall, wall edge, ground | A trail is what a snake *is* and the route is the player's. A texture that moved either would make the board's one reliable colour channel depend on which level you are on |
| The portrait house style | **`viewBox` 32 → 96, flat-shaded characters** — still SVG, still no gradients, no text, no seat colour | The ask is "who am I facing". Flat cel shading is the look anyway, and gradients are where SVG size and rendering differences come from |
| Where the logo goes | **The home screen `<h1>` only, as inline SVG** | `#wordmark` in the game bar has its `textContent` overwritten every render with the level title. An `<img>` cannot follow `--accent`, so a themed mark has to be inline |

## The one thing this plan changed about the docs

`docs/plans/` used to hold the closed record of Release 2 — eighteen sessions, and the reasoning behind
path release, the wall bitmap in the replay, and where maps and the ladder live. It has been replaced
by this. Three links pointed into it and dangle until they are repointed:

- `CLAUDE.md:32` — the *"ask why a shipped thing is shaped the way it is"* row of the reading table.
- `CLAUDE.md:61` — the sentence in *Current state* claiming this directory is a closed plan.
- `docs/research/2026-07-30_Research-Agenda.md:20`.

## Standing rules for every phase

- **Read the row in [`../../CLAUDE.md`](../../CLAUDE.md) that matches what you are about to touch**, and
  [`../Coding-Standards.md`](../Coding-Standards.md) before the first change, not after the first review.
- **Stage a new source file the moment you create it** — `git add <path>`. Never commit, never push.
- **`./gradlew build` is the gate**, and it runs `checkModulePurity` and `ktlintCheck`.
- Five rules bite in this work specifically:
  - **SW-03** — `PathPlanner` and `Clearance` are in a pure module: primitive arrays,
    constructor-allocated buffers, nothing allocated per call.
  - **SW-05** — a `MapShape.slug`, a `GauntletLevel.index` and a `localStorage` key are frozen once
    released. A shape's *drawing* is not: a map travels as squares, never as a name.
  - **CC-06 / CC-15** — `match/human/` stays flat at six files; one public declaration per file.
  - **CC-12** — two near-identical helpers written in the same change are one helper.
  - **CC-18** — every string a player reads is in the player's language, with no internal referents.
  - **SW-08** — phase 6 is the only one that adds assets. SVG only, and check the CI budget arithmetic
    locally with `./gradlew :app:wasmJsBrowserDistribution` before adding eleven of anything.
- **Never background `wasmJsBrowserDevelopmentRun`.** To see the app, build a static bundle and serve it
  yourself on the reserved port:
  ```bash
  ./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
  py -m http.server 8099 --bind 127.0.0.1 \
     --directory app/build/dist/wasmJs/developmentExecutable
  ```
  Kill it when done —
  `Get-NetTCPConnection -State Listen -LocalPort 8099 | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }`.

## Verification, for the whole release

```bash
./gradlew jvmTest --tests "*PathPlanner*" --tests "*Clearance*" --tests "*InputBuffer*"   # phase 1
./gradlew jvmTest --tests "*Gauntlet*"                                                    # phases 2, 4
./gradlew jvmTest --tests "*GenerateMap*" --tests "*BoardMap*" --tests "*OpeningSetup*"   # phase 4
./gradlew jvmTest --tests "*TexturePack*" --tests "*Ticker*"                              # phase 6
./gradlew build                                    # both targets, purity, ktlint
./gradlew allTests -PbrowserTests=true             # SetupPanelTest / ShellTest / GauntletScreenTest
./gradlew :lab:run --args="gauntlet --rounds 40"   # the renamed subcommand, and every new map drawn
./gradlew :app:wasmJsBrowserDistribution           # phase 6 — then check the CI budget arithmetic
```

The browser suite is off by default and phases 3 and 5 genuinely need it: a new `<option>` missing from
`SetupPanelTest.SKELETON` fails every case in that file at construction, and the result-card and tile
changes are `ShellTest`'s and `GauntletScreenTest`'s. **Phase 6 is the one phase whose result no test
can see** — canvas output is not unit-testable here, so its checks below are the gate.

Then by hand, with **You** in a seat on a served bundle:

1. Hover a distant empty square — a dim route appears, an L rather than a staircase.
2. Click it — exactly **one** step along that route, then the snake stops.
3. Press and hold — it walks the rest; release mid-route and it halts where it is.
4. Hover a square with no route (behind a wall, on a body) — no preview; press and hold it and
   **nothing happens at all**, the match does not advance.
5. Press your own head and drag a curve — the route follows the pointer square for square and never
   bolts round an obstacle; drag back along it and it shortens.
6. Drag across your own tail a few squares back — the route goes **through** it.
7. Hold a route across an opponent's line and let them cut it — the route truncates at the cut.
8. Home screen reads **Gauntlet** and carries the controls column; narrow the window until it stacks
   under the buttons and check nothing is cut off.
9. Beat level 1, reload — progress survives, because the storage key did not change.
10. Finish a level: the card offers Next level / Gauntlet in the cluster and **Watch replay** in its own
    bar below; press it, watch, and confirm you are still inside the level rather than in Custom.
11. Play a match, go Home, press **Custom** — a fresh board on a fresh seed, and no stale verdict card.
12. Open Setup, change size and map — the board behind the panel redraws as you pick, spawns and all.
    Close the panel without starting and the real match comes back.
13. At 8×8 the map picker reads **"Rooms — needs 15 × 15"** rather than a silently greyed row, and
    40 × 40 is gone from the size list.
14. Inside a level the bar reads **← Gauntlet** and goes to the level select; Escape does the same; a
    custom match still reads ← Home.
15. Walk far enough up to see `arena`, `islands` and `pinwheel` drawn, that level 1 is no longer a bare
    rectangle, and that `rooms` has doors you can fit through.
16. Beat a level, go back to the Gauntlet screen, press the ▷ on its tile — your winning run plays back.
    Reload the page and it is still there.
17. A snake reads as one connected body with a head that **faces where it is going**, at 8 × 8 and at
    28 × 28. The tail still fades through two steps before it clears; a corpse still does not fade.
18. Kill a snake — it flashes and settles, and the board does not stutter. Switch theme and scheme
    mid-match and everything recolours.
19. Walk three levels with different shapes — the boards feel different and the **trail colours do
    not**. Open a shared `#r=` link: plain textures, nothing missing. Resize twice: the wall pattern is
    identical each time.
20. Turn on OS reduced motion — the page still works and simply does not move. The home screen carries
    the lockup; the game bar still says `Level 7 — The Gambler` as text. A portrait at 7rem on the
    verdict card holds up, and an identicon beside one on the Gauntlet screen still reads as the same
    kind of object.