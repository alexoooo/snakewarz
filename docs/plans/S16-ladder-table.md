# S16 — The ten levels, and `:lab` proving they get harder

**Modules:** `:match`, `:lab`
**Depends on:** [S05](S05-map-catalogue.md), [S06](S06-lab-maps.md).
**Read first:** [`../Bots.md`](../Bots.md), and `bots/.../BotLadderTest.kt`'s KDoc — the two caveats in
it are the reason this session exists as a measurement and not as a table.

## Goal

Ten levels, ten genuinely different opponents, getting harder — **and a `:lab` command that says so
with a number.**

---

## Step 1 — the type

`match/src/commonMain/kotlin/ao/snakewarz/match/ladder/LadderLevel.kt`

```kotlin
public class LadderLevel(
    public val index: Int,            // 1..10
    public val title: String,
    /** One line, in the player's language, on how this opponent plays. CC-18. */
    public val blurb: String,
    public val opponent: BotId,
    public val params: BotParams,
    public val budgetPerTurn: Int,
    public val rows: Int,
    public val cols: Int,
    public val shape: MapShape,
) {
    public fun setup(seed: Long, human: BotId): MatchSetup
}
```

`match/src/commonMain/kotlin/ao/snakewarz/match/ladder/Ladder.kt` — `public object Ladder { public val
levels: List<LadderLevel> }`.

**Why `:match` and not `:ui`.** `:match` already sees `:bot-api` for `BotId`/`BotParams`, and
`MapShape` lands there in S05. `:ui`, `:app` **and `:lab`** all see `:match`, while `:ui` may never see
`:bots`. Putting the table here is what makes step 3 possible — `:lab` can measure that level 7 is
harder than level 6, which is the difference between a difficulty curve and a claim about one.

## Step 2 — the levels

Ten different **slugs**, so the ten play styles are ten algorithms and not one bot at ten budgets. The
order follows the rung-by-rung ordering `BotLadderTest` already certifies, with geometry ramping
alongside.

| # | Opponent | Board | Map | How it plays |
|---|---|---|---|---|
| 1 | `random` | 8×8 | `EMPTY` | any legal move |
| 2 | `burninhell` | 10×10 | `EMPTY` | serpentine column sweep, never looks |
| 3 | `wallhug` | 10×10 | `PILLARS` | runs to a wall and spirals inward |
| 4 | `space` | 12×12 | `CROSS` | always the roomiest move; ignores you entirely |
| 5 | `pressure` | 12×12 | `RING` | room first, then crowds you |
| 6 | `chase` | 14×14 | `ROOMS` | walks the shortest path to your head |
| 7 | `flat-monte-carlo` | 14×14 | `DIAGONALS` | random rollouts, no tree |
| 8 | `uct` | 16×16 | `SCATTER` | MCTS with UCB1 |
| 9 | `puct:eval=territory` | 16×16 | `DOUBLE_SPIRAL` | prior plus a territory leaf |
| 10 | `alphabeta:eval=chamber` | 20×20 | `ROOMS` | exact search, the dearest evaluation |

**This table is a starting hypothesis, not the deliverable.** Three things about it are unverified:

- **`burninhell`'s placement is a guess.** It is a contributed bot, it is not a rung of
  `BotLadderTest`, and [`../Bots.md`](../Bots.md) says the contributed section claims *nothing* about
  strength.
- **Every rung was certified on an empty rectangle**, and S06 step 5 exists to find out what a map
  does to that ordering. Board-size intransitivity is already proven here — `alphabeta:eval=territory`
  rates +131 above bare `puct` at 8×8 while **losing** the head-to-head 89–111, and wins 70.5% at
  12×12. Map topology should be expected to invert something too.
- **The ladder is a 12×12 instrument.** Levels 1-3 and 10 sit outside the board size everything was
  measured at.

So: write the table, then let step 3 correct it.

## Step 3 — budgets are measured, not guessed

Levels 7-10 name a searcher, and a searcher's cost is board-dependent. `MatchSetup`'s KDoc puts
`puct:eval=territory` at **~5.8 ms mean and 8.7 ms dearest** per turn at 1,000 evaluations on 20×20,
and **`eval=chamber` at 4.6× that** — about 27 ms, five and a half times the 8 ms frame guard.

At 12 turns a second a turn has ~83 ms, so a 27 ms bot call is a visible hitch rather than a stall.
It is still not something to write down without looking:

```bash
./gradlew :lab:run --args="time alphabeta:eval=chamber --rows 20 --cols 20 --budget 2000"
./gradlew :lab:run --args="time puct:eval=territory --rows 16 --cols 16 --budget 1200"
```

Set each level's `budgetPerTurn` from what those print. **A budget of zero is not a degenerate case** —
every searcher falls back to `SpaceBot`'s flood fill, which charges nothing — so there is a working
answer at every point on the curve.

## Step 4 — the `ladder` command

`lab/src/main/kotlin/ao/snakewarz/lab/LadderCommand.kt`, registered in `LabCommand`'s `when` beside
`play`, `rate` and `time`.

```bash
./gradlew :lab:run --args="ladder"
./gradlew :lab:run --args="ladder --rounds 40 --against puct"
```

It plays each level's opponent, **on that level's board and map**, against a fixed reference entrant,
and prints the reference's win rate per level.

**The monotonicity check is the deliverable.** The reference's win rate must fall as the level index
rises. That is how "getting harder over time" becomes an assertion instead of a claim, and it is what
tells you where `burninhell` actually belongs.

Read the distinct-games line before anything else. A fixed map plus fixed spawns plus two bots that
draw no randomness is four distinct games however many rounds are asked for — and levels 1-6 are
*entirely* bots that draw little or no randomness, so this is the command where that trap is most
likely to fire. `--openings mirrored` is the default and is why.

Pick a reference strong enough to beat level 1 nearly always and weak enough to lose to level 10 nearly
always, or the curve saturates at both ends and says nothing. A mid-ladder entrant at a modest
allowance is the shape to aim for; the command should take `--against` so this can be retuned without
a code change.

---

## Tests

`match/src/commonTest/kotlin/ao/snakewarz/match/ladder/LadderTest.kt`
- exactly ten levels, indices 1..10 in order;
- **every opponent slug is distinct** — the "ten distinct styles" claim, asserted;
- every opponent resolves in `ShippedBots`… except `:match` may not see `:bots`. So assert it in
  **`:lab`** or in `:app`'s test source set instead, whichever already sees both. This is a real
  constraint, not an oversight: do not add the edge to satisfy a test.
- every level's board meets its shape's `minimumSide`;
- `setup()` produces a valid `MatchSetup` with the human in slot 0 and the opponent in slot 1;
- every level's params parse cleanly against the opponent's declared knobs.

`lab/src/test/kotlin/ao/snakewarz/lab/LabCommandTest.kt` — `ladder` parses, `--against` and `--rounds`
are accepted, an unknown reference errors with the known list.

---

## Done when

```bash
./gradlew build
./gradlew :lab:run --args="ladder --rounds 40"
```

The printed win rate falls monotonically from level 1 to level 10. **If it does not, reorder the table
and run it again** — the number is right and the guess was wrong. Record the final run's output in
[`../Bots.md`](../Bots.md) beside the ladder, so the next person can see what the ordering rests on.
