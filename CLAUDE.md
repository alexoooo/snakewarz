# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this project is

**snakewarz** — a Tron-style snakes game built as an **AI testbed**. Snakes move one cell per turn on a
rectangular grid; walls and every snake body (including your own) are lethal; the last one moving wins.
There is no food and no score. Inception 2005, imported from the Google Code archive.

The point of the project is the **pluggable AI**: writing search bots and pitting them against each other.
Everything else exists to serve that.

## Current state — read this first

**All six phases are complete.** The rewrite is done: the rules engine, the bot contract, the match
driver, the replay codec, the canvas renderer, the DOM chrome, a seven-bot ladder topped by an MCTS
bot, per-match stats and batch tournaments all exist and are verified, and the legacy Java is deleted
— it lives at the `legacy-java-final` tag and nowhere else. You can play against the shipped bots,
watch bots fight, scrub a recording, share a match as a URL, and run a win-rate matrix over a few
hundred matches without the page stopping.

| Path | Status |
|---|---|
| `core/` | `:core` module. Padded-grid primitives plus the rules engine: `Occupancy`, `Board`, `MatchState`, `SplitMix64`, `Budget` |
| `bot-api/` | `:bot-api` module. `Bot`, `Decision`, `Turn`, `BotSetup`, `BotRegistry`, `BotKnob` — what a bot lets you tune — plus `Scratch`/`Playout`, the search arena that makes the budget structural |
| `bots/` | `:bots` module. Nine bots and `ShippedBots`, the `BotRegistry` implementation, over the `internal` search primitives `FloodFill`, `ShortestPaths`, `SpaceOwnership`, `nearestOpponent`, `randomPlayout`, `truncatedPlayout`, `portableLog` and `UctTree` |
| `match/` | `:match` module. `Match` driver, `MatchSetup`, `MatchRecord`, `ReplayCodec`, spawn placement, `MatchStats`, `Tournament`, and human input — `InputBuffer`, `StallPolicy`, `InteractiveBot`, `PlayableRegistry`. No time, no DOM |
| `ui/` | `:ui` module. `GameSession` — the only public class — over `BoardRenderer`, `TurnScheduler`, `TournamentRunner`, `Chrome` and `Palette` |
| `app/` | `:app` module. `main()`, registry injection and `#r=` replay routing. Sixty lines, and that is the point |
| `build-logic/` | Convention plugins `snakewarz.pure` and `snakewarz.browser`, sharing `registerModulePurityCheck` |
| `docs/MIGRATION.md` | The design doc and phase log. **Read this before changing architecture** |

`ShippedBots` has **two sections, and only the first is a ladder**. The ladder is registered weakest
first: `random`, `wallhug`, `space`, `pressure`, `chase`, `flat-monte-carlo`, `uct`. Each rung beats
the one below it over twenty matches — `BotLadderTest` is the gate, and it is the only test in the
suite a *correct but useless* bot would fail. Then come the bots contributed to the original project,
ordered by slug and claiming nothing about strength: `burninhell`, `tomsnake`. They are gated by the
same contract suite as everything else.

`random` must stay `entries.first()`, because `:ui` seats the second slot from it. Append new bots;
do not prepend. Of the nine, only `flat-monte-carlo` and `uct` touch `Turn.scratch` — the other seven
consume no budget at all, and `BotContractTest` enforces that rather than merely asserting it here:
a bot spends budget **if and only if** it declares a `BotKnob.Search`.

Do not assume anything else exists; check the tree.

The pre-rewrite Java is one command away, in its original Maven shape:
`git show legacy-java-final:src/main/java/ao/<path>`. Paths written as `legacy/java/ao/…` in older
notes are that, one directory level shifted.

**Phase tracker** — update this line as phases land, and mirror it in `docs/MIGRATION.md`:

> Current phase: **6 — done**. Release 1 is feature-complete; further work is new work, not the
> remainder of a plan. Landed since: **visual bot configuration** — `BotKnob`, per-slot allowances
> and parameters, configured tournament contestants. See the last section of `docs/MIGRATION.md`.

## What it is built on

Kotlin 2.4.10, Gradle KTS, **`wasmJs` browser target only**, deployed as static files to GitHub Pages.
Rendering is Kotlin/Wasm drawing to an HTML `<canvas>` 2D context, with hand-written HTML/CSS for the
chrome — deliberately **not** Compose Multiplatform. Bots are Kotlin classes compiled into the app and
registered in an explicit `BotRegistry`.

Release 1, all of it shipped: live match view with play/pause/step/speed, human vs bot, deterministic
seeded matches, shareable replays encoded in the URL hash, per-match stats, and batch tournaments.

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

### Stats and tournaments

`MatchStats` is **derived, never accumulated**. The board already knows every figure worth reporting
— lengths, moves survived, who is left and why the rest are not — so `Match.stats()` is a read, taken
at most once a frame, and the driver counts nothing extra as it goes. Do not add a counter to `Match`
for a statistic; work out whether the board can already answer it. It also serves the scoreboard, so
`:ui` has one set of per-slot numbers rather than two that could disagree.

`Tournament.step()` advances **one turn**, not one match, for the same reason `Match.step()` does: a
match at the shipped allowance is most of a second, and `:ui` has to be able to stop between any two
units of work. `TournamentRunner` slices it across frames on an 8ms guard, exactly as `TurnScheduler`
paces a match. That is what lets a batch of search bots run on a page that stays responsive, with no
worker and nothing `suspend` below `:ui`.

`Tournament.current` keeps reporting the **last** match after the batch ends, because somebody is
usually looking at it. `Tournament.setupFor(index)` exposes the whole schedule as a pure function of
the config — that is how `:ui` paints the opening position before a turn is played, and how the tests
assert the seat-swapping without catching the driver between two steps.

The contestants are the **slot pickers**, not a second list of bots: a tournament is the question the
sidebar already asks, over a few hundred matches. A human seat and a duplicate both drop out.

A `Contestant` is a **configured** seat — a `BotId`, an optional allowance and a `BotParams` — and its
identity is all three. That is what lets `uct` enter twice at two allowances, which is the first
question a testbed of search bots should be able to answer and the one a list of ids could not even
express. Two *identically* configured entries are still a duplicate and still refused. The allowance
is `null` rather than pre-filled, so `TournamentConfig.budgetPerTurn` still has something to do.
`TournamentTable` heads its columns with `Contestant.label` — `uct` beside `uct@4k` — numbers a
repeated label `·2`, and spells the settings out in a legend under the grid rather than in the
headings, which have a narrow panel to fit in.

## Four non-obvious facts

Getting any of these wrong silently breaks the game or its determinism.

**1. Snakes grow at half speed.** `SnakeImpl.advance` flips `willGrow` on every call, starting from `false`,
so body lengths go `1, 1, 2, 2, 3, 3, 4…` — the tail only retracts on alternating turns. This is a real
rule, not an artifact. It is `RulesConfig.growEveryNthMove = 2` (`1` = classic Tron) and it has a golden
test. Reference: `git show legacy-java-final:src/main/java/ao/sw/engine/v2/SnakeImpl.java`, lines 47-68.

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

At the *bot* level this is closed rather than merely contained: `UctBot` takes its logarithm from
`portableLog`, which is built from `+ - * /` only, so UCB1 picks the same move on both targets and
the UCT golden hash reproduces bit-for-bit in Chrome. **Nothing in `:bots` may call `kotlin.math.ln`,
`exp` or `pow`** — the failure it buys is a golden hash that passes on the JVM and fails in the
browser, which reads as a codegen bug and is not one.

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
./gradlew :app:wasmJsBrowserDevelopmentRun   # local dev server — yours. See below before an agent runs this
./gradlew :app:wasmJsBrowserDistribution     # production bundle -> app/build/dist/wasmJs/productionExecutable

