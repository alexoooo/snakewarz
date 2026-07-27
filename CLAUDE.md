# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this project is

**snakewarz** — a Tron-style snakes game built as an **AI testbed**. Snakes move one cell per turn on a
rectangular grid; walls and every snake body (including your own) are lethal; the last one moving wins.
There is no food and no score. Inception 2005, imported from the Google Code archive.

The point of the project is the **pluggable AI**: writing search bots and pitting them against each other.
Everything else exists to serve that.

## Before you touch anything

This file is the graph and the tripwires — what depends on what, what may never, and the handful of
facts that break a match without breaking a test. The detail of each module lives in `docs/`, one
file per audience. **Read the row that matches before you edit, not after the first review.**

| About to… | Read | What goes wrong if you don't |
|---|---|---|
| write any code at all | [`docs/Coding-Standards.md`](docs/Coding-Standards.md) | A rule set a review cites by id; several break a match *silently* |
| add or change a bot, or touch `bot-api/` | [`docs/Bots.md`](docs/Bots.md) | A slug or knob name you rename sits in the replay URL of every match somebody shared |
| touch `match/` — human input, stats, tournaments | [`docs/Match.md`](docs/Match.md) | A match with a person in it has no clock; add a counter to `Match` and the scoreboard grows a second source of truth |
| touch `ui/`, `index.html` or `styles.css` | [`docs/UI.md`](docs/UI.md) | The overlay is a second canvas painted whole; get the ordering wrong and every decoration vanishes the frame a batch repaints |
| change the page shell, the boot path or Pages | [`docs/UI.md`](docs/UI.md#deployment) | `#app` revealed after the first measure sizes every board to the minimum cell |
| run a build, a benchmark or `:lab` | [`docs/Workflow.md`](docs/Workflow.md) | A mistyped knob name silently measures the default and wastes however long the batch takes |
| compare against the pre-rewrite Java | [`docs/Legacy.md`](docs/Legacy.md) | Several of its algorithms are dead-broken and look intentional |
| change the shape of the architecture | [`docs/Migration.md`](docs/Migration.md) | It is the record of what was already tried and rejected, and why |

The module graph, the forbidden edges and the four non-obvious facts are **below, not in `docs/`**:
they are what you have to know before you know you need them.

## Current state

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
| `bots/` | `:bots` module. Ten bots and `ShippedBots`, the `BotRegistry` implementation, over the `internal` search primitives `FloodFill`, `ShortestPaths`, `SpaceOwnership`, `nearestOpponent`, `randomPlayout`, `truncatedPlayout`, `portableLog`, `UctTree`, `PuctTree` and `LeafEval` |
| `match/` | `:match` module. `Match` driver, `MatchSetup`, `MatchRecord`, `ReplayCodec`, spawn placement, `MatchStats`, `Tournament`, and human input — `InputBuffer`, `StallPolicy`, `InteractiveBot`, `PlayableRegistry`. No time, no DOM |
| `ui/` | `:ui` module. `GameSession` — the only public class — over `BoardRenderer`, `TurnScheduler`, `TournamentRunner`, `Chrome` and `Palette` |
| `app/` | `:app` module. `main()`, registry injection and `#r=` replay routing. Sixty lines, and that is the point |
| `lab/` | `:lab` module. A JVM command line for running batches headlessly — the one place outside `:ui` where a clock and a `println` live |
| `build-logic/` | Convention plugins `snakewarz.pure`, `snakewarz.browser` and `snakewarz.tool`, sharing `registerModulePurityCheck` |

Release 1 is feature-complete and everything since is new work rather than the remainder of a plan.
The phase log, and a closing section for each thing landed since phase 6, are in
[`docs/Migration.md`](docs/Migration.md).

Do not assume anything else exists; check the tree.

## What it is built on

Kotlin 2.4.10, Gradle KTS, **`wasmJs` browser target only**, deployed as static files to GitHub Pages.
Rendering is Kotlin/Wasm drawing to an HTML `<canvas>` 2D context, with hand-written HTML/CSS for the
chrome — deliberately **not** Compose Multiplatform. Bots are Kotlin classes compiled into the app and
registered in an explicit `BotRegistry`.

Release 1, all of it shipped: live match view with play/pause/step/speed, human vs bot, deterministic
seeded matches, shareable replays encoded in the URL hash, per-match stats, and batch tournaments.

## Module graph

| Module | Responsibility | May depend on | Targets |
|---|---|---|---|
| `:core` | Grid, occupancy, bodies, rules, transition, terminal detection, PRNG, budget | **stdlib only** | wasmJs + jvm |
| `:bot-api` | The contract bot authors read. Small, stable | `:core` | wasmJs + jvm |
| `:bots` | Shipped bots + `BotRegistry` impl | `:core`, `:bot-api` | wasmJs + jvm |
| `:match` | Turn sequencing, slot wiring, human input, replay codec, stats. No time, no DOM | `:core`, `:bot-api` | wasmJs + jvm |
| `:ui` | Canvas renderer, DOM chrome, rAF scheduler | `:core`, `:match`, `kotlinx-browser` | wasmJs |
| `:app` | `main()`, wiring, URL hash routing | all | wasmJs |
| `:lab` | A command line for running batches headlessly. Nothing depends on it | `:core`, `:bot-api`, `:bots`, `:match` | jvm |

The `jvm()` target on the four pure modules exists **only to run tests fast** — it is never deployed and
contributes nothing to the wasm bundle. It doubles as a second compiler proving those modules are
platform-free.

`:lab` is the one JVM **binary**, and the only module besides `:app` that sees both a bot registry and
the match driver. It may, for the same reason `:app` may: it *injects* `ShippedBots` into a
`Tournament` that knows nothing but the `BotRegistry` interface. That is the sanctioned inversion
rather than a new edge — `:match` still has never seen a bot class. It is never deployed and is not
on `:app`'s classpath.

## Forbidden dependency edges

These are the load-bearing constraint of the architecture. Do not add any of them, even temporarily.

- `:core` → **any** project dependency, ever. Notably **not** `:bot-api` — the engine does not know bots exist.
- `:core`, `:bot-api`, `:bots`, `:match` → `kotlinx-browser`, `org.w3c.*`, or any wasm-only API.
- `:match` → `:bots`. The driver resolves bots through the `BotRegistry` *interface*; `:app` injects the
  implementation. This is what keeps the replay codec free of bot classes.
- `:bots` → `:match`, `:ui`, `:app`. A bot must not be able to reach the clock or another slot's RNG.
- `:ui` → `:bots`. The renderer paints a `BoardView` and the chrome names slots through the
  `BotRegistry` interface, so nothing in `:ui` can tell a wall hugger from a human.
- **Anything** → `:lab`. It is a measuring instrument, so it sits above everything and under nothing.
  It may see `:bots` and `:match` together; it may not see `:ui` or `:app`.

Every one of these is enforced by `checkModulePurity`, which is wired into `check` for all three
convention plugins and walks every `*CompileClasspath` — case-insensitively, because a Kotlin/JVM
module spells its main one `compileClasspath` while a multiplatform one prefixes the target — and
test source sets included.

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
`portableLog`, built from `+ - * /` only, so UCB1 picks the same move on both targets and the UCT
golden hash reproduces bit-for-bit in Chrome. `PuctBot` needs no logarithm at all. That is rule
**SW-02** in [`docs/Coding-Standards.md`](docs/Coding-Standards.md), which is where the reasoning and
the exact prohibition live.

**4. Legality is evaluated *before* the tail retracts.** A snake may not move into the square its own tail
is about to leave, even on a turn when that square is certain to clear. This is the legacy rule —
`SimpleSnakesGame` tested the destination against a board built before the retraction — and letting the
tail clear first is a materially different game, one where a snake can chase its own tail forever. It is
`BoardRulesTest."a snake may not move into the square its own tail is about to leave"`.

## Coding standards

**[`docs/Coding-Standards.md`](docs/Coding-Standards.md) is the rule set every change is reviewed
against — determinism, the hot path, naming, comments, tests, module purity.** Read it before the
first change, not after the first review. Each rule carries an id so a review can cite one: `SW-NN`
are this project's own, `CC-NN` share their numbering and intent with the sibling kzen project.

These five break something *silently* when missed, so they are worth knowing before you open it:

- **SW-01 Determinism** — RNG injected per slot and forked from the match seed, `SplitMix64` rather
  than `kotlin.random.Random`, no `HashMap`/`HashSet` iteration in `:core` or `:bots`, no wall clock
  below `:ui`.
- **SW-02 Portable arithmetic** — nothing in `:bots` calls `ln`, `exp` or `pow`. `portableLog` and
  `sqrt` are what reproduce bit-for-bit on both targets.
- **SW-03 Hot path** — primitive arrays, no `Sequence`, no `data class`, no `List<Cell>` on a path
  MCTS calls millions of times a turn; mutate-and-undo over allocating a state; search buffers are
  constructor-allocated instance fields.
- **SW-04 Module purity** — the forbidden edges above, `explicitApi()` everywhere, everything outside
  a module's contract `internal`.
- **SW-05 Frozen identifiers** — a released `BotId`, knob name or `Choice` value sits in the replay
  URL of every match somebody shared. Renaming one breaks them all.

Naming, file layout, comment style, fail-fast, test colocation and the rest are in the document.

## Git

Standing rules. They override any default the harness carries.

- **Stage a new source file the moment you create it** — `git add <path>`, nothing more. An untracked
  file is invisible to `git diff`, so a review of the change reads as if it were never written, and a
  stash or a branch switch drops it without a word.
- **Never commit and never push on your own initiative.** Leave the work staged and say so. Committing
  is the user's call, every time — a green build is not permission.
- **A commit message is at most 200 characters**, subject and body together. A change needing more
  explanation than that is explained in the code or in `docs/`, not in the log.
- **No co-author trailer.** No `Co-Authored-By`, no "Generated with", no attribution of any kind. The
  commit is authored by the person who asked for it.

## Commands

```bash
./gradlew build                              # both targets, JVM tests, checkModulePurity, ktlintCheck
./gradlew jvmTest                            # fast inner loop, with breakpoints
./gradlew ktlintFormat                       # fix what the style gate can fix by itself
./gradlew -p build-logic ktlintCheck         # build-logic lints itself; root `check` depends on this
./gradlew allTests -PbrowserTests=true       # browser suite (needs Chrome; off by default)
./gradlew :app:wasmJsBrowserDevelopmentRun   # local dev server — yours. See below before an agent runs this
./gradlew :app:wasmJsBrowserDistribution     # production bundle -> app/build/dist/wasmJs/productionExecutable
./gradlew :lab:run --args="play puct:eval=expert puct:eval=rollout --rounds 40 --budget 40000"
```

`:bots` tests take a couple of minutes because `BotLadderTest` plays several hundred complete matches;
narrow with `--tests` while working on something else. The `[bench]` throughput runs, the `:lab`
entrant grammar, why browser tests are off, and a ktlint trap that survives deleting the offending
file are in [`docs/Workflow.md`](docs/Workflow.md).

### Never background `wasmJsBrowserDevelopmentRun` — serve the distribution instead

The dev server is **not** a child of the `gradlew` you launched: Gradle runs the build inside a
detached **daemon**, and the daemon is what forks the webpack `serve` process. Kill the shell and the
client dies while a webpack server keeps listening on 8080 — in nobody's process tree, and nothing
reaps it. `./gradlew --stop` is **not** the fix; it kills every daemon on the machine, including the
one hosting a dev server somebody is deliberately using.

So an agent that needs to see the app in a browser builds a static bundle and serves it itself:

```bash
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution   # terminates; holds no port
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable      # a direct child, killable by port
```

**8099 is reserved for this** and for nothing else, which is what makes "kill whatever is on 8099"
unambiguous — a human's dev server and an agent's look identical on the command line. Kill it when
finished, and do not rely on the task runner to do it:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8099 |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```
