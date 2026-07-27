# Snake Warz

Tron-style snakes game as an **AI testbed**. Snakes move one square per turn on a rectangular grid;
walls and every snake body — including your own — are lethal; the last one moving wins. No food, no
score. Snakes grow at half speed: the tail retracts on alternating turns only.

Originally written in 2005 and imported from the Google Code archive, and rewritten from Java/Swing
into Kotlin/Wasm as a web app.

## Status

**The rewrite is done, and there is now something worth losing to.** Play against
the shipped bots with the arrow keys, or sit out and watch up to four of them fight; pause, step a
turn at a time, change the speed, scrub back through a finished match, and share the whole thing as a
link. A 160-turn duel is 129 characters of URL, and no server is involved at any point.

Seven of the ten bots are a ladder, weakest first, and each rung beats the one below it over twenty
matches:

| Bot | How it plays |
|---|---|
| Random | Uniformly among the moves that do not kill it |
| Wall Hugger | Straight while it can, then left, then right |
| Space Filler | Flood-fills each way and takes the side with the most room |
| Pressure | Room first, then crowds an opponent with what is left over |
| Chaser | Walks the shortest path to the nearest opponent, then hands over to Pressure |
| Flat Monte Carlo | Plays each move out to the end at random, many times, and takes the best |
| UCT | Monte Carlo tree search with UCB1 |

Two more were contributed to the original 2005 project and are not rungs — they are here for what
they are, and they play the same contract suite as everything else:

| Bot | How it plays |
|---|---|
| Burnin Hell | First open direction, always north, south, east, west — which comes out as a serpentine sweep of the board |
| Tom Snake | Pressure one turn in five, Random the other four |

And one is experimental:

| Bot | How it plays |
|---|---|
| PUCT | AlphaZero's tree search with a hand-written appraisal of the position where the neural network would be |

PUCT is not a rung because it has not earned one: measured over forty rounds a pairing it is ahead of
UCT at an equal allowance and only level with it per unit of *time*, and until those two readings
agree it makes no claim a rung would make. Its `Evaluation` setting is the interesting part —
`expert` is the hand-written appraisal, `rollout` makes it judge a leaf exactly as UCT does, and
`mobility` is a near-free reading that gets the same search for a fraction of the clock. Setting two
seats to the same bot at two evaluations and running a tournament is how those numbers were arrived
at.

## Settings

A bot with a real choice to offer says so, and the sidebar offers it: pick UCT in a slot, open
**Settings** under it, and there is its search allowance and its exploration constant. Each seat is
configured on its own, so one bot can play another copy of itself set up differently. Nothing about
this is hard-coded in the page — the rows come off the same registry the pickers do, so a contributed
bot's settings appear by declaring them and nothing else.

The bar for appearing there is deliberately high: a **tradeoff**, meaning several values are valid and
each plays visibly differently, rather than a number a sweep settles better than you can. A bot's other
tunables are still declared and still reachable — `:lab` sweeps them and a replay link carries them —
they just do not take up a row in front of somebody with no way to judge them.

The allowance is counted in **evaluations** — rollouts, appraisals, tree iterations — rather than in
milliseconds, which is what keeps a match reproducible on any machine and what makes one number mean
the same amount of search to bots that do quite different things with it. Everything you change
travels in the replay link, so a shared match says what it was played under and opens one click away
from a rematch under the same conditions.

## Tournaments

"Is this bot better than that one" is a question about a few hundred matches, not about one, and the
engine runs millions of turns a second — so asking it properly is nearly free. Pick two to four bots
in the sidebar, choose how many rounds a pairing, and press **Run tournament**: every pair meets over
that many matches, each seed played from both seats so that acting first is not a free point, and the
win-rate matrix fills in as it goes.

```
        | wallhug |  random |   space |   score
wallhug |       - |       7 |       4 |     55%
random  |       3 |       - |       2 |     25%
space   |       6 |       8 |       - |     70%
```

A contestant is a *configured* seat rather than just a bot, so the same bot may enter twice at two
settings — which is the question this whole thing exists to answer:

```
        |     uct | uct@100 |   score
uct     |       - |       7 |     70%
uct@100 |       3 |       - |     30%

uct@100   budget=100
```

It runs on the animation frame in slices of a few milliseconds, so the page stays responsive
throughout and the board shows whichever match the batch is currently on. No server, no worker, and
nothing to install.

[CLAUDE.md](CLAUDE.md) is the map of the design — the module graph, the forbidden dependency edges
and the handful of rules that are easy to get subtly wrong — and it routes to a file per audience
under [docs/](docs). The measurements behind the tuning constants live beside the constants
themselves; [docs/Bots.md](docs/Bots.md) says which is where.

The original Java implementation is at the `legacy-java-final` git tag —
`git show legacy-java-final:src/main/java/ao/…`. It was deleted from the working tree once the port
was complete.

## Building

Needs a JDK 17–26 on `PATH`. Everything else, including the Kotlin and Node toolchains, is fetched by
the Gradle wrapper.

```bash
# compiles wasmJs + jvm, runs JVM tests, checks module purity
./gradlew build

# inner loop, with IDE breakpoints
./gradlew jvmTest

# local dev server with hot reload
./gradlew :app:wasmJsBrowserDevelopmentRun
```

Most modules test in seconds. `:bots` takes a couple of minutes, because the tests that claim one bot
is stronger than another play several hundred complete matches to say so.

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

Seven modules with strictly enforced layering. `:core` has no project dependencies at all — the engine
does not know that bots exist — and the four pure modules cannot reference the DOM. That is checked
by the build (`checkModulePurity`), not by convention, because it is what keeps tests runnable on the
JVM and keeps a Kotlin/JS fallback target a config change rather than a rewrite.

| Module | Responsibility |
|---|---|
| `:core` | Grid, occupancy, rules, state transition, PRNG. Pure Kotlin |
| `:bot-api` | The contract bot authors implement |
| `:bots` | Shipped bots and the registry |
| `:match` | Turn sequencing, human input, replay codec, stats, tournaments. No time, no DOM |
| `:ui` | Canvas renderer, DOM chrome, frame schedulers |
| `:app` | Entry point and wiring |
| `:lab` | A JVM command line for running batches headlessly. Not shipped, and nothing depends on it |

Time lives only in `:ui` and `:lab`: a bot is handed a budget counted in evaluations and has no way to
reach a clock, so a match reproduces by construction rather than by discipline. `:lab` sits outside
the shipped graph precisely so that reporting how long a batch took cannot put a clock inside it.

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
allocating. Asking for one is what spends the turn's allowance, and one the allowance will not
stretch to comes back already over, so a search loop stops on its own rather than on trust.

That is the whole of it: no HTML to edit, because the pickers in the sidebar are built from the
registry. Matches are deterministic from a seed, so results are reproducible and a whole game fits in
a URL.

## Contributing

Read [CLAUDE.md](CLAUDE.md) first — it documents the module graph, the forbidden dependency edges,
and the four non-obvious engine facts that a rewrite tends to get wrong, and it routes to the rest of
[`docs/`](docs): the rules a change is reviewed against, and one file per module.
