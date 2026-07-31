# Bots

**For:** adding or changing a bot, or touching `bots/` and `bot-api/`.
**Assumes:** [`../CLAUDE.md`](../CLAUDE.md) — the module graph, the forbidden dependency edges and
the five non-obvious facts live there and are **not repeated here**.
**Reviewed against:** [`Coding-Standards.md`](Coding-Standards.md), especially SW-01 determinism,
SW-02 portable arithmetic, SW-03 the hot path and SW-05 frozen identifiers.

## The shipped registry

`ShippedBots` has **two sections, and only the first is a ladder**. The ladder is registered weakest
first: `random`, `wallhug`, `space`, `pressure`, `chase`, `flat-monte-carlo`, `uct`, `puct`,
`alphabeta`. Each rung beats the one below it over twenty matches — `BotLadderTest` is the gate, and
it is the only test in the suite a *correct but useless* bot would fail. Then come the bots
contributed to the original project, ordered by slug and claiming nothing about strength:
`burninhell`. Both sections are gated by the same contract suite.

There used to be a third, **experimental**, holding `puct` and `alphabeta` — registered but asserting
nothing, because the only readings anybody had were at *equal allowance*, where a dear leaf is handed
several times the wall clock a cheap one gets. Three equal-clock fields settled it: `puct` clears
`uct` by +54/+58/+62 Elo on 8×8/12×12/20×20 and `alphabeta` clears `puct` on all three, so both were
seated and the section is empty. **Read `BotLadderTest`'s KDoc before quoting the top rung** — at 8×8
`alphabeta` loses its head-to-head to `puct` while rating above it, and the ladder is a 12×12
instrument.

`:ui` opens slot 2 on the slug `uct` — the page should start on the game somebody came here to play
— and falls back to `entries.first()` when a registry does not offer it, so registration order still
shows through there. Append new bots; do not prepend. Of the ten, only `flat-monte-carlo`, `uct`,
`puct` and `alphabeta` touch `Turn.scratch`; the other six consume no budget at all.

### A bot earns its place by what it lets you measure

The roster is a set of instruments, and that is the test to apply before adding one and before
keeping one:

| bot | what it is for |
|---|---|
| `random` | the Elo floor, and the opponent an unconfigured page opens on |
| `wallhug` | a move stream with no randomness in it, so `wallhug` against itself is pinned by the rules alone |
| `space` | flood-fill room ranking, and the zero-allowance fallback `uct` and `puct` both delegate to |
| `pressure` | the adjacency-penalty heuristic, and the rung between room and pursuit |
| `chase` | the strongest reactive bot, and the only free one that takes games off a searcher — 13% against a `uct` field |
| `flat-monte-carlo` | **the ablation control**: `uct`'s rollout policy and allowance with the tree removed |
| `uct` | the flagship |
| `burninhell` | the second bot that draws no randomness, which is what `ArenaTest` measures openings with |
| `puct` | the frontier, and ahead of `uct` at an equal allowance *and* at equal clock |
| `alphabeta` | the only **exact** search, over `puct`'s own default leaf — what a full-width minimax is worth here |

A bot that is merely *weak* is not an instrument: `random` is already the floor, more cleanly, and a
second one only adds a picker row and a column to every matrix. That is what retired `tomsnake`, an
80/20 mixture of `random` and `pressure` that scored 5% against a field and beat no candidate version
of anything. **Retiring is a real cost** — the slug is frozen, so it is never reused — and it is worth
paying only when the bot answers no question that another bot here does not answer better.

### The single-player gauntlet is a different ordering, and it is measured per level

`Gauntlet` in `:match` seats each of the ten slugs at least once, on **its own board, map and
allowance** — so it is not the registry order with numbers on it, and it could not be. `BotLadderTest`
certifies its rungs on an empty 12x12, and neither half of that survives:
`alphabeta:eval=territory` rates above bare `puct` at 8x8 while losing its head-to-head to it, and a
six-entrant field on `cross` moved `wallhug` about 400 Elo up the table while compressing the whole
field to half its empty-board width.

So the order is measured on the geometry each level plays, by `:lab`'s `gauntlet` subcommand: one fixed
reference against every level in turn, and the ordering is right when the reference's score falls.

