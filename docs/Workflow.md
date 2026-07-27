# Workflow

**For:** running a build, a benchmark or `:lab`, and for when a build task behaves strangely.
**Assumes:** [`../CLAUDE.md`](../CLAUDE.md) — it carries the short command list and the one hazard
you have to know before you type a command you already think you know: never background
`wasmJsBrowserDevelopmentRun`. The full reasoning for that is below.

## Commands

```bash
./gradlew build                              # both targets, JVM tests, checkModulePurity, ktlintCheck
./gradlew jvmTest                            # fast inner loop, with breakpoints
./gradlew ktlintFormat                       # fix what the style gate can fix by itself
./gradlew -p build-logic ktlintCheck         # build-logic lints itself; root `check` depends on this
./gradlew allTests -PbrowserTests=true       # browser suite (needs Chrome; off by default)
./gradlew :app:wasmJsBrowserDevelopmentRun   # local dev server — yours. See below before an agent runs this
./gradlew :app:wasmJsBrowserDistribution     # production bundle -> app/build/dist/wasmJs/productionExecutable

# The measuring instruments. Both print `[bench]` lines and run on either target.
./gradlew :bots:jvmTest --tests '*ThroughputTest*' -i | grep '\[bench\]'
./gradlew :bots:wasmJsBrowserTest -PbrowserTests=true --rerun -i | grep '\[bench\]'

# And the lab, for the questions a batch answers rather than a test. `play` prints the same win
# matrix the sidebar does; `time` costs one bot's turn against an opponent handed no allowance.
./gradlew :lab:run --args="play puct:eval=expert puct:eval=rollout --rounds 40 --budget 40000"
./gradlew :lab:run --args="time puct:eval=expert --budget 40000"
```

## Lab entrant syntax

A lab entrant is `<slug>[:name=value,...]`, where `budget` is that entrant's own allowance and every
other name is one of that bot's declared knobs — so one bot enters twice at two configurations, which
is the question a testbed of search bots exists to answer. Parsing is **strict**, unlike
`BotKnob.Param.read`: a `main` has something to catch a throw, and a mistyped knob name would
otherwise quietly measure the default and waste however many minutes the batch takes. A `play` of
`uct` against `flat-monte-carlo` reproduces `BotLadderTest`'s conclusion, which is how you tell the
tool is still honest.

## Why browser tests are off by default

Browser tests are disabled unless `-PbrowserTests=true`, because Karma startup dominates the runtime
of small suites. Anything provable on the JVM should be proven there instead.

## What each suite costs

Prefer `jvmTest` while developing; most modules answer in seconds. **`:bots` does not** — it is a
couple of minutes, because `BotLadderTest` and `RolloutTruncationTest` play several hundred complete
matches with a search bot in them, which is the point of both. Narrow with `--tests` while working on
something else. A full cold `build` takes several minutes; the wasm toolchain is slow to warm up.

## The ktlint deletion trap

**A ktlint failure you fix by *deleting* the offending file can survive the fix.** The check task
compares against its last *successful* run, so removing a file restores the inputs it already knows
and it reports the old violation again without looking. Fixing the code in place is fine; deleting
needs `--rerun-tasks` on that module. It is a ktlint-gradle behaviour, not something this build
configures, and the build cache usually hides it.

## Why the dev server must not be backgrounded

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
