# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this project is

**snakewarz** — a Tron-style snakes game built as an **AI testbed**. Snakes move one cell per turn on a
rectangular grid; walls and every snake body (including your own) are lethal; the last one moving wins.
There is no food and no score. Inception 2005, imported from the Google Code archive.

The point of the project is the **pluggable AI**: writing search bots and pitting them against each other.
Everything else exists to serve that.

## Current state — read this first

Mid-rewrite. **Phase 3 of 6 is complete**, which means the game is **playable**: the rules engine,
the bot contract, the match driver, the replay codec, the canvas renderer and the DOM chrome all
exist and are verified. You can play against the shipped bots, watch bots fight, scrub a recording
and share a match as a URL. What is missing is a bot worth losing to — that is Phase 4.

| Path | Status |
|---|---|
| `core/` | `:core` module. Padded-grid primitives plus the rules engine: `Occupancy`, `Board`, `MatchState`, `SplitMix64`, `Budget` |
| `bot-api/` | `:bot-api` module. `Bot`, `Decision`, `Turn`, `BotSetup`, `BotRegistry`, plus `Scratch`/`Playout` — the search arena that makes the budget structural |
| `bots/` | `:bots` module. `RandomBot`, `WallHugBot`, and `ShippedBots`, the `BotRegistry` implementation |
| `match/` | `:match` module. `Match` driver, `MatchSetup`, `MatchRecord`, `ReplayCodec`, spawn placement, and human input — `InputBuffer`, `StallPolicy`, `InteractiveBot`, `PlayableRegistry`. No time, no DOM |
| `ui/` | `:ui` module. `GameSession` — the only public class — over `BoardRenderer`, `TurnScheduler`, `Chrome` and `Palette` |
| `app/` | `:app` module. `main()`, registry injection and `#r=` replay routing. Sixty lines, and that is the point |
| `build-logic/` | Convention plugins `snakewarz.pure` and `snakewarz.browser`, sharing `registerModulePurityCheck` |
| `legacy/java/ao/**` | The original Java, reference only. Not in the build. Deleted at release 1 |
| `docs/MIGRATION.md` | The design doc and phase plan. **Read this before changing architecture** |

There is still **no search bot**: `RandomBot` and `WallHugBot` are the whole roster, and neither
touches `Turn.scratch`. Do not assume anything else exists; check the tree.

The pre-rewrite tree is one command away: `git show legacy-java-final:<path>`.

**Phase tracker** — update this line as phases land, and mirror it in `docs/MIGRATION.md`:

> Current phase: **4 — not started** (`AStar`, flood fill, then `UctBot` on flat node pools)

## Where the project is going

Kotlin 2.4.10, Gradle KTS, **`wasmJs` browser target only**, deployed as static files to GitHub Pages.
Rendering is Kotlin/Wasm drawing to an HTML `<canvas>` 2D context, with hand-written HTML/CSS for the
chrome — deliberately **not** Compose Multiplatform. Bots are Kotlin classes compiled into the app and
registered in an explicit `BotRegistry`.

Release 1: live match view with play/pause/step/speed, human vs bot, deterministic seeded matches,
shareable replays encoded in the URL hash, per-match stats.

### Module graph

| Module | Responsibility | May depend on | Targets |
|---|---|---|---|
| `:core` | Grid, occupancy, bodies, rules, transition, terminal detection, PRNG, budget | **stdlib only** | wasmJs + jvm |
| `:bot-api` | The contract bot authors read. Small, stable | `:core` | wasmJs + jvm |
| `:bots` | Shipped bots + `BotRegistry` impl | `:core`, `:bot-api` | wasmJs + jvm |
| `:match` | Turn sequencing, slot wiring, human input, replay codec, stats. No time, no DOM | `:core`, `:bot-api` | wasmJs + jvm |
| `:ui` | Canvas renderer, DOM chrome, rAF scheduler | `:core`, `:match`, `kotlinx-browser` | wasmJs |
| `:app` | `main()`, wiring, URL hash routing | all | wasmJs |

The `jvm()` target on the four pure modules exists **only to run tests fast** — it is never deployed and
contributes nothing to the wasm bundle. It doubles as a second compiler proving those modules are
platform-free.

### Forbidden dependency edges

These are the load-bearing constraint of the architecture. Do not add any of them, even temporarily.

- `:core` → **any** project dependency, ever. Notably **not** `:bot-api` — the engine does not know bots exist.
- `:core`, `:bot-api`, `:bots`, `:match` → `kotlinx-browser`, `org.w3c.*`, or any wasm-only API.
- `:match` → `:bots`. The driver resolves bots through the `BotRegistry` *interface*; `:app` injects the
  implementation. This is what keeps the replay codec free of bot classes.