```bash
./gradlew :lab:run --args="gauntlet --rounds 200"
```

> **Stale as of 2026-07-31, and shipped that way deliberately.** The table below was taken on the
> ten-level gauntlet and on the *old* drawings of `rooms`, `diagonals` and `double-spiral`. Since then
> those three shapes were redrawn, `arena`, `islands` and `pinwheel` were added, four levels changed
> map and an eleventh level — `alphabeta:eval=chamber` on an empty 8x8 — was added on top. **Eight of
> the eleven rows now name a board that did not exist when these figures were taken**; only `cross` at
> level 2, `pillars` at level 3 and `ring` at level 5 are unchanged drawings under unchanged opponents.
>
> A shape travels as squares and never as a name, so no shared link broke — but a measurement is
> exactly what a redraw invalidates. `Gauntlet`'s KDoc says which placements are now guesses, the
> re-measurement is on the research agenda, and the command above is what settles it. Read the numbers
> below as the last honest reading of a *different* gauntlet, kept because the three bullets under them
> are still the reason placement is measured at all.

Two references, 2,000 matches each, mirrored openings, seed 1. `uct@100` is the shipped default and
is what resolves the whole gauntlet; `puct@250` is carried beside it because a single reference cannot
be believed on an ordering it saturates at both ends. **The board and map columns are the gauntlet as
it stood on 2026-07-30**, which is not the one `Gauntlet.levels` ships today — see the note above.

| # | opponent | board | map | allowance | `uct@100` | `puct@250` |
|---|---|---|---|---|---|---|
| 1 | `random` | 8x8 | `empty` | — | 100% | 100% |
| 2 | `burninhell` | 10x10 | `cross` | — | 98% | 100% |
| 3 | `wallhug` | 10x10 | `pillars` | — | 100% | 100% |
| 4 | `space` | 12x12 | `empty` | — | 92% | 99% |
| 5 | `pressure` | 12x12 | `ring` | — | 74% | 99% |
| 6 | `chase` | 14x14 | `double-spiral` | — | 55% | 94% |
| 7 | `flat-monte-carlo` | 14x14 | `diagonals` | 400 | 58% | 64% |
| 8 | `uct` | 16x16 | `scatter` | 600 | 26% | 51% |
| 9 | `puct:eval=territory` | 16x16 | `rooms` | 1,000 | 8% | 30% |
| 10 | `alphabeta:eval=territory` | 20x20 | `rooms` | 1,000 | 4% | 12% |

Every row of the `uct@100` run was **200 of 200 distinct games** bar level 6 at 197, which is the
honest sample size and the first thing to read: five of these levels are bots that draw no randomness,
and a reference that did the same would have played each of them the same four games. Neither column
falls strictly — 2/3 and 6/7 are ties under one reference each, both inside a five-point band — and
the two references disagree about nothing. `puct@250` is the noisier sample for exactly the reason
above: it converges on a deterministic opponent, so its rows against those run 86 to 148 of 200
distinct, which is why it is the second reading and not the first.

**Three things in that table are the measurement correcting a guess, and each is worth knowing.**

- **`cross` had to come down, not up.** Its first assignment was level 4 (`space`), where it lifted a
  flood-filling bot above the two levels over it: `puct@250` scored 68% on it against 100% on level 5.
  `cross` boosts a room-filler by so much that it can only sit under a bot too weak to be lifted past
  anything.
- **`double-spiral` inverts the value of search**, so it cannot sit above a searcher. At 16x16,
  `puct@250` against `puct@1000` scores **77%** on `double-spiral` and **23%** on an empty board of
  the same size — a quarter of the allowance beating the full one, on the map whose own KDoc says the
  game there is close to pure space-filling. Measured across the catalogue at 16x16, the full
  allowance scores 83% on `ring`, 77% on `scatter` and on `empty`, 65% on `pillars`, 63% on `rooms`,
  57% on `diagonals`, 50% on `cross` and 23% on `double-spiral`. So it is level 6, under the last bot
  that does not search.
