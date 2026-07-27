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
touch `Turn.scratch` — the other seven consume no budget at all, and `BotContractTest` enforces that
rather than merely asserting it here: a bot spends budget **if and only if** it declares a
`BotKnob.Search`.

`puct` is the one bot that **charges its own budget**. A rollout spends the allowance a move at a
time and the engine can see it; a static evaluation sweeping the board cannot be seen that way, so
`PuctBot.judge` calls `Turn.budget.tryConsume(eval.cost)` — *before* running the evaluation, so the
allowance is a bound rather than a note about work already done. Without it, `budgetPerTurn` would
quietly mean something different for every bot that declared one.

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
    val EXPLORATION = BotKnob.Decimal("exploration", "Exploration", "...", default = 5.0, min = 0.1, max = 100.0, step = 0.1)
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

For a rollout, take `turn.scratch.playout()` and spin on `outcome`:

```kotlin
val p = turn.scratch.playout()
while (p.outcome == null) p.advance(policy.pick(p.board.legalMoves(p.toAct)) ?: Direction.NORTH)
```

`advance` charges the budget itself, and an exhausted budget makes `outcome` a draw — so the loop
condition *is* the budget check and the search terminates structurally rather than on trust.

**A search that does not simulate has to charge itself.** `Turn.budget.tryConsume(units)` is public
for that, and `PuctBot` is the one bot that uses it: a board-wide sweep at a leaf costs real time the
engine cannot see, and a bot that charged nothing for one would make `budgetPerTurn` mean something
different for it than for everything else. Charge **before** doing the work — `tryConsume` refuses
and charges nothing once there is not enough left, so charging afterwards makes the allowance a
record rather than a bound. Do not tune the figure down to make your bot look better; report the
wall-clock beside the win rate instead, which is what `:lab`'s `time` subcommand is for.

Adding a bot needs **no HTML change**: the pickers in the sidebar are filled from `BotRegistry.entries`
at startup, and each seat's settings rows are built from that entry's `knobs`. Those are the only two
places `:ui` builds DOM, and this is why.
