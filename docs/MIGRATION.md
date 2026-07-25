# Migration: Java/Swing desktop → Kotlin/Wasm web app

This is the design document for the rewrite. It is the reasoning behind the module graph, the
forbidden dependency edges, and the engine's data representation — the things that are expensive to
change later and cheap to get right now.

**Phase tracker**

| Phase | Status | Summary |
|---|---|---|
| 0 | **done** | Gradle scaffold, Java moved to `legacy/`, empty page deploys |
| 1 | not started | Core rules, rewritten from scratch |
| 2 | not started | Driver, replay codec, two trivial bots |
| 3 | not started | UI — first playable milestone |
| 4 | not started | Search bots (A*, flood fill, MCTS) |
| 5 | not started | Contributed bots |
| 6 | not started | Stats, batch tournaments, delete `legacy/` |

---

## Context

`snakewarz` is a Tron-style snakes game built as an **AI testbed** (inception 2005, imported from
the Google Code archive, last touched 2019 to "make it work with Java 13"). It was ~4,900 lines of
Java across 57 files — realistically ~3,500 lines of live logic once commented-out experiments are
stripped. It ran only as a Swing desktop app launched from an IDE, had **zero tests**, and depended
on `ao.util:util-lang:2.0.0` served from a `raw.githubusercontent.com` Maven repo (transitively
dragging in log4j 1.2.14) for one thing: a global RNG.

The goal is a **web app hosted on GitHub Pages**, rewritten in Kotlin, with an architecture clean
enough to grow to ~150k LOC. The existing AI plug-in SPI is the best part of the codebase and the
reason the project exists; the rewrite keeps that extensibility while making matches deterministic
and replayable.

Intended outcome: a static site where you watch bots fight, play against them, and share a replay
as a URL — plus an engine fast and clean enough to keep developing search bots against for another
twenty years.

## Decisions

| Area | Decision |
|---|---|
| Language / build | Kotlin 2.4.10, Gradle KTS, version catalog, `build-logic` convention plugins |
| Ships | `wasmJs` browser target only, static files on GitHub Pages |
| Also compiles | `jvm()` on the four platform-free modules, **tests only — never deployed** |
| Rendering | Kotlin/Wasm → HTML `<canvas>` 2D; hand-written HTML/CSS chrome. No Compose |
| Bots | Kotlin classes compiled in, explicit `BotRegistry`. Fork → add file → register → PR |
| Release 1 | Live match view (play/pause/step/speed), human vs bot, seeded matches, URL replays, per-match stats |
| Old Java | Tagged `legacy-java-final`, moved to `legacy/java/`, deleted at release 1 |

---

## Three findings that shaped the design

**1. Snakes grow at half speed — this is a real rule, and a naive rewrite gets it wrong.**
`SnakeImpl.advance` returns `new SnakeImpl(history, !willGrow())`, starting from `willGrow = false`.
Traced by hand: body lengths go **1, 1, 2, 2, 3, 3, 4…** The tail only retracts on alternating
turns. Encoded as `RulesConfig.growEveryNthMove = 2` (`1` = classic Tron), locked with a golden
test. Reference: `legacy/java/ao/sw/engine/v2/SnakeImpl.java:47-68`.

**2. Bots run the engine synchronously, inside their own turn — so `Bot` must NOT be `suspend`.**
`BiState.rollout()` (`legacy/java/ao/ai/sample/monte_carlo/BiState.java:70`) constructs an entire
`SimpleSnakesGame` per rollout and runs it to completion using `RandomAi` as the policy. A
suspending bot cannot serve as a rollout policy inside another bot's search without `runBlocking`,
which does not exist in wasm — and it would allocate a continuation per call across millions of
rollout steps. `chooseMove` stays **synchronous**; the human returns `Decision.Pending` and the
driver polls. Bonus: bot code then physically cannot reach `delay()` or a clock, so determinism
holds by construction rather than by discipline.

**3. Replays record the move stream, not just the seed.**
Seed-replay has two independent leaks: bots evolve (one tuned constant invalidates every old
replay), and `log`/`exp` are **not** specified bit-identical across platforms while `+ - * / sqrt`
are. UCB1 is `sqrt(log(v)/(5*cv))`, so MCTS bots are a genuine cross-target divergence hazard.
Recording moves sidesteps both. The seed is kept as provenance and as a CI verification input.

### Legacy bugs — do not faithfully reproduce