- `:bots` → `:match`, `:ui`, `:app`. A bot must not be able to reach the clock or another slot's RNG.
- `:ui` → `:bots`. The renderer paints a `BoardView` and the chrome names slots through the
  `BotRegistry` interface, so nothing in `:ui` can tell a wall hugger from a human.

Every one of these is enforced by `checkModulePurity`, which is wired into `check` for both
convention plugins and walks every `*CompileClasspath` — test source sets included.

### Where the human lives

`InteractiveBot` is in **`:match`**, not `:bots`, and is deliberately **not** in `ShippedBots`. The
bot contract suite requires that no registry entry claims to be interactive — a search bot that
stalls has malfunctioned — so the human seat is composed *on the outside* of the shipped registry by
`PlayableRegistry`, which `:app` wraps around `ShippedBots`. `:match` already owns human input in the
module table, and keeping the pieces there makes them JVM-testable.

`PlayableRegistry.HUMAN_ID` is the slug `"human"`, and it is **frozen** like every released bot id:
it goes into the header of every replay of a game somebody played themselves. Such a replay plays
back perfectly — playback substitutes a scripted stand-in for every slot regardless of slug — but it
will not survive `MatchRecord.verify`, because re-running a person is not a thing a registry can do.

Every interactive slot reads the same `InputBuffer`, because there is one keyboard. A match takes at
most one human, and `:ui` offers the seat for slot 1 only.

**A match with a person in it is turn-based.** `StallPolicy.WAIT_FOR_INPUT` is the default of both
`InteractiveBot` and `PlayableRegistry`, so a human slot answers `Pending` on every turn it has no
key for, and `:ui` does not start `TurnScheduler` at all while `Match.interactive` — one keypress
plays exactly the round it belongs to, and the transport is disabled because there is no clock to
drive. When the player is eliminated `interactive` goes false, the scheduler takes over and the
survivors finish the match on the clock. `Match.interactive` is deliberately not
`bots.any { it.interactive }`: `ScriptedBot` claims to be interactive so that a partial recording
parks rather than forfeits, so playback is excluded by a flag `Match.playback` sets.

**A held key repeats on our clock, not the operating system's.** `Chrome` drops
`KeyboardEvent.repeat` — a text-editing rate, half a second of nothing then thirty a second, and
different on every machine — and `KeyRepeat` (in `:ui`, on `requestAnimationFrame`) turns a held key
into one move every 250ms. A tap is exactly one move. A second key pressed while the first is down
takes the repeat over, and `blur` cancels it, because a key released while the page is not looking
never sends `keyup`.

**A trapped player plays a fatal move instead of waiting.** `InputBuffer.take` filters illegal
input, so once nothing is legal no key the player could press would ever come back from it, and
`WAIT_FOR_INPUT` would park that match for good. Every direction from there is the same death — the
engine records `TRAPPED` whichever is played — so this is a move in the sense that a snake has to
make one, not a choice, and it is not the `MoveTracker` bug (which invented a *survivable* move
nobody chose).

## Four non-obvious facts

Getting any of these wrong silently breaks the game or its determinism.

**1. Snakes grow at half speed.** `SnakeImpl.advance` flips `willGrow` on every call, starting from `false`,
so body lengths go `1, 1, 2, 2, 3, 3, 4…` — the tail only retracts on alternating turns. This is a real
rule, not an artifact. It is `RulesConfig.growEveryNthMove = 2` (`1` = classic Tron) and it has a golden
test. Reference: `ao/sw/engine/v2/SnakeImpl.java:47-68`.

**2. `Bot.chooseMove` is synchronous and must never become `suspend`.** Bots run the engine *inside their
own turn*: `BiState.rollout()` builds a whole game per MCTS rollout using `RandomAi` as its policy. A
suspending bot cannot serve as another bot's rollout policy without `runBlocking`, which does not exist in
wasm — and it would allocate a continuation per call across millions of rollout steps. The human player
returns `Decision.Pending` and the driver polls instead. A useful side effect: bot code physically cannot
reach a clock, so determinism holds by construction rather than by discipline.

**3. Replays record the move stream, not just the seed.** `log`/`exp` are not specified bit-identical across
platforms (`+ - * / sqrt` are), and UCB1 is `sqrt(log(v)/(5*cv))` — so a seed-only replay of an MCTS match
could diverge between the JVM test target and the browser. Recording moves also survives you tuning a bot
constant. The seed is kept as provenance and as a CI verification input, never as the playback source of
truth.