# The measuring instruments. Both print `[bench]` lines and run on either target.
./gradlew :bots:jvmTest --tests '*ThroughputTest*' -i | grep '\[bench\]'
./gradlew :bots:wasmJsBrowserTest -PbrowserTests=true --rerun -i | grep '\[bench\]'
```

Browser tests are disabled unless `-PbrowserTests=true`, because Karma startup dominates the runtime
of small suites. Anything provable on the JVM should be proven there instead.

### Never background `wasmJsBrowserDevelopmentRun` — serve the distribution instead

The dev server is **not** a child of the `gradlew` you launched. Gradle runs the build inside its
**daemon**, a detached process that outlives the client, and the daemon is what forks the webpack
`serve` process. Kill the shell — close the terminal, stop the background job, hit Ctrl-C on a pipe —
and the client dies while the daemon happily keeps a webpack server listening on 8080, or 8081, or
whatever port was free. It is not in anybody's process tree and nothing reaps it. This has stranded a
server more than once.

`./gradlew --stop` is **not** the fix: it kills every daemon on the machine, including the one hosting
a dev server somebody is deliberately using.

So an agent that needs to see the app in a browser builds a static bundle and serves it itself:

```bash
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution   # terminates; holds no port
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable      # a direct child, killable by port
```

**8099 is reserved for this** and for nothing else, which is what makes "kill whatever is on 8099"
unambiguous — a human's dev server and an agent's look identical on the command line, because they are
the same command in the same project. Python maps `.wasm` to `application/wasm`, so
`instantiateStreaming` is happy; there is no live reload, which is the whole point — rebuild and
reload by hand.

Kill it when finished, and do not rely on the task runner to do it:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8099 |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

Prefer `jvmTest` while developing; most modules answer in seconds. **`:bots` does not** — it is a
couple of minutes, because `BotLadderTest` and `RolloutTruncationTest` play several hundred complete
matches with a search bot in them, which is the point of both. Narrow with `--tests` while working on
something else. A full cold `build` takes several minutes; the wasm toolchain is slow to warm up.

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

A bot instance is created once per slot per match, so instance fields persist across turns — which is
where every search buffer belongs: allocate from `setup.grid.cellCount` in the constructor and reuse
it forever. Get randomness from `setup.rng`, never `Random.Default`. Poll `turn.budget` in any search
loop.

Three rules that are not obvious until they bite:

- **`turn.legalMoves.isEmpty` is the first branch of every bot.** The contract suite opens on a 1x1
  board where nothing is legal on turn one, and an unguarded `legalMoves.nth(0)` takes it down.
- **Re-read `playout.outcome` after every `advance`, never carry it.** An exhausted budget makes the
  playout over, and `advance` on an over playout throws — so a stale reading is an exception that
  only fires when the allowance lands on that exact move.
- **A bot handed a budget of zero must spend exactly zero and still play well.** That is a contract
  test, and the shipped answer is to fall back on `SpaceBot`, whose flood fill charges nothing.

The `internal` primitives in `:bots` are there to be used: `FloodFill` for room, `ShortestPaths` for
distances and first steps, `SpaceOwnership` for the board carved up between the snakes,
`nearestOpponent` for `PvpAi`'s reduction, `randomPlayout` for a rollout, `truncatedPlayout` for a
short one judged by ownership, `UctTree` for a flat-array search tree.

`truncatedPlayout` and `SpaceOwnership` ship **wired and off**, and the reason is measured rather
than aesthetic — see `UctBot.ROLLOUT_DEPTH`. Do not turn them on without re-running
`RolloutTruncationTest`, and do not delete them either: they are the evidence.

Every registry entry is run against the shared contract suite in CI (`bots/src/commonTest/.../BotContractTest`):
never returns an illegal move when a legal one exists, survives a budget of zero, is deterministic given
an identical seed, retains no cross-match state, does not claim to be interactive, terminates on every
board shape, spends budget exactly when it declares an allowance, and plays the same match at its own
declared defaults as it does with nothing set. That suite is what makes "fork → add a bot → PR" safe
to accept.

### Declaring a knob

Anything worth tuning is declared as a `BotKnob` and passed to `register`. **The declaration is the
reader** — that is the whole design, and it is why the constructor holds no literal:

```kotlin
private val exploration = EXPLORATION.read(setup.params)

