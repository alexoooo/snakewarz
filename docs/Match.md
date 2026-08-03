# Match

**For:** touching `match/` — human input, the turn driver, stats, replays, tournaments.
**Assumes:** [`../AGENTS.md`](../AGENTS.md) — the module graph, the forbidden dependency edges and
the five non-obvious facts live there and are **not repeated here**.
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

**A match with a person in it is turn-based, and runs a clock only while they are holding one.**
`StallPolicy.WAIT_FOR_INPUT` is the default of both `InteractiveBot` and `PlayableRegistry`, so a
human slot answers `Pending` on every turn it has no input for. On the keyboard that is the whole
story: `:ui` does not start `TurnScheduler`, one keypress plays exactly the round it belongs to, and
the transport is disabled because there is nothing running to stop. A **drawn route** is the
exception, and it is a `:ui` decision rather than a change here — a held drag fills the queue and
starts the scheduler, so the snake walks the route at the speed on the slider, and letting go empties
the queue and stops the clock. Nothing in this module knows the difference: the queue is full or it is
not, and `AwaitingInput` is what an empty one produces either way. When the player is eliminated
`interactive` goes false, the scheduler takes over and the survivors finish the match on the clock.
`Match.interactive` is deliberately not `bots.any { it.interactive }`: `ScriptedBot` claims to be
interactive so that a partial recording parks rather than forfeits, so playback is excluded by the
recording `Match.playback` hands the driver.

**A parked replay is parked for good, and stepping it again throws.** That park is the only place a
waiting person and an exhausted script look alike, and they are not alike at all: no key exists
behind a scripted slot, so no later step can answer differently and a caller looping until
`outcome` is non-null never returns. So the first `AwaitingInput` under playback is the signal —
`Match.playbackExhausted` is the same fact without stepping to find it — and the second ask is an
`IllegalStateException` naming the recording and the turn it ran out at. Stop on the signal; do not
catch the throw.

**A held key repeats on our clock, not the operating system's.** `Chrome` drops
`KeyboardEvent.repeat` — a text-editing rate, half a second of nothing then thirty a second, and
different on every machine — and `SteerRepeat` (in `:ui`, on `requestAnimationFrame`) turns a held key
into one move every 250ms. A tap is exactly one move. A second key pressed while the first is down
takes the repeat over, and `blur` cancels it, because a key released while the page is not looking
never sends `keyup`.

**A trapped player plays a fatal move instead of waiting.** `InputBuffer.take` filters illegal
input, so once nothing is legal no key the player could press would ever come back from it, and
`WAIT_FOR_INPUT` would park that match for good. Every direction from there is the same death — the
engine records `TRAPPED` whichever is played — so this is a move in the sense that a snake has to
make one, not a choice, and it is not the `MoveTracker` bug (which invented a *survivable* move
nobody chose).

### A drawn route is a plan, not a promise

`PathPlanner` draws two different things. A press calls `route`, which searches from the anchor under
the head to the square the pointer named; a drag calls `trace`, which draws the line from the route's
end towards the pointer and cuts it where it is blocked. Each part of that is load-bearing:

- **`route` is breadth-first rather than "append the square if it is adjacent".** A press names where
  to go rather than how, so the answer has to go *round* a body and round a wall. Among equally short
  routes, reconstruction stays nearest the straight grid line, producing a staircase on an open
  board. `trace` uses the same 4-connected staircase without search, because a drag names the way and
  must not be allowed to detour or jump.
