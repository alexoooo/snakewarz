# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this project is

**snakewarz** — a Tron-style snakes game built as an **AI testbed**. Snakes move one cell per turn on a
rectangular grid; walls and every snake body (including your own) are lethal; the last one moving wins.
There is no food and no score. Inception 2005, imported from the Google Code archive.

The point of the project is the **pluggable AI**: writing search bots and pitting them against each other.
Everything else exists to serve that.

## Current state — read this first

Mid-rewrite. **Phase 1 of 6 is complete**: the Gradle/Kotlin scaffold, the deployment pipeline and
the whole rules engine exist and are verified. There is no driver, no bot and no game UI yet.

| Path | Status |
|---|---|
| `core/` | `:core` module. Padded-grid primitives plus the rules engine: `Occupancy`, `Board`, `MatchState`, `SplitMix64`, `Budget` |
| `app/` | `:app` module. Phase 0 sanity page — paints an empty grid, no game |
| `build-logic/` | Convention plugins `snakewarz.pure` and `snakewarz.browser`, incl. `checkModulePurity` |
| `legacy/java/ao/**` | The original Java, reference only. Not in the build. Deleted at release 1 |
| `docs/MIGRATION.md` | The design doc and phase plan. **Read this before changing architecture** |

`:bot-api`, `:bots`, `:match` and `:ui` do **not exist yet** — they arrive in Phases 2–3. Do not
assume anything below exists; check the tree.

The pre-rewrite tree is one command away: `git show legacy-java-final:<path>`.

**Phase tracker** — update this line as phases land, and mirror it in `docs/MIGRATION.md`:

> Current phase: **2 — not started** (driver, replay codec, `RandomBot` and `WallHugBot`)

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
- `:ui` → `:bots`.

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
class MyBot(private val setup: BotSetup) : Bot {
    override fun chooseMove(turn: Turn): Decision =
        turn.rng.pick(turn.legalMoves)?.let { Decision.Move(it) } ?: Decision.Resign
}

// bots/BotRegistry.kt
register("my-bot", ::MyBot)
```

A bot instance is created once per slot per match, so instance fields persist across turns — that is how
MCTS keeps its tree with no extra API. Get randomness from `setup.rng`, never `Random.Default`. Poll
`turn.budget` in any search loop.

Every registry entry is run against the shared `botContract` suite in CI: never returns an illegal move
when a legal one exists, respects its budget, is deterministic given an identical seed, retains no
cross-match state. That suite is what makes "fork → add a bot → PR" safe to accept.

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
  size. `document.body.classList.add("booted")` must stay ahead of `render()` in `Main.kt`.
- **The board container's width must not depend on the canvas.** The canvas sizes its backing store
  from the container width; with `flex: 1 1 auto` that was circular and the board came out a
  different size on each load. `.arena` is a CSS grid with `minmax(0, 1fr)` so the track width is
  definite. Don't switch it back to flexbox.
- **Canvas resizing resets the 2D context**, so apply `scale(dpr, dpr)` *after* setting
  `canvas.width`/`height`, never before.
- In `wasmJs`, `fillStyle`/`strokeStyle` take `JsAny?`, so a Kotlin `String` needs `.toJsString()`.
- A harmless configure-time warning — `Kotlin does not yet support 26 JDK target, falling back to
  Kotlin JVM_25` — comes from `:app`, which emits no JVM bytecode. `:core` correctly compiles to Java
  21 bytecode via `jvmToolchain`.