- **The dearest evaluation is not the hardest level.** Level 10 plays `alphabeta` at its shipped
  `territory` leaf rather than `eval=chamber`, for two reasons that agree: `AlphaBetaBot.EVAL` records
  that leaf finishing *below* the cheap one in a common field, and `MatchSetup.DEFAULT_BUDGET_PER_TURN`
  puts it at about 4.6x `territory` per evaluation, which on a 20x20 overruns `:ui`'s 8 ms slice
  several times over. **Both reasons are about a 20x20**, which is what the eleventh level added on
  2026-07-31 turns on: `eval=chamber` on an empty **8x8** is affordable, and a small bare board is a
  tactical duel rather than a filling race. Whether it is in fact the hardest level is unmeasured.

The allowances are the frame criterion rather than a ramp somebody liked the look of. `lab time` on
each level's own board and map, best of five, with `uct` at 20x20 and the shipped allowance carried as
the control this table's figures are read against — that control reads 2.19 ms here against the 2.0 ms
`MatchSetup.DEFAULT_BUDGET_PER_TURN` records, so the machine is comparable:

| level | JVM us/turn |
|---|---|
| 7 `flat-monte-carlo` @400, 14x14 `diagonals` | 191 |
| 8 `uct` @600, 16x16 `scatter` | 896 |
| 9 `puct` @1,000, 16x16 `rooms` | 1,628 |
| 10 `alphabeta` @1,000, 20x20 `rooms` | 2,142 |
| *control:* `uct` @1,000, 20x20 | 2,189 |

Every level is at or under the control, which is the configuration the 8 ms slice was chosen by — so
no level costs a player more of a frame than an unconfigured match already does. **Levels 8 and 9 have
since changed map and level 11 has no row here at all**, so this table is stale for the same reason the
one above it is; the frame criterion it establishes is not, and re-running `lab time` per level is part
of the re-measurement.

## A bot on a map: two readings, and the one denominator

Interior walls cost the hot path nothing — they are the padded ring's `WALL` byte, so `freeNeighbors`
already treats them as not-free and `copyFrom` carries them into every search arena for free. What
they break is anything that answered a question about the board **without asking the board**. Two
sites in `:bots` did, and both are worth recognising because the shape recurs:

- **A reading taken off a row and a column is blind to a wall.** `MovePrior`'s wall feature counted
  how many of a destination's four sides were the *edge of the rectangle*, from `row == 0`,
  `row == lastRow` and so on. It now asks `BoardView.isWall` at each of the four neighbours, which
  answers for the border ring and the map's own obstacles alike. **No golden hash could have caught
  the old reading**, and that is the part to remember: `PuctBot.PRIOR_WALL` defaults to `0.0`, so the
  feature is multiplied out of the shipped prior entirely and every move stream is identical either
  way. A feature at a zero weight is a feature no test is watching. Pricing it is `MovePrior`'s own
  ablation table — the wall and tail readings together are 1.02x a turn, at or under the resolution of
  a `time` run.
- **`Grid.playableCount` is the geometry; `BoardView.openCount` is the denominator.** A share of the
  board is a share of the squares a snake could ever stand on. `PositionFeatures` normalises by
  `openCount`, so `boardFill` still starts at zero on a fresh walled board and still reaches one;
  against `playableCount` a map would arrive already looking part filled, which is a phase of the game
  it is not in. `TempoOwnership.walkableCount()` is read against the same quantity, and `MatchStats`
  divides by `openCells` for the same reason one level up. **`TerritoryEval` needed no change at all**
  — it normalises by `totalOwned`, the ground the sweep actually handed out, which is already a
  quantity a map shrinks correctly.

### `eval=learned` is correct on empty, honest on a map, and unfitted for one

All three clauses are true at once and none of them substitutes for another.

**Correct on empty:** the readings are the ones `LearnedWeights` was fitted on, to the bit, because
`openCount` equals `playableCount` where there are no walls.

**Honest on a map:** every feature stays in range and defined. `boardFill` still runs zero to one,
`regionShare` is still at most one, `headWalls` still lands in `{0, .25, .5, .75, 1}`. So the model is
*extrapolating* rather than degenerate — it is being asked about a distribution it has not seen, not
being handed a number outside its domain.