- `RelLocation.directionTo` is dead-broken: `closestDist = Double.MIN_VALUE` (the smallest
  *positive* double) compared with `dist < closestDist`, so it always returns `FOREWARD`. Unreferenced; dropped.
- `MoveTracker.retrieveOrCreateSpecifier` seeds a bot's first move with *the first available
  direction*, so a bot that never sets one plays a move it never chose — then repeats it forever.
- `SnakesRunner.setupGame` wraps `PlayerAvatar` and `SnakesGame2.addPlayer` wraps it again, burning
  two colour-pool slots and two indices per player.
- `Node`'s static 2-thread `ExecutorService` is entirely dead — its only caller is commented out.
- `NestedSwingInput.queue` is a plain `ArrayList` written from the Swing EDT and read from the game
  thread with no synchronization.
- The 13 `assert` statements are inert (`-ea` unset), so `Reward`'s `[0,1]` invariant was never enforced.

---

## Module graph

Multi-module Gradle, not package layering in one module. The compiler is the only enforcement that
cannot be bypassed, and at 150k LOC "the engine physically cannot reference the DOM" should be a
build fact. The KMP-config-per-module friction is solved once in a convention plugin.

| Module | Responsibility | May depend on | Targets |
|---|---|---|---|
| `:core` | Grid, occupancy, bodies, rules, transition, terminal detection, PRNG, budget | **stdlib only** | wasmJs + jvm |
| `:bot-api` | The contract bot authors read. Small, stable | `:core` | wasmJs + jvm |
| `:bots` | Shipped bots + `BotRegistry` impl | `:core`, `:bot-api` | wasmJs + jvm |
| `:match` | Turn sequencing, slot wiring, human input, replay codec, stats. No time, no DOM | `:core`, `:bot-api` | wasmJs + jvm |
| `:ui` | Canvas renderer, DOM chrome, rAF scheduler | `:core`, `:match`, `kotlinx-browser` | wasmJs |
| `:app` | `main()`, wiring, URL hash routing | all | wasmJs |

### Forbidden edges

- `:core` → *any* project dependency, ever. Notably **not** `:bot-api` — the engine does not know bots exist.
- `:core`, `:bot-api`, `:bots`, `:match` → `kotlinx-browser`, `org.w3c.*`, any wasm-only API.
- `:match` → `:bots`. The driver resolves bots through the `BotRegistry` *interface*; `:app` injects
  the implementation. This is what keeps the replay codec free of bot classes.
- `:bots` → `:match`, `:ui`, `:app`. A bot cannot see the driver, so it cannot reach the clock or
  another slot's RNG.
- `:ui` → `:bots`.

Enforced by `snakewarz.pure` (both targets, `explicitApi()`, plus a `check`-wired
`checkModulePurity` task that walks the resolved dependency graph and fails on a browser artifact or
a browser-only project) and `snakewarz.browser` (wasmJs only), plus aggressive `internal`.

---

## Core engine API

### Padded grid + linear-index value classes

Allocate `(rows + 2) × (cols + 2)` and permanently mark the border ring as wall. Neighbour-stepping
becomes pure integer addition with **no bounds check** — off-board is indistinguishable from
occupied. This collapses legacy's `withinBounds` + `isAvailable` double dispatch into one array
read, on the hottest path in the program.

Implemented in Phase 0: `Cell`, `Direction`, `DirectionSet`, `Grid`.

**Wasm allocation note that shapes every API:** a `value class` over `Int` unboxes in most positions
but **boxes as a generic type argument or when nullable** — so `List<Cell>` allocates per element.
Bodies live in `IntArray`, neighbour sets are `DirectionSet`, and no hot-path API returns a
collection of cells. `Cell.NONE` exists so `Cell?` is never needed.

### Occupancy: `ByteArray` of owner ids, incrementally updated, Zobrist-hashed

This fixes the worst performance bug in the codebase. `SimpleSnakesGame.commonBoard()` (`:179`)
allocates a fresh `BitSetMatrix` and ORs three matrices **on every `askForMove`** — inside every
rollout step of every MCTS iteration. `SnakeHistory.board()` does the same per query, several times
per turn.

`ByteArray` of owner ids beats a bitset: one read gives both "is it free" and "whose is it", which
rendering and flood-fill bots both need, with no shift/mask.

