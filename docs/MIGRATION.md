# Migration: Java/Swing desktop → Kotlin/Wasm web app

This is the design document for the rewrite. It is the reasoning behind the module graph, the
forbidden dependency edges, and the engine's data representation — the things that are expensive to
change later and cheap to get right now.

**Phase tracker**

| Phase | Status | Summary |
|---|---|---|
| 0 | **done** | Gradle scaffold, Java moved to `legacy/`, empty page deploys |
| 1 | **done** | Core rules, rewritten from scratch |
| 2 | **done** | Driver, replay codec, two trivial bots |
| 3 | **done** | UI — first playable milestone |
| 4 | **done** | Search bots — space, pressure, chase, flat Monte Carlo, UCT |
| 5 | **done** | Contributed bots |
| 6 | **done** | Stats, batch tournaments, measured budget, `legacy/` deleted |

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
| Old Java | Tagged `legacy-java-final`, moved to `legacy/java/`, **deleted in Phase 6** |

Every `legacy/java/…` path quoted below was live when it was written and is now reachable only at the
tag, where the tree still has its Maven shape: `git show legacy-java-final:src/main/java/ao/…`.

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

Enforced by `snakewarz.pure` (both targets, `explicitApi()`) and `snakewarz.browser` (wasmJs only),
plus aggressive `internal`. Both wire a `checkModulePurity` task into `check` that walks the resolved
dependency graph of every `*CompileClasspath` — test source sets included — and fails on a forbidden
edge. The walk itself is shared as `registerModulePurityCheck`, so `:ui → :bots` is enforced by the
same code that keeps a browser artifact out of `:core`. Negative-tested by adding the edge and
watching it fail.

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
    fun isFree(c: Cell): Boolean; fun isWall(c: Cell): Boolean
    fun ownerOf(c: Cell): SnakeId                   // SnakeId.NONE if empty or wall
    fun occupy(c: Cell, by: SnakeId); fun vacate(c: Cell)
    fun freeNeighbors(c: Cell): DirectionSet
    fun copyFrom(other: Occupancy)                  // reuse allocation
    fun clear()                                     // keeps the wall ring
}
```

Zobrist keys derive from a **fixed compile-time constant**, not the match seed — the hash is never
persisted, and MCTS tree reuse needs it stable within a process. This replaces `BiState.equals()`'s
full `BitSet` comparison with a `Long` compare. Shipped as a stateless `mix64(cell, owner)` rather
than a key table: same guarantee, no per-instance array to copy around or miss cache on.

`Occupancy.hash` covers the squares only. `BoardView.hash` xors in the heads, the growth phases, the
liveness bits and whose turn it is — all four of which distinguish genuinely different positions that
occupancy alone cannot tell apart.

The invariant to property-test, the guard on this whole optimization: *incremental occupancy always
equals occupancy rebuilt from all bodies.*

### Mutable canonical state + immutable snapshots — both, deliberately

Fully persistent state is right for the driver, replay and rendering, and catastrophic inside
rollouts doing millions of steps. Legacy chose persistent everywhere, which is exactly why `UctAi`
is slow. So: **the canonical representation is a mutable arena with an undo journal; `MatchState` is
an immutable snapshot derived from it.** Rules logic exists once, on the mutable board. The driver
snapshots at most once per turn — O(snakes), negligible.

```kotlin
class Board(grid, spawnCells: IntArray, rules: RulesConfig, turnOrder: IntArray) : BoardView {
    fun apply(id: SnakeId, d: Direction): MoveOutcome   // mutates, pushes undo record
    fun eliminate(id: SnakeId, reason: EliminationReason)  // RESIGNED / FORFEIT only
    fun undo(); val undoDepth: Int
    fun reset()
    fun copyFrom(other: Board)
    fun snapshot(): MatchState
}

interface BoardView {
    val grid: Grid; val rules: RulesConfig; val snakeCount: Int
    val toAct: SnakeId; val turnIndex: Int; val aliveCount: Int
    val outcome: MatchOutcome?; val hash: Long
    fun isFree(c: Cell): Boolean
    fun ownerOf(c: Cell): SnakeId            // SnakeId.NONE, never null — a nullable value class boxes
    fun legalMoves(id: SnakeId): DirectionSet
    fun snake(id: SnakeId): SnakeView
}

interface SnakeView {
    val id: SnakeId; val alive: Boolean; val eliminationReason: EliminationReason?
    val length: Int; val head: Cell; val tail: Cell
    val lastDirection: Direction?; val movesMade: Int
    val growsOnNextMove: Boolean
    fun cellAt(i: Int): Cell                 // 0 == tail
}
```

Snake ids are dense — exactly `0 until snakeCount` — so the sketched `snakeIdAt(i)` was redundant and
is not there. Turn order is a permutation `Board` holds, not a reordering of the ids, so `:match` can
randomise who acts first without disturbing the slot identities the replay format records.

Bodies are a **ring buffer over `IntArray`** with push/pop at both ends (both needed for undo). The
undo record fits in one `Long` — vacated tail cell or grow sentinel, previous direction ordinal,
elimination reason, the acting position in the turn order, and a bit for "the match ended here" —
stored in a `LongArray` stack, so search nodes cost zero allocation.

### Rules and outcome

```kotlin
data class RulesConfig(
    val growEveryNthMove: Int = 2,   // legacy half-speed growth; 1 = classic Tron
    val maxTurns: Int = 4096,        // guarantees termination; browsers cannot hang
)