**Unfitted for one:** nothing claims it plays a map as well as it plays a rectangle, and no measurement
here says it does. A refit wants its own instrument — loss on a map corpus by these weights against
the same weights refitted, then a field — and one thing has to be known before building it: `:lab`'s
`train` keys its `PositionFeatures` cache on rows, columns and slot count, so **two different maps of
the same size share a reader**. That is sound exactly as long as a reader is built from a grid and a
slot count and nothing else. A reading that depended on the map would be answered off whichever map
happened to arrive first, silently, for the whole corpus. `LearnedEval`'s KDoc carries the same
argument beside the code.

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
- **Re-read `playout.outcome` after every `advance`, never carry it.** Any move can be the one that
  ends the game, and `advance` on an over playout throws — so a stale reading is an exception that
  only fires when a rollout happens to finish on that exact move.
- **A bot handed a budget of zero must spend exactly zero and still play well.** That is a contract
  test, and the shipped answer is to fall back on `SpaceBot`, whose flood fill charges nothing.

The `internal` primitives in `:bots` are there to be used: `FloodFill` for room, `ShortestPaths` for
distances and first steps, `SpaceOwnership` for the board carved up between the snakes — and
`isolated`, for whether a snake's ground still runs into anybody else's — `nearestOpponent` for
`PvpAi`'s reduction, `randomPlayout` for a rollout, `truncatedPlayout` for a short one judged by
ownership, `RolloutPolicy` for what a snake plays inside either of them, `UctTree` for a flat-array
search tree, `PuctTree` for one guided by a prior, and
`LeafEval` for a hand-written value at a leaf. Inside `search.puct`, `TempoOwnership` is the sweep
with turn order and retracting tails in it, `FillableSpace` answers how much of a region a single
walk can actually spend — which is not how big it is — `ChamberTree` runs that same block
decomposition without summing it away, so a leaf can ask what each chamber is worth on its own, and
`MovePrior` is the other half of what a network would supply: a weighted reading of each candidate
destination, normalised proportionally or as a softmax. `PuctTree` carries two mechanisms that are
built only when asked for and cost nothing when they are not — the MCTS-Solver's proof arrays and
RAVE's AMAF ones — and each carries in its knob's KDoc how often it fires, which is the number that
bounds what it can be worth. `portableExp` is beside it, for
`portableLog`'s reason — a temperature needs an exponential and `puct` is in the cross-target golden
set, so the exponential is built from `+ - * /` rather than the rule being excepted.

`search.learned` is the one leaf whose weights nobody chose. `PositionFeatures` reads a position as
twenty-nine bounded numbers — the sweep, the chamber decomposition and the readings six phases of
measurement say carry the signal — and `LearnedNet` turns them into a probability of winning off
`LearnedWeights`, a fixed-point literal `:lab`'s `train` fitted to a million logged positions **taken
at three board sizes, which is the part that turned out to matter**: the fit it replaced was taken at
one, and lost more to that than to any feature anybody has added.
**`PositionFeatures` is the only public class in `:bots` besides `ShippedBots`, and that is
deliberate**: `:lab` cannot see this module's internals, so a trainer that could not import the
extractor would have to reimplement it, and a copy that drifts by one term produces a bot that is
merely mediocre with nothing failing anywhere. One definition, read by the trainer and by the bot.

`truncatedPlayout` and `SpaceOwnership` ship **wired and off**, and the reason is measured rather
than aesthetic — see `UctBot.ROLLOUT_DEPTH`. Do not turn them on without re-running
`RolloutTruncationTest`, and do not delete them either: they are the evidence.

`RolloutPolicy`'s two non-uniform settings ship the same way, and for a reason with one extra term in
it: an allowance is counted in evaluations, `EvaluationCost.ROLLOUT` is a flat `1`, and a dearer
rollout therefore buys **no fewer iterations** — all of its cost lands on the wall clock. So a matrix
between two of these at one allowance is not a comparison, it is a handicap, and `RolloutPolicyTest`
carries both halves of what one has to be read against: how often each would play a different move
from the default, and what each costs a turn.

Every registry entry is run against the shared contract suite in CI (`bots/src/commonTest/.../BotContractTest`):
never returns an illegal move when a legal one exists, survives a budget of zero, is deterministic given
an identical seed, retains no cross-match state, does not claim to be interactive, terminates on every
board shape, spends budget exactly when it declares an allowance, and plays the same match at its own
declared defaults as it does with nothing set. That suite is what makes "fork → add a bot → PR" safe
to accept.