**4. Legality is evaluated *before* the tail retracts.** A snake may not move into the square its own tail
is about to leave, even on a turn when that square is certain to clear. This is the legacy rule —
`SimpleSnakesGame` tested the destination against a board built before the retraction — and letting the
tail clear first is a materially different game, one where a snake can chase its own tail forever. It is
`BoardRulesTest."a snake may not move into the square its own tail is about to leave"`.

## Determinism rules

A match must reproduce exactly. This is a hard invariant, not a nice-to-have.

- **No global RNG.** RNG is injected per slot, forked from the match seed (`matchRng.fork(slotIndex)`) so one
  bot's consumption never shifts another's stream.
- **Use the project's `SplitMix64`, not `kotlin.random.Random`.** A persisted URL replay format must not
  depend on stdlib algorithm stability across Kotlin versions and targets.
- **No `HashMap`/`HashSet` *iteration* in `:core` or `:bots`.** Use `LinkedHashMap` or sorted arrays. The
  legacy code iterated a `HashMap` and was only *accidentally* stable because `PlayerAvatar.hashCode()`
  returned a monotonic index.
- **No wall-clock anything in `:core`, `:bot-api`, `:bots`, or `:match`.** Bot budgets are counted in
  iterations, never milliseconds. Time lives in `:ui` only.

## Conventions

- Root package **`ao.snakewarz`**; sub-packages mirror module names.
- `explicitApi()` in every module. Anything outside a module's contract is `internal`.
- **No version suffixes in names, ever.** No `v2` package, no `SnakesGame2`. Name for behaviour:
  `UctBot` vs `FlatMonteCarloBot`.
- **No `Foo`/`FooImpl`.** Either the class is concrete (`Board`, `MatchState`), or the interface names the
  role and implementations name the mechanism (`Bot`/`UctBot`, `Rng`/`SplitMix64`). No `Util`/`Helper`
  objects — use methods or extensions.
- Value classes for ids: `Cell`, `SnakeId`, `BotId`, `DirectionSet`.
- `rows`/`cols`, `row`/`col`. Not `numRows`, not `getRowCount`.
- One public top-level declaration per file, named after it. Sealed hierarchies may share a file.
- **Bot ids are stable lowercase slugs and are frozen once released** — they are embedded in the replay
  format. Renaming one breaks every existing replay URL.
- Fix legacy misspellings on sight; do not carry them forward: `deleget` → the concept is deleted;
  `obsticles` → `obstacles`; `untill`, `seriese`, `demilited` → corrected; `utcSearch` → `uctSearch`
  (legacy consistently swaps UCT and UTC, including in class docs).
- `kotlin.code.style=official`, 4 spaces, 120 columns, ktlint in CI. Do **not** reproduce legacy's
  `//------` banner comments or column-aligned assignments — they do not survive automated refactoring.

### Hot-path rules

The engine is called millions of times per turn from inside MCTS. In `:core` and `:bots`:

- Primitive arrays (`IntArray`, `ByteArray`, `LongArray`) over `List<Int>`.
- A `value class` over `Int` unboxes in most positions but **boxes as a generic type argument or when
  nullable** — so `List<Cell>` allocates per element. No hot-path API returns a collection of `Cell`.
- No `Sequence`. No `data class` for hot-path types (generated `equals`/`hashCode`/`copy` add code size and
  invite allocation).
- Prefer mutate-and-undo over allocating a new state. The engine's canonical representation is a mutable
  arena with an undo journal; immutable `MatchState` snapshots are derived at most once per turn.

## Commands

```bash
./gradlew build                              # both targets, JVM tests, checkModulePurity
./gradlew jvmTest                            # fast inner loop, with breakpoints
./gradlew allTests -PbrowserTests=true       # browser suite (needs Chrome; off by default)
./gradlew :app:wasmJsBrowserDevelopmentRun   # local dev server
./gradlew :app:wasmJsBrowserDistribution     # production bundle -> app/build/dist/wasmJs/productionExecutable
```

Browser tests are disabled unless `-PbrowserTests=true`, because Karma startup dominates the runtime
of small suites. Anything provable on the JVM should be proven there instead.

Prefer `jvmTest` while developing. The wasm toolchain is slow to warm up; a full cold `build` takes
several minutes, while `jvmTest` is seconds.

## Adding a bot

```kotlin
class MyBot(setup: BotSetup) : Bot {
    private val rng = setup.rng

    override fun chooseMove(turn: Turn): Decision =
        Decision.Move(rng.pick(turn.legalMoves) ?: Direction.NORTH)
}

// bots/ShippedBots.kt
register("my-bot", "My Bot", ::MyBot)
```

