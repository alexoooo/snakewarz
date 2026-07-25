# Snake Warz

Tron-style snakes game as an **AI testbed**. Snakes move one square per turn on a rectangular grid;
walls and every snake body — including your own — are lethal; the last one moving wins. No food, no
score. Snakes grow at half speed: the tail retracts on alternating turns only.

Originally written in 2005 and imported from the Google Code archive. Currently being rewritten from
Java/Swing into Kotlin/Wasm as a web app.

## Status

Rewrite in progress — **Phase 0 of 6 complete** (build scaffold and deployment pipeline). There is no
playable game in the Kotlin code yet; the page exists to prove the toolchain end to end. First
playable milestone is Phase 3.

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

Modules arrive as their phase lands; `:core` and `:app` exist today.

## Writing a bot

Bots are Kotlin classes compiled into the app. The flow is: fork, add a file, register it, open a PR.
CI runs every registered bot against a shared contract suite — it must never return an illegal move
when a legal one exists, must respect its search budget, and must be deterministic given the same
seed.

```kotlin
class MyBot(private val setup: BotSetup) : Bot {
    override fun chooseMove(turn: Turn): Decision =
        turn.rng.pick(turn.legalMoves)?.let { Decision.Move(it) } ?: Decision.Resign
}
```

This API lands in Phase 2. Matches are deterministic from a seed, so results are reproducible and
replays can be shared as a URL.

## Contributing

Read [CLAUDE.md](CLAUDE.md) first — it documents the conventions, the forbidden dependency edges, and
the three non-obvious engine facts that a rewrite tends to get wrong.