**It sweeps two things beside the entry, and both are bots the entry alone does not describe.** The
claims above are made across **one to four snakes** rather than at two, because a duel is not the game
a free-for-all is: `alphabeta`'s reduction to a single opponent, `puct`'s max^n solver and every
reading of "the nearest opponent" have only one answer at two seats. And every value of every
`BotKnob.Choice` an entry declares is swept as a bot of its own, so `puct` at each of its six `eval`
settings and `alphabeta` at each of its five are gated rather than only the two defaults. Both come off
the declaration rather than off a slug, so a contributed bot's own choices enrol the day it is
registered.

## Declaring a knob

Anything worth tuning is declared as a `BotKnob` and passed to `register`. **The declaration is the
reader** — that is the whole design, and it is why the constructor holds no literal:

```kotlin
private val exploration = EXPLORATION.read(setup.params)

internal companion object {
    val SEARCH = BotKnob.Search(min = 0, max = 10_000, step = 100)
    val EXPLORATION = BotKnob.Decimal("exploration", "Exploration", "...", default = 3.0, min = 0.1, max = 100.0, step = 0.1, tradeoff = true)
    val KNOBS: List<BotKnob> = listOf(SEARCH, EXPLORATION)
}

// bots/ShippedBots.kt
register("my-bot", "My Bot", ::MyBot, MyBot.KNOBS)
```

The four leaves are `Integer`, `Decimal`, `Flag` and `Choice`. **A `Choice` holds names, never
ordinals** — its value travels in a replay URL beside its name, so `eval=territory` survives somebody
reordering the list it offers and `eval=2` does not, with nothing in the codec able to tell. That is
the same argument that freezes the knob's name, applied to its value.

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

### Declaring one is not the same as offering one

**`tradeoff = true` puts a knob on the sidebar, and it defaults to `false`.** A tradeoff is a choice
with no single best answer: several values are valid, each produces a visibly different bot, and which
one you want depends on what you are after. An allowance is the type case — bigger is stronger and
slower, and neither end is wrong — so `BotKnob.Search` declares itself one and you get an allowance
field by declaring an allowance.

Everything else is a **hyperparameter**, and a sweep settles one better than a person staring at a
form can. Those stay declared and stay off the sidebar. Declaring one still buys everything that
matters: `:lab` sweeps it, a replay carries it, a test pins it, `BotParams` reads it. What it does not
buy is a row in front of somebody who has no way to judge the number and no reason to expect the
default is wrong.

The two lists on `BotEntry` are that split. `params` is complete and is what `:lab` validates against;
`offered` is the handful a form reads. Of the ten shipped bots, four offer anything at all: an
allowance, `uct`'s `exploration`, and the `eval` `puct` and `alphabeta` each declare.
`ShippedBotsTest` pins that list, so a knob
cannot arrive on the sidebar without somebody having said so.

The failure this prevents is quiet and was real: `puct` used to show four appraisal weights that do
nothing at all at `eval=mobility`, so most of the time most of that panel was inert, and `uct`
showed a tree ceiling the allowance already bounds. Neither was wrong, and neither was answerable.

For a rollout, take `turn.scratch.playout()` and spin on `outcome`:

```kotlin
while (true) {
    val p = turn.scratch.playout(EvaluationCost.ROLLOUT)
    if (p.outcome != null) break                    // the allowance would not stretch to another
    while (p.outcome == null) p.advance(policy.pick(p.board.legalMoves(p.toAct)) ?: Direction.NORTH)
    credit(p.outcome)
}
```

### The allowance is a count of evaluations

**One unit buys one judgement of a position** — a rollout played to the end, a static appraisal, one
iteration of a tree search. Not one simulated move, which is what it used to be and which meant
something different for every bot: doubling the moves buys a rollout bot twice the search and a bot
that never simulates nothing at all.

`Scratch.playout(cost)` is where that is charged, and charging it there does three things at once:

- **Termination is structural.** An iteration a bot cannot afford is an iteration it cannot start —
  the playout comes back with `outcome` already non-null and every rollout loop's first line stops
  it. Nothing is trusted to count.
- **Nothing is ever half-charged.** The evaluation is paid for *before* it runs, so a rollout that
  has begun always finishes and a search never has to tell an exhausted line from a real one part
  way through crediting it. `tryConsume` refuses and charges nothing when there is not enough left.