A bot instance is created once per slot per match, so instance fields persist across turns — that is how
MCTS keeps its tree with no extra API. Get randomness from `setup.rng`, never `Random.Default`. Poll
`turn.budget` in any search loop.

Every registry entry is run against the shared contract suite in CI (`bots/src/commonTest/.../BotContractTest`):
never returns an illegal move when a legal one exists, survives a budget of zero, is deterministic given
an identical seed, retains no cross-match state, does not claim to be interactive, and terminates on every
board shape. That suite is what makes "fork → add a bot → PR" safe to accept.

For a rollout, take `turn.scratch.playout()` and spin on `outcome`:

```kotlin
val p = turn.scratch.playout()
while (p.outcome == null) p.advance(policy.pick(p.board.legalMoves(p.toAct)) ?: Direction.NORTH)
```

`advance` charges the budget itself, and an exhausted budget makes `outcome` a draw — so the loop
condition *is* the budget check and the search terminates structurally rather than on trust.

Adding a bot needs **no HTML change**: the pickers in the sidebar are filled from `BotRegistry.entries`
at startup. That is the one place `:ui` builds DOM, and it is why.

## Working on the UI

`:ui` exposes exactly two things — `GameSession` and `ReplayLink`. Everything else is `internal`, and
should stay that way; `:app` builds a session and is otherwise sixty lines of wiring.

Inside, it is a one-way data flow with no virtual DOM. State goes down through `Chrome.render(model)`,
everything a person does comes back up as a `UiIntent` into `GameSession.dispatch`, and the board is
painted separately per turn because painting two rectangles is nearly free while writing text is not.
Keep those two cadences apart: `UiModel` is built once per *frame*, not once per turn.

**Playing and replaying are one code path.** A replay is a match whose slots already know what they
are going to do, so play, pause, step, restart and the scoreboard work on both without a branch. Only
seeking is replay-specific, and it is implemented by rebuilding the playback match and stepping to the
target — microseconds, and nothing to keep consistent.

What *does* branch is which clock runs, and it branches on `Match.interactive` rather than on a mode
flag: `TurnScheduler` paces bots and replays, while a match with a live player is stepped by
`GameSession.playRound` straight out of the keydown.

The static skeleton lives in `app/.../index.html`. Kotlin looks elements up by id once and then only
writes text, values and `hidden`; do not start constructing structure there.

## Working with the legacy Java

Treat it as a **specification to read, not code to translate**. It has two competing board representations
and the wrong performance shape; `:core` is a from-scratch rewrite. Port algorithms semantically
(`AStar`, `WallHugAi`, `ForkAi`, `ForkPathAi`, the UCB1 formula, `PvpAi`'s nearest-opponent reduction,
`BoardOccupancy.mostDistant` spawn placement) and delete the scaffolding.

Known legacy bugs — **do not faithfully reproduce these**:

- `RelLocation.directionTo` is dead-broken: `closestDist = Double.MIN_VALUE` (smallest *positive* double)
  compared with `dist < closestDist`, so it always returns `FOREWARD`. The class is unreferenced; drop it.
- `MoveTracker.retrieveOrCreateSpecifier` seeds a bot's first move with *the first available direction*, so
  a bot that never sets one plays a move it never chose — and then repeats it forever.
- `SnakesRunner.setupGame` wraps `PlayerAvatar` and then `SnakesGame2.addPlayer` wraps it again, burning two
  colour-pool slots and two indices per player.
- `Node`'s static 2-thread `ExecutorService` is entirely dead — its only caller is commented out.
- `NestedSwingInput.queue` is a plain `ArrayList` written from the Swing EDT and read from the game thread
  with no synchronization.
- The 13 `assert` statements are inert (`-ea` is not set), so `Reward`'s `[0,1]` invariant was never enforced.

The single external dependency, `ao.util:util-lang:2.0.0`, is served from a `raw.githubusercontent.com`
Maven repo and drags in log4j 1.2.14. Only `Rand` was ever used. Drop it entirely.

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

### Browser gotchas already hit — don't rediscover these

- **Reveal `#app` before the first paint.** It starts `display: none`, and a hidden element reports
  `clientWidth == 0`, so measuring the board container first sizes every board to the minimum cell
  size. `document.body.classList.add("booted")` must stay ahead of `session.start()` in `Main.kt`.
- **The board container's width must not depend on the canvas.** The canvas sizes its backing store
  from the container width; with `flex: 1 1 auto` that was circular and the board came out a
  different size on each load. `.arena` is a CSS grid with `minmax(0, 1fr)` so the track width is
  definite. Don't switch it back to flexbox.
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