- **A square is passable at plan index `i` if it is free now, or its owner is alive and will have
  retracted past it within `i - 1` of that snake's own moves.** `Clearance` is that arithmetic, and
  **two unrelated reasons produce the same `i - 1`** — collapse them into one and whichever is fixed
  is lost. For your own body it is an *ordering rule*: `Board.apply` reads `isFree(target)` before the
  tail retracts, so your tail has clearance 1 and the route may enter it at step 2 and not at step 1.
  For everyone else it is a *move count*: a retraction is visible the moment that snake's own move is
  done, and an opponent has made `i - 1` or `i` moves depending where it sits in the cyclic to-act
  order from the current `board.toAct` — which is not its slot index, because a route can be begun
  mid-round. `i - 1` assumes fewer retractions, so it believes more squares occupied. Conservative,
  always, and conservative is the only safe direction: `InputBuffer.take` answers a discarded
  direction with *the next legal one from the same route*, so an over-optimistic plan makes the snake
  skip to a later leg rather than stop.
- **Three things are still unpredicted, and each is deliberate.** An opponent's future head — a square
  free now is assumed free forever; a dead snake, whose body freezes where it fell; and
  `growEveryNthMove == 1`, classic Tron, where no trail ever clears. `InputBuffer.take` remains the
  other half of the bargain: a queued direction that has become illegal is *discarded* rather than
  played, so a square somebody else took costs the rest of the route and not the player's life.
- **`route` returning `false` is an ordinary answer, not a fault**, and so is `trace` appending `0`.
  Off the board, onto a wall, into a pocket, or longer than the queue can hold all read the same way:
  the path is left exactly as it was. The one answer that is not a refusal is a press on your own
  head — a zero-length route *exists*, which is what lets a freehand drawing start from nothing.
- **`revalidate` truncates rather than replans.** Everything past the first square that will still be
  held when the snake could reach it is dropped, once per step, so a held route stays honest as
  opponents move across it. Truncating to a bare anchor is not a state of its own: `InteractiveBot`
  answers `Pending` and the clock above waits.
- **The path is anchored on the head and consumed as the snake walks it.** `advance()` drops the
  square just left; `:ui` calls it on every move the player's slot makes and re-anchors when the
  snake lands somewhere the route did not spell out.

**`InputBuffer` has two capacities because it serves two intents, and `replace` is the second one.**
`push` collapses a repeat of the direction queued last, because a held arrow key fires `keydown` at
the operating system's auto-repeat rate and would otherwise eat the next several turns the player
meant; `DEFAULT_CAPACITY` is three, because a deep keyboard queue *reads as input lag* — every key
waits behind the ones before it and the snake stops answering the one just pressed. `replace` swaps
the whole queue as one intent and neither collapses nor drops: five squares east is five moves, not
one, and swapping in whole is what makes letting go of a drag mean **stop**. `PATH_CAPACITY` is 512,
which is deep without being a backlog because the player takes all of it back by lifting a finger, and
is what `PathPlanner` bounds itself by. Both live here rather than in `:ui`, so a route can be planned
and a queue driven on the JVM with no DOM anywhere near them.

## The map is in the header, as squares

`MatchSetup` carries `walls` beside the geometry, the rules, the spawns and the turn order, for the
reason its KDoc gives for all of them: **a recorded match replays under the layout it was played
under, never under today's defaults.** They are *playable* indices, `row * cols + col`, strictly
ascending — the same canonical form `BoardMap.walls()` produces, so a generated map feeds a setup with
no conversion, and ascending order buys duplicate detection in one pass, an `equals` that compares
maps rather than orderings, and an array the spawn check can binary-search.

**A shape id never travels.** Freezing one would make every generator's internals a compatibility
contract for every URL anybody has ever shared; carrying the squares themselves means **a map shape
can be redesigned or deleted without breaking a single shared link.** Run-length encoding was measured
and rejected on the shape a map actually has: a 20x20 pillar lattice is about twenty runs a row, some
four hundred bytes, against a raw bitmap's fifty. `docs/Maps.md` is the catalogue and how to add to it.

Two knock-on rules live here rather than in `map/`:

- **A spawn may not stand on a wall, and beyond seat 2 must be *reachable*.** `mostDistantSpawns`
  seats slot 0 at the lowest open square and slot 1 at the highest — exact images of each other under
  the half turn, which is where a two-seat opening's fairness comes from — and filters every later
  candidate through `openRegionFrom` so nobody starts in a pocket the map sealed off. The scoring
  metric stayed **Euclidean**: graph distance on a wall-free rectangle is Manhattan, a different
  argmin, so switching would have moved every three-seat opening *on an empty board* and invalidated
  every three-seat replay header already written. The escalation is available and unspent, and
  `mostDistantSpawns`' KDoc names the condition.
- **`MatchStats` counts the board's open squares, not its area.** A share of the board is a share of
  what a snake could stand on; the geometry is not that quantity once a map exists.

## Replays arrive from strangers

A `#r=` payload is the one input to this program nobody here wrote, so **every field the codec decodes
is bounded before anything allocates from it** — `BotId.MAX_LENGTH`, the three `BotKnob` ceilings, and
`MatchSetup.MAX_SIDE` for the geometry, which is the field that allocates most. That is
[SW-09](Coding-Standards.md#sw-09--a-bound-that-protects-an-allocation-runs-before-the-allocation),
and the ordering is the whole of it: a check that runs after the array is a check that has already
lost, and it loses as an `OutOfMemoryError` rather than the `IllegalArgumentException` `:app` catches
to fall back to a fresh match.

**The geometry bound runs at the read site, and that is not where it used to be.** `MatchSetup.init`
checking `MAX_SIDE` was enough while nothing between the two varints and the constructor allocated.
The wall bitmap does: it is `ceil(rows * cols / 8)` bytes off a pair a varint can inflate to a quarter
of a billion squares. So `ReplayCodec.decode` range-tests `rows` and `cols` against the same constant
immediately after reading them — the range form, because `+ 1` on a varint can wrap negative and a
negative passes every ceiling downstream.

### The version, the flags, and why an old link is byte-identical

The header opens with a version byte and a flags byte. Bit 0 is a per-slot configuration block, bit 1
a wall bitmap, and bit 2 says a trapped sole survivor takes its fatal turn. `versionFor(flags)` is the
**only** place that maps those features to versions, and it says the version written is *the oldest
that can express the record*. Versions 1–3 carry the immediate-survivor rule they were recorded
under, so an old link still decodes and re-encodes byte for byte; a new match uses version 4 even when
it has no map or per-slot configuration.

Writing the newest version unconditionally would have cost every plain replay two bytes and a needless
incompatibility. Writing a flag without raising the version would leave an older page reporting *"the
flags byte is reserved and must be zero"*, which is true and useless. Together they let an older page
say the version is unsupported, which somebody can act on. The decoder holds both ends: an unknown
flag bit is refused, and so is a known bit at a version too old to have meant it.

The bitmap itself is one bit per playable square, low bit of each byte first — the packing
`DirectionStream` already uses for moves. Two rejections keep a map's spelling **unique**: bits set
past the last square of the board, and a `MAPPED` flag over an empty wall set. Without them two
payloads describe one map, `encode(decode(x))` stops being `x`, and a link can come back spelled
differently from the way it was sent.

A payload's **length** is bounded by `RulesConfig.maxTurns` — two bits a turn, so the longest match
the rules allow is around 1,400 base64url characters and every real one is a fraction of that. If a
future rule set ever pushes past what a URL comfortably holds, the answer is a downloadable record
file rather than a longer link; the codec already produces bytes, and `#r=` is only one transport
for them.

**`MatchRecord.verify` treats a partial recording as a prefix.** `outcome == null` means the record
stopped before the match did, which is the *usual* shape for a shared link — `GameSession.share()`
calls `record()` at whatever turn the board is on. The replay always runs to completion and is longer
by construction, so `verify` compares the recorded moves and the eliminations inside the recorded
turns, and stops there. A replay that ends **short** of the recording is still a divergence, and a
finished record is still held to an exact match.

### One record is content rather than history

`demo/DemoReplay.PAYLOAD` is a thirty-turn 8x8 match that the home screen plays on a loop to show new
players what winning looks like. It is a payload like any other, and everything above applies to it —
which is the point of it being one.

**It is authored, not played, so it does not `verify`.** `verify` re-runs the real bots from the seed
and asks whether they still play these moves; they never did. A match between two real bots would have
been cheaper to obtain and would have ended however the seed decided — a wall bump, a mutual crash, a
turn limit — and this record has one job: the loser must end **boxed in**, with two walls ahead of it,
its own body behind it and the winner's head immediately below. The slugs name real bots so the seats
would read sensibly if anything showed them, and nothing more should be read into them.

**It lives here rather than in `:ui`, which draws it.** `:ui` has no JVM target, so its suite runs only
under `-PbrowserTests=true`; a demo that quietly stopped decoding, or started ending in a draw, would
go unnoticed. In `:match` it is covered by the default `./gradlew build`, and `DemoReplayTest` pins the
story — board size, an empty map, the turn count, the winner, and `TRAPPED` rather than any other
fate — so a payload swapped for a prettier one still has to end with somebody out of room. The module
already hosts fixed content in `gauntlet/`; this is the same kind of thing.

Authoring one is `MatchSetup`'s raw constructor rather than `create` — `create` shuffles the turn
order from the seed and derives spawns through the internal `mostDistantSpawns`, and a demo wants both
fixed. The moves are a single interleaved stream in play order, the fatal move needs no symbol because
an illegal recorded direction describes itself, and `outcome` must be set: a `null` outcome is a
partial record, which parks on `AwaitingInput` and throws on the step after.

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
usually looking at it.

**The schedule is a class of its own**, and separate from the driver that plays it.
`TournamentSchedule(config)` answers `setupFor`, `seatingFor`, `seedFor` and `pairKeyFor` without a
registry, a table or a match — it is a pure function of the config, so asking what is coming should
not require constructing a player. `:ui` paints an opening position with it, a test asserts the seat
swap with it, and `:lab` plays the same schedule its own way — in parallel, from diversified openings
— and is still running *this* schedule rather than a re-derived guess at it.

`pairKeyFor` names the group of matches sharing a board, and it is answered here rather than
recomputed by every caller that wants it: head to head that is the seed played from both seats, free
for all a rotation through every seat, and it is the unit a paired comparison counts in. Four
consumers re-implementing `(index / rounds, (index % rounds) / 2)` is three chances at a silently
wrong confidence interval.

**Scoring is one function for both formats.** `pairwiseOutcomes(format, stats)` turns a finished
match into the comparisons it settles, and both `Tournament` and anything measuring a batch from
outside this module fill their matrix through it and through `TournamentTable.record`. The two
would otherwise be free to disagree about the same match. That the head-to-head rule and the
outlasting rule *agree* for two snakes is true today for a non-obvious reason — `Board` resolves a
field of two the instant one dies, so the `movesMade` tiebreak never fires — and is one rules change
away from not holding, which is why the function asks the format rather than assuming the answer.

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

## Ratings, and what a rating will not tell you

`fitRatings(table)` reads the matrix as a single ordering, which is what a matrix cannot give once
the field is larger than a pair: a bot can win more matches than another and still lose to it, having
met different opposition. It is Bradley-Terry fitted by the Zermelo iteration — half a dozen lines, no
derivatives, no matrix — with a draw worth half a win because `scoreRate` already says so and two
summaries disagreeing about a draw would be worse than one being slightly conservative.

Two things about it are there to stop it overclaiming.

**A phantom opponent bounds the fit.** Left alone the likelihood has no maximum for a contestant that
never lost — it climbs forever — so every contestant gets one virtual drawn game against a fixed
strength of `1`. That is also why nothing is rescaled *inside* the loop: the phantom makes the overall
scale mean something, so re-centring each pass would walk off the fixed point and make the answer
depend on the iteration count. Centring happens once, at the end, to the Elo figures only.

**It says which ratings are the prior speaking.** The fit is identifiable only where the results
*strongly* connect, which is stronger than it sounds: A having beaten B twenty times with no draws
leaves the pair unbounded. So the win digraph's strongly-connected components are computed, and
anything alone in one or outside the largest is flagged `priorDetermined`. A ladder that presented
those gaps as measurements would be presenting a regularizer.

Ordering never goes through a logarithm. The fit is in strengths, where every step is `+ - * /`;
`ranking` and `expectedScore` read those, and the Elo figure — which needs a `log10` that is not
specified bit-identical across the JVM and wasm — exists only to be displayed. Two close contestants
cannot swap places between targets.

`expectedScore` is what makes a rating checkable rather than merely orderable: compare it with what
happened and a cell that disagrees is a pairing the single number cannot describe. Those cells exist
here, and `:lab`'s `rate` prints the worst of them.

## The gauntlet is a table here, so it can be measured

`gauntlet/` holds the seven single-player levels, and it is in `:match` for one reason: `:ui`, `:app`
**and `:lab`** all see this module, while `:ui` may never see `:bots`. Putting the table here is what
lets `:lab` play the exact match a player will play and *measure* that level 7 is harder than level 6.

- **A `GauntletLevel` is a whole match configuration, not a difficulty number.** Three things move from
  rung to rung and only one of them is the bot: the geometry changes, each surviving wall shape is
  used once before the empty-8 boss, and a searcher's allowance grows. Each moves
  the game about as much as swapping the algorithm does.
  `setup(seed, human)` builds an ordinary `MatchSetup` from all of it — human in slot 0, opponent in
  slot 1, turn order still shuffled from the seed, because a level is meant to be hard rather than
  unfair — so **a level is shareable, replayable and scrubbable exactly like a custom match**, and a
  shared level link comes back as a custom match because a replay carries no level number.
- **The opponent is a slug and its knobs are pinned.** A slug because this module has never seen a bot
  class; pinned because a level is a character a player learns to beat, and a registry default moving
  under it would quietly hand somebody a different opponent at the same number.
- **`index` is frozen within this campaign identity**, and harder than a `BotId` is: it is the key
  somebody's saved progress is stored under. A replacement campaign therefore gets a new storage
  identity instead of pretending old level numbers name the new matches.
- **Two of the seven grant an allowance of zero**, which is the honest figure rather than a
  placeholder: those bots spend nothing whatever they are handed, and writing a default there would
  imply their difficulty has a knob in it.
- **The wall seed is pinned separately from the match seed.** A retry still changes turn order and bot
  randomness, but it cannot redraw `scatter` or `islands`. The board qualified in `:lab` is therefore
  the board a player gets, and a rung remains a place that can be learned.

**The order is measured per level and is not the registry's.** `BotLadderTest` certifies its rungs on
an empty 12x12 and that ordering survives neither a map nor a board size; `:lab`'s `gauntlet`
subcommand plays every level's opponent on that level's own board, pinned map and allowance against
fixed references. The ordering is accepted when neither reference's score rises beyond the declared
five-point tolerance. [`Bots.md`](Bots.md#the-single-player-gauntlet-is-a-different-ordering-and-it-is-measured-per-level)
records the campaign and the qualification rule.

## No worker, and where the seam would be

**Web Workers are deferred deliberately, and `Bot` never becomes async.** One would cost a
serialization boundary, a second wasm instantiation, and a message layer that would leak into `Bot` —
destroying the synchronous property the entire search layer rests on, since a bot runs the engine
inside its own turn using another bot as its rollout policy. Allowances are counted rather than
timed, so a turn's worst case is bounded without one, and `:ui`'s frame guard degrades the turn rate
instead of freezing the page.

The seam is nevertheless already in the right place, and that is what `:match` being headless buys.
A batch is naturally message-shaped — send a `MatchSetup`, get a `MatchRecord` — and it is the
*match* that would become asynchronous at that boundary, never `Bot`. What a worker would buy today
is a second core rather than a responsive page, because slicing this driver across frames already
delivers that. A smaller and much later prize.