- **A matrix at "equal allowance" compares the bots rather than their arithmetic.** `puct` at
  `eval=survival` takes the whole board apart and at `eval=mobility` reads sixteen squares; both are
  one iteration, so the same number means the same amount of search.

What it does *not* claim is equal wall clock. `bots/search`'s `EvaluationCost` is the exchange rate,
it carries the measurements, and every entry in it is `1` today — so read a win-rate matrix with the
`time` figures beside it. Do not tune your own cost down to look better in one; that is rule SW-07 in
[`Coding-Standards.md`](Coding-Standards.md).

Adding a bot needs **no HTML change**: the pickers in the sidebar are filled from `BotRegistry.entries`
at startup, and each seat's settings rows are built from that entry's `knobs`. Those are the only two
places `:ui` builds DOM, and this is why.

## Adopting a measured setting

`:lab`'s `tune` searches a bot's declared knobs and **recommends**; it never edits a default. That is
not caution. Changing a default moves every `GoldenMoveStreamTest` hash that reaches it, and a process that
could change both would turn SW-01's "a golden failure is a question, never a hash to update" into a
formality — which is exactly the way that rule gets defeated without anybody noticing.

So adopting one is a deliberate act, in this order:

1. **Re-confirm at the allowance the bot ships at.** A knob tuned at one budget is tuned at that
   budget. `tune puct --knobs cpuct --budget 400` recommends `cpuct=0.5` at +73 Elo confirmed over
   280 fresh boards; the same value re-tested at the shipped 1000 measures **−19 ±23**. Exploration
   constants trade against search depth, and that trade moves with the allowance. Re-swept properly
   at 1000, that knob's answer was *nothing beats the shipped `1.5`* — see `PuctBot.CPUCT`.
