# Snake Warz

Tron-style snakes game as an **AI testbed**. Snakes move one square per turn on a rectangular grid;
walls and every snake body — including your own — are lethal; the last one moving wins. No food, no
score. Snakes grow at half speed: the tail retracts on alternating turns only.

Originally written in 2005 and imported from the Google Code archive. Currently being rewritten from
Java/Swing into Kotlin/Wasm as a web app.

## Status

Rewrite in progress — **Phase 2 of 6 complete**. The engine, the bot contract, the match driver and
the replay codec are all in place: matches run headless, reproduce exactly from a seed, and encode
into a shareable URL. There is no game UI yet, so the deployed page is still the toolchain sanity
check. First playable milestone is Phase 3.

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
| `:match` | Turn sequencing, replay codec, stats. No time, no DOM |
| `:ui` | Canvas renderer, DOM chrome, frame scheduler |
| `:app` | Entry point and wiring |

Modules arrive as their phase lands; everything but `:ui` exists today.

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

Matches are deterministic from a seed, so results are reproducible and a whole game fits in a URL.

## Contributing

Read [CLAUDE.md](CLAUDE.md) first — it documents the conventions, the forbidden dependency edges, and
the three non-obvious engine facts that a rewrite tends to get wrong.