enum class EliminationReason { TRAPPED, SUICIDE, RESIGNED, FORFEIT }
enum class MoveOutcome { MOVED, TRAPPED, SUICIDE }
enum class MatchEnd { LAST_SNAKE_STANDING, ALL_ELIMINATED, TURN_LIMIT }
data class MatchOutcome(val winner: SnakeId, val end: MatchEnd)  // winner NONE for a draw
```

`apply` into a non-free cell eliminates the snake — `TRAPPED` if `legalMoves` was empty (no choice
existed), `SUICIDE` otherwise. This preserves legacy semantics while replacing `GameResult`'s single
confusing `isSuicide` boolean with a per-snake reason.

**Legality is evaluated before the tail retracts**, so a snake cannot move into the square its own
tail is about to leave. This is the legacy rule — `SimpleSnakesGame` tested the destination against a
board built before the retraction — and the alternative is a different game, one in which a snake can
chase its own tail indefinitely.

**A dead snake's body stays on the board** as an obstacle, as in legacy. In a three-way match the
first casualty leaves a wall behind, which is most of what makes three-way matches interesting.

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
than by a mistimed keypress into their own neck; and the stall policy is what decides whether a live
match is arcade or turn-based. Under replay the interactive slot is substituted by a `ScriptedBot`,
so `Pending` never appears on a deterministic path.

*Landed in Phase 3, in `:match` rather than `:bot-api`* — the module table always gave `:match`
human input, and putting them there keeps them JVM-testable. Two refinements the sketch did not have.
`CONTINUE_STRAIGHT` **waits before the first move**, because there is no heading to continue and
inventing one is precisely the legacy `MoveTracker` bug. And `push` collapses a repeat of the
direction queued most recently, because a held arrow key fires `keydown` at the operating system's
auto-repeat rate and would otherwise fill the queue and eat the next several turns the player meant.

*Changed after Phase 3*: **`WAIT_FOR_INPUT` is the shipped default**, and a match with a person in
it is turn-based rather than real-time — see the note under Phase 3 below.

The seat is composed *outside* `ShippedBots`, by a `PlayableRegistry` that `:app` wraps around it.
That is forced, and usefully so: the bot contract suite requires that no registry entry claims to be
interactive, and the alternative was to weaken the gate that makes accepting a contributed bot safe.

Also port `PvpAi`'s nearest-opponent reduction as an abstract `DuelBot` base class — every MCTS bot
depends on it — while keeping `Bot` itself N-player-capable.

*Superseded in Phase 4, and the reasoning above is what dates it.* That sentence was written before
`Playout` existed, when the only model of a search state was legacy's two-snake `BiState`. `Playout`
sequences however many snakes are alive and rotates the turn order itself, so a duel reduction buys
an MCTS bot nothing and costs it a third snake's worth of accuracy — `UctBot` and
`FlatMonteCarloBot` search the real N-player game. The reduction ships as `internal fun
nearestOpponent` with exactly **one** consumer, `ChaseBot`; a base class would have had one subclass.
Reading the legacy again while porting confirms it: `ForkPathAi` never used the reduction either, as
it takes the *mean* distance over all opponents rather than picking one. What the drop does cost is
that credit assignment can no longer alternate — see the Phase 4 notes.

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
table, and a turn listed there carries no entry in the move stream, which is what keeps that stream
dense.

The `slots - 1` bound is right for a contested match and wrong for a **solo** one, where there is no
survivor to crown and the single snake can itself be the one that leaves. `MatchRecord.maxTerminals`
is the corrected form.

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
and match start. Gridlines are painted once. Use integer cell sizes to avoid seams, and be
`devicePixelRatio` aware.

*Built differently from the sketch, and better.* The gridlines share the board's single canvas rather
than getting an underlay of their own: a cell fill is inset by one pixel, that gutter belongs to the
line, and so nothing ever repaints a line. And "be `devicePixelRatio` aware" turned out to mean the
opposite of the usual recipe — the cell size is chosen in **device** pixels and the context is never
scaled, because `scale(dpr, dpr)` at a fractional ratio lands every coordinate between two device
pixels and antialiases the hairlines away.

One thing the renderer has to track that the engine does not: `TurnEvents` reports the squares the
*engine* changed, and a head that became ordinary body is not one of them — nothing about that square
changed as far as the rules are concerned. Drawing heads differently is the renderer's idea, so the
renderer keeps the previous head per snake and repaints the handoff itself.

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

1. **Rules units** (JVM) — *done, Phase 1*: legality; the **growth cadence golden `1,1,2,2,3,3,4`**;
   `TRAPPED` vs `SUICIDE`; first-move reversal legal, later reversal illegal; `TURN_LIMIT` draw.
2. **Property tests** — *done, Phase 1*: `undo(apply(s, m))` restores the board bit-for-bit and by
   Zobrist hash; **incremental occupancy == occupancy rebuilt from all bodies**.
3. **Codec round-trip** + fuzz: `decode(encode(r)) == r`.
4. **Golden move-stream hashes** per bot: `(seed, 20×20, bot vs RandomBot) → hash`. Catches the
   classic failure — iteration order over a `HashMap`. Legacy `GameStateImpl` iterated a `HashMap`
   and was only *accidentally* stable because `PlayerAvatar.hashCode()` returned a monotonic index.
   **Ban `HashMap`/`HashSet` iteration in `:core` and `:bots`.**
   *Phase 4:* one per shipped bot. The two that simulate are hashed on **12×12 at a budget of 500**
   rather than 20×20 at 1,000 — still hundreds of thousands of simulated moves, and the suite also
   runs in a real browser, where the engine is slower. Nothing in `:bots` may use `kotlin.math.ln`,
   `exp` or `pow`, or these hashes stop meaning the same thing on the two targets.
5. **Replay verification:** `record.verify(registry)` for each golden record.
6. **Bot contract suite:** a shared `botContract(factory)` run against *every* registry entry —
   never returns an illegal move when a legal one exists, respects the budget, deterministic given an
   identical seed, retains no cross-match state. This is the CI gate that makes "fork → add a bot →
   PR" safe to accept.
   *Phase 4 added two things to it.* `HeadlessMatch` now asserts `board.hash` is unchanged across
   every `chooseMove`, so a bot that thought on the driver's arena rather than on `turn.scratch`
   fails immediately and for every bot at once. And `BotLadderTest` checks that each rung beats the
   one below it — the only assertion in the suite that a bot which is correct, deterministic and
   *useless* would fail.
7. **Browser conformance (CI, one job):** conformance suite on `wasmJs`, plus one end-to-end check
   that boots the page, loads a replay from the hash, steps 100 turns and asserts final state.
8. **Benchmarks:** rollouts/sec on both targets, to quantify the wasm gap with numbers.
   *Phase 6:* `ThroughputTest` and `RolloutTruncationTest`, both printing `[bench]` lines and both
   run on both targets. Their assertions are an order of magnitude looser than their measurements on
   purpose — a benchmark that fails when the machine is busy teaches everyone to ignore it, so they
   fail only on a regression nobody could argue with and the *printed* numbers are what tuning
   decisions get made from.

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

**Phase 1 — core rules, rewritten from scratch. DONE.** `Occupancy`, `SnakeBody`, `Board`,
`RulesConfig`, `MatchState`, `MatchOutcome`. Explicitly a rewrite, not a port: legacy has two
competing board representations and the wrong performance shape. `SplitMix64` and `Budget` landed
here too — both are `:core`'s responsibility, both are small and fully specified above, and the
property tests wanted a PRNG anyway.

Measured results: 72 tests green, and green **identically on `wasmJs` in Chrome** — worth running
once at this stage, because the RNG's known-answer vectors and the Zobrist keys both lean on 64-bit
multiply and unsigned shift, which is exactly where the two targets could have drifted. The suite
covers the growth golden `1,1,2,2,3,3,4`, classic Tron at `growEveryNthMove = 1`, `TRAPPED` vs
`SUICIDE`, first-move reversal legal and later reversal fatal, the tail-square rule, the turn-limit
draw, corpses as obstacles, dead slots skipped in the turn order, and the two property tests that
guard the design: *unwinding a whole random game restores every position bit for bit, hash included*,
and *incremental occupancy equals occupancy rebuilt from the bodies*.

**Phase 2 — driver, replay codec, two trivial bots. DONE.** `:bot-api`, `:bots` and `:match` landed
together, because the thing worth getting right is the *seam* between them and it cannot be evaluated
one module at a time. `RandomBot` and `WallHugBot` as semantic ports; `Match` with `step()`;
`MatchSetup`/`MatchRecord`/`ReplayCodec`; `mostDistantSpawns`. Still no UI.

Measured results: 168 tests green, and green **identically on `wasmJs` in Chrome** — which for this
phase is not a formality. The golden move-stream hashes and the two-bit codec both lean on 64-bit
arithmetic and byte packing, and the replay format is the one artifact here that has to keep decoding
years from now, so a silent divergence between the JVM the tests run on and the browser the game runs
in would be exactly the bug worth catching early. A 20×20 match encodes to under 400 base64url
characters. `verify()` re-runs the real bots from the seed and reproduces the stream move for move,
and the same test proves it *fails* on a stream tampered with at one index.

Throughput, measured rather than assumed: **~80,000 complete 20×20 matches per second** on the JVM,
around 26M turns/s, with the trivial bots — so the figure is the engine and the driver rather than a
search. That is the number the phase existed to reach, and it is what makes batch tournaments in
Phase 6 nearly free.

Four decisions worth recording, because each closed off a plausible alternative:

- **Spawns are recorded in the header, not re-derived at playback.** Deriving them saves a dozen bytes
  and stakes every replay ever shared on `mostDistantSpawns` never changing again. Recording them
  costs a varint per slot and makes the record self-contained.
- **`budgetPerTurn` is in the header too.** Playback does not need it, but `verify()` does: re-running
  an MCTS bot under a different allowance produces different moves, and the divergence would look
  like a determinism bug rather than a missing field.
- **The contract suite lives in `:bots`, not `:match`.** Testing "the driver plus the real bots" would
  have meant a `:match` → `:bots` test dependency, and a test dependency is still an edge in the
  resolved graph. `:bots` proves its bots deterministic against a forty-line local turn loop, `:match`
  proves the driver and codec against its own stub bots, and the layering survives. `checkModulePurity`
  now encodes the whole forbidden-edge table and checks test source sets as well.
- **`Scratch`/`Playout` shipped now, though no Phase 2 bot uses them.** `:bot-api` is supposed to be
  the small stable thing bot authors read; adding a field to `Turn` in Phase 4 would break that
  promise at exactly the moment the first external bots exist.

One real bug found and fixed while writing the tests: the "at most `slots - 1` terminal events" bound
in this document is wrong for a **solo** match. A contested match ends the instant one survivor is
left, so nobody is ever the last to go — but a solo match has no survivor to crown, ends with
`ALL_ELIMINATED`, and its single snake genuinely can be the one that leaves. The literal bound made a
lone player resigning unrecordable, which Phase 3 would have hit the first time somebody quit a
practice game.

**Phase 3 — UI. First playable milestone. DONE.** `:ui` with `BoardRenderer`, `TurnScheduler`,
`Chrome` and `Palette`; `InputBuffer`/`StallPolicy`/`InteractiveBot`/`PlayableRegistry` in `:match`;
`:app` reduced to registry injection and `#r=` routing. The game is playable: human against the
shipped bots, up to four seats, play/pause/step/speed, a scoreboard, a scrub bar over a recording,
and a match shared as a URL.

