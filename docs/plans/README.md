# Release 3 — the game gets its hands on

> **Shipped. All six phases are done and this is now a record rather than a plan.** Read it to find
> out *why* something is shaped the way it is — the decision table below is where the answers are —
> and read the code, [`../../AGENTS.md`](../../AGENTS.md) and `docs/` for what the program does today.
> Where a phase document and the tree disagree, **the tree is right**: a plan records what was
> intended, and several details were settled differently once the code was in front of somebody.
>
> The record this replaced is Release 2's, and it was not deleted so much as superseded: it lives at
> `git show 9112201:docs/plans/README.md`, with its eighteen `S*.md` sessions beside it in the same
> tree.

**For:** anyone asking why a shipped thing in the pointer, the gauntlet, the map catalogue or the
paint is shaped the way it is.
**Assumes:** [`../../AGENTS.md`](../../AGENTS.md) — the module graph, the forbidden dependency edges
and the five non-obvious facts. They are **not** repeated here.

Release 3 was gameplay feel and how the thing looks. **Nothing in it changed the engine, the bot
contract or the replay codec**, and that held: `:core`, `:bot-api` and `:bots` came through untouched,
and a `#r=` link written before it still decodes to the same bytes.

## Why

Release 2 made a game out of a research console. Release 3 made it a game you can play without being
told how, and one that looks like a game rather than like a diagram of one. What it inherited:

- **The pointer was a drawing tool and nothing else.** A press had to land within one square of your
  head, and dragging ran a breadth-first search that routed *around* obstacles — so the path bolted
  the long way round instead of following the mouse. There was no way to say "go over there".
- **A route was planned against the board as a snapshot**, so a tail square that would certainly have
  cleared before you reached it was treated as a wall.
- **Nothing on the home screen said how to play.**
- **Backing out of a level dropped you to the front page** rather than to the level select.
- **`Custom` on top of a running game showed that game**, stale verdict card and all.
- **The setup panel was a form you submitted blind** — you could not see the board you had picked
  until you started it.
- **Several level maps were too closed to be interesting**, and level 1 was a bare rectangle.
- **A run you were pleased with was gone** the moment you started another.
- **"Ladder" named three different things in this repo**, only one of which a player ever sees.
- **A snake was a run of flat squares with a gridline through the middle of it**, a portrait was eight
  rectangles you could not read at 3rem, the logo was the system font, every map was painted
  identically, and **nothing on the page moved** — `styles.css` had no `transition`, no `animation`
  and no `@keyframes` in 1012 lines.

## What shipped

Each phase document is the record of what was decided before it ran, kept in full. Read one to find
the argument behind a shape; read the tree to find out what the code does.

| # | Phase | What it delivered |
|---|---|---|
| 1 | [Pointer controls](P1-pointer-controls.md) | Click to step, hold to keep going, hover to preview, drag traces the pointer exactly, and "blocked" became time-aware. `PathPlanner` grew `route` / `trace` / `revalidate` and lost `extend`; `match/human/Clearance.kt` is the tail-retraction arithmetic under all three, and `ui/nearHead.kt` — the grace radius — is gone |
| 2 | [Gauntlet rename](P2-gauntlet-rename.md) | The campaign stopped being called the Ladder everywhere a player can see. `match/ladder/` → `match/gauntlet/`, `ui/model/ladder/` → `ui/model/gauntlet/`, `:lab`'s `ladder` subcommand → `gauntlet`. `BotLadderTest` and `lab/strength/Ladder.kt` kept the word, and so did the storage key |
| 3 | [Screens and setup](P3-screens-and-setup.md) | Controls on the home screen, Replay on the verdict card, `← Gauntlet`, Custom starts fresh, a live setup preview behind the panel, map options that say what size they need, 40 × 40 gone from the size list |
| 4 | [Maps and levels](P4-maps-and-levels.md) | `rooms`, `diagonals` and `double-spiral` opened up; `arena`, `islands` and `pinwheel` added, for eleven shapes; eleven levels, ending in a Final Boss. The per-level ordering was invalidated by the redraw and **ships as a hypothesis that says so** |
| 5 | [Level replays](P5-level-replays.md) | Every cleared level keeps the run that cleared it, one `localStorage` key per rung, carrying `ReplayCodec`'s own string rather than a new format |
| 6 | [Art and motion](P6-art-and-motion.md) | Snake bodies moved to the overlay canvas and became connected animals with heads and joints; `render/TexturePack.kt` and `render/fnv1a.kt` give each map shape a treatment without touching a trail colour; a real logo; portraits redrawn at `viewBox` 96; and `schedule/Ticker.kt`, the page's first motion |

