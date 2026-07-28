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

# And the lab, for the questions a batch answers rather than a test.
./gradlew :lab:run --args="play puct:eval=territory puct:eval=survival --rounds 40 --budget 2000"
./gradlew :lab:run --args="time puct:eval=survival --budget 2000"
./gradlew :lab:run --args="rate --board 12x12 --budget 1000"
./gradlew :lab:run --args="ab uct uct:exploration=2.5"
./gradlew :lab:run --args="report puct --against uct --worst 5"
./gradlew :lab:run --args="tune puct --knobs cpuct,territoryWeight"
```

## The six subcommands, and which question each answers

They are separate because they are separate measurements, and one of them producing a number does not
mean another would have produced the same one.

| | Question | Reads | Writes |
|---|---|---|---|
| `play` | what happened between these bots | — | the match log |
| `time` | what one turn of this bot costs | — | — |
| `rate` | how strong is each, with error bars | the log | — |
| `ab` | **is this change better, and how sure are we** | — | the match log |
| `report` | why is it losing | the log | — |
| `tune` | what should this knob be | — | a journal |

`ab` is the one to reach for when deciding whether to keep a change. `play` gives a matrix, and a
matrix has to be read against a threshold somebody invented; `ab` plays until the evidence settles
and then says which hypothesis it settled on.

`rate` refuses to pool runs that are not comparable — a different board, allowance, openings mode or
**build** is a different measurement — because a log accumulated over weeks would otherwise average
yesterday's bots with today's under one name. `--pool true` overrides it and says so in the output.

## Openings, and the four-distinct-games problem

**A `--openings fixed` batch of a hundred matches can be four games played twenty-five times.**

Spawns do not depend on the seed at all — `mostDistantSpawns` puts two snakes in opposite corners on
every board — and the seed's only other effect on the position is the turn order, which for two slots
has two values. So a pairing of bots that draw no randomness plays at most four distinct games however
many rounds are asked for, and `puct` against `puct` is exactly such a pairing: the flagship
invocation above was, until openings existed, measuring four games and reporting forty.

So `--openings mirrored` is the default: a square drawn from the seed with the opponent at its image
through the centre of the board. Point reflection maps the board onto itself and takes each direction
to its opposite, so neither side of the draw is the better one — two identical bots score exactly half
on every board, which is the test that keeps it honest. `fixed` is kept because the shipped ladder was
measured under it.

**Every batch prints how many of its matches were distinct games.** Read that number before any
other. The two openings modes gave opposite answers to
`play puct:eval=territory puct:eval=survival --rounds 40 --budget 300`: 22-18 to territory over four
distinct games, 15-25 against it over thirty-six.

## The match log

Every `play` and `ab` appends to `.lab/` (gitignored), which is what `rate` and `report` read.

- `runs.tsv` — one row per batch: board, rules, allowance, openings, and a **build fingerprint**
  (`git rev-parse --short HEAD`, plus `+dirty`). An expanded spec pins a bot's settings; nothing else
  pins its code, and pooling across a change averages away the improvement that change was made to
  measure.
- `matches.tsv` — one row per **(match, seat)**, the match's own columns repeated on each. That
  denormalisation is deliberate: a run killed mid-write leaves a line that does not parse and gets
  dropped, rather than a dangling join.
- `replays.tsv` — the encoded move streams, apart because they are an order of magnitude larger and
  only a person opening one match ever reads them. `--replays none` for a sweep.

An entrant is recorded **expanded** — every declared knob at the value it played under — so a log line
keeps its meaning after a default moves. `rate` and `report` shorten it back down for display by
dropping whatever matches the registry's defaults today.

## Deciding whether a change helped

```bash
./gradlew :lab:run --args="ab uct uct:exploration=2.5 --elo0 0 --elo1 10"
```

A sequential test, on **boards** rather than matches: the schedule plays each seed twice with the
seats exchanged, so a board is one observation scored in quarters and its variance is far below twice
a single game's. It stops as soon as the likelihood ratio clears either bound.

Two settings decide what a run costs, and they pull against each other:

- `--elo1` is **how small a gain is worth finding**, and it dominates the cost. The test compares two
  *hypotheses*, so how fast it decides depends on how far apart they are and not on how large the real
  effect is. Bounds of `0..3` need thousands of boards even for a large effect; `0..20` settles in
  tens.
- `--max-pairs` caps it. A run that stops there **says so** rather than reporting the last number it
  happened to hold.

`Sprt.MINIMUM_PAIRS` is forty and is not configurable: the variance is estimated from the same sample
that decides, so a lucky first handful overstates the evidence twice over.

## Tuning a knob

```bash
./gradlew :lab:run --args="tune puct --knobs cpuct --budget 400"
```

Coordinate descent over the ranges the bot itself declares — nothing in the tuner names a bot, a knob
or a value — with each step decided by the same sequential test. A pass that finds nothing halves the
stride, so the same code does the coarse sweep and the polish. Every decision is appended to
`.lab/tune-<slug>.tsv` and replayed rather than re-played on a resume, so an overnight sweep survives
a kill.

Two things about it are load-bearing:

**It confirms on boards it never searched.** A search runs dozens of tests against one set of seeds,
each with its own false-positive rate, and both push the same way — something looks better eventually.
The winner is re-run against the original from a disjoint seed base at a finer bound, and that is the
only number a recommendation carries.

**It never edits a default.** Changing one moves all twelve `GoldenMoveStreamTest` hashes, and a
process that could change both would turn "a golden failure is a question" (SW-01) into a formality.
It prints a recommendation; [`Bots.md`](Bots.md) carries what a person does with it.

**A knob tuned at one allowance is tuned at that allowance.** `tune puct --knobs cpuct --budget 400`
recommends `cpuct=0.5` at +73 Elo, confirmed over 280 fresh boards; the same setting re-tested at the
shipped 1000 measures −19 ±23. Exploration constants trade against search depth, so the confirming
run has to be at the allowance the bot actually ships at.

## Why `:lab` is a module

`Tournament.runToCompletion` had no caller it was written for. `:match` may not see `:bots`, and
`:app` — the only place both meet — is `wasmJs` only, so the answer to "run four hundred matches and
tell me which evaluation is stronger" was to open a browser and watch, which is not an answer. `:lab`
injects `ShippedBots` into a `Tournament` that knows nothing but the `BotRegistry` interface, which
is the inversion `:app` already performs rather than a new edge. The rejected alternative was a
property-gated JVM test in `:bots` hand-rolling the round robin the way `BotLadderTest` does: from
there `Tournament`, `TournamentTable` and `Contestant` are all unreachable, so it would have
re-implemented the win matrix, the seat rotation and the contestant legend to avoid adding a module.

`:lab` is also the one place below `:ui` where a clock is allowed, which is why `time` is a separate
subcommand rather than a column in the matrix: a two-bot match's elapsed time is the **sum** of both
bots' thinking, so a per-contestant figure taken off a shared match is really a figure about the
pairing. `time` seats the subject against an opponent handed no allowance at all and reports the
fastest of several passes.

## Lab entrant syntax

A lab entrant is `<slug>[:name=value,...]`, where `budget` is that entrant's own allowance and every
other name is one of that bot's declared knobs — so one bot enters twice at two configurations, for
the reason [`Match.md`](Match.md) gives for a `Contestant`'s identity being all three of id,
allowance and params. Parsing is **strict**, unlike
`BotKnob.Param.read`: a `main` has something to catch a throw, and a mistyped knob name would
otherwise quietly measure the default and waste however many minutes the batch takes. A `play` of
`uct` against `flat-monte-carlo` reproduces `BotLadderTest`'s conclusion, which is how you tell the
tool is still honest.

Each subcommand declares the options **it** takes, so `--passes` on `rate` is an error rather than a
setting nobody reads. Same argument, one level up.

## Why browser tests are off by default

Browser tests are disabled unless `-PbrowserTests=true`, because Karma startup dominates the runtime
of small suites. Anything provable on the JVM should be proven there instead.

Two things are only provable there, and both are worth the Karma startup in CI. The four pure
modules' `commonTest` suites recompile to wasm, which is what re-runs the golden hashes in a real
browser and is [SW-02](Coding-Standards.md#sw-02--portable-arithmetic-only-in-bots)'s whole purpose.
And `:ui` and `:app` have no other target at all, so their `wasmJsTest` suites — the two clocks, the
hit-test, the labels, the palette, the replay fragment — run here or nowhere:

```bash
./gradlew :ui:wasmJsBrowserTest :app:wasmJsBrowserTest -PbrowserTests=true
```

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