Measured results: 186 JVM tests green, production bundle **50.5 KiB gzipped** (up from 18.5 in
Phase 0) against the 1.5 MiB ceiling. Verified in Chrome against the production distribution rather
than the dev server: a three-way match ran to `LAST_SNAKE_STANDING` in 165 turns and encoded to a
**131-character** URL fragment; reloading that link reproduced the final position exactly — lengths
34/33/17, same winner — and seeking to turn 80 and back cost nothing measurable. Pacing was checked
by replacing `requestAnimationFrame` and pumping the callback with synthetic timestamps: 12 turns a
second comes out as 12, identically at 60 fps and at 30 fps, and a five-second stall yields three
turns rather than sixty.

Five decisions worth recording:

- **The human lives in `:match`, outside `ShippedBots`.** The bot contract suite requires that no
  registry entry claims to be interactive, so `InteractiveBot` cannot be a shipped bot without
  either weakening that gate or special-casing it. Composing the seat *outside* the shipped registry
  with `PlayableRegistry` keeps the gate absolute, and `:match` already owned human input. The slug
  `"human"` is frozen like any other; a replay carrying it plays back but cannot `verify`.
- **`CONTINUE_STRAIGHT` waits before the first move.** It sustains a heading the player chose and
  never invents one — which is the legacy `MoveTracker` bug read backwards, and it turns the opening
  into "the board is drawn, the game is live, waiting for you" instead of a snake that bolts.
  *Superseded after Phase 3, and the opening it describes is now the whole game* — see below.
- **One canvas, not an underlay.** This document specified a second canvas for the gridlines. Inset
  fills reach the same end: a cell owns `(c*s+1, r*s+1)` to `(c*s+s, r*s+s)`, the one-pixel gutter
  belongs to the gridline, and no fill ever touches it. Same "paint the lines once" property, one
  fewer canvas, no stacking context and no second device-pixel-ratio dance.
- **Draw in device pixels; never scale the context.** The obvious `scale(dpr, dpr)` puts every
  coordinate between two device pixels as soon as the ratio is fractional — 1.35 on the machine this
  was built on — and a hairline gridline becomes a soft two-pixel smear. Choosing a whole number of
  device pixels per cell makes it exact at any ratio; sampling the backing store across a row now
  yields exactly two colours.
- **Seeking rebuilds and replays rather than snapshotting.** The engine runs tens of millions of
  turns a second and a scripted slot costs no search, so winding to turn N is microseconds. Keeping
  periodic snapshots would have bought nothing and added a consistency problem.

Two bugs found by looking at the actual page rather than at the code. Author CSS `display: flex` and
`display: grid` outrank the user agent's `[hidden] { display: none }`, so hidden rows sat on screen
while correctly reporting `hidden == true` — invisible from Kotlin, and fixed with one `!important`
in `styles.css`. And an unclamped negative frame interval, which real `requestAnimationFrame`
timestamps should never produce, would drive the accumulator below zero and silently freeze the match
until it climbed back; the lower bound now costs one `coerceIn`.

Not built, and deliberately: a light-theme pass was written but only exercised on a dark display, and
the scoreboard shows the first four slots of a replay that somehow carries more.

**After Phase 3 — a match with a person in it is turn-based.** The arcade default was wrong for this
game. A snake that keeps moving while you think turns every decision into a reaction test, and on a
board where one square is the whole difference between trapping somebody and trapping yourself, that
is the wrong difficulty to be selling. So `WAIT_FOR_INPUT` is now the default of both `InteractiveBot`
and `PlayableRegistry`, and the key is the clock: `:ui` does not start `TurnScheduler` at all while a
player is alive, and each keypress plays exactly the round it belongs to.

