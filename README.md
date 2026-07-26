# Snake Warz

Tron-style snakes game as an **AI testbed**. Snakes move one square per turn on a rectangular grid;
walls and every snake body — including your own — are lethal; the last one moving wins. No food, no
score. Snakes grow at half speed: the tail retracts on alternating turns only.

Originally written in 2005 and imported from the Google Code archive. Currently being rewritten from
Java/Swing into Kotlin/Wasm as a web app.

## Status

Rewrite in progress — **Phase 4 of 6 complete, and there is now something worth losing to**. Play
against the shipped bots with the arrow keys, or sit out and watch up to four of them fight; pause,
step a turn at a time, change the speed, scrub back through a finished match, and share the whole
thing as a link. A 165-turn three-way game is 131 characters of URL, and no server is involved at
any point.

The roster is a ladder, weakest first, and each rung beats the one below it over twenty matches:

| Bot | How it plays |
|---|---|
| Random | Uniformly among the moves that do not kill it |
| Wall Hugger | Straight while it can, then left, then right |
| Space Filler | Flood-fills each way and takes the side with the most room |
| Pressure | Room first, then crowds an opponent with what is left over |
| Chaser | Walks the shortest path to the nearest opponent, then hands over to Pressure |
| Flat Monte Carlo | Plays each move out to the end at random, many times, and takes the best |
| UCT | Monte Carlo tree search with UCB1 |

Contributed bots are Phase 5; batch tournaments are Phase 6.

See [docs/MIGRATION.md](docs/MIGRATION.md) for the design and the phase plan.

The original Java implementation is preserved in `legacy/java/` for reference during porting, and at
the `legacy-java-final` git tag. It is not part of the build and will be deleted at release 1.

## Building

Needs a JDK 17–26 on `PATH`. Everything else, including the Kotlin and Node toolchains, is fetched by
the Gradle wrapper.

```bash
./gradlew build          # compiles wasmJs + jvm, runs JVM tests, checks module purity
./gradlew jvmTest        # fast inner loop, with IDE breakpoints
./gradlew :app:wasmJsBrowserDevelopmentRun   # local dev server with hot reload
```

The `wasmJs` target is what ships. The `jvm` target exists **only** so tests run in milliseconds with
a debugger instead of seconds in headless Chrome; it is never deployed.

Browser tests need a real Chrome and are off by default:

```bash
./gradlew allTests -PbrowserTests=true
```

## Deploying

CI builds `app/build/dist/wasmJs/productionExecutable` and publishes it to GitHub Pages on every push
to `master`. The workflow fails the build if the gzipped transfer size exceeds its budget.

To enable it on a fresh clone or fork: **Settings → Pages → Source → GitHub Actions**.

The game needs WebAssembly with garbage collection — Chrome 119+, Firefox 120+, or Safari 18.2+.
Older browsers get an explicit message rather than a blank page.

## Architecture

Six modules with strictly enforced layering. `:core` has no project dependencies at all — the engine
does not know that bots exist — and the four pure modules cannot reference the DOM. That is checked
by the build (`checkModulePurity`), not by convention, because it is what keeps tests runnable on the
JVM and keeps a Kotlin/JS fallback target a config change rather than a rewrite.

| Module | Responsibility |
|---|---|
| `:core` | Grid, occupancy, rules, state transition, PRNG. Pure Kotlin |
| `:bot-api` | The contract bot authors implement |
| `:bots` | Shipped bots and the registry |
| `:match` | Turn sequencing, human input, replay codec, stats. No time, no DOM |
| `:ui` | Canvas renderer, DOM chrome, frame scheduler |
| `:app` | Entry point and wiring |

All six exist. Time lives only in `:ui`: a bot is handed a budget counted in iterations and has no
way to reach a clock, so a match reproduces by construction rather than by discipline.

## Writing a bot

Bots are Kotlin classes compiled into the app. The flow is: fork, add a file, register it, open a PR.
CI runs every registered bot against a shared contract suite — it must never return an illegal move
when a legal one exists, must respect its search budget, must be deterministic given the same seed,
and must keep nothing from one match to the next.

```kotlin
class MyBot(setup: BotSetup) : Bot {
    private val rng = setup.rng

    override fun chooseMove(turn: Turn): Decision =
        Decision.Move(rng.pick(turn.legalMoves) ?: Direction.NORTH)
}
```

Then one line in `bots/src/commonMain/kotlin/ao/snakewarz/bots/ShippedBots.kt`:

```kotlin
register("my-bot", "My Bot", ::MyBot)
```

A bot instance lives for a whole match, so a search tree is just an instance field. To explore moves,
take `turn.scratch.playout()` — a private copy of the board that plays forward and unwinds without
allocating, and which stops on its own when the turn's budget runs out.

That is the whole of it: no HTML to edit, because the pickers in the sidebar are built from the
registry. Matches are deterministic from a seed, so results are reproducible and a whole game fits in
a URL.

## Contributing

Read [CLAUDE.md](CLAUDE.md) first — it documents the conventions, the forbidden dependency edges, and
the four non-obvious engine facts that a rewrite tends to get wrong.
