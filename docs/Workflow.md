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
./gradlew :lab:run --args="report puct:eval=horizon --against uct --worst 5"
./gradlew :lab:run --args="phases puct:eval=learned --log .lab/rave-field"
./gradlew :lab:run --args="tune puct --knobs cpuct,territoryWeight"
./gradlew :lab:run --args="spsa puct:eval=chamber --knobs parityWeight,sealPenalty --budget 1000"
./gradlew :lab:run --args="train --rows 12 --cols 12 --hidden 16 --epochs 60"
./gradlew :lab:run --args="ladder --rounds 200"

# And the same questions asked on a board with walls in it.
./gradlew :lab:run --args="play uct puct --map cross --rows 12 --cols 12 --rounds 100"
./gradlew :lab:run --args="rate --board 12x12 --budget 1000 --map cross"
```

## The ten subcommands, and which question each answers

They are separate because they are separate measurements, and one of them producing a number does not
mean another would have produced the same one.

| | Question | Reads | Writes |
|---|---|---|---|
| `play` | what happened between these bots | — | the match log |
| `time` | what one turn of this bot costs | — | — |
| `rate` | how strong is each, with error bars | the log | — |
| `ab` | **is this change better, and how sure are we** | — | the match log |
| `report` | why is it losing | the log | — |
| `phases` | **when** is it losing — before the board splits, or after | the log's replays | — |
| `tune` | what should this knob be, up to about three of them | — | a journal |
| `spsa` | what should these ten knobs be | — | a journal |
| `train` | **what should this value function's weights be** | the log's replays | a literal, on stdout |
| `ladder` | **do the single-player levels actually get harder** | — | the match log |

`ladder` is the only one that takes no board: every match it plays comes off a `Ladder` level, so the
ten rows are ten different geometries and one fixed reference. Nothing else in the list can answer it
— `rate` refuses to pool ten geometries, correctly, and `ab` compares two entrants on one board where
two adjacent levels never share one. [`Bots.md`](Bots.md) carries the shipped run.

`ab` is the first one to reach for when deciding whether to keep a change. `play` gives a matrix, and
a matrix has to be read against a threshold somebody invented; `ab` plays until the evidence settles
and then says which hypothesis it settled on. It has one blind spot, it is not a rare one, and
[below](#ab-measures-what-two-entrants-do-to-each-other-which-is-not-always-the-change) is what it
looks like and what to do instead.

`rate` refuses to pool runs that are not comparable — a different board, allowance, openings mode,
**map** or **build** is a different measurement — because a log accumulated over weeks would otherwise
average yesterday's bots with today's under one name. `--pool true` overrides it and says so in the
output.

## Boards with walls in them

`--map <shape>` draws interior walls, **one map for the whole run**, at that run's own
`--rows`/`--cols` and `--seed`. It is on every subcommand that plays anything:

| | takes `--map` | takes `--density` |
|---|---|---|
| `play`, `time`, `ab`, `tune`, `spsa` | yes | yes |
| `rate` | **as a filter** | — |
| `report`, `phases`, `train` | no — they read the log | — |
| `ladder` | no — every board comes off the level | — |

Four things about the flag are decisions rather than plumbing:

- **`--map empty` is the default and draws nothing**, so a run that names it is byte-identical to a
  run that says nothing. That is what lets every command in this document and every batch already in
  the log keep its meaning.
- **One map per run, not one per match.** A batch is then a comparison *on* a board rather than a
  comparison *across* boards. `--map scatter` is the one shape that reads the seed, so it is
  reproducible from the command line alone and two seeds are two different scatterings.
- **`--density` needs `--map`**, because a density with no map behind it draws nothing and would read
  back as a setting that took effect. Only `scatter` reads one; every other shape is a function of the
  geometry.
- **A shape refuses a board too small to express it**, by name, rather than drawing a degenerate
  version — a cross with no arms is a bug in the game, not a small cross. `MapShape.minimumSide` is
  the number, and [`Maps.md`](Maps.md) is the catalogue.

### Fairness on a new map, before any strength number taken on it

An asymmetric map turns seat advantage into map advantage, and a field run on one measures the map. So
the standing question before a shape's first strength number is *is this board worth points on its
own* — and the answer at two seats is **settled by construction rather than by a batch**:

- `generateMap` mirrors every placement through the half turn `ρ(row, col) = (rows-1-row, cols-1-col)`
  and then **asserts** the result — `checkSymmetric` that ρ maps the wall set onto itself, and
  `checkEndsPair` that the lowest and the highest open squares are each other's image.
- `mostDistantSpawns` seats slot 0 at the lowest open square and slot 1 at the highest. Those two
  sentences are the same sentence: the two seats are exact images, so the opening is fair.
- `GenerateMapTest` sweeps every shape at every size it can be drawn at, so **adding a shape to the
  enum is what enrols it**. A shape that breaks either property fails the build rather than producing
  a batch somebody has to interpret.

**A null-strength `play` at two seats cannot check this and is not worth running.** Two things stop
it. `play` refuses two identical entrants outright — *"which measures the seating and nothing else"* —
so the pairing has to be spelled differently to be accepted at all; and under `HEAD_TO_HEAD` every
seed is played from both seats, so two entrants that play alike split every board by construction and
the matrix reads 50/50 whatever the map is doing. There is no seat column in `play`'s output to read
instead.

**What construction does not cover, and what to do about each:**

| case | why ρ says nothing | what to do |
|---|---|---|
| a hand-drawn `BoardMap.of(...)` fixture | `BoardMap` demands ascending and in-range and nothing more | draw it symmetric, or do not quote a strength number off it |
| a map decoded from somebody's replay | it is whatever they played on | same |
| **three seats or more** | slots 2 and up come from the scored branch, which ρ constrains not at all | `--openings mirrored`, which is the default, and read [Measuring at three seats](#measuring-at-three-seats) before the number |

The precedent for taking that last row seriously is `Openings.FIXED` at three seats, where three
identical entrants scored **83.4 / 0.05 / 16.6%** by seat — a seat there is worth more than any bot in
this repository.

### A map is a different game, not a harder one

**A map changes which bot is stronger, not merely by how much.** This is the single most expensive
thing to rediscover here, and it is not a small effect:

- On `cross` the top pair **inverts** — `uct` finishes above `puct` — and the whole field compresses
  from **979 Elo to 479**. `wallhug` gains about **400 points**, because a map that gives a room
  filler rooms to fill is a different question from the one an empty rectangle asks.
- On `double-spiral` at 16x16, **`puct@250` beats `puct@1000` 77–23**, and loses **23–77** to it on an
  empty board of the same size. More search is *worse* there: the corridor turns the game into a pure
  filling race, and a deeper search finds no more of one.

Two consequences follow, and both are worth writing on the wall:

1. **Every empty-board measurement taken before Release 2 is an empty-board measurement.** Nothing in
   the ladder, in `docs/Bots.md`'s tables or in a closed research agenda is a claim about a map. They
   are not wrong; they are conditioned on a board nobody has to play on any more.
2. **`RunHeader.comparabilityKey` carries the map**, derived from the walls and never from a shape
   name, and `rate` refuses to pool across it. Do not reach for `--pool true` to make two maps average
   — the average of an inversion is a number describing neither board.

## Openings, and the four-distinct-games problem

**A `--openings fixed` batch of a hundred matches can be four games played twenty-five times.**

Spawns do not depend on the seed at all — `mostDistantSpawns` seats slot 0 at the lowest open square
and slot 1 at the highest, which on a bare board is two opposite corners — and the seed's only other
effect on the position is the turn order, which for two slots has two values. So a pairing of bots that draw no randomness plays at most four distinct games however
many rounds are asked for, and `puct` against `puct` is exactly such a pairing: the flagship
invocation above was, until openings existed, measuring four games and reporting forty.

So `--openings mirrored` is the default: a square drawn from the seed with the opponent at its image
through the centre of the board. Point reflection maps the board onto itself and takes each direction
to its opposite, so neither side of the draw is the better one — two identical bots score exactly half
on every board, which is the test that keeps it honest. `fixed` is kept because the shipped ladder was
measured under it.

**A map does not add a distinct game, and it is easy to assume it does.** `--map` is a function of the
geometry and the run's own seed, so every match of a batch is played behind *the same* walls; the
spawns are still the two ends of the board, and the turn order still has two values. Four games on
`empty` is four games on `double-spiral`. Where this bites hardest is the ladder's lower rungs, which
are **entirely** bots that draw no randomness — `random` excepted, five of the ten — so a `ladder` run
whose reference is also deterministic measures four games per level however many rounds it is given.
The shipped run in [`Bots.md`](Bots.md) uses `uct@100` for exactly that reason, and reports 200 of 200
distinct games on nine of its ten rows.

**Every batch prints how many of its matches were distinct games.** Read that number before any
other. The two openings modes gave opposite answers to
`play puct:eval=territory puct:eval=survival --rounds 40 --budget 300`: 22-18 to territory over four
distinct games, 15-25 against it over thirty-six.

## The match log

Every `play` and `ab` appends to `.lab/` (gitignored), which is what `rate` and `report` read.

- `runs.tsv` — one row per batch: board, rules, allowance, openings, **the map**, and a **build
  fingerprint** (`git rev-parse --short HEAD`, plus `+dirty`). The map is a fingerprint of the wall
  squares — `empty`, or `40w1a2b3c4d` — and **never a shape name**, so a header cannot keep saying
  `cross` after the generator was redrawn while every rating fitted across the change silently pools
  two different games. `--map cross` narrows by *redrawing* cross at each run's own geometry and
  seed and comparing keys, which is why a run played on the old cross stops matching rather than
  being pooled under a name that no longer describes it. `rate` prints the shape's slug beside the
  key wherever the walls still reproduce it, and names the map on every summary line, `empty`
  included. An expanded spec pins a bot's settings; nothing else pins its code, and pooling across a
  change averages away the improvement that change was made to measure.
- `matches.tsv` — one row per **(match, seat)**, the match's own columns repeated on each. That
  denormalisation is deliberate: a run killed mid-write leaves a line that does not parse and gets
  dropped, rather than a dangling join.
- `replays.tsv` — the encoded move streams, apart because they are an order of magnitude larger and
  only a person opening one match ever reads them. `--replays none` for a sweep.

An entrant is recorded **expanded** — every declared knob at the value it played under — so a log line
keeps its meaning after a default moves. `rate` and `report` shorten it back down for display by
dropping whatever matches the registry's defaults today.

> **Known: `rate` dies with `Cannot round NaN value` when shortening collapses a field to one
> entrant.** Two specs that differ only in ways that shorten away — `uct` beside `uct:budget=1000` at
> a `--budget 1000` run, which is the null-strength pairing `play` will accept — log as two entrants
> and rate as one, and a one-entrant field has no opponent to fit against. The line above the crash
> says `1 entrants`, which is the tell. Nothing is corrupt; the batch is in the log and `report` reads
> it. Use `--log` to give such a batch a directory of its own, or read the matrix `play` already
> printed. It is not about maps — it reproduces on `--map empty`.

### Naming one back

`report` takes a **subset**, not a prefix: a slug plus however many `name=value` pairs it takes to
pick one entrant, in any order. `puct:eval=horizon` finds the entrant playing that evaluation
whatever else it was set to, and values compare as numbers, so `cpuct=1.5` finds `cpuct=1.50`. A name
that could mean several fails and lists them.

```bash
./gradlew :lab:run --args="report puct:eval=horizon --against puct:eval=survival --worst 5"
```

Prefix matching looked equivalent and was not. It forced the bot's *declaration order* on the reader,
so only the last knob in the list could ever be named on its own — which is why P2 had to append its
new knob to keep existing commands working. It also cut inside a value, so `budget=10` silently
selected `budget=1000`. Pasting a whole logged spec back still works and always did.

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

### `ab` measures what two entrants do *to each other*, which is not always the change

A head-to-head test can only see a change that alters the game between the two bots in it, and plenty
of real improvements do not. `ChaseBot.ROOM_SHARE` is the worked example, and the two numbers are
worth remembering because they look like a contradiction and are not:

| how it was measured | result |
|---|---|
| `ab chase chase:roomShare=0.5` — 260 boards | `NO BETTER`, **1 Elo ±3** |
| `play` against the six reactive bots, then `rate` — 6,600 games | **+14 Elo**, intervals disjoint |

Both are correct answers to different questions. The guard refuses a chase step into a pocket, the
pocket is one *this bot's own approach* walks into, so an opponent running the same approach is in the
same corridor at the same moment and the guard changes nothing between them. It is worth points
against everybody who would not have died there.

The signature is visible in the run, and `ab` prints it: two entrants that play the same game share
every mirrored board exactly, so a `NO BETTER` verdict sitting on a pile of exact splits is a test
that never saw the change. When that note appears, re-measure against a field:

```bash
./gradlew :lab:run --args="play chase chase:roomShare=0.5 space pressure random wallhug --rounds 600"
./gradlew :lab:run --args="rate"
```

The general rule: **`ab` for a change that alters how a bot plays this opponent, a field and `rate`
for one that alters how often it loses games it should not.** A rating over a field is the weaker
instrument per game played — no pairing, wider intervals — and it is the only one that can see the
second kind at all.

There is a sharper form for the case where both entrants are the *same bot at different knobs*: that
head-to-head measures a style match-up, and only a common field converts it into strength. The worked
case — an ablation that came out intransitive and put the wrong sign on the coordinate carrying the
point — is in [`Research-Process.md`](Research-Process.md#3-head-to-head-knowing-what-it-measures),
with the rest of what an experiment does before and after this step.

## Measuring at three seats

Everything above and everything the shipped ladder says was measured at two. `--format ffa` seats
more, and seven things about it are not the same measurement.

**A free-for-all batch has exactly as many entrants as it has seats.** `TournamentConfig.seatsPerMatch`
is `contestants.size` under `FREE_FOR_ALL` and `--rounds` counts matches rather than matches per
pairing, so a three-seat batch is three entrants and there is no `--seats` flag. **A field wider than
three is several triples pooled**, which needs no code — `comparabilityKey` does not include the
contestants and `Ladder` keys entrants on the expanded spec — but does need a **disjoint `--seed` per
triple**, or two triples sharing a pair measure it twice on the same boards while
`bootstrapIntervals` counts them as independent evidence. Keep `--rounds` a multiple of the seat
count times two so no seed group is cut short. Do not mix formats in one log directory: `rate` takes
the format off the first eligible run and applies it to everything.

**`ab`, `tune` and `spsa` do not exist here.** `SequentialTest.configFor` builds two contestants and
no `format`, so all three subcommands are head-to-head whatever they are handed. The instruments at
three seats are `play`, `rate`, `report` and `phases`, and **every three-seat number is a field
rating** — there is no sequential test to decide anything with.

**`--openings mirrored` or the batch is void.** `Openings.FIXED` puts the third snake in a third
corner, which no seating rotation evens out: three identical entrants score **83.4 / 0.05 / 16.6%**
by seat under `fixed` and **32.8 / 31.6 / 35.6%** under `mirrored`. A seat there is worth more than
any bot in this repository. Under `mirrored` the seating is fair and it has been checked twice — with
identical entrants by P7a, and on a nine-bot field of 14,400 matches at 33.4 / 33.1 / 33.5%, χ² 0.39
on 2 df. Acting first, which is a real advantage at two seats, is worth nothing measurable at three.

**Read the score column and not a cell.** The seat rotation is cyclic, so at three seats a pair meets
in each unordered pair of seats once and never reversed; any residual seat effect lands on the same
side of every cell and shows up as a perfect intransitive cycle while every score column still reads
50%. `rate`'s "the ladder does not fully describe these pairings" block is expected output here and
is not by itself evidence of intransitivity between bots. `TournamentSchedule.seatInto` carries the
demonstration.

**The rating is fitted to who *outlasted* whom, not to who won**, because that is what
`pairwiseOutcomes` scores past two seats. So `rate` prints a `win` column and its bar beside every
free-for-all rating, and says whether the two order the field the same way. Read them together and
quote them together. Measured over all 84 triples of the nine shipped bots on a 12x12, 25,200
matches, the two rules **ordered the triple identically in 79 of them**, and each of the five flips
is between two entrants five points of win share or less apart — so the rules never disagree about a
difference either of them can see. What they do disagree about is the **scale**, and by how much
depends on where in the field you are: `pressure` against `wallhug` reads **65 / 35** by outlasting
and **90 / 10** by who actually won, because a third snake takes 67% of those matches and the rule
spends them grading two doomed snakes on the order they died in. At the top, where a third snake wins
8%, the two rules agree to a point or two. **So trust the top of a three-seat table and read the
bottom of it with the `win` column open.**

**And nothing here is comparable with a two-seat number.** Different scoring rule, different
schedule, different game — the same nine bots on the same board at the same allowance span **1,215
Elo at two seats and 663 at three**, in the same order, so a three-seat rating is the same ladder at
55% of its width. There is no three-seat ladder of record; a phase that wants one builds it.

**A covering design balances pairs; only the complete design balances company.** A pairwise cell
moves **12.7 points on average and up to 33.7** on the identity of the third snake alone — a cell is
nearest even when the third snake is the strongest available and nearest its two-seat value when the
third snake is the weakest. `fitRatings` has one parameter per contestant and nowhere to put that.
A seven-triple Steiner design over nine bots, which meets every pair exactly once, produced two
inversions against the two-seat ladder that the complete design did not reproduce: the pairs had been
measured under their own worst company. **State the exact triples beside any three-seat rating**, and
prefer the complete design where the field is small enough to afford it — 84 triples of 300 rounds at
12x12 was **ten minutes of arena**, and half an hour of wall clock, because eighty-four `:lab:run`
invocations are eighty-four Gradle starts and that is what dominates a batch of triples.

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

**It never edits a default.** Changing one moves every `GoldenMoveStreamTest` hash that reaches it, and a
process that could change both would turn "a golden failure is a question" (SW-01) into a formality.
It prints a recommendation; [`Bots.md`](Bots.md) carries what a person does with it.

**A knob tuned at one allowance is tuned at that allowance.** `tune puct --knobs cpuct --budget 400`
recommends `cpuct=0.5` at +73 Elo, confirmed over 280 fresh boards; the same setting re-tested at the
shipped 1000 measures −19 ±23. Exploration constants trade against search depth, so the confirming
run has to be at the allowance the bot actually ships at.

**Both of those numbers are why the confirming run exists, and a sweep of the same knob at the
shipped allowance is the demonstration.** It accepted `cpuct=2.3` at +112 Elo over 80 boards, then
`2.2` at +127 over 40, walked the descent out to `2.2` — and the confirmation came back
`NOT CONFIRMED, −19 Elo over 800 fresh boards`. Nothing beat the shipped `1.5`. A search step is
deliberately cheap and greedy, so a lucky block can send the whole descent off in a direction; the
disjoint-seed re-run at a finer bound is the only part of `tune` whose number you should act on.
`PuctBot.CPUCT` carries both tables.

**"Nothing beat the default" is a result and is worth writing down.** It costs ~20 minutes to
establish and it stops the next person spending them again. Both runs land in
`.lab/tune-<slug>.tsv`; the confirming one is the row with a negative `pass`.

## Tuning ten knobs

```bash
./gradlew :lab:run --args="spsa puct --knobs cpuct --budget 1000"
```

`tune` costs one sequential test per knob per stride per pass, and the knobs interact, so the passes
have to be repeated — past about three knobs it is not a search you can afford to finish. `spsa`
estimates a gradient over **every numeric knob at once from two measurements**, so an iteration costs
the same at ten knobs as at one. It is what chess engines tune with, for exactly this situation.

Four things about it decide whether a run means anything.

**It searches the entrant you name, spec and all.** `tune` and `spsa` both take a full entrant spec
rather than a bare slug, and everything the spec pins is held still — in both arms of every
measurement *and* in the baseline the confirming run is played against. That second half is the one
that leaves no trace when it is wrong: a confirmation against the bare defaults measures the spec and
the point together and credits the whole difference to the point. A knob that is both pinned and
named for searching is refused rather than resolved one way.

The reason it is not a convenience is that a weight can live under a `Choice`. `ChamberEval`'s three
weights are not read at all unless `eval=chamber`, so `spsa puct --knobs parityWeight` would perturb
a number nothing looks at, find a flat objective, and print a well-formed answer about a bot it was
not searching.

**Numbers only.** A `Choice` or a `Flag` has no direction to be perturbed along, so naming one is an
error rather than a coordinate silently built out of the order its values happen to be declared in.
Left unnamed they stay at their defaults and the run says which. `tune` is what enumerates those.

**The two arms play each other, over one set of boards.** That is common random numbers, and it is
where most of the leverage is: a board's own difficulty is far larger than the difference two knob
settings make, and sharing the board cancels it inside the difference instead of leaving it to be
averaged away. Measured on a synthetic objective in `SpsaTest`, pairing is worth about **7× the
games** — so an unpaired design is not a slower search, it is a different budget.

The cost of pairing is that a run inherits `ab`'s blind spot whole. A knob that changes how the bot
plays *other* opponents and not how it plays a copy of itself has no gradient here at all, every
board splits down the middle, and the point never leaves its defaults. The run prints the split rate
and says so; `chase --knobs roomShare` is the worked case, and the answer for it is a field.

**Everything is counted in the knob's own declared steps**, which is the unit `tune`'s stride already
speaks, so the same numbers mean the same thing on a `cpuct` that moves in tenths and a weight that
moves in twentieths.

| | what it sets | default |
|---|---|---|
| `--iterations` | gradient steps | 200 |
| `--boards` | boards behind one gradient estimate | 6 |
| `--spread` | declared steps between the centre and each arm | 8 |
| `--stride` | **the most** a knob moves on the first iteration | 6 |

`--iterations` and `--boards` buy the same total games and are **not** interchangeable: doubling
either doubles the cost, but doubling the iterations also sharpens the answer, because the run
averages the last quarter of its trajectory and those are more independent draws to average.
Doubling the boards only sharpens one step that the next one overwrites. When a search has not
settled, raise `--iterations`.

`--stride` is a ceiling rather than a typical move — reached only if one arm won every board of an
iteration — so on six boards the everyday move is about a step. `SpsaTest` walks a synthetic bowl of
the curvature a knob here has: at 200 iterations the point finishes 12 of a hundred steps from the
answer at a stride of 1, 7 at 6 and 8 again at 12. Below the balance a run never arrives; above it, a
run arrives and then wanders.

**What it answers with is the averaged tail, and never its own best iterate.** A search over a noisy
objective visits its best-looking point by construction, so the maximum of a trajectory is a
statement about the noise; the last iterate is one sample of a random walk. Averaging the last
quarter is the standard answer to both, and it beats the last step on any run long enough to have
arrived.

**And the point it settles on is an attempt, not a finding.** Every run ends with a confirming `ab`
of that point against the shipped defaults, over a disjoint seed base at a stricter bound, and prints
that command so it can be re-run on its own. Only that number is worth acting on — the same rule
`tune` follows, for the same reason. The journal in `.lab/spsa-<slug>.tsv` holds every iteration with
its two arms, its sign vector and its seed, and no iteration in it carries a verdict, because none of
them ran a test.

A run resumes from its journal: recorded gaps are replayed rather than re-bought, and a resume whose
arms do not match what was written stops rather than walking a different trajectory under the old
run's name.

## Fitting a value function

```bash
./gradlew :lab:run --args="train --log .lab/chamber-ab,.lab/prior-ab --rows 12 --cols 12 --hidden 16"
```

The one subcommand that plays nothing. A logged match is a move stream, and a move stream is a whole
game that can be walked again for free — `ReplayCodec` gives back the spawns, the turn order and the
rules, and `Match.playback` drives it without consulting a bot. So every batch any phase has ever
run is training data, and `train` reads each position through the **same** `PositionFeatures` the bot
reads it through. That sharing is the design: `:lab` cannot see `:bots`' internals, so a trainer that
could not import the extractor would have to reimplement it, and a copy that drifts by one term
produces a bot that is merely mediocre with nothing failing anywhere.

Four things decide whether a run means anything.

**`--log` takes a list, and which batches are on it is a judgement about the play in them.** A field
of reactive bots and an `ab` between two searchers are both logged matches and are not both training
data for a leaf that will only ever be asked about positions a search reached.

**Everything it reports is held out by game.** Consecutive positions of a match differ by one move
and share a label, so a row-wise split reports the training loss under another name. Read the holdout
log-loss against `0.693`, which is what a model that always answers even scores.

**Read the gap between the training and holdout columns before the loss itself — and read what it
says, which is narrower than it looks.** Equal columns mean the fit is not short of *capacity* on the
population it was fitted to. They say nothing whatever about whether it transfers to another one. P4
of the 2026-07-29 agenda cost six phases on that inference: `LearnedWeights` had equal columns to five
places, was read as "bounded by its twenty-five features", and was in fact bounded by a corpus drawn
entirely from one board size — refitting the *identical* readings on a 20x20 was worth 0.048 of
log-loss where four new readings were worth 0.0039.

**So a holdout is an in-distribution reading, and `--model` is the out-of-distribution one.**

```bash
./gradlew :lab:run --args="train --log .lab/p2b-field-20 --model .lab/shipped-model.txt"
```

That fits nothing. It scores an existing literal over the **whole** corpus — a model that has never
seen these games needs nothing held back — and reports log-loss, accuracy, spread, and **mean answer
against mean label**, which is the pair that separates *this model is mispriced here* from *this board
is harder to call*. A corpus spanning more than one geometry also reports its holdout **per board**,
because one pooled number over a mixture is a claim about the mixture and about no board in it.

**`--stride` buys distinct games, not positions.** Rows from one match are correlated; rows from two
are not. Within a fixed `--positions` ceiling, a larger stride spends the budget on more games.

Like `tune` and `spsa` it **recommends and never edits**: it prints the literal `LearnedWeights`
should hold, wrapped ready to paste, and adopting one is the sequence in [`Bots.md`](Bots.md) because
it changes how the bot plays every game.

## Asking when a bot's games are decided

```bash
./gradlew :lab:run --args="phases puct:eval=learned --log .lab/rave-field"
```

The other subcommand that plays nothing. A snakes match is two games in sequence — a contact game
while the snakes can still reach each other, and a solo filling race once they cannot — and they ask
for entirely different play. A rating cannot tell you which of them a bot is losing, and `report`
answers a different question: *how* it went out, not *when* it stopped being able to win.

`phases` replays the log, finds the move the free squares came apart **for good**, and reports the
wins and losses on each side of that line. Three things about it are load-bearing.

**The split is taken with hindsight, so this is a diagnostic and never a dispatch rule.** A bot
choosing a move cannot know whether the separation it is looking at will hold.

**The conservative predicate is vacuous at two snakes, and the run prints its rate so you can see
that rather than take it on trust.** Treating every *living* body as ground — the only reading under
which a separation is provably permanent — leaves the whole playable rectangle connected, because a
two-snake match ends at the first death and so never has a corpse in it.

**A third seat makes it a real predicate and it then fires on about one match in a hundred, flat
across board size** — 10, 14 and 12 of 1,200 on an 8×8, a 12×12 and a 20×20, which is 0.11%, 0.22%
and 0.089% of contested positions. A corpse is the only wall there is and one snake rarely
disconnects a rectangle by itself. So the hindsight split below is what a three-seat analysis is cut
on too, and the proof of zero has been replaced by a measurement of almost-zero rather than by a
usable test.

**What stands in for it is measured, not argued:** the run reports how often a board that came apart
was rejoined by the moves after it, which is the size of the mistake `SpaceOwnership.isolated` makes.

**Read the `lead` column before the score beside it, and the band table before either.** "Ahead on
room" is a sign, and a one-square lead buckets with a commanding one — so two bots' AHEAD rows can
differ entirely because one of them arrives further ahead. The band table is the same matches cut by
*how far* ahead, which is the only form in which one bot's conversion can be read against another's;
on the log `PhasesCommand`'s KDoc tabulates, more than half the apparent difference in fill quality
between two leaves was the size of the lead. The `usable` column beside it is the same margin over
the ground a walk can actually spend rather than the raw flood, and where the two disagree on sign
the flood called the race the wrong way.

## Measuring what a change costs

**Pair each seed across two builds. Do not compare timing blocks.**

`time` reports microseconds, and a microsecond is a statement about the machine as much as about the
code. A block of timings taken now and a block taken an hour ago are not comparable, and nothing in
either says so. The failure is not subtle and it is not rare: an unpaired 20×20 block taken during
the bitboard conversion reproduced entirely plausible numbers **and put an unchanged `uct` control at
0.82×** — the machine had degraded about 50% across the session and the block was reading drift as
signal. **Absolute microseconds from a single block are worthless. Only paired ratios with a control
survive.**

The method:

```bash
git worktree add ../snakewarz-before <baseline-commit>     # a detached build of the old code
./gradlew -p ../snakewarz-before :lab:run --args="time puct --budget 1000 --seed 7"
./gradlew                        :lab:run --args="time puct --budget 1000 --seed 7"
```

Three parts, all of them load bearing:

- **The same seed, back to back.** Not the same seed an hour apart, and not two seeds. The pair is
  the measurement; either figure on its own is noise with a unit attached.
- **A control the change cannot touch.** Carry an entrant the change does not reach — `uct` for a
  change to `puct`'s leaf, a reactive bot for a change to a search primitive — and time it in the
  same pairs. A control that moves is a machine that moved, and the run is void.
- **A precondition: turn counts have to match per seed.** Back-to-back seed pairing measures cost
  with the game held still, which is only true when the change is behaviour preserving. If it alters
  a move, the two builds play different games of different lengths and the ratio is measuring both.
  For a change that alters play, time it at a fixed position count instead and say so.

Pairing is not a refinement. The same bitboard conversion measured 1.49× unpaired and **1.59×**
paired on a 12×12, and 1.93× against **2.13×** on a 20×20 — the unpaired numbers understated the
speedup, and would have overstated it just as easily on a machine drifting the other way.

**A sweep is not a pair, and its self-consistency is not evidence.** One JVM process timing seven
subjects in sequence produced ratios that agreed with themselves on every pass and were wrong by
**4–5×** against three independent references: the subjects share a warmed JIT, a heap and a run of
the clock, so what the passes agree about is the process rather than the code. `AppraisalTape`
(`bots/src/commonTest/`) is what replaced it — it seats the subject at *no* slot and gives the line
bot a private `SplitMix64`, so a subject drawing a different count of random values cannot shift the
line under itself, which is what makes the ratio a statement about two appraisals rather than two
games.

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
hit-test, the labels, the theme, the focus handoff, the portrait slugs, the replay fragment — run here
or nowhere:

```bash
./gradlew :ui:wasmJsBrowserTest :app:wasmJsBrowserTest -PbrowserTests=true
```

## What each suite costs

Prefer `jvmTest` while developing; most modules answer in seconds. **`:bots` does not** — it is a
couple of minutes, because `BotLadderTest` and `RolloutTruncationTest` play several hundred complete
matches with a search bot in them, which is the point of both. Narrow with `--tests` while working on
something else. A full cold `build` takes several minutes; the wasm toolchain is slow to warm up.

**One test in the suite reads a wall clock, and it flakes.** `:bots`'
`RolloutPolicyTest."a prior at its swept weights is priced before anything is built on it"` is a
benchmark rather than an assertion about behaviour — it exists because a dearer rollout buys no fewer
iterations, so all of its cost lands on the clock and a matrix at one allowance would not see it. It
occasionally fails under Chrome on a loaded machine and passes on a re-run. Re-run it before believing
it; a *repeatable* failure there is a real cost regression and is what the test is for.

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