```kotlin
class Occupancy(val grid: Grid) {
    private val owner = ByteArray(grid.cellCount)   // 0 empty, 1..n snake, -1 wall
    var hash: Long = 0L; private set                // Zobrist, O(1) update
    fun isFree(c: Cell): Boolean
    fun ownerOf(c: Cell): Int
    fun occupy(c: Cell, by: SnakeId); fun vacate(c: Cell)
    fun freeNeighbors(c: Cell): DirectionSet
    fun copyFrom(other: Occupancy)                  // reuse allocation
}
```

Zobrist keys derive from a **fixed compile-time constant**, not the match seed — the hash is never
persisted, and MCTS tree reuse needs it stable within a process. This replaces `BiState.equals()`'s
full `BitSet` comparison with a `Long` compare.

The invariant to property-test, the guard on this whole optimization: *incremental occupancy always
equals occupancy rebuilt from all bodies.*

### Mutable canonical state + immutable snapshots — both, deliberately

Fully persistent state is right for the driver, replay and rendering, and catastrophic inside
rollouts doing millions of steps. Legacy chose persistent everywhere, which is exactly why `UctAi`
is slow. So: **the canonical representation is a mutable arena with an undo journal; `MatchState` is
an immutable snapshot derived from it.** Rules logic exists once, on the mutable board. The driver
snapshots at most once per turn — O(snakes), negligible.

```kotlin
class Board : BoardView {
    fun apply(id: SnakeId, d: Direction): MoveOutcome   // mutates, pushes undo record
    fun undo()
    fun copyFrom(other: Board)
    fun snapshot(): MatchState
}

interface BoardView {
    val grid: Grid; val rules: RulesConfig
    val toAct: SnakeId; val turnIndex: Int; val aliveCount: Int
    val outcome: MatchOutcome?
    fun isFree(c: Cell): Boolean
    fun ownerOf(c: Cell): SnakeId?
    fun legalMoves(id: SnakeId): DirectionSet
    fun snake(id: SnakeId): SnakeView
    fun snakeCount(): Int; fun snakeIdAt(i: Int): SnakeId
}

interface SnakeView {
    val id: SnakeId; val alive: Boolean
    val length: Int; val head: Cell; val tail: Cell
    val lastDirection: Direction?; val movesMade: Int
    fun cellAt(i: Int): Cell                 // 0 == tail
    fun growsOnNextMove(): Boolean
}
```

Bodies are a **ring buffer over `IntArray`** with push/pop at both ends (both needed for undo). The
undo record fits in one `Long` — vacated tail cell or grow sentinel, previous direction ordinal,
liveness bits — stored in a `LongArray` stack, so search nodes cost zero allocation.

### Rules and outcome

```kotlin
data class RulesConfig(
    val growEveryNthMove: Int = 2,   // legacy half-speed growth; 1 = classic Tron
    val maxTurns: Int = 4096,        // guarantees termination; browsers cannot hang
)

enum class EliminationReason { TRAPPED, SUICIDE, RESIGNED, FORFEIT }
```

`apply` into a non-free cell eliminates the snake — `TRAPPED` if `legalMoves` was empty (no choice
existed), `SUICIDE` otherwise. This preserves legacy semantics while replacing `GameResult`'s single
confusing `isSuicide` boolean with a per-snake reason.

**Two deliberate rule changes:** `maxTurns` is new (legacy has no cap; required for browser safety
and bounded rollouts). And the last survivor now **wins immediately** even if trapped — legacy asks
the survivor for one more move and returns a `null` winner if it is also trapped
(`SnakesGame2.java:96-101`), which requires an awkward bot call after the game is over.

**Do not add a "never reverse" rule.** Reversal is illegal only because your own neck occupies that
square; at body length 1 there is no neck, so a first-move reversal is legal. Keep the emergent
behaviour, test it.

---

## Bot contract

Replaces `void makeMove(board, you, MoveSpecifier, others)` and the entire three-implementation
`MoveSpecifier` family.

```kotlin
interface Bot {
    fun chooseMove(turn: Turn): Decision
    fun onEliminated() {}
}

sealed interface Decision {
    data class Move(val direction: Direction) : Decision
    data object Resign : Decision
    data object Pending : Decision        // only legal for interactive slots
}

class Turn(
    val board: BoardView,
    val self: SnakeId,
    val legalMoves: DirectionSet,
    val budget: Budget,
    val scratch: Scratch,
)

class BotSetup(
    val self: SnakeId, val grid: Grid, val rules: RulesConfig,
    val opponents: IntArray,
    val rng: Rng,                          // per-slot, forked from the match seed
    val params: BotParams,
)

fun interface BotFactory { fun create(setup: BotSetup): Bot }
```