internal companion object {
    val SEARCH = BotKnob.Search(min = 0, max = 400_000, step = 10_000)
    val EXPLORATION = BotKnob.Decimal("exploration", "Exploration", "...", default = 5.0, min = 0.1, max = 100.0, step = 0.1)
    val KNOBS: List<BotKnob> = listOf(SEARCH, EXPLORATION)
}

// bots/ShippedBots.kt
register("my-bot", "My Bot", ::MyBot, MyBot.KNOBS)
```

The default on the form and the default in the field initializer cannot drift apart, because there is
only one of them. Four things about the shape:

- **`BotKnob.Search` is the allowance, and is not a `BotParams` value.** The engine grants it; a bot
  never reads one. Declaring it is how the sidebar knows to offer an allowance field at all — and the
  contract suite checks the claim against what the bot actually spends, so it cannot become a lie.
- **`read` is total.** An unparseable or out-of-range value falls back on the default rather than
  throwing, which is a deliberate departure from `BotParams`' own strict readers. `Match` builds its
  bots in a field initializer, *outside* the `try` that guards `chooseMove`, and one route in is
  whatever somebody pasted into the address bar — a throw there has nothing above it to catch it and
  takes the page down. Strict reading lives in `reject`, which is what the form calls.
- Knob names are **frozen once released**, like a `BotId` and for the same reason: they travel in the
  replay URL of every match somebody configured.
- Nothing else has to change. No HTML, no `:ui` code, no codec work.

For a rollout, take `turn.scratch.playout()` and spin on `outcome`:

```kotlin
val p = turn.scratch.playout()
while (p.outcome == null) p.advance(policy.pick(p.board.legalMoves(p.toAct)) ?: Direction.NORTH)
```

`advance` charges the budget itself, and an exhausted budget makes `outcome` a draw — so the loop
condition *is* the budget check and the search terminates structurally rather than on trust.

Adding a bot needs **no HTML change**: the pickers in the sidebar are filled from `BotRegistry.entries`
at startup, and each seat's settings rows are built from that entry's `knobs`. Those are the only two
places `:ui` builds DOM, and this is why.

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
`GameSession.playRound` straight out of the keydown. `TournamentRunner` is the third clock and the
only one with no speed at all — a batch is not something you watch at a rate, it is something you
wait for, so it runs flat out on an 8ms-per-frame guard and reports progress instead.

While a batch runs it **owns the arena**: `GameSession` paints its current match and builds the whole
`UiModel` from that match, so the board, the scoreboard and the stats cannot disagree. The transport
is greyed, and `dispatch` drops transport intents outright — the space bar does not read the DOM's
disabled flags. Touching the transport afterwards hands the arena back with a full `fit`, because the
renderer paints one square at a time and would otherwise step a match onto somebody else's board.

The static skeleton lives in `app/.../index.html`. Kotlin looks elements up by id once and then only
writes text, values and `hidden`; do not start constructing structure there. The win-rate matrix is
the case that most invites breaking that rule and does not: `TournamentTable.toString()` lays it out
in `:match` and the chrome writes the text into one `<pre>`.

**There are exactly two exceptions, and both come off `BotRegistry.entries`**: the `<option>` list in
each picker, and the knob rows inside each seat's `<details class="knobs">`. Both exist to keep
"fork, add a file, register it, open a PR" from also meaning "and edit the markup". A pre-written pool
of rows would have been the doctrinal answer and is the wrong one — the day a bot declares one knob
more than the pool holds, it silently loses it, which is the exact coupling the rule is there to
prevent. The *containers* are still static, and adding a third exception needs a better reason than
either of these had.

`SlotForm` owns all of that, one per seat, and nothing in it dispatches a `UiIntent`. Which bot is
picked and what its knobs are set to is **form state**, like the reseed button writing `#seed`; it
becomes app state only when Start match calls `read()`. Two things there are load-bearing:

- **A value is corrected in the field, not just in the read.** `SlotForm` runs `BotKnob.reject`
  first, falls back to the declared default, and writes the correction back — a match that quietly
  played at a number nobody typed would be worse than one that refused to start.
- **Values equal to the declared default are omitted**, so an untouched seat yields
  `BotParams.EMPTY`, `MatchSetup.configured` stays false, and the replay URL of a stock match is
  byte-identical to the one the codec produced before any of this existed.

## Working with the legacy Java

**The port is finished and `legacy/` is deleted.** What follows is a record of what was found there,
kept because it is the reasoning behind several decisions in the live code and because somebody will
eventually read the old Java and wonder why the rewrite disagrees with it. The tree is at
`git show legacy-java-final:src/main/java/ao/…`; nothing in it is outstanding work.

It was always a **specification to read, not code to translate**. It has two competing board
representations and the wrong performance shape; `:core` is a from-scratch rewrite. Algorithms were
ported semantically and the scaffolding deleted.

**The AI is fully ported and nothing under `ai/` is outstanding.** The sample bots landed in
Phase 4 — `WallHugAi`, `RandomAi`, `ForkAi`, `ForkPathAi`, `PathAi`, `AStar`, `MonteCarloAi`,
`UctAi`/`Node`/`BiState`, `PvpAi`'s reduction and `BoardOccupancy.mostDistant` — and the contributed
`ai/da/` bots in Phase 5, as `BurninHellBot` and `TomSnakeBot`. `OtherSnake` is the one deliberate
omission: its body is `RandomAi`'s body, so it is already shipped as `random`, and a second slug for
one policy is a duplicate picker row and nothing else. Do not "finish the port" by adding it.

All three `ai/da/` bots extended `PvpAi` and **none of them ever read the `opp` it computed**, so the
nearest-opponent reduction is dropped from all three rather than ported.

Known legacy bugs — **do not faithfully reproduce these**:

- `RelLocation.directionTo` is dead-broken: `closestDist = Double.MIN_VALUE` (smallest *positive* double)
  compared with `dist < closestDist`, so it always returns `FOREWARD`. The class is unreferenced; drop it.
- `PvpAi` picks the **walled-off** opponent every time. `AStar.pathBetween` returns an *empty list*
  for an unreachable target, `PvpAi` reads its `size()` as the distance, and `0` beats every real
  distance. `nearestOpponent` uses `ShortestPaths.UNREACHABLE`, and there is a named test for it.
- `AStar` is not A\*: `Path.compareTo` orders by cost-so-far and uses the heuristic only as a
  tie-break, so the frontier comes off in `g` order and the heuristic prunes nothing. On a unit-cost
  4-neighbour grid that is breadth-first search, which is what `ShortestPaths` is.
- `AiUtil.availableArea` checks its `stopAt` cap only *between* search layers, so it overshoots by up
  to a whole frontier — `ForkAi(6)` never meant six squares.
- `ForkPathAi` keys a `TreeMap` on the move appraisal, so two equally-rated directions collapse into
  one entry and one of them silently stops being a candidate; its `Math.random() < 0.5` tie-break is
  non-uniform; and with no opponents left its mean distance is `0 / 0`.
- `MonteCarloAi` divides by `numRuns` having run `numRuns / |legal|` rollouts, and drains one
  candidate at a time — so a budget that expires part-way biases the argmax toward the first
  direction.
- `Node.propagateValue` complements the reward at every step up the path. That is correct for two
  players alternating and wrong the moment a third exists.
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
