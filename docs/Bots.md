# Bots

**For:** adding or changing a bot, or touching `bots/` and `bot-api/`.
**Assumes:** [`../CLAUDE.md`](../CLAUDE.md) — the module graph, the forbidden dependency edges and
the four non-obvious facts live there and are **not repeated here**.
**Reviewed against:** [`Coding-Standards.md`](Coding-Standards.md), especially SW-01 determinism,
SW-02 portable arithmetic, SW-03 the hot path and SW-05 frozen identifiers.

## The shipped registry

`ShippedBots` has **three sections, and only the first is a ladder**. The ladder is registered weakest
first: `random`, `wallhug`, `space`, `pressure`, `chase`, `flat-monte-carlo`, `uct`. Each rung beats
the one below it over twenty matches — `BotLadderTest` is the gate, and it is the only test in the
suite a *correct but useless* bot would fail. Then come the bots contributed to the original project,
ordered by slug and claiming nothing about strength: `burninhell`, `tomsnake`. Then **experimental**:
`puct`, which is level with `uct` per unit of *time* and behind it at an equal allowance, so it makes
no claim a rung would make. All three sections are gated by the same contract suite.

`:ui` opens slot 2 on the slug `uct` — the page should start on the game somebody came here to play
— and falls back to `entries.first()` when a registry does not offer it, so registration order still
shows through. Append new bots; do not prepend. Of the ten, only `flat-monte-carlo`, `uct` and `puct`
touch `Turn.scratch`; the other seven consume no budget at all. `puct` is also the only one that
charges for work the engine cannot watch it do — see *A search that does not simulate*, below.

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
- **Re-read `playout.outcome` after every `advance`, never carry it.** An exhausted budget makes the
  playout over, and `advance` on an over playout throws — so a stale reading is an exception that
  only fires when the allowance lands on that exact move.
- **A bot handed a budget of zero must spend exactly zero and still play well.** That is a contract
  test, and the shipped answer is to fall back on `SpaceBot`, whose flood fill charges nothing.

The `internal` primitives in `:bots` are there to be used: `FloodFill` for room, `ShortestPaths` for
distances and first steps, `SpaceOwnership` for the board carved up between the snakes — and
`isolated`, for whether a snake's ground still runs into anybody else's — `nearestOpponent` for
`PvpAi`'s reduction, `randomPlayout` for a rollout, `truncatedPlayout` for a short one judged by
ownership, `UctTree` for a flat-array search tree, `PuctTree` for one guided by a prior, and
`LeafEval` for a hand-written value at a leaf.

`truncatedPlayout` and `SpaceOwnership` ship **wired and off**, and the reason is measured rather
than aesthetic — see `UctBot.ROLLOUT_DEPTH`. Do not turn them on without re-running
`RolloutTruncationTest`, and do not delete them either: they are the evidence.

Every registry entry is run against the shared contract suite in CI (`bots/src/commonTest/.../BotContractTest`):
never returns an illegal move when a legal one exists, survives a budget of zero, is deterministic given
an identical seed, retains no cross-match state, does not claim to be interactive, terminates on every
board shape, spends budget exactly when it declares an allowance, and plays the same match at its own
declared defaults as it does with nothing set. That suite is what makes "fork → add a bot → PR" safe
to accept.

## Declaring a knob

Anything worth tuning is declared as a `BotKnob` and passed to `register`. **The declaration is the
reader** — that is the whole design, and it is why the constructor holds no literal:

```kotlin
private val exploration = EXPLORATION.read(setup.params)

internal companion object {
    val SEARCH = BotKnob.Search(min = 0, max = 400_000, step = 10_000)
    val EXPLORATION = BotKnob.Decimal("exploration", "Exploration", "...", default = 5.0, min = 0.1, max = 100.0, step = 0.1, tradeoff = true)
    val KNOBS: List<BotKnob> = listOf(SEARCH, EXPLORATION)
}

// bots/ShippedBots.kt
register("my-bot", "My Bot", ::MyBot, MyBot.KNOBS)
```

The four leaves are `Integer`, `Decimal`, `Flag` and `Choice`. **A `Choice` holds names, never
ordinals** — its value travels in a replay URL beside its name, so `eval=expert` survives somebody
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
`offered` is the handful a form reads. Of the ten shipped bots, three offer anything at all: an
allowance, `uct`'s `exploration`, and `puct`'s `eval`. `ShippedBotsTest` pins that list, so a knob
cannot arrive on the sidebar without somebody having said so.

The failure this prevents is quiet and was real: `puct` used to show four `ExpertEval` weights that do
nothing at all unless `eval=expert`, so most of the time most of that panel was inert, and `uct`
showed a tree ceiling the allowance already bounds. Neither was wrong, and neither was answerable.

For a rollout, take `turn.scratch.playout()` and spin on `outcome`:

```kotlin
val p = turn.scratch.playout()
while (p.outcome == null) p.advance(policy.pick(p.board.legalMoves(p.toAct)) ?: Direction.NORTH)
```

`advance` charges the budget itself, and an exhausted budget makes `outcome` a draw — so the loop
condition *is* the budget check and the search terminates structurally rather than on trust.

### A search that does not simulate

A rollout spends the allowance a move at a time and the engine can see it; a static evaluation
sweeping the board cannot be seen that way. `Turn.budget.tryConsume(units)` is public for that, and
`PuctBot` is the one bot that uses it — `PuctBot.judge` calls `Turn.budget.tryConsume(eval.cost)`.
A bot that charged nothing for such a sweep would make `budgetPerTurn` mean something different for
it than for everything else.

Charge **before** doing the work: `tryConsume` refuses and charges nothing once there is not enough
left, so charging afterwards makes the allowance a record rather than a bound. Do not tune the figure
down to make your bot look better; report the wall-clock beside the win rate instead, which is what
`:lab`'s `time` subcommand is for. That is rule SW-07 in
[`Coding-Standards.md`](Coding-Standards.md).

Adding a bot needs **no HTML change**: the pickers in the sidebar are filled from `BotRegistry.entries`
at startup, and each seat's settings rows are built from that entry's `knobs`. Those are the only two
places `:ui` builds DOM, and this is why.

## Measurements, and what they are the reason for

A search bot's strength is how much search fits in its allowance, so most of the design questions
here were settled by a batch rather than by an argument. Each of those numbers is the reason
something is or is not in the code, which is exactly the kind of fact that gets re-proposed every
year or two. Three live in the KDoc of the constant they set: `UctBot.ROLLOUT_DEPTH` carries the
rollout-truncation table, `MatchSetup.DEFAULT_BUDGET_PER_TURN` the allowance table and the 8ms frame
budget that sets it, and `ExpertEval` the two that put `puct` in the experimental section rather than
on the ladder. Re-running any of them is a `:lab` command rather than an archaeology.

The fourth has no constant to live in, because what it settled was that there is no code.

**Tree reuse across turns was built, measured and rejected.** It looks free: a bot instance lives for
the whole match, so a tree kept in a field needs no new API, and `BoardView.hash` makes finding last
turn's subtree a `Long` compare where legacy's `BiState.equals` compared whole `BitSet`s. It is not
worth it. A turn builds about **137 nodes on a 20x20 at the shipped allowance**, spread over four
openings and then over the opponent's replies, which leaves roughly **8 visits** in the subtree that
would survive — six percent, in exchange for a `hash` column on the node pool and a copying
compaction of it, because node ids are positional and "keep only this subtree" is not a free
operation on a flat array. There is also a soundness wrinkle worth knowing before anybody tries
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