Turn-based does not have to mean one key per square, and `KeyRepeat` is the other half of the feel:
a held key plays a move every 250ms, a tap plays exactly one, and a key pressed while another is
held takes the repeat over. It is deliberately *not* the keyboard's own auto-repeat, which
`Chrome` still drops — that rate is tuned for cursors in text, arrives after half a second of
nothing, and differs per machine, none of which is a rate you can stop a snake on. It runs on
`requestAnimationFrame` for the reasons `TurnScheduler` does, and takes its timestamps from the
frame, so the same synthetic-frame trick drives it in a test.

One bug came with it, and it was only ever latent under `CONTINUE_STRAIGHT`: a **trapped** player
can never press a legal key, because `take` filters illegal input — so waiting for one parked the
match for good, with the board saying "your move" and no move able to exist. `InteractiveBot` now
plays a fatal direction when `legalMoves` is empty, which the engine records as `TRAPPED` exactly as
it would for a bot in the same position. Found by playing the thing in a browser, not by reading it.

Three more things fell out of it. The transport is *disabled* rather than removed while somebody is
playing — there is no clock to start, stop or step, and greying it says so without moving anything.
The moment the player is eliminated the match stops being interactive, the scheduler takes over, and
the survivors finish the game at the speed on the slider. And `Match.interactive` cannot be
`bots.any { it.interactive }`, because `ScriptedBot` claims to be interactive so that the end of a
partial recording parks rather than forfeits; playback is therefore excluded explicitly, by the flag
`Match.playback` sets. Every one of those is a JVM test in `MatchTest`.

**Phase 4 — search bots. DONE.** Five bots and six `internal` primitives, all of it inside `:bots`.
`FloodFill` and `ShortestPaths` on the padded array; `nearestOpponent`; `randomPlayout`;
`portableLog`; `UctTree`. Then `SpaceBot` (`ForkAi`), `PressureBot` (`ForkPathAi`), `ChaseBot`
(`PathAi` over `AStar`), `FlatMonteCarloBot` (`MonteCarloAi`) and `UctBot` (`UctAi`/`Node`/
`BiState`). `Reward` deleted, `Rollout` folded into return values, `AdaptiveUct` dropped. `:bot-api`
did not change at all, which is the promise Phase 2 made when it shipped `Scratch`/`Playout` early.

Measured results: **263 JVM tests green, and green identically on `wasmJs` in Chrome** — which for
this phase is the headline rather than a formality, because UCB1 is the one place in the program
where the two targets could legitimately disagree, and the UCT golden move-stream hash reproduces
bit-for-bit in the browser. Production bundle **58.0 KiB gzipped** (up from 50.5 in Phase 3) against
the 1.5 MiB ceiling, so the whole search layer costs 7.5 KiB.

Checked in Chrome against the production distribution rather than the dev server, as Phase 3 was:
all seven bots appear in the sidebar pickers with no HTML change, `uct` against `space` on 20x20 at
seed 424242 ran to `LAST_SNAKE_STANDING` in **133 turns** with both snakes at length 34, and it
encoded to a **101-character** URL fragment that reloads to exactly that final position. The board
shows the search doing something recognisable rather than merely legal: UCT folds the open half of
the board into a staircase and leaves its opponent nowhere to go.

The ladder, at 12x12 over twenty matches per pairing, each seed played from both seats, at the
shipped allowance of 10,000. Each rung beats the one below it and the order is the registration
order:

| | random | wallhug | space | pressure | chase | flat-mc | uct |
|---|---|---|---|---|---|---|---|
| **wallhug** | 16 | — | 3 | 0 | 0 | 1 | 0 |
| **space** | 17 | 17 | — | 2 | 7 | 1 | 1 |
| **pressure** | 18 | 20 | 18 | — | 6 | 6 | 7 |
| **chase** | 19 | 20 | 13 | 14 | — | 6 | 5 |
| **flat-mc** | 20 | 19 | 19 | 14 | 14 | — | 4 |
| **uct** | 20 | 20 | 19 | 13 | 15 | 16 | — |

Six decisions worth recording, because each closed off a plausible alternative:

- **No `DuelBot`** — see the superseded note under the bot contract above. `UctBot` searches the real
  N-player game, and the reduction is a function with one caller.
- **Per-actor credit assignment, not negamax.** Dropping the duel reduction is exactly what forces
  it. `Node.propagateValue` complemented the reward at every step up the path, which is right for two
  players alternating and wrong the moment there is a third: "bad for A" is not "good for B" when
  there is a C, and the bot ends up helping whichever opponent is not on the current line. Each node
  instead stores its reward from the point of view of *the snake that moved into it*, one `ByteArray`
  column, and backs up `1.0`/`0.5`/`0.0` with no complementing anywhere. Because a child's actor is
  the snake to act at its parent, maximising a child's average maximises the mover's own payoff at
  every node — correct for any number of snakes, and identical to the legacy behaviour when there are
  two. `UctTreeTest` pins it with the assertion negamax would fail.
- **`portableLog` instead of `kotlin.math.ln`.** The risk section below says `log`/`exp` are not
  bit-identical across targets and floats "a fixed-point `log` for UCB1" as the mitigation. It is
  twenty lines — IEEE exponent, mantissa folded into `[1/√2, √2)`, `atanh` as a polynomial, `+ - * /`
  throughout — and it is what lets `UctBot` carry a golden move-stream hash that runs in the browser
  conformance job. Without it that hash would pass on the JVM and fail in Chrome, which reads as a
  codegen bug and is not one. Recording the move stream remains the answer at the *format* level;
  this closes it at the *bot* level.
- **`playout()` per iteration, not `undo()` back to the root.** `Playout.reset` is one `ByteArray`
  copy of the occupancy plus the live body lengths — about the cost of five `apply` calls — while
  unwinding costs one `undo` per simulated move, and the rollout is the long part. So resetting is
  both cheaper and structurally safe: an off-by-one unwind would quietly poison every later
  iteration, and there is no unwind to get wrong.
- **No tree reuse, and the arithmetic is why.** `Bot`'s KDoc anticipates it and `BoardView.hash`
  makes finding last turn's subtree a `Long` compare, so it was implemented last and measured first.
  A turn builds **137 nodes on a 20x20 at the shipped allowance** — so 137 rollouts — spread over
  four openings and then over the opponent's replies, which puts roughly **8 visits** in the subtree
  that would survive into the next turn. Six percent, in exchange for a `hash` column and a copying
  compaction of the pool, because node ids are positional and "keep only this subtree" is not a free
  operation on a flat array. Not worth it. There is also a soundness wrinkle worth writing down:
  `hash` covers occupancy, heads, growth phases, liveness and whose turn it is, but **not**
  `turnIndex` — and `turnIndex` is what `maxTurns` terminates on, so statistics gathered at a
  shallower turn describe a position with a longer horizon than the one they get grafted onto.
- **Variance ceiling dropped rather than put behind a flag.** UCB1-Tuned needs a second `DoubleArray`
  of squared rewards and a second logarithm per child, and legacy's only caller passed the flag off.
  A knob that ships permanently off is dead code with extra steps.