**The order was forced in four places.** Phase 2 landed before phase 4 so the level work was written
once against `Gauntlet*` rather than twice. Phase 5 landed after phase 4 so no stored replay ever
described a level table that had since changed. Phase 3's §3.1 waited on phase 1, because it is the
page that documents those controls. Phase 6 was last: §6.2 needed phase 4's catalogue to have stopped
moving, and §6.0 rewrote how `BoardRenderer` splits its two canvases — cheaper once the pointer work
had stopped moving the overlay around.

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

## Where the reasoning ended up

The rule while this was open was that a phase folds its reasoning into the code and into `docs/`
rather than leaving it here, so a decision above is usually the *short* form of something written
beside what it governs:

- The pointer's two verbs, the clearance off-by-one and the input queue's two capacities are in
  [`../Match.md`](../Match.md); the clocks, the panels, the overlay's paint order and the texture
  packs are in [`../UI.md`](../UI.md).
- The catalogue, the half-turn symmetry argument and what a redraw costs a measurement are in
  [`../Maps.md`](../Maps.md).
- The level table's own KDoc — `Gauntlet` in `:match` — carries which of its placements are now
  guesses and which three still stand on a number, and [`../Bots.md`](../Bots.md) carries the run that
  the redraw invalidated, kept and labelled rather than deleted.

Three links pointed into this directory while it described Release 2 and were repointed when it
closed: the *"ask why a shipped thing is shaped the way it is"* row of `AGENTS.md`'s reading table,
the *Current state* sentence naming this directory a closed plan, and the delivery note near the top
of [`../research/2026-07-30_Research-Agenda.md`](../research/2026-07-30_Research-Agenda.md).

## Checking this area

The commands that exercise what these phases touched. **`--tests` is scoped per module**, because a
bare `./gradlew jvmTest --tests "*Foo*"` fails with `No tests found for given includes` on every
module that has no matching test — which is most of them:

```bash
./gradlew :match:jvmTest --tests "*PathPlanner*" --tests "*Clearance*" --tests "*InputBuffer*"
./gradlew :match:jvmTest --tests "*Gauntlet*" --tests "*GenerateMap*" --tests "*BoardMap*"
./gradlew :lab:test      --tests "*Gauntlet*" --tests "*OpeningSetup*"    # :lab is JVM-only: `test`, not `jvmTest`
./gradlew build                                    # both targets, purity, ktlint
./gradlew allTests -PbrowserTests=true             # SetupPanelTest / ShellTest / GauntletScreenTest / TexturePackTest / TickerTest
./gradlew :lab:run --args="gauntlet --rounds 40"   # the renamed subcommand, on every new map drawn
./gradlew :app:wasmJsBrowserDistribution           # then check the CI budget arithmetic — SW-08
```

`TexturePack` and `Ticker` are `:ui`, which has no JVM target at all, so they run under
`-PbrowserTests=true` or not at all. The browser suite is off by default and phases 3 and 5 genuinely
needed it: a new `<option>` missing from `SetupPanelTest.SKELETON` fails every case in that file at
construction.

**Phase 6 is the one phase whose result no test can see.** Canvas output is not unit-testable here, so
it shipped behind the hand check below. Anything that changes `BoardRenderer`, `TexturePack` or
`styles.css` is checked the same way, on the reserved port and never by backgrounding the dev server —
[`../Workflow.md`](../Workflow.md) has why.

### The hand check it shipped behind

Kept with its numbering, because P3, P4 and P5 each cite the items that were theirs. Run with **You**
in a seat on a served bundle; it is still the regression list for this area.

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
13. At 8×8 the map picker reads **"Rooms — needs 14 × 14"** rather than a silently greyed row, and
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
