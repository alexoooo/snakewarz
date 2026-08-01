# Repository guidance

Durable, agent-neutral instructions for working in this repository. Claude Code reads the thin
`CLAUDE.md` compatibility file, which imports this file.

## What this project is

**snakewarz** is a Tron-style snakes game and an **AI testbed**. Snakes move one cell per turn; walls
and every snake body are lethal; the last snake moving wins. There is no food or score. The point of
the project is its pluggable AI: writing search bots and measuring them against each other.

The Kotlin/Wasm rewrite and Releases 1–3 are complete. Nothing here is a stub waiting to be filled.
Read [`docs/plans/README.md`](docs/plans/README.md) for settled product decisions, not for an open
backlog, and check the tree rather than assuming another component exists.

## Before you touch anything

This file carries repository-wide constraints. The detailed design lives under `docs/`, and the
specialized tripwires live in module-level `AGENTS.md` files. A Codex session launched at the
repository root does not automatically load instructions below that directory: **before changing a
module, explicitly read its `AGENTS.md` when one exists.**

| About to… | Read first |
|---|---|
| write any code | [`docs/Coding-Standards.md`](docs/Coding-Standards.md) |
| add or change a bot | [`bots/AGENTS.md`](bots/AGENTS.md) and [`docs/Bots.md`](docs/Bots.md) |
| touch `bot-api/` | [`bot-api/AGENTS.md`](bot-api/AGENTS.md) and [`docs/Bots.md`](docs/Bots.md) |
| touch `match/`, including human input, stats or tournaments | [`match/AGENTS.md`](match/AGENTS.md) and [`docs/Match.md`](docs/Match.md) |
| add a map shape or touch `match/map/` | [`match/AGENTS.md`](match/AGENTS.md) and [`docs/Maps.md`](docs/Maps.md) |
| touch `ui/` | [`ui/AGENTS.md`](ui/AGENTS.md) and [`docs/UI.md`](docs/UI.md) |
| touch `app/`, `index.html`, `styles.css`, the boot path or Pages | [`app/AGENTS.md`](app/AGENTS.md) and [`docs/UI.md`](docs/UI.md) |
| run a build, benchmark or `:lab` command | [`lab/AGENTS.md`](lab/AGENTS.md) and [`docs/Workflow.md`](docs/Workflow.md) |
| measure whether a change helped or tune a knob | [`docs/Workflow.md`](docs/Workflow.md#deciding-whether-a-change-helped) |
| plan or run a research phase | [`docs/Research-Process.md`](docs/Research-Process.md) |
| compare against the pre-rewrite Java | [`docs/Legacy.md`](docs/Legacy.md) |
| ask why a shipped feature has its current shape | [`docs/plans/README.md`](docs/plans/README.md) |

## Technology and module graph

Kotlin 2.4.10, Gradle KTS, a `wasmJs` browser deployment, Canvas 2D rendering, and hand-written
HTML/CSS. This deliberately does not use Compose Multiplatform. Bots are Kotlin classes compiled
into the app and registered through an explicit `BotRegistry`.

| Module | Responsibility | May depend on | Targets |
|---|---|---|---|
| `:core` | Grid, occupancy, bodies, rules, transitions, PRNG, budget | stdlib only | wasmJs + jvm |
| `:bot-api` | Small, stable bot contract, registry interfaces, knobs, scratch arena | `:core` | wasmJs + jvm |
| `:bots` | Shipped bots and the `BotRegistry` implementation | `:core`, `:bot-api` | wasmJs + jvm |
| `:match` | Turn sequencing, human input, replay, stats, maps, tournaments, gauntlet | `:core`, `:bot-api` | wasmJs + jvm |
| `:ui` | Canvas renderer, DOM chrome, scheduler | `:core`, `:match`, `kotlinx-browser` | wasmJs |
| `:app` | `main()`, dependency injection, URL routing, deployed resources | all app modules | wasmJs |
| `:lab` | Headless JVM measurement and training CLI; nothing depends on it | `:core`, `:bot-api`, `:bots`, `:match` | jvm |
| `build-logic/` | Convention plugins and module-purity enforcement | build only | jvm |

The pure modules have a JVM target only so tests run quickly and prove that the code stays
platform-free. Modules are architectural fences; packages organize code inside those fences.

## Forbidden dependency edges

These constraints are load-bearing and enforced by `checkModulePurity`. Do not add an edge even
temporarily.

- `:core` must never depend on another project module, including `:bot-api`.
- `:core`, `:bot-api`, `:bots`, and `:match` must not reference `kotlinx-browser`, `org.w3c.*`, or
  another wasm-only API.
- `:match` must not depend on `:bots`; it resolves the `BotRegistry` interface and `:app` injects the
  implementation.
- `:bots` must not depend on `:match`, `:ui`, or `:app`.
- `:ui` must not depend on `:bots`; it names slots and portraits through interfaces and slugs.
- Nothing may depend on `:lab`, and `:lab` must not see `:ui` or `:app`.

Use `internal` for everything outside a module's deliberate contract. `explicitApi()` is enabled.

## Non-obvious game invariants

These can break determinism or change the game without producing an obvious compile failure.

1. **Snakes grow at half speed.** `SnakeImpl.advance` alternates tail retraction, so lengths are
   `1, 1, 2, 2, 3, 3…`. This is `RulesConfig.growEveryNthMove = 2`, not an artifact.
2. **`Bot.chooseMove` is synchronous and must not become `suspend`.** Bots simulate complete games
   inside their turn; the human returns `Decision.Pending` and is polled by the driver.
3. **Replays record the move stream.** Seed-only playback is insufficient because floating-point
   search arithmetic can vary across platforms. In `:bots`, use `portableLog` and `sqrt`; do not call
   `ln`, `exp`, or `pow`.
4. **Legality is evaluated before the tail retracts.** A snake cannot enter the cell its own tail is
   about to leave.
5. **Interior walls use the border ring's `WALL` byte.** Consequently:
   - `Occupancy.clear()` must preserve walls.
   - `Occupancy.hash` excludes walls, while `Board.hash` includes the map's `wallKey`.
   - evaluations normalize by `BoardView.openCount`, not `Grid.playableCount`.
   - logic that needs to observe a wall must ask `BoardView`; row/column geometry cannot see one.

## Coding standards

[`docs/Coding-Standards.md`](docs/Coding-Standards.md) is the review rule set. Read it before the first
change. The highest-risk rules are:

- **SW-01 Determinism:** inject and fork `SplitMix64`; do not use `kotlin.random.Random`, unordered
  collection iteration in engine/search code, or a wall clock below `:ui`.
- **SW-02 Portable arithmetic:** no `ln`, `exp`, or `pow` in `:bots`; golden hashes must reproduce on
  the JVM and in Chrome.
- **SW-03 Hot paths:** avoid allocation in search loops; prefer primitive arrays, preallocated
  buffers, and mutate-and-undo.
- **SW-04 Module purity:** preserve the dependency fences and public API boundaries.
- **SW-05 Frozen identifiers:** released `BotId` values, knob names, `Choice` values, and gauntlet
  indices are persistent replay/progress data and must not be renamed.

Follow the documented naming, comments, fail-fast, test-colocation, file-layout, and formatting rules.
Formatting is four spaces, 120 columns, LF, final newline, and no star imports.

## Git

These rules override agent defaults.

- Stage a new source file as soon as it is created with `git add <path>`, and nothing broader.
- Never commit or push unless the user explicitly asks. A green build is not permission. Leave
  completed work staged and report that state to the user.
- Keep the entire commit message at or below 200 characters.
- Do not add co-author, generated-by, or other attribution trailers.
- Preserve unrelated user changes in a dirty worktree.

## Commands

Commands below use the POSIX/Git Bash wrapper spelling. In native PowerShell, replace `./gradlew`
with `.\gradlew.bat`.

```bash
./gradlew build                              # wasm + JVM, tests, purity and ktlint
./gradlew jvmTest                            # fast inner loop
./gradlew ktlintFormat                       # format application modules
./gradlew -p build-logic ktlintCheck         # build-logic lints itself
./gradlew allTests -PbrowserTests=true       # Chrome suite; off by default
./gradlew :app:wasmJsBrowserDistribution     # production bundle
./gradlew :lab:run --args="play puct:eval=territory puct:eval=survival --rounds 40 --budget 2000"
./gradlew :lab:run --args="ab uct uct:exploration=2.5"
./gradlew :lab:run --args="rate"
./gradlew :lab:run --args="spsa puct --knobs cpuct"
./gradlew :lab:run --args="play uct puct --map arena"
./gradlew :lab:run --args="gauntlet --rounds 200"
```

Read the distinct-games line before interpreting any batch. Under fixed openings, deterministic bots
can replay only four distinct games regardless of the requested round count. A map is a different
game, not merely a harder variant, and results from different wall layouts must not pool.

`:bots` tests take minutes. While iterating elsewhere, scope `--tests` to the module that owns the
test, for example:

```bash
./gradlew :match:jvmTest --tests "*Gauntlet*"
```

A root `jvmTest --tests` applies the filter to every module and fails where no matching test exists.
`:lab` uses `test`, while `:ui` and `:app` require browser tests. If a ktlint violation remains after
deleting its file, rerun that module's check with `--rerun-tasks`; ktlint may otherwise reuse the last
failed result.

## Browser inspection

Never background `wasmJsBrowserDevelopmentRun`: webpack is forked by a detached Gradle daemon and can
leave an orphan server. Build a static development distribution and serve it as a process you own:

```bash
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

Port 8099 is reserved for agent inspection. Kill that server when finished; do not use
`./gradlew --stop`, which can terminate unrelated Gradle daemons.