Two findings that were not expected going in:

- **The tree is worth nothing at a thousand simulated moves a turn, and a lot at ten thousand.** A
  rollout runs a hundred-odd moves, so a thousand buys about fifteen iterations — four of which go on
  giving each opening its first visit. Against flat Monte Carlo, which shares the rollout policy and
  the allowance and differs only in remembering what it learned, `UctBot` wins **9 of 20 at a
  thousand and 16 of 20 at ten thousand**. Both numbers are pinned in `BotLadderTest`, because the
  first one is the reason strength must not be measured at the contract suite's smaller allowance.
- **Mocha's per-test timeout is two seconds**, which is a unit-test budget, and `BotLadderTest` plays
  two hundred complete matches on purpose. Raised to two minutes in `bots/karma.config.d/`, rather
  than shrinking the sample, because the sample size is the point. Found by running the browser job,
  which is the only place it can be found.

**The lever for later, identified and deliberately not pulled:** truncating the rollout at a depth
and evaluating the cut-off position by reachable-space share would multiply iterations per turn
rather than adding to them, which is worth more than anything else on the list. It is a
*measurement* question, Phase 6 owns measurement, and a knob shipped off by default is dead code —
so it waits.

*Pulled in Phase 6, and it is not a lever.* Even at equal budget it is dead level over forty matches
a depth, and it costs between one and a fifth and three times the wall-clock per turn — because a
rollout on the mutable arena is cheap enough that a hundred simulated moves cost about what one
board-wide sweep costs. The table is under Phase 6 below. It ships wired and off, which is the one
case where a knob defaulted off earns its keep: the alternative is deleting the evidence.

**Phase 5 — contributed bots. DONE.** `BurninHellBot` and `TomSnakeBot` in `:bots`, appended to
`ShippedBots` as a second section after the ladder, gated by the same contract suite as everything
else, attribution in the KDoc. `OtherSnake` was dropped rather than ported — see below. Nothing else
changed: no new file outside `:bots`, no `:bot-api` surface, no HTML.

Measured results: **280 JVM tests green** (up from 263), and green identically on `wasmJs` in Chrome.
Production bundle **58.5 KiB gzipped** (up from 58.0 in Phase 4) against the 1.5 MiB ceiling — the
two bots cost half a kilobyte between them, which is what porting into primitives that already exist
is supposed to look like.

The three files were 202 lines and about 49 of live code, and reading them is what set the shape of
the phase. All three extended `PvpAi`, so all three paid for a path search per opponent every turn
to reduce the field to a single `opp` — and **not one of them ever read `opp`**. The reduction is
dropped from all of them, which also disposes of the inherited walled-off-opponent bug.

Where the two land, on the same terms as the Phase 4 ladder table — 12x12, twenty matches a pairing,
each seed played from both seats, at the shipped allowance of 10,000:

| | random | wallhug | space | pressure | chase | flat-mc | uct |
|---|---|---|---|---|---|---|---|
| **burninhell** | 17 | 20 | 4 | 0 | 10 | 0 | 0 |
| **tomsnake** | 11 | 5 | 7 | 1 | 3 | 0 | 0 |

So `burninhell` sits between `wallhug` and `space`, and `tomsnake` sits between `random` and
`wallhug` — neither is a ladder rung, which is why they are registered as a separate section rather
than spliced in. The two rows worth resampling were resampled at a hundred: `burninhell` beats
`random` 79 and `wallhug` 100, and `tomsnake` beats `random` 56 — see the second finding below for
why that last one is not the number to quote.

Four decisions worth recording:

- **`OtherSnake` is not ported, because it is already on the ladder.** Its whole body is
  `Rand.fromList(Direction.availableFrom(board, you.head()))`, which is `RandomAi`'s body, which is
  `RandomBot`. Registering it would have added a second slug for one policy: a duplicate row in every
  sidebar picker, a second golden hash pinning behaviour already pinned, and nothing a player could
  tell apart. The alternative — port it for completeness — buys a count and costs clarity. Its 27
  commented-out lines are a broken earlier draft, four unguarded `if`s that overwrite each other so
  the *last* available direction wins; that is an inverted `Burninhell` and it is not ported either.
- **`TomSnakeAi` ships faithful, at 0.2, and not as what it nearly was.** Directly above its random
  branch sits `new UctAi(256)`, commented out — evidently the author's intent, and presumably
  unaffordable when a legacy rollout built a whole persistent game per iteration. Shipping that line
  would have produced a stronger and more interesting bot that the contributed file never ran. It is
  recorded in the KDoc as a thing to measure instead. The `9.0/10` in its commented `else` is a red
  herring; the branch is exhaustive, so 0.2 is the whole ratio.
- **The registry is two sections now, and the docs say so.** Appending after `uct` keeps
  `entries.first() == random`, which `:ui` relies on for the default second seat, and needs no UI
  change at all — the pickers are filled from `BotRegistry.entries`. What it costs is the claim that
  registration order is strength order, so that claim is now scoped to the ladder explicitly.
  Splicing them in by measured strength was the alternative, and it would have interleaved
  contributed bots into a ladder whose rungs `BotLadderTest` asserts.
- **`burninhell` is written against an explicit priority array, not `legalMoves.nth(0)`.** They pick
  the same move today, because `DirectionSet` iterates by ordinal and `Direction` is declared
  `NORTH, SOUTH, EAST, WEST`. Spelling it out costs one shared array and stops the bot's entire
  identity from being a silent consequence of an enum's declaration order.

Two findings that were not expected going in:

- **`burninhell` beats `wallhug` 100 times out of 100, and draws dead even with `chase`.** "First of
  four, always in the same order" reads like a null bot and is not one: because the reverse direction
  is always your own neck, the fixed order becomes a serpentine column sweep — north to the wall,
  east one column, south to the wall, east again. A spiral eventually encloses itself and a column
  sweep does not, which is the entire 100-0. The 50-50 against `chase` is the more interesting half:
  a chaser walks toward the sweeper and dies inside the corridor the sweep just laid down, which is
  the failure mode `ChaseBot`'s hand-off to `PressureBot` exists to avoid and does not fully.
- **The 20% appraisal share in `tomsnake` is worth less than it looks, and the honest comparison is
  against itself.** Against the shipped `random` it wins 56 of 100 — about 1.2 sigma, which is not
  evidence of anything. Against its own `forkShare = 0.0` variant, which holds the board, the seeds
  and the class fixed and changes only the ratio, it wins 68 of 100. That is the number the test
  pins, because it is the one that isolates the thing being claimed. `forkShare = 1.0` against
  `forkShare = 0.0` is 95 of 100, which is just `PressureBot` against `RandomBot` and confirms the
  branches are wired the way round they are supposed to be.