- **Per-turn state** persists in instance fields — a `Bot` is created once per slot per match, so
  MCTS keeps its tree with no extra API. `board.hash` makes tree reuse a `Long` compare instead of
  `childWithBiState`'s full board equality.
- **No silent moves.** `Resign` → `Eliminated(RESIGNED)`; `Pending` from a non-interactive slot →
  `FORFEIT`; a thrown exception → `FORFEIT` (driver catches); a `Move` into an occupied cell →
  `SUICIDE`/`TRAPPED`. `Decision` is non-nullable, so "not deciding" is unrepresentable.

### Budget made structural, not advisory

```kotlin
interface Scratch { fun playout(): Playout }        // pooled; no allocation per rollout

interface Playout {
    val board: BoardView; val toAct: SnakeId; val outcome: MatchOutcome?
    fun advance(d: Direction): MoveOutcome          // toAct moves, then rotates
    fun apply(id: SnakeId, d: Direction): MoveOutcome
    fun undo(); fun reset()
}

class Budget(val limit: Int) {
    fun tryConsume(units: Int = 1): Boolean
    val exhausted: Boolean
}
```

A rollout is `while (p.outcome == null) p.advance(policy.pick(p))`, with `Playout.advance` consuming
budget internally. On exhaustion `outcome` returns a sentinel draw, so **search terminates
structurally** — for the 99% of bots dominated by simulation cost, budget enforcement is automatic
rather than trusted.

*Honest limitation:* single-threaded wasm cannot preempt a bot spinning in a pure loop. Mitigation is
audit-after-return plus the contract suite in CI. True isolation needs a Web Worker.

### RNG

Write a ~20-line `SplitMix64`. Do **not** use `kotlin.random.Random(seed)` — a persisted URL replay
format must not depend on stdlib algorithm stability across Kotlin versions and targets. Specify
`nextInt(bound)` precisely (modulo-with-rejection) and lock it with known-answer vectors. Fork per
slot (`matchRng.fork(slotIndex)`) so one bot's consumption never shifts another's stream.

### The human uses the same interface

```kotlin
class InputBuffer {
    fun push(d: Direction)                          // from DOM keydown
    fun take(legal: DirectionSet): Direction?       // drops queued illegal inputs
}

enum class StallPolicy { WAIT_FOR_INPUT, CONTINUE_STRAIGHT }

class InteractiveBot(buffer: InputBuffer, policy: StallPolicy) : Bot
```

Two UX choices baked in: `take` **filters illegal inputs**, so humans die by being trapped rather
than by a mistimed keypress into their own neck; and `CONTINUE_STRAIGHT` is the default for the live
view, because a match that visibly stalls reads as broken. Under replay the interactive slot is
substituted by a `ScriptedBot`, so `Pending` never appears on a deterministic path.

Also port `PvpAi`'s nearest-opponent reduction as an abstract `DuelBot` base class — every MCTS bot
depends on it — while keeping `Bot` itself N-player-capable.

---

## Match driver and pacing

`:match` is time-free and exposes a function that advances **at most one turn** and makes **at most
one bot call**.

```kotlin
class Match {
    val view: BoardView; val turnIndex: Int; val outcome: MatchOutcome?
    fun step(): StepResult
    fun events(): TurnEvents          // dirty cells; valid until next step()
    fun record(): MatchRecord
}

sealed interface StepResult {
    data class Advanced(val id: SnakeId, val direction: Direction) : StepResult
    data class Eliminated(val id: SnakeId, val reason: EliminationReason) : StepResult
    data object AwaitingInput : StepResult
    data class Finished(val outcome: MatchOutcome) : StepResult
}
```

This deletes legacy's side-effecting for-condition, its `players.set(i, null)` tombstones and its
`nextPlayer = -1` sentinel (`SnakesGame2.java:69-111`). Turn order is an explicit `IntArray`
permutation derived from the seed at setup and recorded in the replay; dead slots are skipped by a
liveness check, not by nulling.

**Pacing lives in `:ui`, on `requestAnimationFrame` with an accumulator — not coroutine `delay`.**
rAF is vsync-aligned so painting never tears against stepping, and it **automatically stops in
hidden tabs**; `delay()` in a background tab is throttled to ~1s then releases a burst of turns,
producing visible jank on return.

