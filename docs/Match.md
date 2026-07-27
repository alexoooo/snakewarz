# Match

**For:** touching `match/` — human input, the turn driver, stats, replays, tournaments.
**Assumes:** [`../CLAUDE.md`](../CLAUDE.md) — the module graph, the forbidden dependency edges and
the four non-obvious facts live there and are **not repeated here**.
**Enforced elsewhere:** `checkModulePurity` fails the build on `:match → :bots`; the driver resolves
bots through the `BotRegistry` *interface* and `:app` injects the implementation.

## Where the human lives

`InteractiveBot` is in **`:match`**, not `:bots`, and is deliberately **not** in `ShippedBots`. The
bot contract suite requires that no registry entry claims to be interactive — a search bot that
stalls has malfunctioned — so the human seat is composed *on the outside* of the shipped registry by
`PlayableRegistry`, which `:app` wraps around `ShippedBots`. `:match` already owns human input in the
module table, and keeping the pieces there makes them JVM-testable.

`PlayableRegistry.HUMAN_ID` is the slug `"human"`, and it is **frozen** like every released bot id:
it goes into the header of every replay of a game somebody played themselves. Such a replay plays
back perfectly — playback substitutes a scripted stand-in for every slot regardless of slug — but it
will not survive `MatchRecord.verify`, because re-running a person is not a thing a registry can do.

Every interactive slot reads the same `InputBuffer`, because there is one keyboard. A match takes at
most one human, and `:ui` offers the seat for slot 1 only.

**A match with a person in it is turn-based.** `StallPolicy.WAIT_FOR_INPUT` is the default of both
`InteractiveBot` and `PlayableRegistry`, so a human slot answers `Pending` on every turn it has no
key for, and `:ui` does not start `TurnScheduler` at all while `Match.interactive` — one keypress
plays exactly the round it belongs to, and the transport is disabled because there is no clock to
drive. When the player is eliminated `interactive` goes false, the scheduler takes over and the
survivors finish the match on the clock. `Match.interactive` is deliberately not
`bots.any { it.interactive }`: `ScriptedBot` claims to be interactive so that a partial recording
parks rather than forfeits, so playback is excluded by a flag `Match.playback` sets.

**A held key repeats on our clock, not the operating system's.** `Chrome` drops
`KeyboardEvent.repeat` — a text-editing rate, half a second of nothing then thirty a second, and
different on every machine — and `KeyRepeat` (in `:ui`, on `requestAnimationFrame`) turns a held key
into one move every 250ms. A tap is exactly one move. A second key pressed while the first is down
takes the repeat over, and `blur` cancels it, because a key released while the page is not looking
never sends `keyup`.

**A trapped player plays a fatal move instead of waiting.** `InputBuffer.take` filters illegal
input, so once nothing is legal no key the player could press would ever come back from it, and
`WAIT_FOR_INPUT` would park that match for good. Every direction from there is the same death — the
engine records `TRAPPED` whichever is played — so this is a move in the sense that a snake has to
make one, not a choice, and it is not the `MoveTracker` bug (which invented a *survivable* move
nobody chose).

## Stats and tournaments

`MatchStats` is **derived, never accumulated**. The board already knows every figure worth reporting
— lengths, moves survived, who is left and why the rest are not — so `Match.stats()` is a read, taken
at most once a frame, and the driver counts nothing extra as it goes. Do not add a counter to `Match`
for a statistic; work out whether the board can already answer it. It also serves the scoreboard, so
`:ui` has one set of per-slot numbers rather than two that could disagree.

`Tournament.step()` advances **one turn**, not one match, for the same reason `Match.step()` does: a
match at the shipped allowance is most of a second, and `:ui` has to be able to stop between any two
units of work. `TournamentRunner` slices it across frames on an 8ms guard, exactly as `TurnScheduler`
paces a match. That is what lets a batch of search bots run on a page that stays responsive, with no
worker and nothing `suspend` below `:ui`.

`Tournament.current` keeps reporting the **last** match after the batch ends, because somebody is
usually looking at it. `Tournament.setupFor(index)` exposes the whole schedule as a pure function of
the config — that is how `:ui` paints the opening position before a turn is played, and how the tests
assert the seat-swapping without catching the driver between two steps.

The contestants are the **slot pickers**, not a second list of bots: a tournament is the question the
sidebar already asks, over a few hundred matches. A human seat and a duplicate both drop out.

A tournament has a **format**, and it is a config property rather than a second driver.
`TournamentFormat.HEAD_TO_HEAD` is the pairwise round-robin, each seed played from both seats.
`FREE_FOR_ALL` seats every contestant in every match — the seat swap generalizes to a seat rotation
per seed, and the scoring to *outlasting*, recorded pairwise off `SlotStats.movesMade` so the one
`TournamentTable` serves both formats. For two contestants the formats are the same schedule, and
there is a test pinning that identity.

A `Contestant` is a **configured** seat — a `BotId`, an optional allowance and a `BotParams` — and its
identity is all three. That is what lets `uct` enter twice at two allowances, which is the first
question a testbed of search bots should be able to answer and the one a list of ids could not even
express. Two *identically* configured entries are still a duplicate and still refused. The allowance
is `null` rather than pre-filled, so `TournamentConfig.budgetPerTurn` still has something to do.
`TournamentTable` heads its columns with `Contestant.label` — `uct` beside `uct@4k` — numbers a
repeated label `·2`, and spells the settings out in a legend under the grid rather than in the
headings, which have a narrow panel to fit in.