**Phase 6 — stats, batch tournaments, and the two measurements. DONE.** `MatchStats`/`SlotStats`
and `Tournament`/`TournamentConfig`/`TournamentTable` in `:match`; `TournamentRunner`,
`TournamentOptions` and `TournamentStatus` in `:ui`, with a stats panel in the sidebar and a
tournament panel under the board; `SpaceOwnership` and `truncatedPlayout` in `:bots`, wired into
`UctBot` and shipped off; `ThroughputTest` and `RolloutTruncationTest` as the two measuring
instruments. `MatchSetup.DEFAULT_BUDGET_PER_TURN` goes from a guessed 10,000 to a measured 40,000.
`legacy/` is deleted.

Measured results: **311 JVM tests green** (up from 280), and green identically on `wasmJs` in Chrome.
Production bundle **64.6 KiB gzipped** (up from 58.5 in Phase 5) against the 1.5 MiB ceiling, so
stats, tournaments and a second search primitive cost 6 KiB between them.

Checked in Chrome against the production distribution, as every phase since 3 has been. A three-bot
tournament of thirty matches runs at a visible pace on a page that stays responsive, paints the match
it is currently on, and prints the matrix; `uct` beats `chase` **10 of 10** on a 12x12 at the new
allowance; a 160-turn `uct` vs `space` match on a 20x20 shares as a **129-character** fragment that
reloads to the same position, the same lengths and the same stats, and scrubbing to turn 80 and back
moves the stats panel with it. The tab a browser automation tool drives is *hidden*, so
`requestAnimationFrame` never fires in it — the checks above run by replacing `requestAnimationFrame`
and pumping the callback with synthetic timestamps, which is the same trick Phase 3 used to verify
pacing and is worth knowing before concluding that the page is frozen.

**The two numbers Phase 4 handed this phase, answered.**

*The allowance.* `UctBot` timed on a 20x20 in headless Chrome, which is the slower target and the one
people play on, against the JVM for scale:

| allowance | Chrome | JVM |
|---|---|---|
| 10,000 | 1.2 ms/turn | 0.40 ms/turn |
| 40,000 | 4.1 ms/turn | 1.6 ms/turn |
| 60,000 | 5.2 ms/turn | 2.1 ms/turn |
| 100,000 | 16.6 ms/turn | 4.1 ms/turn |

The criterion is the scheduler's 8 ms frame budget, which can only be honoured *between* turns — so a
turn that overruns the slice overruns the frame. 40,000 is half of it, and that headroom is the whole
argument: the machine these came off is a desktop, and one four times slower still lands inside a
single 60 Hz frame. The engine itself runs **2.7M turns/s in Chrome against 8.8M on the JVM** with
trivial bots, a gap of about 3× and inside the band the risk section predicted.

Strength was checked separately, because a timing cannot say whether the search is worth having:
`uct` at 40,000 beats `uct` at 4,000 **17 of 20**, and the ladder still holds at the new allowance
(17, 18, 14, 16, 15 for the five rungs). That 17 is also the ceiling on the argument for going
further — ten times the budget is three wins in twenty, so the curve is flat enough that the frame
budget and not strength is the right thing to set this by.

*Rollout truncation, settled: **no**.* Phase 4 named it the highest-value lever left. Built as
`SpaceOwnership` — one multi-source breadth-first sweep from every live head, first arrival wins,
ties belong to nobody — and `truncatedPlayout`, and played against full rollouts over forty matches a
depth on a 12x12 at the same allowance:

| depth | wins of 40 | µs/turn | against full |
|---|---|---|---|
| 10 | 20 | 1,120 | 3.1× |
| 25 | 22 | 630 | 1.8× |
| 60 | 23 | 435 | 1.2× |
| played out | — | 357 | — |

Dead even on strength — one sigma over forty matches is ±3.2, so 20, 22 and 23 are the same number —
for one and a fifth to three times the wall-clock. The reasoning behind the idea does not survive
contact with *this* engine, and the mutable arena is exactly why: a rollout here is mutate-and-undo at
tens of nanoseconds a move, so a hundred of them cost about what **one** board-wide sweep costs, and
truncating at ten buys seven times as many sweeps rather than seven times as much search. Equal
budget is the generous comparison too — buying more iterations per simulated move is the entire point
of truncating — so losing there *and* costing more leaves no allowance at which it is the better use
of a millisecond. It ships wired and off rather than deleted: a measured "no" is worth more with the
thing still there to re-measure.

Five decisions worth recording:

- **`Tournament.step` plays one turn, not one match.** The only caller that matters is a browser, and
  a match at the shipped allowance is most of a second. Stepping by turn lets `:ui` slice a
  tournament across frames on an 8 ms guard, so a batch of search bots runs at whatever the machine
  gives on a page that never stops answering the mouse — with no worker, no threads, and no
  asynchrony anywhere in the driver. It is the same reasoning that made `Match.step` one turn, and it
  is what the risk section meant by "design for a worker by keeping `:match` headless": the seam is
  already in the right place.
- **The tournament's contestants are the slot pickers.** There is no second list of bots on the page.
  The sidebar already says who is playing; a tournament is that question asked a few hundred times
  instead of once. A human seat and a bot picked twice drop out on the way through, and the panel
  says so plainly when fewer than two are left.
- **The matrix is text, and `:match` renders it.** `TournamentTable.toString` lays the win-rate
  matrix out — the same shape as the tables in this document — and `:ui` writes it into one `<pre>`.
  So the one panel most likely to have broken the rule that the chrome never constructs DOM does not
  break it, and the same rendering serves a browser panel and a test log.
- **`MatchStats` is derived, never accumulated.** The board already knows every figure worth
  reporting — lengths, moves survived, who is left and why the rest are not — so the driver counts
  nothing extra. A statistic the engine has to be modified to collect is one that has to stay correct
  forever after. It also replaced `:ui`'s own `SlotStatus`: the scoreboard and the stats panel now
  read one set of numbers rather than two that could disagree.
- **A tournament owns the arena while it runs.** The board paints whichever match the batch is on,
  and the scoreboard and stats describe *that* match, so one snapshot drives the whole frame. The
  moment the player touches the transport the arena goes back to their own match with a full repaint
  — the renderer paints one square at a time, and stepping a match onto a board still showing
  somebody else's game would leave that game underneath it.

Two findings that were not expected going in:

- **The 10,000 shipped since Phase 4 was four times too cautious.** The guess was made against a
  legacy `UctAi` that built a persistent board per node; the arena rewrite made a simulated move so
  cheap that a whole turn's search cost a millisecond in the browser, and nobody had measured it. The
  general lesson is the one this phase exists to make: a constant chosen by judgement in Phase 4 is
  worth re-deriving once the thing it describes has been rewritten underneath it.
- **Karma disconnects a browser that is working perfectly.** A test method is one synchronous call
  into wasm, so nothing — not the reporter, not Karma's own heartbeat — gets a turn on the event loop
  until it returns. Quadrupling the ladder's allowance pushed it past `pingTimeout`, and the failure
  reports as `reconnect failed before timeout of 2000ms (ping timeout)`, which reads as flaky
  infrastructure and is not. Mocha's timeout, raised in Phase 4, is not the one that bites first.