Per frame: `accumulator += dt * turnsPerSecond`, then step while `accumulator >= 1`, breaking on
`AwaitingInput`, `Finished`, a turns-per-frame cap, and a **wall-time guard**
(`if (now() - frameStart > 8.0) break`). The guard degrades effective turn rate under heavy bots
instead of freezing the page, and it cannot affect outcomes because `step()` results do not depend
on how many steps happened in a frame. On `AwaitingInput`, do not decrement the accumulator and
clamp it to ≤ 1, so a human who thinks for five seconds does not get five seconds of turns fired at
them on the next keypress.

---

## Replay

```kotlin
class MatchRecord(
    val setup: MatchSetup,                 // formatVersion, seed, rows/cols, rules, slots, turnOrder
    val moves: DirectionStream,            // 2 bits per turn
    val terminals: List<TerminalEvent>,    // at most slots-1 entries
    val outcome: MatchOutcome?,
)
```

**2 bits per turn** plus a small side-table for terminal events. The obvious alternative — a 3-bit
alphabet reserving codes for resign/forfeit — costs 50% more for events occurring at most
`slots - 1` times per match. A **suicide needs no symbol at all**: it is a recorded direction that
happens to be illegal on replay, so it is self-describing. Only `RESIGNED` and `FORFEIT` need the
table.

Header: version byte, flags, `rows-1`, `cols-1`, LE64 seed, rules varints, then per slot a
length-prefixed **stable string slug** (`"uct"`, `"wallhug"`). Slugs, not registry indices — indices
break the instant the registry is reordered, and a shipped slug must never change.

An 800-turn match is 200 bytes of moves ≈ 264 base64url chars, under 400 with header. Use
`kotlin.io.encoding.Base64.UrlSafe` from the common stdlib, not `btoa`, so the codec is JVM-testable.
Transport is `#r=<payload>` via `history.replaceState` — hash-only, because GitHub Pages has no
server-side routing and a hash change causes no reload.

Playback substitutes `ScriptedBot` for every slot, so it costs no search. Seeking to turn N replays
moves into a scratch board (microseconds for a thousand turns) then triggers a full repaint. And
`MatchRecord.verify(registry)` re-runs the *real* bots from the seed and asserts the move stream
matches — a free, very strong regression test that also detects accidental non-determinism.

---

## Rendering

The renderer reads `BoardView` and `TurnEvents`, both engine-shaped, neither containing a pixel or
colour. Colours live in a `Palette` in `:ui`, keyed by slot index. This dissolves legacy's worst
coupling: `PlayerAvatar` fused player identity + AI delegate + `java.awt.Image`, and was the key
type of `GameState`'s map — so the game state transitively dragged in AWT. It becomes three separate
things in three modules: `SnakeId` in `:core`, `Bot` in `:bot-api`, `Palette` in `:ui`.

**Dirty-cell rendering.** A normal turn paints one or two cells; full repaint only on resize, seek
and match start. Gridlines go on a separate underlay canvas painted once. Use integer cell sizes to
avoid seams, and be `devicePixelRatio` aware.

Deliberately **no** per-frame immutable `Scene` value type — producing one per frame allocates for
no benefit, since `BoardView` is already a read-only projection with no drawing concepts.

**DOM chrome without a framework** — a ~200 LOC one-way data flow, no vdom. State flows down through
`render(model)`, events flow up as sealed `UiIntent`s into one `dispatch`. That is a minimal MVI and
it scales. The static skeleton lives in `index.html` so first paint happens before wasm finishes
compiling; Kotlin queries elements by id once at startup and never constructs structural DOM.

Two layout traps already hit and fixed in Phase 0, worth remembering:

- The canvas sizes its backing store from its container's width, so **the container's width must not
  depend on the canvas**. With `flex: 1 1 auto` it did, and the board came out a different size on
  each load. `.arena` is a CSS grid with `minmax(0, 1fr)` so the track width is definite.
- `#app` starts `display: none` until Kotlin adds `booted`. A hidden element reports
  `clientWidth == 0`, so **reveal before the first paint**, or every board renders at the minimum
  cell size.

---

## Testing

The four pure modules get `jvm()` for tests only. Tests run in milliseconds with breakpoints and
coverage instead of seconds in headless Chrome — and compiling `commonMain` for JVM is itself a
feature, mechanically catching accidental wasm-only API use in code that is supposed to be
platform-free. A second compiler enforcing the module layering.