2. **`play` it against a field, then `rate`.** Two different things go wrong without this, and only
   the first is about strength:
   - A knob can be worth points against its own bot and cost them against a different opponent —
     snakes are a rock-paper-scissors sort of game, and `rate` prints the residual cells where a
     single ordering fails to describe a pairing.
   - **A head-to-head test cannot see a change that does not alter the game between those two bots**,
     and that is not an exotic case. `ChaseBot.ROOM_SHARE` measures `1 Elo ±3` under
     `ab chase chase:roomShare=0.5` and **+14** against a field, because the pocket it refuses is one
     the bot's own approach walks into and an opponent doing the same thing is in the same corridor.
     `ab` now prints a note when its boards mostly split exactly, which is that situation's
     fingerprint. [`Workflow.md`](Workflow.md#deciding-whether-a-change-helped) has the full account.
3. Change the `default` in the knob's declaration. One literal, because the declaration is the
   reader — see above.
4. `GoldenMoveStreamTest` fails. **Re-pin it recording the measurement that moved it**: which run,
   what delta, over how many boards, at what bound. That record is the answer to the question a
   golden failure asks.

   **Count the hashes that move, before touching any of them.** Moving a `Choice` default should
   move exactly the *bare* case for that bot and nothing else, because every leaf worth pinning also
   has a case naming its value explicitly — `PUCT at territory` and `alpha-beta at chamber` exist for
   this, and a leaf pinned only by a default goes unpinned the moment the default moves. Two hashes
   moving together, or the named one moving alone, is arithmetic or search order and is a different
   question. If the bot is in the cross-target set, **verify in real Chrome**:
   `./gradlew :bots:wasmJsBrowserTest -PbrowserTests=true --rerun -i`, never with `--tests`, which
   silently runs one method and reports success.
5. Re-check `BotLadderTest`'s thresholds and update the measured figures in its comments. **This is
   a real step for every bot the ladder seats and a no-op for every bot it does not** — it was a
   no-op for `puct` and `alphabeta` until P3 seated them, and the two phases that read this line
   before then both had to discover that for themselves. The ladder now seats nine bots and the two
   at the top are the ones a default move is most likely to reach.

   Re-measure the thresholds; do not adjust them to fit. A rung whose measured figure has fallen to
   where its threshold no longer asserts a majority is a finding to report, not a number to lower.
6. Put the table in the KDoc beside the constant, the way the four below already do. **Where a claim
   is weaker than the headline it came from, say so there** — a fitted rating and its own residual
   table have disagreed about a pairing three times on this project, twice by more than the margin
   being adopted, so the KDoc records which comparison the default actually moved on.

A **hardcoded** constant is not searchable, and `PuctBot.FIRST_PLAY`'s KDoc says as much
("Promote it if there is"). The route is to declare it as a knob with `tradeoff = false` first —
which by itself changes nothing, and `BotContractTest` proves it — then sweep it, then set the
default.

## Measurements, and what they are the reason for

A search bot's strength is how much search fits in its allowance, so most of the design questions
here were settled by a batch rather than by an argument. Each of those numbers is the reason
something is or is not in the code, which is exactly the kind of fact that gets re-proposed every
year or two. Five live in the KDoc of the constant they set: `UctBot.ROLLOUT_DEPTH` carries the
rollout-truncation table, `MatchSetup.DEFAULT_BUDGET_PER_TURN` the allowance table and the 8ms frame
budget that sets it, `TerritoryEval` the two readings that kept `puct` out of the ladder until an
equal-clock field settled them, `EvaluationCost` what an evaluation of each kind actually costs — the
one that is recorded and deliberately *not* acted on — `ChamberEval` the three-weight sweep that made
it the strongest leaf in the box **at equal allowance**, including the weight the sweep moved and the
ablation refused, `AlphaBetaBot.EVAL` why that qualifier is load-bearing: at equal *clock* the same
leaf costs 2.4–4.6× and finishes below the cheap one it beat, which is what moved a default, and
`MovePrior` the four-weight sweep over the *prior*, where the ablation and the head-to-head that
produced it disagreed on the sign of the coordinate that mattered, `AlphaBetaBot` how deep an
exact search actually gets here and what it is worth once it does, `LearnedEval` what a value
function fitted to half a million logged positions buys over the best hand-written one, and
`PuctBot.RAVE` why AMAF statistics buy nothing behind a searcher that has no rollout to harvest them
from. Re-running any of them is a `:lab` command rather than an archaeology.

**`MovePrior`'s is the one to read before designing a sweep.** Ablating a multi-weight point is not
optional — that is `ChamberEval`'s lesson — and doing it as a stack of `ab` runs is an ordering built
out of one row, which the protocol already forbids and which came out intransitive here. Enter every
ablation into **one field** and `rate` it.

The next one has no constant to live in, because what it settled was that there is no code.

**Tree reuse across turns was built, measured and rejected.** It looks free: a bot instance lives for
the whole match, so a tree kept in a field needs no new API, and `BoardView.hash` makes finding last
turn's subtree a `Long` compare where legacy's `BiState.equals` compared whole `BitSet`s. It is not
worth it. A turn builds **one node per evaluation** — a thousand of them at the shipped allowance —
spread over four openings and then over the opponent's four replies, so what survives into next turn
is about a sixteenth of the tree. That is what the original measurement found when an allowance was
counted in moves and a turn built 137 nodes: **8 visits** would have survived, six percent. Counting
evaluations moved the node count and not the fraction, because the fraction is set by the branching
— raising the allowance raises both sides of it together. Six percent is not worth a `hash` column on
the node pool and a copying compaction of it, which is what it would cost: node ids are positional,
and "keep only this subtree" is not a free operation on a flat array.

There is also a soundness wrinkle worth knowing before anybody tries
again: `hash` deliberately omits `turnIndex`, and `turnIndex` is what `maxTurns` terminates on, so
statistics gathered at a shallower turn describe a position with a longer horizon than the one they
would be grafted onto.

**The wasm target costs about 3x, and the arena is why that is affordable.** The engine runs **2.7M
turns/s in Chrome against 8.8M on the JVM** with trivial bots. That gap is the platform and it was
expected; it is also dwarfed by the decision above it, `Board` mutating and unwinding a single arena
where legacy allocated a persistent board per node. The choices that keep the gap at 3x are each
stated where they are made — flat parallel pools instead of an object graph (`UctTree`), the UCB1
logarithm hoisted out of the child loop where legacy recomputed it per child, and `DirectionSet`
instead of an allocated `List<Direction>`. Settle a suspected regression with `ThroughputTest`, which
runs on either target, rather than by assuming the platform.