**Deleted outright, no port:** `SnakesRunner`, `SnakesContest`, `SimpleSnakesGame`, `SnakesGame2`,
`SnakeHistory`, `GameGraphics*`, `SnakesGameDisplay`, `MoveTracker`, the whole `MoveSpecifier` family
(all three collapse into `Decision`), `PlayerAvatar`/`PlayerWrapper`/`PlayerDisplay`/
`BasicPlayerDisplay`, both Swing inputs, `BoardArrangement`/`Matrix`/`BitSetMatrix`/
`MatrixBoardArrangement`, `BoardLocation`, `Action`, `RelLocation`, `WeightedMoveSpecifier`. Joined
in Phase 5 by **`OtherSnake`**, which is not deleted for being scaffolding but for being a duplicate:
its body is `RandomAi`'s body, and `RandomAi` is already shipped as `random`.

**Semantic ports (algorithm preserved, API and performance reshaped):** `AStar`/`Path`, `WallHugAi`,
`RandomAi`, `ForkAi`, `ForkPathAi`, `PathAi`, `MonteCarloAi`, `UctAi`/`Node`/`BiState`, `PvpAi`'s
reduction, and `BoardOccupancy.mostDistant` (spawn placement — keep, but make it explicitly
deterministic) — *all done as of Phase 4* — then `Burninhell` and `TomSnakeAi` in Phase 5, which
completes the port: **nothing under `legacy/java/ao/ai/` is now unaccounted for.** `AStar`/`Path`
landed as `ShortestPaths` — legacy's
`Path.compareTo` ordered by cost-so-far and used the heuristic only as a tie-break, so the class was
Dijkstra under an A\* name, and on a unit-cost four-neighbour grid that is breadth-first search.
Every consumer wants distances to several opponents in one turn anyway, which a single sweep answers
and a goal-directed search does not.

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

*Phase 6 built batch tournaments without one, and the shape held.* `Tournament.step` advances one
turn and returns, so `:ui` slices a batch across frames on the same 8 ms guard the match scheduler
uses and the page stays responsive throughout — no worker, no threads, and not one `suspend` anywhere
below `:ui`. What a worker would buy now is a second core rather than a responsive page, which is a
smaller and much later prize. The seam is still where it was designed to be if that day comes.

**Cross-target determinism drift** (`log`/`exp` not bit-identical): mitigated by recording move
streams, reducing it to a CI concern — `verify()` may need a same-target assertion, or a fixed-point
`log` for UCB1 if cross-target verification is wanted.

*Resolved in Phase 4, by taking the second option.* `portableLog` computes UCB1's logarithm from
`+ - * /` alone, so `UctBot` picks the same move on both targets and its golden move-stream hash
reproduces bit-for-bit in headless Chrome. `verify()` needs no same-target caveat. The standing rule
that falls out: **nothing in `:bots` may call `kotlin.math.ln`, `exp` or `pow`.**

**Replay URL length** is bounded by `maxTurns`; if a match still exceeds comfortable hash length,
offer a downloadable record file instead of a link.

---

## After release 1 — visual bot configuration

Not a phase; release 1 was feature-complete and this is new work on top of it.

`BotParams` shipped in Phase 4 and was never reachable. `Match` passed `BotParams.EMPTY` at its only
production construction site, so `UctBot`'s `exploration`, `maxNodes` and `rolloutDepth` — and the
knobs on `PressureBot`, `ChaseBot` and `TomSnakeBot` — could only be set from a JVM test, which
`RolloutTruncationTest` did by fabricating bot ids. The per-turn allowance had the same problem one
level up: a single match-wide `Int`, so `HeadlessMatch.budgetPerSlot` could ask "is a bigger allowance
worth anything?" and the browser could not.

Four decisions worth keeping the reasoning for.

**The declaration is the reader.** A knob is a `BotKnob` object that a bot both declares to the
registry and reads its own value out of — `EXPLORATION.read(setup.params)`. The alternative was a
separate schema beside a constructor still holding `params.double("exploration", 5.0)`, where the
number on the form and the number in the fallback are two facts that can disagree. This way there is
one of them, and the drift is unrepresentable rather than merely tested.

**`BotKnob.Search` is in the knob list but is not a parameter.** An allowance is granted by the engine
and lives in the replay header; a bot never reads one. It is declared anyway because that list is the
only place that knows a bot searches at all, which is how the form knows not to offer Wall Hugger a
budget field. `BotContractTest` then checks the declaration against what the bot actually spends, so
it cannot rot into a lie.

**`BotKnob.Param.read` is total, and `BotParams`' own readers are not.** `Match` builds its bots in a
field initializer, outside the `try` that guards `chooseMove`, and one route into it is an arbitrary
`#r=` fragment. A throw there has nothing above it to catch it. So `read` coerces and `reject` — which
the form calls, and which corrects the field in front of the player — carries the strictness.

**The replay format grew without any replay changing.** A match nobody configured is still written as
version 1 with a zero flags byte, byte for byte what shipped; a configured one is version 2 with the
reserved flags bit set and a per-slot block after the turn order. Writing the *oldest version that can
express the record* is what makes that work, and `ReplayCodecTest` now pins a literal v1 payload from
both ends — it must keep decoding, and a stock match must keep encoding to exactly it. That golden was
missing before, and nothing else in the suite would have caught a silent change to already-shared
links.

One thing this removed rather than added: `RolloutTruncationTest.uctWith`, which built a whole fake
`BotEntry` around a hand-rebuilt `BotSetup` purely because the shipped registry had no way to offer a
bot its parameters. It is a label and a `BotParams` now.

## After release 1 — reading the board

Also not a phase. Four things that only show up once somebody sits down with the thing.

**A seat is a configured bot, so its name has to be too.** The Players list printed
`registry[bot].displayName`, which made two seats running `uct` at two allowances the same word
twice — the one experiment this testbed exists for, illegible in the panel that reports it. The
matrix had already solved it, so the qualifier was split out of `Contestant.label` into
`Contestant.suffix` and `:ui` composes `displayName + suffix` over it. `label` stays `slug + suffix`,
byte for byte what it was, so every existing test of the table passes untouched.

The one thing deliberately *not* shared is the numbering. `TournamentTable` leaves the first of a
repeated heading bare (`uct*`, then `uct*·2`) because a column has a legend underneath it; a
four-row sidebar reads better as `Random ·1` and `Random ·2`, where neither row is the unmarked one.
Same qualifier, different numbering, and the difference is the panel rather than an oversight.