Divergence risk is real and bounded: `+ - * / sqrt` on `Double` are IEEE-754-exact on both targets;
`log`/`exp` are not guaranteed identical, and `Long` performance differs. So a **conformance suite**
runs on both targets and is the *only* thing that runs in a browser in CI.

1. **Rules units** (JVM): legality; the **growth cadence golden `1,1,2,2,3,3,4`**; `TRAPPED` vs
   `SUICIDE`; first-move reversal legal, later reversal illegal; `TURN_LIMIT` draw.
2. **Property tests:** `undo(apply(s, m))` restores the board bit-for-bit and by Zobrist hash;
   **incremental occupancy == occupancy rebuilt from all bodies**.
3. **Codec round-trip** + fuzz: `decode(encode(r)) == r`.
4. **Golden move-stream hashes** per bot: `(seed, 20×20, bot vs RandomBot) → hash`. Catches the
   classic failure — iteration order over a `HashMap`. Legacy `GameStateImpl` iterated a `HashMap`
   and was only *accidentally* stable because `PlayerAvatar.hashCode()` returned a monotonic index.
   **Ban `HashMap`/`HashSet` iteration in `:core` and `:bots`.**
5. **Replay verification:** `record.verify(registry)` for each golden record.
6. **Bot contract suite:** a shared `botContract(factory)` run against *every* registry entry —
   never returns an illegal move when a legal one exists, respects the budget, deterministic given an
   identical seed, retains no cross-match state. This is the CI gate that makes "fork → add a bot →
   PR" safe to accept.
7. **Browser conformance (CI, one job):** conformance suite on `wasmJs`, plus one end-to-end check
   that boots the page, loads a replay from the hash, steps 100 turns and asserts final state.
8. **Benchmarks:** rollouts/sec on both targets, to quantify the wasm gap with numbers.

---

## Phases

**Phase 0 — scaffold and deploy an empty page. DONE.** Tagged `legacy-java-final`, moved
`src/main/java` → `legacy/java/`, deleted `pom.xml`. Gradle KTS + `build-logic` + version catalog,
`:core`/`:app`, `index.html` with a canvas drawing an empty grid, GitHub Actions with a gzipped
bundle budget, `.nojekyll`. Deployed *first*, so Pages, wasm MIME and bundle-size surprises land on
day one rather than at the finish line.

Measured results: 15 JVM tests green; production bundle **18.5 KiB gzipped** (9.7 KiB excluding the
source map) against a 1.5 MiB ceiling; `.wasm` served as `application/wasm`; boots clean in Chrome
with no console output; the unsupported-browser panel verified by serving a deliberately broken dist.

**Phase 1 — core rules, rewritten from scratch.** Occupancy, SnakeBody, Board, Rules, MatchState,
outcome. Full JVM suite including the growth golden. Explicitly a rewrite, not a port: legacy has two
competing board representations and the wrong performance shape.

**Phase 2 — driver, replay codec, two trivial bots.** `RandomBot`, `WallHugBot` as semantic ports.
Headless matches in JVM tests, replay round-trip, `verify()`. Still no UI — at the end of this phase
you can run thousands of matches per second on the JVM.

**Phase 3 — UI. First playable milestone.** Canvas renderer with dirty-cell painting, rAF scheduler,
play/pause/step/speed, scoreboard, hash replay load/share, `InteractiveBot`.

**Phase 4 — search bots.** `AStar`/`Path` semantic ports. `ForkAi`/`ForkPathAi` with flood-fill
rewritten onto the padded array. Then MCTS: `Reward` **deleted** (a `double` wrapper that allocates
per rollout); `Rollout` folded into return values; `BiState` **rewritten** onto `Playout`; `Node`
**rewritten** (454 lines, roughly half commented-out experiments) keeping only the live path — UCB1
with the `sqrt(log(v)/(5*cv))` variant at `Node.java:423`, the `10000 + 1000 * Rand.nextDouble()`
first-visit tiebreak at `:398`, variance ceiling behind a flag. `UctAi` → `UctBot(iterations)`.
**`AdaptiveUct` dropped** — thread-based, and its value is subsumed by a larger `UctBot` budget.