**The board is a fixed rectangle of device pixels.** It used to be sized from the container and
capped at `MAX_CELL = 30` CSS px, which made an 8x8 board a third of a 20x20 one and let browser zoom
rescale the game along with the text. It is now `BOARD_EXTENT` device pixels square, anchored to the
`devicePixelRatio` the page loaded at, and the grid chooses only how finely that square is divided —
8x8, 20x20 and 40x40 all come out within eight device pixels of each other. Because a CSS pixel
covers `devicePixelRatio` device pixels, pinning the device extent is exactly what makes zoom move
the text and leave the board alone; the ratio is read once because a single reading cannot tell a
150% zoom from a 1.5x display. `MAX_CELL` is gone rather than raised: a cap on the cell is the thing
that made a small board small, and it would fight the extent at every size the picker offers.

Two CSS consequences, both load-bearing. `#board` carries an `outline` instead of a `border`, because
`box-sizing: border-box` had a border quietly eating two pixels out of the width Kotlin wrote —
resampling every gridline — and because `getBoundingClientRect` reports the border box, which the new
hit-test would have been a pixel out on. And `.board-wrap` is a one-cell grid with `justify-items:
center` holding both canvases in the same area, so they are centred by the same algorithm rather than
by two rules that agree to within half a pixel.

**Hover is a read, and its place in `dispatch` says so.** It is answered above both existing guards:
above the batch guard, because asking what is under the pointer while a tournament runs harms
nothing; and above the arena handback, because otherwise moving the mouse across a finished batch's
last position would silently swap it for the player's own game. The highlight goes on a second canvas
— `paintMove` repaints two or three squares a turn, so a decoration in that bitmap would have to be
understood by every one of those paints, and the full `repaint` a batch does every frame would wipe
it. What is remembered is the hovered **square**, never the snake, so a restart, a seek and a batch
changing match all resolve to whoever holds it now. Two cues, because either alone is ambiguous: a
wash that brightens toward the head says which way the snake runs, and a thread through the body
centres says where it ran, which a doubled-back coil needs. The tail being about to clear is left to
the label — the board already fades that square, and a third telling of one fact is noise.

**Holding an arrow key scrolled the page**, and the cause is worth recording because the fix looks
like a no-op. `onKeyDown` returned on `event.repeat` *before* `preventDefault`, so the first press was
cancelled and every auto-repeat after it fell through to the browser. Dropping those events is a
statement about how fast the snake moves and says nothing about what the page may do with them, so
the cancel now comes first and the repeat guard second. The listener stays on `window` — that is what
lets somebody play without clicking the board first — and the same edit added a modifier guard,
because `key` is still `"a"` under Ctrl and `"ArrowLeft"` under Alt, so the page had been steering on
select-all and swallowing Back.

## After release 1 — the thread comes off the pointer

Two small things, and the first one splits a decoration that had been treated as one.

**The thread is drawn always; only the wash waits for a pointer.** The overlay went on as a unit on
hover, and that bundled two cues with different jobs. The thread traces where a body ran, which is
genuinely hard to read off the squares when a snake has doubled back beside itself — and that is
true of every snake on the board, on every turn, whether or not a pointer is near it. The wash picks
*one* snake out of the others, which is a question only a pointer asks. So the thread and its head
marker are painted for every snake every time the position moves, and the wash alone still follows
the pointer, laid down first so hovering adds a layer under the threads rather than rearranging
them.

That makes `paintOverlay` — `highlight` as was — the second half of every paint rather than a
response to pointer moves, which is why it is now named for the bitmap it owns. `GameSession` already
called it after every one of them, for the *other* reason a stale square needs re-asking, so nothing
moved: `refreshOverlay` is the same call sites under a truer name.

Two consequences worth writing down. A corpse keeps the share of its colour `Palette.CORPSE_ALPHA`
gives it on the board and loses the head marker entirely, because `paintSnake` stops marking a head
the moment a snake is out and an overlay that disagreed would make the dead snakes the loudest thing
on the board. And the cost is now a redraw proportional to how much of the board is occupied, once
per turn — the same order the hovered snake already cost, times the number of snakes, and bounded
above by a scheduler that tops out at eighty turns a second.

**The page opens on Human vs UCT at 8x8.** It used to open on you against `entries.first()` — the
random bot — on a 20x20 board, which is the weakest opponent in the registry on the emptiest board
the picker offers. `:ui` cannot depend on `:bots` and must not start to, so `Chrome.DEFAULT_OPPONENT`
names `uct` as a **slug** and falls back to `entries.first()` when a registry does not offer one, and
`DEFAULT_SIZE` moves with the `selected` option on `#size`.

## After release 1 — free-for-all tournaments and watching your own replay

Two answers to the same observation: somebody seats four bots, and the page then only lets them see
two at a time.

**A tournament now has a format, and it is a config property rather than a second driver.**
`TournamentFormat.HEAD_TO_HEAD` is the schedule that always existed — every unordered pair, each
seed from both seats — and was never a regression to fix: a win-rate matrix is a statement about
pairs, and a match of it is one. `FREE_FOR_ALL` seats *every* contestant in every match, so four
snakes fight on one board and the schedule is simply `rounds` matches long. The driver branches on
the config the way `Match.interactive` branches the clocks; `Tournament.step`, the runner, the
arena handover and the panel all run unchanged.

Two generalizations carry the whole thing. The seat swap becomes a seat *rotation*: each group of
`contestants` matches shares a seed and rotates the seating a step, so everybody starts from every
corner of the same board — and for two contestants, rotating is swapping, which
`TournamentTest.“a free-for-all of two is the head-to-head schedule”` pins as an identity on the
`MatchSetup`s themselves. And the scoring becomes *outlasting*: a four-way game does not produce
one loser per winner, so each match is recorded pairwise — alive beats eliminated, more
`SlotStats.movesMade` beats fewer, the rest draw — and the one matrix, `scoreRate` and the ranking
serve both formats without `TournamentTable` changing a line. The measure is honest about what it
is: `wins(a, b)` free-for-all counts matches `a` outlasted `b` *with everybody else interfering*,
which is a different question from head to head, and the panel note says which one each format
answers. `MatchStats` staying derived is what made this free — the moves-survived figure was
already in the board, so the driver still counts nothing.

**Watch replay is `load()`, fed from the board instead of the address bar.** The in-page switch to
playback existed the whole time — `GameSession.load` is public, and the `hashchange` listener calls
it — but the only way to reach it was to copy the share link and open it somewhere else. A button
in the Replay section now dispatches `UiIntent.WatchReplay`, which is a guard and one line:
`load(match.record())`. Everything after that was already built — the scrub bar reveals itself off
`UiModel.replay`, Restart replays from the top, Share publishes the recording being watched, and
Start match is the way back to live play, because `newMatch` was always the thing that cleared
`replay`.

The button is offered only for the player's own *finished* match — `replay == null &&
match.outcome != null`, guarded in the session and not just by the disabled flag, for the same
reason the space bar is. Mid-match it stays grey because a partial recording parks at "end of the
recording", which reads as broken rather than as a replay; and while a batch owns the arena it
stays grey because the matches finishing on screen are the tournament's, not the player's — the
model flag is computed from `match`, never from `shown`.