**Phase 5 — contributed bots.** `Burninhell`, `OtherSnake`, `TomSnakeAi`, each gated by the contract
suite, attribution preserved in KDoc. Lowest priority, droppable.

**Phase 6 — polish.** Stats panel, and **batch tournament mode** (K seeded matches → win-rate
matrix). Nearly free once `:match` runs headless and fast, and it is the actual point of an AI
testbed. Delete `legacy/` here.

**Deleted outright, no port:** `SnakesRunner`, `SnakesContest`, `SimpleSnakesGame`, `SnakesGame2`,
`SnakeHistory`, `GameGraphics*`, `SnakesGameDisplay`, `MoveTracker`, the whole `MoveSpecifier` family
(all three collapse into `Decision`), `PlayerAvatar`/`PlayerWrapper`/`PlayerDisplay`/
`BasicPlayerDisplay`, both Swing inputs, `BoardArrangement`/`Matrix`/`BitSetMatrix`/
`MatrixBoardArrangement`, `BoardLocation`, `Action`, `RelLocation`, `WeightedMoveSpecifier`.

**Semantic ports (algorithm preserved, API and performance reshaped):** `AStar`/`Path`, `WallHugAi`,
`RandomAi`, `ForkAi`, `ForkPathAi`, `MonteCarloAi`, the UCB1 formula, `PvpAi`'s reduction, and
`BoardOccupancy.mostDistant` (spawn placement — keep, but make it explicitly deterministic).

---

## Risks

**Kotlin/Wasm is Beta; WasmGC needs Chrome 119+, Firefox 120+, Safari 18.2+.** Safari 18.2 shipped
Dec 2024, so coverage is high but devices pinned to iOS 17 will fail. Mitigations: the
unsupported-browser panel (built in Phase 0, and verified — it detects boot *failure* rather than
probing wasm features, which cannot produce a false negative); and **keep the layering so adding a
Kotlin/JS (`js()`) fallback is a build-config change, not a rewrite** — free if the four pure modules
stay platform-free, and a strong independent reason to hold the module discipline. Do not build the
fallback for release 1; just don't preclude it. Pin the Kotlin version and treat upgrades as a
deliberate tested step — Beta means codegen bugs are plausible, and the golden-hash tests are the
canary.

**Bundle size.** Wasm has a larger floor than JS for trivial apps and wins for compute-heavy ones;
this app is compute-heavy, so wasm is right. Avoid reflection and `toString()`-heavy paths (they drag
in number formatting), keep the loading indicator in `index.html`, and the CI job fails if the
**gzipped** dist exceeds the budget — GitHub Pages serves gzip, so measure gzipped, not brotli'd.

**MCTS performance, wasm vs JVM.** Expect the same order of magnitude but slower — commonly 1.5–3×
for allocation-light integer/float code, materially worse if you allocate, since WasmGC allocation is
young relative to HotSpot's TLAB bump allocator. In descending impact: (a) the mutable arena with
undo instead of a fresh persistent board per node — likely a 10×+ win over the legacy design,
dwarfing the platform gap; (b) **store MCTS nodes in flat parallel `IntArray`/`DoubleArray` pools
indexed by node id**, not a `Node` object graph with `Array<Node?>` children — highest-leverage
single choice, do it from the first commit of `UctBot`; (c) hoist `log(visits)` out of the child loop
(legacy recomputes it per child at `Node.java:412,423`); (d) `DirectionSet` instead of an allocated
`List<Direction>`. Then measure and set default budgets from data so no match drops below ~30 fps.

**No threads. Web Workers explicitly deferred past release 1.** With countable budgets, worst-case
turn cost is bounded to a few ms and the frame-time guard degrades turn rate instead of freezing. A
worker now would cost a serialization boundary, a second wasm instantiation, and an async message
layer that would leak into `Bot` — destroying the synchronous property that finding #2 requires. The
right *first* use of a worker is batch tournament mode, which is naturally message-shaped: send a
`MatchConfig`, get a `MatchRecord`. Design for it now by keeping `:match` fully headless. `Bot` never
becomes async; the *match* becomes async at the worker boundary.

**Cross-target determinism drift** (`log`/`exp` not bit-identical): mitigated by recording move
streams, reducing it to a CI concern — `verify()` may need a same-target assertion, or a fixed-point
`log` for UCB1 if cross-target verification is wanted.

**Replay URL length** is bounded by `maxTurns`; if a match still exceeds comfortable hash length,
offer a downloadable record file instead of a link.
