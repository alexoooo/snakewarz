# UI

**For:** changing anything in `ui/`, `app/.../index.html`, `app/.../styles.css`, the boot path, or
the GitHub Pages deployment.
**Assumes:** [`../AGENTS.md`](../AGENTS.md) — the module graph, the forbidden dependency edges and
the five non-obvious facts live there and are **not repeated here**.
**Enforced elsewhere:** `checkModulePurity` fails the build on `:ui → :bots`, so nothing here can
tell a wall hugger from a human. It does not check anything below that.

## Working on the UI

`:ui` exposes exactly three things — `GameSession`, and the two seams `ReplayLink` and `Portraits`.
Everything else is `internal`, and should stay that way; `:app` builds a session and is otherwise
under fifty lines of wiring.

### One-way data flow, and two cadences

Inside, it is a one-way data flow with no virtual DOM. State goes down through `Chrome.render(model)`,
everything a person does comes back up as a `UiIntent` into `GameSession.dispatch`, and the board is
painted separately per turn because stroking the snakes again is nearly free while writing text is not.
Keep those two cadences apart: `UiModel` is built once per *frame*, not once per turn.

### Playing, replaying, and five clocks

**Playing and replaying are one code path.** A replay is a match whose slots already know what they
are going to do, so run, pause, step and restart work on both without a branch. Only seeking is
replay-specific, and it is implemented by rebuilding the playback match and stepping to the target —
microseconds, and nothing to keep consistent. Presentation still follows the retained mode: a
Gauntlet replay keeps its rival card and hidden scoreboard, while a custom replay keeps its seat
cards. The replay transport says *Run replay* and *Restart replay*, because *Play* and *Restart*
beside a human recording sound like ways back into the game rather than ways through the recording.

What *does* branch is which match clock runs, and it branches on `Match.interactive` rather than on a
mode flag: `TurnScheduler` paces bots and replays, while a match with a live player is stepped by
`GameSession.playRound` from direct input. `SteerRepeat` is the second input clock, producing held
arrow and D-pad directions at a human rate rather than advancing the match itself. `TournamentRunner`
is the third clock and the only one with no speed at all — a batch is not something you watch at a
rate, it is something you wait for, so it runs flat out on an 8ms-per-frame guard and reports progress
instead. The paint-only `Ticker` is the fourth and is detailed with the overlay below.

`AnimatedSteering` is the fifth: rapid arrow, WASD, or D-pad directions are still intentions, but a
second one cannot advance while the renderer is gliding the first move. It keeps up to three direct
directions in order and releases one on the first animation frame after the preceding 50ms move
transition finishes. The first direction remains synchronous when no move is animating, reduced
motion adds no delay, and match replacement, navigation, a route taking over, or another overlay
cancels the anticipated directions with the other controls.

That keydown sentence is the **keyboard's half** and no longer the whole of an interactive match.
There is a fourth arrangement, and it is the first time a live player has run `TurnScheduler`: a held
press starts it, so the same clock that paces two bots walks a person's snake along the route they
asked for. Which clock runs still branches on `Match.interactive` and on nothing else — what a press
changes is whether the scheduler is *started*, not what decides.

Its catch-up rule does branch there: a live player's scheduler exposes **at most one turn per browser
frame**. A browser paints only after the animation callback returns, so batching three turns would
hide two intermediate positions and make the first snake appear to move twice. Bot matches and
replays may still spend accumulated turns in one frame; only their final position matters.

**A press is the one thing that puts a live player on the scheduler**, and two sentences are what make
the whole interaction legible:

- *What you can see is what a press will do.* Hovering a square previews the route from your head to
  it, dimmer and committed to nothing, because the preview and the press call the same
  `PathPlanner.route`.
- *A press says go there; a drag says go this way.* A press searches, so it goes round a body and
  round a wall. A drag traces the pointer literally — a staircase appended from the route's own end
  and cut where it is blocked, which can neither detour nor jump.

So: press a square there is a route to, and `GameSession` takes hold, swaps the whole queue with
`InputBuffer.replace`, plays **exactly one move** along it and starts the scheduler. Keep holding and
the rest is walked at the speed on the slider; a quick click costs one step and no more, because the
`pointerup` a few milliseconds later discards what is left, lets the opponents finish the current
round and parks on the player's next turn. Press a square with no route — a wall, a sealed pocket,
off the board — and nothing happens at all while a legal move still exists: no hold, no step, no
clock, which is what the empty preview already said. If no legal move exists, that same press polls
the player once and `InteractiveBot` makes the forced fatal move instead of leaving finger control
parked forever. Press your own head and the zero-length route counts as existing, so it takes hold
and plays nothing; that is how a freehand drawing starts.

**There is no grace radius any more, and the reason it had one is why it is not missed.** It existed
because a fingertip covers several squares of a large board while the browser reports one point
somewhere under it, so a press that missed the head by a square had to mean what a press on it meant.
Now every press takes hold if a route exists at all, and a near miss costs one step *towards where the
finger already is* rather than a wrong move.

**The one thing that genuinely breaks is the feature:** with a human seated, any click on the board
costs a move and there is no cancel. It is exactly one move, and the preview shows where it goes
before you commit — which is why the preview shipped alongside it rather than after it. What stands
between a stray click and a lost snake otherwise is CSS: `#panel-scrim` (`z-index: 10`) and
`#dialog-result` (`z-index: 20`) are full-viewport and above `#board`, so no press reaches the canvas
while either is up. That is a **CSS invariant with no Kotlin counterpart** — the pointer has nothing
like `Shell.boardHasKeys`.

Six consequences, each of which is a thing to get wrong:

- **One move per press means `playRound` is the wrong primitive.** It plays until an interactive slot
  has nothing queued, and with a whole route just swapped in that is a slot per snake plus the poll —
  the entire route, not a square of it. `GameSession.playPlayerMove` loops until the player's own
  `movesMade` changes instead, and keeps `playRound`'s two escapes: bail on anything but `CONTINUED`,
  and hand the ending to the clock the moment the match stops being interactive. Release calls
  `playRound` only **after clearing that route**, so it can finish the opponents' outstanding turns
  without buying the player a second move.
- **Letting go is the stop.** Release, cancel or `lostpointercapture` all discard the rest of the
  route, empty the queue and stop the clock, so the snake halts on the square it is on that turn
  rather than finishing the route. The rest of the round still finishes before the match parks on
  the player's turn; carrying an AI turn into the next press makes the two controls appear to move
  twice in turn. Holding is what makes the player's snake move, and that is the whole interaction.
- **A finishing press owns its release.** If the first point-to-move step ends the match,
  `GameSession` withholds the result and replay action while the board gesture remains active.
  `PathInput` retains capture and reports the ordinary release; only then is the route settled and
  the result dialog exposed. Match replacement, navigation, and a new overlay still cancel controls
  synchronously, because those are genuine ownership changes rather than steering becoming false.
- **A route that empties while still held needs no state at all.** The queue runs dry,
  `InteractiveBot` answers `Pending`, `Match.step` reports `AwaitingInput`, and the scheduler clamps
  its accumulator and waits — so no debt builds while the player thinks, and dragging further just
  refills the queue. There is no "parked" flag and there must not be one.
- **A held route spends at most one turn per rendered frame.** `TurnScheduler.oneTurnPerFrame` is
  supplied from `Match.interactive`; after a stall it drops excess credit rather than drawing only
  the last of several turns. Do not impose that ceiling on bots or replays, whose scheduler is also
  their catch-up clock.
- **`consumePlan` is the single authority over the queue while a route is held**, and it is three
  obligations in one place. A plan is anchored on the head, so `PathPlanner.advance()` drops its first
  square on every move the player's slot makes — miss that and the painted route trails a square
  further behind every move. `PathPlanner.revalidate` then cuts it where an opponent has moved across
  it, on **every** step and not only the player's, because an opponent is what cuts a route. And the
  queue is rewritten from the plan in the same breath, so the painted route and the moves waiting to
  be played can never become two accounts of one thing — miss *that* and the snake walks a route the
  overlay has stopped showing, with no pointer event coming to put it right.
- **An arrow key mid-drag takes over outright**, route and queue and clock together. Two ways of
  saying where to go, left to interleave, produce a move neither one asked for. `steer` opens with
  `endPath()` and then pushes its key, which is why that queue rewrite is guarded on a route actually
  being held: unguarded, it would swap the keypress straight back out.
- **Rapid direct directions wait for separate move transitions.** Two pointer or key events can run
  before a browser paint; stepping both synchronously replaces the first `MoveTransition`, making the
  snake jump a square even though both turns were played. `AnimatedSteering` holds later directions
  outside the match's route queue and releases one only when `BoardRenderer.moveAnimating()` is
  false, so each direction gets its own visible glide without changing their order.

The press, the drag and the release are `UiIntent.Shell` for **Hover**'s reason, and taking hold is
refused outright while a batch owns the arena — the board on screen is then the tournament's, so a
press on it is not this player steering, whatever square it lands on. That refusal is what makes the
tier honest: a pointer dragged over a running batch changes nothing about it, and does not take the
arena off it either. It lives with the rest of the conditions in `GameSession.canSteer`, which the
preview and the press share so that what is painted and what a press does are one decision — and which
adds `outcome == null` to `Match.interactive`, since that only tests whether the player is still
*alive* and would otherwise plan a route across a board that is over.

The one place the shared path *does* need to know which it is: **running off the end of a partial
recording is terminal, not a pause.** A scripted slot with no move left answers `Pending`, which under
a live player would mean "waiting for a key" — but there is no key that could resume a recording, so
`GameSession.advance` reports `FINISHED` when `replay != null` and the scheduler parks. Without that
it re-arms `requestAnimationFrame` forever, stepping once a frame to be told the same thing, while
the Run replay button reads "Pause" and says nothing is stopped. Running a parked recording therefore
rewinds it first, exactly as running a finished replay does.

While a batch runs it **owns the arena**: `GameSession` paints its current match and builds the whole
`UiModel` from that match, so the board, the scoreboard and the status line cannot disagree. The transport
is greyed, and `dispatch` drops transport intents outright — the space bar does not read the DOM's
disabled flags. Touching the transport afterwards hands the arena back with a full `fit`, because the
renderer paints one square at a time and would otherwise step a match onto somebody else's board.

**Hover is answered above both of those guards**, and that placement is the whole of it: asking what
is under the pointer changes nothing, so it neither has to be dropped while a batch owns the board
nor is grounds for taking the board back off one. Put the branch below either guard and moving the
mouse across a finished tournament's last position silently swaps it for the player's own game.

Which side of the guards an intent is answered on is **its type, not its position** in a `when`:
`UiIntent` is split into `Shell` — the pointer, a relayout, navigation and the panels — and `Match`,
everything that acts on the match. `GameSession.dispatch` is a two-line fork over that pair, so a new
intent has to declare its tier where it is declared, and a rewrite of the dispatcher cannot lose the
decision. Opening a panel is a `Shell` intent for exactly Hover's reason: it changes nothing about
the match, so folding one away must not end somebody's tournament.

### Steering with a thumb

**A drawn route cannot be the only way to steer, because on a phone the finger drawing it is over the
squares it is aiming at.** `SteerPad` is the answer on every screen size: four arrows in
`#steer-pad`, feeding
`UiIntent.Steer` through the same `SteerRepeat` a held arrow key drives. There is no second control
path — a tap is one move and a hold is a move every 250ms, on a keyboard and under a thumb alike.

- **The arena reserves the room.** In portrait, the board owns the first row and the rival/pad own a
  second row; in landscape, rival, board, and pad occupy three columns. The board shrinks inside its
  own `minmax(0, 1fr)` track on a tight screen, so controls never overlap or clip a canvas edge.
- **The cluster is a fixed 3×2 map.** W/up is centred above A/left, S/down, and D/right. Each key is
  clamped from 48 to 64 CSS pixels and has theme-derived hover, focus, held, and disabled states.
- **It is shown exactly while `UiModel.steering`** — which is `GameSession.canSteer()`, the very
  predicate a press on the board is answered by, so the pad offers what the board would accept.
  Absent rather than greyed in bot matches, replay playback, intros, and finished games.
- **No `setPointerCapture`, and the release listens on `window`.** Capture throws outright on a
  pointer the browser has stopped tracking, which would take the press down with it; what it exists
  to prevent — a thumb that leaves the pad before it lifts, leaving a snake walking with nobody
  holding it — is answered by listening for the release globally instead. `PathInput` keeps its
  capture because a *route* genuinely needs the moves in between, and this does not: sliding off the
  pad keeps the direction it left with.

### Screens, panels and focus

The page is **three static sections** — `#screen-home`, `#screen-gauntlet`, `#screen-game` — of which
exactly one is visible, plus four panels, a backdrop and one modal. `Shell` owns which is showing;
`Chrome` owns the game screen; `HomeScreen` owns which modes are offered; `GauntletScreen` owns the
seven level tiles; and one class per panel, under `chrome/panel/`, owns what is behind each — `SetupPanel`
the new-match form and its seats, `TournamentPanel` the schedule and the matrix, `SharePanel` the
link, `SettingsPanel` the theme and the speed.
Every one of them is rendered from the same `UiModel` on the way through `Chrome.render`.

The home screen's release badge is written into `index.html` whenever the app processes its browser
resources, which covers the development server and both development and production distributions.
That task is neither cached nor considered up to date, so its UTC instant identifies this browser
build rather than one whose resources Gradle restored. The browser converts it locally and renders
the fixed numeric form `Release · yyyy-MM-dd HH:mm:ss`, with no locale-dependent month or zone label.

- **`#panel-setup` is the tall one and is the reason panels scroll.** It carries the board size, the
  map, four seats with their knob grids and the seed, so it is what `.panel-body`'s `overflow-y:
  auto` exists for. It is also **what the rest of the tree means by "the sidebar"** — the phrase is in
  KDoc across `:bot-api`, `:bots`, `:match` and `:lab`, all of which are talking about the one form
  where a match is configured and a knob is offered. There is no column on this page.
- **The map picker offers shapes, and a replay's map is not a shape.** A `#panel-setup` shape is a
  *recipe*: it is redrawn at whatever size and seed the boxes above it say, so moving either moves the
  map. A replay carries the wall squares themselves, so `applyMap` recognises a shape by redrawing
  each at the setup's own size and seed and comparing — and where none matches, the bitmap becomes the
  picker's own answer under the otherwise-hidden `#map-from-replay` option. Anything that moves the
  board discards it, because a map drawn for another board is not a map. Options the chosen board is
  too small for are `disabled` off `MapShape.minimumSide`, which is the single source of that number:
  a copy in the markup is a Start match that throws. **A disabled option also says what it wants** —
  `Rooms — needs 14 × 14` — because half a list greyed with no reason reads as a broken picker. The
  base label stays the markup's: it is captured once when the options are resolved, and Kotlin only
  ever appends the suffix to that, never to whatever the last write left behind.
- **The form previews the board it describes**, on the arena behind it. A panel is an overlay over a
  translucent scrim, so the real board is already visible beside a 24rem panel on anything wider than
  one — which makes a second canvas a second thing to keep in step rather than a feature. Moving the
  size, the map or the seed raises `UiIntent.PreviewSetup` and `GameSession` builds one `Match` from
  it: one board and no search, the same cost `fitToBatch` pays, and the spawn squares come with it
  because *where will I start on this map* is half of what picking one asks. Closing the panel and
  starting a match both put the player's own board back. The size handler dispatches **after**
  `refreshMapOptions`, and that order is load-bearing — `generateMap` throws on a shape the board is
  too small for, and that call is the only thing preventing it, which is also why nothing catches
  around the build.
- **Which panels a mode offers is `Mode`, and it is enforced by hiding the button that opens one.**
  A gauntlet level *is* its configuration, so Setup and Tournament are not offered there — and a
  control that could never apply is absent rather than greyed, which also takes it out of the tab
  order. `Mode` is deliberately not derived from `Screen`: a level and a custom match are both played
  on `#screen-game`, so the mode is decided by the way in and kept while the board is up.
- **A hidden screen is `hidden`, never merely off-screen.** An off-screen but focusable section means
  Tab walks into controls nobody can see.
- **A panel is an overlay, not a column.** Opening one leaves the board's box exactly where it was, so
  there is nothing to re-measure and a batch underneath keeps its arena. A panel that ever *pushed*
  the board would have to come up as `UiIntent.Relayout` — which is why that intent is still here
  with **no emitter**: the tournament disclosure that used to raise it is now `#panel-tournament`.
- **`inert` on everything that is not the overlay on top is the whole of the focus trap.** The
  panels and the dialog are siblings of `#app` rather than children precisely so one attribute can
  put a whole screen out of reach; with the unopened overlays `hidden`, the only focusable elements
  left in the document are the ones inside the open one, so Tab cycles within it without a key
  handler counting elements. **The set is `#app` *and the four panels*, not `#app` alone** — that
  sibling relationship cuts both ways, so a panel still open when the match ends would otherwise sit
  beside the result card, visible and still a tab stop, while the card claims `aria-modal`.
  `Shell.behind` is that list and `ShellTest."while the verdict is up, the only controls Tab can
  reach are its own"` is what keeps it honest.
- **The focus is handed over and handed back.** Opening an overlay remembers what had it and moves it
  to the overlay's `[data-focus]` control, or to the overlay itself where it names none — every screen
  and panel carries `tabindex="-1"` for that. Closing gives it back. **Navigating also moves it**, to
  the section that arrived: the control that navigated is on the screen that just left, so otherwise
  the focus drops to `<body>` and the next Tab starts in the browser's own chrome.
- **`[data-focus]` is a list, and the focus goes to the first entry a person could press.** A
  container is allowed to mark several, because which is the default can depend on what just
  happened: the verdict marks Retry, Next level and Home and shows one or two of them, and the level
  select marks whichever tile is open. `focusInto` skips the hidden and the disabled, so a card whose
  default action is not showing still lands somewhere — focusing a hidden element silently does
  nothing, which would leave the focus on a screen that has just gone away.
- **Escape closes the overlay on top; with none open it goes back a screen.** One `ClosePanel` intent
  rather than one per overlay, because which is on top is the session's to know.
- **Back is one screen out, not one screen home.** A rung of the gauntlet is left to the level
  select, so walking out of level 7 does not also cost the seven tiles. `#game-back`'s label is written
  from the same answer, and the verdict card's way out and Escape are the *same call* — which is
  where the consistency comes from, rather than from three destinations that have to be kept equal.
  The level select's own way out is always the menu: `level` outlives the screen it was chosen on, so
  the screen is part of the question.
- **While an overlay is up the board does not have the keyboard.** `Chrome.onKeyDown` asks
  `Shell.boardHasKeys` before its own two guards, so the space bar activates the focused button — which
  is what pressing it in a panel means — and the arrow keys steer nothing behind a scrim. Its two other
  guards still matter on the game screen: a focused `<select>` or slider owns the arrows, and a held
  modifier means Ctrl+A and Alt+Left are not swallowed.

Leaving the game screen calls `scheduler.stop()` and `batch.stop()`: a match nobody can see must not
keep running. It is also the one navigation that ends in a `refit`, and it renders the chrome
**before** it measures, for the reason `begin()` does — the board's track belongs to the screen that
just appeared.

### The gauntlet, and the one thing on this page that is remembered

**A level is an ordinary match, and that is the whole design.** `GauntletLevel.setup` in `:match`
builds one from the rung's own board, map, opponent and allowance; `GameSession.startLevel` runs it through
the same driver, the same renderer and the same codec as anything else, so a level is shareable,
replayable and scrubbable like a custom match. **The mode must never branch the match code path.**

What the mode does branch comes off `UiModel.level` — the
rung number, `null` for a custom match:

1. **Which panels are offered.** `Mode` is derived from `level`, and `Mode.offers` hides `#panel-setup`
   and `#panel-tournament`: a level *is* its configuration, so re-seating it would be playing
   something else under its name.
2. **What the verdict offers.** A lost level shows Retry, a beaten one shows Next level, the seventh
   shows Home and says *Gauntlet cleared*. `UiModel.nextLevel` is derived from `level` and
   `levelCleared` so the card cannot offer a rung that does not exist.
3. **What the top bar names** — the level, where a custom match is named by its seat cards. It is the
   same single line either way, because the bar's height is what the board's track is measured
   against. On a phone the wordmark is hidden by the `max-width: 30rem` rule, and the rival card is
   what identifies the level there.
4. **The rival presentation.** The scoreboard is absent from both live levels and retained level
   replays. Its sole replacement is a prominent card outside the board with the campaign title,
   configured bot label — including any applicable allowance and mode — portrait, live length, and
   outcome state. Custom matches and tournaments retain the ordinary scoreboard unchanged. On the
   first live entry to each level, a full-viewport version holds and fades for about 1.5 seconds while
   pointer, keyboard, and pad input remain blocked.

First-entry memory is independent of unlock progress. `snakewarz.gauntlet.intros.v1` is a decimal
bitmask keyed by frozen level index; missing or malformed storage means none seen. The bit is written
on entry, so retries do not repeat the intro, and neither saved nor shared replay playback enters this
path. Replacing the match, navigating, or opening another overlay cancels the timer and presentation.

**Retry draws a fresh match seed, not a fresh map.** `GameSession.restart` is the one place the mode
is read on the match path: another attempt varies turn order and bot randomness, while
`GauntletLevel.mapSeed` keeps the walls qualified for that rung fixed.

**Progress is `localStorage["snakewarz.gauntlet.v2"]`, written only on a win.** The value is
`v1:<highest unlocked>:<cleared bits>`, hand-parsed by `GauntletProgress` — not JSON, because there is
none in the bundle and pulling one in for two integers is what SW-08 is about. `Preferences` owns
whether the store can be read at all and `GauntletProgress` owns what the text means, which keeps two
unrelated failures apart. Every way of the value being unusable — a missing key, a version this reader
has never heard of, junk, or storage that throws — is a playable level 1, and a `highest` past the end
of the table clamps rather than indexing off it. A level index is **frozen** on release for the same
reason a `BotId` is: it is the key somebody's saved game is stored under.

**The key is a new campaign identity.** The retired eleven-level development table remains under its
old key and this reader does not migrate it: its level numbers named different opponents and maps,
so carrying the bits forward would claim progress through games the player had not played.

Because progress is only ever written on a win, walking out of a level costs nothing — which is why
`navigate` and an `#r=` link arriving by hash may both drop `level` without ceremony.

**Watching a level's own run back is not walking out of it**, though, and that is the one thing
`GameSession.load` takes an argument for. A shared link is somebody else's configuration and clears
the rung; the verdict card's Replay button and the ▷ on a tile both pass the rung they are already
on, so the bar still names the level, Setup and Tournament stay off the offer, and the way out is
still the level select. The parameter is `keepLevel` and it defaults to none, because the route that
has to be right by default is the one a stranger's URL takes.

**A replay keeps the live decisions beside its transport.** *Try again* starts the same rung with a
fresh seed, or the recorded setup and seed for a custom match, and is absent where the recording has
no human seat. A completed level recording won by that human also offers *Next level* unless it is the
last rung. Those actions precede Run, Step and Restart so a replay opened by mistake on a phone does
not hide the way forward.

**Every cleared level also keeps the run that cleared it, one `localStorage` key per rung** —
`snakewarz.gauntlet.replay.<n>.v2`, written by `GameSession.recordLevelWin` beside the progress write
and read by `GauntletScreen` per tile. One key each rather than one holding seven records: writing
one rung rewrites nothing else, a value that arrives corrupt costs that rung rather than the lot, and
there is no concatenation format for anybody to parse. The payload is **not a new format** — it is
`ReplayCodec`'s base64url string, the same one a `#r=` link carries — so a record is self-describing
and a stored run needs nothing beside it to play back. Only on a **win**, because a level you lost has
a replay nobody wants and writing one would make the tile's ▷ mean something other than its `Cleared`
badge; and a payload that will not decode is treated as absent, which is `GauntletProgress.parse`'s
rule applied to the same store. The `.v2` is the lever if the level table ever changes again: a new
suffix leaves these values unread rather than showing somebody a game on a board that no longer
exists.

The tiles are static markup, like the four scoreboard cards, and `GauntletScreen` writes only their
text, state, picture and whether the ▷ is there. It renders **before** `Shell` in `Chrome.render`,
because it marks the open tile `[data-focus]` and the shell reads that on the frame the screen
arrives. It also skips the write entirely when the progress instance and the theme id are both
unchanged: every screen is rendered once a frame, and seven tiles of unchanged text sixty times a
second would be the one wasteful thing on that path. The storage read sits safely inside that cache
because a run is written on the same turn progress is replaced, so the two can never be a frame apart.

**The ▷ is a sibling of the tile, not a child of it.** A tile is a `<button>`, so a nested one would
be invalid markup and would never receive the click; the control lives beside it in the `<li>`, is put
over the tile's top-right corner by `.level-replay` in `styles.css`, and is looked up by its own id.
A locked or unbeaten rung's is `hidden` rather than disabled — the argument `Mode.offers` already
makes for the panel openers, that a control which can never apply should not be present at all, and
hiding is also what takes seven of them out of the tab order on a browser that has cleared nothing.

### Playing it without a mouse

Every screen is reachable, every match startable, playable and restartable, from the keyboard alone.
The map below is written down in exactly two places — here, and the Keys note in `#panel-settings` —
so `Chrome.onKeyDown`, that note and this table are changed together. A shortcut list that has
stopped matching what the keys do is worse than no list at all.

| Key | Does | Where |
|---|---|---|
| Arrows / WASD | steer | the game screen, unless a `<select>` or a slider has the focus |
| Space | play / pause | the game screen; a match with a person in it has no clock to toggle |
| `.` | step one turn | the game screen |
| Enter | presses the control the focus is on | everywhere |
| Escape | closes the overlay on top, or goes back a screen | everywhere |
| Tab | the next control, in DOM order | everywhere |

- **Enter costs no code, and that is the point.** Every control on the page is a native `<button>`,
  `<select>` or `<input>`. A custom widget is a keyboard-accessibility bill that has to be paid in
  full and nothing here needs one, so the whole of "Enter activates it" is choosing native elements
  and putting the focus somewhere worth pressing — which is `Shell`'s half, above.
- **On the game screen the space bar is the match's, not the focused button's.** `boardHasKeys` is
  true there, so Space is cancelled and toggles play even while a button has the focus; Enter is
  what presses a button on that screen and the Keys note says so. Behind a panel the reverse holds,
  which is what pressing the space bar in a form full of buttons means.
- **`preventDefault` first, the repeat guard second.** Dropping auto-repeat is a statement about how
  fast the *snake* moves and says nothing about what the browser may do with the event. The other
  way round, a held arrow steered at `SteerRepeat`'s rate and scrolled the page at the keyboard's.
- **`onKeyUp` has no focus guard on purpose**, and the `blur` listener cancels the repeat: a key let
  go of while the page is not looking never reports it, and a release that stops nothing costs
  nothing.
- **There is no keyboard way to *draw* a route, and there does not need to be.** The arrow keys are
  the keyboard's steering, and a key mid-drag takes the route over outright — `GameSession.steer`
  opens with `endPath()`, so the two ways of saying where to go can never interleave.
- **A phone has none of these keys, and `SteerPad` is the row of the table it gets instead.** Four
  arrows that raise the same `UiIntent.Steer` through the same `SteerRepeat`, so a thumb and a
  keyboard are one control path and cannot disagree about what a hold does. See *Steering with a
  thumb*, below.
- **No element carries a positive `tabindex`.** The order is DOM order; `tabindex="-1"` appears only
  on the screens and panels, which are focused programmatically and are not tab stops. One positive
  value anywhere would make the whole page's order a puzzle.
- **`:focus-visible` rings, not `:focus`**, in `--accent` at a 2px offset — so they read against a
  panel, against the page and against a primary button's own accent fill, in every theme and both
  schemes, and a mouse click leaves none behind. `.screen` and `.panel` opt out: a ring the width of
  the page says nothing a person cannot already see.
- **`#tournament-table` is the one region that has to ask for a tab stop.** It scrolls and holds no
  control of its own, so without `tabindex="0"` a wide matrix has rows only a pointer can reach.

The screen-reader half is the same work. Every control has an accessible name — a `<label for>`, the
`<label>` wrapped around a knob row by `SlotForm`, or an `aria-label` on the icon-only ones. `#status`
keeps `role="status"` and `#unsupported` `role="alert"`. Portraits are decoration: `aria-hidden`,
empty `alt`, always beside the seat's name in text. The result card announces itself by taking the
focus — `aria-labelledby` the verdict, `aria-describedby` the line under it — rather than through a
live region, which inside a just-focused modal would say all of it twice. The panels claim
`aria-modal` and are entitled to: they really do trap the focus.

### The board fills its frame

**The board is as large as the room it is given**, so a phone in portrait and a 4K monitor are the
same board at two magnifications. `BoardRenderer.fit` measures `.board-wrap` and divides it by the
longer of `rows` and `cols`: the container is the *input* to the size, not a clamp on a size decided
elsewhere. An 8x8 and a 28x28 therefore occupy the same frame, at different magnifications.

The upper clamp survives: `MAX_CELL` stops a small board turning into a
handful of enormous squares in a large window — it binds only on the small end of the picker, since a
20x20 needs nearly nine hundred pixels of frame before it is reached at all. The lower bound is one
device pixel per cell so a tight landscape screen preserves all four edges and both side controls
rather than clipping the board. The maximum is stated in CSS pixels at the `devicePixelRatio` the page opened at,
which is what makes zooming move the text around a board that stays put: the room is measured in
device pixels too, and there the zoom cancels out exactly.

### Themes, and the one place a colour is written

**Every colour on the page comes out of `Theme`, and `styles.css` holds only what the page looks like
before one arrives.** There is one `Theme` instance per named theme *and* per light/dark scheme, and
the two axes carry different things — a theme is the player's, a scheme is their system's, so flipping
the OS to dark recolours the theme they chose rather than resetting it.

- **A trail belongs to the theme; a head, the board, the gridlines and the walls belong to the
  scheme.** `Theme.body(slot)` is the same string under either scheme, which is what a snake *is*;
  `Theme.head(slot)` is the readable-against-the-page end of it. `ThemeTest` pins the split, and it is
  why a scheme change repaints the canvas but the seat swatches do not have to move.
- **`Theme.applyToPage()` writes `--bg`, `--panel`, `--ink`, `--ink-dim`, `--line`, `--accent`,
  `--accent-ink` and `--board` inline on `<html>`.** Inline beats every stylesheet rule and custom
  properties inherit, so one write recolours the document. `--board` is the very string the canvas
  fills the board with — the board and the frame around it cannot disagree because there is one value.
  `styles.css` therefore carries **no theme's numbers**: what is in `:root` is the loading strip and
  the unsupported-browser panel, and a value repeated there would be a second opinion nothing keeps in
  step. `--danger` and `--scrim` are not a theme's to set and live only in the stylesheet.
- **The theme is applied in `GameSession`'s constructor**, which runs before `:app` adds `booted` and
  therefore before `#app` stops being `display: none`. Later and the page is briefly the wrong colour.
- **The seat swatches take their colour from `UiModel.theme`, never from a global.** A theme can move
  a trail hue, and a swatch painted from a global keeps the old one until something else happens to
  redraw the card — intermittent, and it looks like nothing at all.
- **The theme picker is static markup**, like the map shapes and unlike the bot pickers: `Theme.ALL`
  is not a registry and there is no "fork, add a file, register it" workflow behind a colour scheme.
  `SettingsPanel` checks the markup against `Theme.ALL` at boot, so a theme with no `<option>` fails
  with its own name.
- **The choice is `localStorage["snakewarz.theme.v1"]`, through `Preferences`.** A theme is not part of
  a replay and never travels in a link. Reading survives a missing key, a value from another version
  and storage that throws outright — Safari in private browsing does — because a boot that died on a
  preference lookup would be a black page for the whole game. `Theme.of` is total for the same reason.
  Gauntlet progress is the other thing `Preferences` keeps, under its own key and on the same terms.
- **Walls are painted in `repaint`'s one sweep, between the background fill and the gridline stroke**,
  and nothing repaints a square of the board afterwards — the snakes are on the overlay, so a wall a
  snake moves past cannot be painted back as board. `Theme.wallEdge` outlines each block in the same
  call — without it a room's wall reads as one slab — and is dropped below
  `BoardRenderer.WALL_EDGE_MIN_CELL`, where the perimeter would be most of the square.

**A `TexturePack` is a second axis over the board, the way a scheme is a second axis over a theme.**
It picks a *treatment* and the theme still picks the *colour*, so all three themes times both schemes
keep working without a pack knowing a single hex string.

- **A pack may vary the wall fill, the wall edge and the board's own ground, and nothing else.** All
  three are `Theme.wall`, `Theme.wallEdge` and `Theme.background` shaded towards the first of them —
  a figured ground is the wall colour at a whisper of itself, which is why it follows a theme change
  for free and can never be mistaken for a wall.
- **A staged ground is painted at 0.88 alpha.** The board bitmap is cleared before that fill so the
  stage illustration whispers through it; wall fills, edges, gridlines, snakes, and every non-level
  board return to alpha 1. The canvas frame is an inset shadow inside the overlay's visible box, so
  no external ring can be clipped away.
- **It may never vary `Theme.body`, `Theme.head` or `Theme.accent`.** A trail is what a snake *is*
  and a route is the player's; a texture that moved either would make the board's one reliable colour
  channel depend on which level you happen to be on.
- **A pack's per-cell figure is a pure function of `(row, col)`, never an RNG.** The board bitmap is
  laid down only by `fit`, so a resize redraws every wall — and a pattern that reshuffled when it did
  gets reported as *the map changed*. Where a figure wants variety it takes it from `render/fnv1a.kt`,
  the same hash `identicon` is drawn from and frozen for the same reason. The cell size enters only as
  a cut-off: below `BoardRenderer.TEXTURE_MIN_CELL` every pack collapses to the plain one, which is
  `WALL_EDGE_MIN_CELL`'s decision taken again one size up.
- **A pack is chosen by whoever *starts* the match, because a shape never reaches one.**
  `MatchSetup` takes wall squares and no shape by design — see [Maps.md](Maps.md) — so there is
  nothing on a board to derive a pack from, and deriving one would be a picture that changed when a
  link was reopened. `startLevel` reads the rung's `shape`, a custom match and a batch read the
  picker's through `MatchOptions.shape`, which is **a decoration hint `setupFrom` never reads**, and
  a `#r=` link gets `null` and the plain pack.
- **Four packs across seven shapes, and the `when` has no `else`.** A pack is a feeling, so `empty`
  and `arena` share one; an eighth shape is a compile error rather than a board that quietly comes
  out plain.

### Faces, and the seam they arrive through

**Every bot opponent has a picture; the human seat deliberately does not.** `Portraits` is a
`fun interface` taking a stable **artwork key** and answering a URL or `null`, `GameSession`'s
constructor takes one, and `:app` fills it from `portraitUrl` — an explicit set of keys it ships
under `resources/art/portrait/`. Generic keys are frozen bot slugs; campaign keys are frozen level
identities with regular and `-defeated` variants. The seam keeps the resource table in `:app`, where
it belongs, rather than introducing a forbidden `:ui` to `:bots` dependency.

- **`null` is answered with a drawn mark, not a broken image.** `render/identicon.kt` hashes the slug
  into a mirrored 5×5 block grid and emits an SVG `data:` URI, so a *contributed* bot has a face on
  the day it is registered and a registry nobody drew anything for still tells its seats apart. The
  hash is FNV-1a written out in the file rather than `String.hashCode()`, which Kotlin does not
  specify to be identical across targets — the same bot must get the same mark forever, and
  `IdenticonTest` pins one literal to say so.
- **A mark is tinted with `Theme.body(slot)`, so it is keyed by *slot* and not by artwork key**, and
  `GameSession` rebuilds `SlotPortraits` when the match, level, or theme id changes. Not on a scheme
  change: a trail is the same string under light and dark, so the sun going down would spend a hash
  and a base64 encode per seat to produce the marks that are already on the page.
- **The human key is the exception to that fallback.** `SlotPortraits` answers `null` before asking
  for shipped art or drawing a mark. A human row in a custom match or tournament still says *You*
  and keeps its trail swatch, length, and status; only the decorative portrait is absent.
- **The level tiles resolve their own**, because `SlotPortraits` is keyed by a `MatchSetup`'s slots and
  a tile has no match. `GauntletScreen` asks the same seam by stable campaign artwork key and tints a fallback with
  `Theme.body(1)` — the seat `GauntletLevel.setup` puts the opponent in — so a tile's face is already
  the colour that snake will be on the board, and it is rebuilt on the same theme-id rule.
- **A portrait is decoration and never information.** `aria-hidden`, empty `alt`, and always beside
  the seat's name in text — a reader hears `PUCT - 1k/territory`, not "image". It does not replace
  the swatch either: the shipped art has a fixed palette of its own — one green ramp, plus bone and
  ink for the eyes and the shadow a face needs — and **carries no seat colour at all**, so the swatch
  is the only thing tying a card to a trail on the board.
- **It is written through `showPortrait`, which compares the attribute first.** The seat cards and the
  result dialog are both rendered once a *frame* while a portrait changes only when the match does,
  and a bot with no shipped art carries its whole picture in the URL.
- **The result portrait describes the rival, not the winner.** A Gauntlet win shows that stage's
  non-graphic defeated rival, and a loss shows the regular rival. Draws and custom-match human wins
  are portrait-free; they do not bring human artwork back through another surface.
- **The bot pickers in `#panel-setup` get none.** They are `<select>`s; a styled listbox is a custom
  widget, and every custom widget is a keyboard-accessibility bill. The names are enough.
- **The shipped art is opaque WebP in one pulp-arcade style.** Generic portraits keep frozen bot slugs
  as keys; each of the seven `gauntlet-<stage>` character identities has a regular and a non-gory
  defeated portrait, so two levels backed by the same bot still look like different opponents and a
  victory keeps that identity intact. All portrait sources are cropped edge-to-edge without the pale
  generation perimeter, and their square head-and-shoulders crops remain readable in compact cards.
  `PortraitUrlTest` walks the generic registry set, excludes the human key, and separately pins all
  seven regular and seven defeated campaign keys, including the unique Final Boss pair.
- **Eight wide environments and twenty-six portraits share one asset budget.** The backgrounds keep their
  centres subdued because a board is drawn over them; CSS adds the vignette rather than baking a
  second dark copy of each file. All 34 WebPs total roughly 401 KiB raw, and the production gzip gate
  still measures every file below `art/`.
- **The bundle gate measures subdirectories.** CI's size step walks the distribution with `find`
  rather than a glob, because `art/` is a directory and a glob would hand `gzip` one, measure it
  as zero, and leave every asset under it outside the budget. See
  [SW-08](Coding-Standards.md#sw-08--the-bundle-is-a-budget).

### One page, and the one thing that scrolls

**The game screen does not scroll; the open panel does.** This is a game, not a document —
`body.booted` is a `100dvh` column with `overflow: hidden`, `.panel-body` is the `overflow-y: auto`
region a panel's own content lives in, and the board takes whatever height the two bars around it
leave. `#screen-home` and `#screen-gauntlet` may scroll, and that is not the same concession: nothing
measures them, so a landscape phone with no room for the menu should give it a scrollbar rather than
put the last button out of reach.

`100dvh` and not `100vh`: a phone's address bar is the difference between them, and `vh` is the
taller — which puts the bottom bar underneath the browser's own chrome. The two bars carry
`env(safe-area-inset-*)` for a notch and a home indicator, inside `max()` so a display that reports
zero still gets the padding the layout was drawn with.

**`.board-wrap` carries `touch-action: none`, `overscroll-behavior: contain` and `user-select: none`,
and never padding.** The first two are what make a finger dragged across the board mean the board
rather than the page — without them a drag scrolls, and there is nothing on this screen for it to
scroll to. The third answers the mouse rather than the finger: a drag begun on the board is drawing a
route, and without it the browser reads the same gesture as a text selection and lights up the label
and the bars behind it. **They are not the whole of it** — the box also clips its overflow, so a drag
that leaves the canvas would simply stop arriving; `PathInput` takes `setPointerCapture` on
`pointerdown` for that, and treats `lostpointercapture` as a release, because a browser that takes
capture back would otherwise leave a snake walking with nobody holding it. The no-padding rule is
arithmetic: `clientWidth` counts an element's own padding, so padding on the box the renderer
measures is room the board would claim and then overflow by. The game screen's side insets live on
`#screen-game` for that reason.

The board taking whatever the bars leave is the piece with a trap in it. `#screen-game` is a grid
whose middle row is
`minmax(0, 1fr)`, and `BoardRenderer.fit` measures `.board-wrap`'s `clientHeight` — so the track has a
height of its own that the canvas inside cannot influence. Its single `minmax(0, 1fr)` column does the
same across. **Size the track from its content and the canvas sizes the box that sizes the canvas**,
which is how the board once came out different on every load; do not switch either axis to flexbox and
do not put a shrink-to-fit box around the canvas. Every `min-height: 0` down that chain is
load-bearing for the same reason — a flex or grid item's automatic minimum size is its content, so one
missing `min-height` and the column refuses to shrink, the page grows a scrollbar, and the whole
arrangement quietly does nothing.

Anything that changes the height of the bars therefore changes the board, and the `resize` listener
will not hear about it. Two things do, and neither is a panel: **a screen change**, which
`GameSession.navigate` answers with a `refit`, and **replay mode revealing the scrub row**, which
`GameSession` causes itself and so handles by rendering the chrome **before** it measures, in
`begin()`. A panel does not, because it is an overlay and the board's box does not move.

Everything else in the bars is therefore built to be **a fixed number of lines tall**, because there
is no third event and a match would have to end before anyone noticed. `#status` is one line with an
ellipsis rather than a paragraph that wraps on a long winner's name; `#scoreboard` is one row of
cards that truncate their names rather than a row that grows a second one the turn a seat is
eliminated and the word "trapped" arrives. It is also why the speed slider is under Settings and not
in the bar: it is set once and left, where the transport beside it is pressed every few seconds, and
a control the bar does not carry is width the seat cards do not have to fight for on a phone.

### The overlay canvas

**The snakes live on a second canvas, and it is painted whole.** The board bitmap is background,
walls and gridlines — the ground — and `BoardRenderer.fit` is the only thing that ever paints it,
because none of the three moves while a match is played. Everything alive is on the overlay, which is
cleared with one `clearRect` and redrawn end to end from the position every time that position moves.
It is sized off the same integers as the board, never measured, so the two cannot drift, and
`BoardRenderer` owns both, so the cell size and the grid still have one home.

**A body is on the overlay because of the gutter.** A cell of side `s` owns `(c*s + 1 … c*s + s)` and
the one-pixel gutter along its top and left edge belongs to the gridline — which is exactly what lets
the lines be stroked once and left alone, and exactly what puts a grid line down the middle of an
animal if the animal is painted as squares. A connected snake has to cross that gutter. The
alternative was to let body fills overwrite gridline pixels and have every path that vacates a cell
put the line back, four short strokes per cleared cell and exactly right or the board grows holes.
Moving the bodies to a bitmap that is never *partially* repainted makes the question disappear, and it
is what §6.5's animation would need in any case: an animated body needs a per-frame repaint and a
dirty-rectangle board cannot give one.

A body is therefore a **stroked polyline through the cell centres** with round joins and caps, at
`BODY_WIDTH` of a cell, in `Theme.body(slot)` — one path and one `stroke` for the whole trunk, because
a corpse's ribbon is translucent and a segment drawn over its neighbour's round cap would deepen at
every joint. Over it runs the **spine**, the same line at `SPINE_WIDTH` in `Theme.head(slot)`, ramping
in both alpha and width along the body so a coil reads from tail to head. The **head** is a marker in
the same head colour with two eyes offset perpendicular to `SnakeView.lastDirection`, and the
**tail** tapers, in a filled quadrilateral rather than a stroke, because a stroke has one width.

**The tail fade is a rule being drawn, not decoration**, and it survives all of that:
`BoardRenderer.tailAlpha` fades the oldest square through `Theme.AGING_ALPHA` and then
`Theme.DYING_ALPHA`, because `growEveryNthMove = 2` makes the square a snake is about to give back
knowable a move ahead. Its two carve-outs are what decide the drawing's one seam: a corpse never
fades, and a trail that never retracts has nothing to fade, so the fading square is lifted out of the
body's own path *exactly* when its alpha differs from the body's — which is only ever on a **living**
snake, whose ribbon is opaque and therefore cannot deepen where the taper runs under it. A corpse
keeps `Theme.CORPSE_ALPHA`, loses its spine and loses its eyes: a snake that is out is scenery.

**No new colour enters `BoardRenderer` for any of it.** A highlight is `Theme.head(slot)` over
`Theme.body(slot)` — the pair the theme already keeps for "this snake, but readable against itself" —
and an eye is `Theme.background`, the far end of the same contrast, so a dot reads against all six
head hues of both schemes. `ThemeTest` does not move.

The other three cues answer to different things. The **wash** picks one snake out of the others, which
only a pointer asks, so it stays with the pointer. The **route** is a statement about squares nothing
has happened on yet, and the **preview** is that statement in the conditional, what a press *would*
commit to rather than what one did.

The order is **wash → preview → route → bodies → heads**, and every step of it is load-bearing. The
wash goes down first, so hovering lays a tint under everything rather than rearranging it — under the
ribbon it shows as a halo in the corners the body does not fill, which is the one place the drawing
still says outright *which squares* a snake holds. The preview goes under the route, because a
committed route is the stronger claim and must win where the two overlap. Both go *under* the bodies,
so a snake reads on top of its own plan rather than being hidden by it. And the heads come after every
body rather than each with its own, so the square that says where a snake is about to go is never
buried under the animal it is about to meet. The routes are drawn in `Theme.accent` — the one colour
on the page that is already the player's — rather than in a trail hue, so a plan laid over the
position cannot be mistaken for a seventh snake, and each skips its own first square, which is the one
the head is already standing on.

**`paintOverlay` has to follow every turn played and every `fit`.** `GameSession.refreshOverlay` is
that obligation, not a pointer handler, and it is also where the preview is re-planned, because a
ghost route is anchored on a head that moves. The obligation is stronger than it looks: this is the
only thing that draws a snake at all, so a missed call does not lose a decoration — it leaves a board
with no game on it.

**Everything on the overlay has a floor, and the eyes have a cut-off instead.** `MIN_MARK = 2.0`
device pixels is under every width and radius here, because an arena can shrink a cell to a single
device pixel on an exceptionally tight screen. Eyes are *dropped* below
`EYE_MIN_CELL` rather than scaled to it — the same shape of decision as `WALL_EDGE_MIN_CELL` — since
two dots and the gap between them are three features across a fraction of a square and shrink into one
smudge.

**Moving the snakes onto the overlay takes nothing from a screen reader.** `#board` carries
`aria-label="Game board"` and `#board-overlay` is `aria-hidden`, and that stays right: both canvases
are decoration, and what a reader is actually given is `#board-tip`, the scoreboard and the status
line.

**A preview and a committed route are one drawing at two weights**, which is why `drawRoute` takes the
two alphas and is called twice rather than being copied. The weights are not a step down but a
fraction — `PREVIEW_ALPHA` is well under half `PLAN_ALPHA` — because the moment the two are compared
in is a hover sliding straight into a press on the same squares, and on this board that press costs a
move. They also cannot both be live: `pathBegan` sets the hovered square to `Cell.NONE`.

`GameSession` remembers the hovered **square**, never the snake, so a restart, a seek and a batch
moving on to its next match all resolve to whoever holds it now — the same rule every colour on this
board already follows.

### The overlay moves, and none of it can change a result

`Ticker` is the fourth thing on this page that reads a clock and the only one that cannot affect a
match: it produces **paints**, never turns, which is the framing `SteerRepeat` already has. Two things
on the overlay need frames the turn clock does not provide — a body settling to `CORPSE_ALPHA` over
about a third of a second, and the dashes marching along a held route, with the pulse on the head
that route is anchored on. `TurnScheduler` parks at `Progress.FINISHED`, which is the exact instant
the last snake dies, so the death effect would otherwise be drawn once and never again.

It **runs while something is moving and stops itself the frame nothing is**, off the boolean
`BoardRenderer.paintOverlay` and `animate` both answer with. Its clock measures *motion somebody saw*
rather than time that passed — it advances only while the loop runs and is never reset — because a
death is stamped with it on a turn, which is to say while the loop is still stopped.

A flash is an **event** and a board only reports a **position**, so the renderer recovers it by
comparing each paint against the last. That is the one thing on this bitmap that remembers anything,
and `fit` drops it: closing a setup preview over a finished match would otherwise flash a corpse that
died a minute ago.

The page's motion is otherwise CSS, all of it **entrances** — a screen, a panel, the verdict card, a
cleared badge, a press. There are no exits, and that is a limit rather than an omission:
`[hidden] { display: none !important }` is binary, so there is no frame on which an outgoing element
is still painted, and beating it would mean Kotlin holding elements alive for the length of an
animation. **The `prefers-reduced-motion` guard arrived in the same change as the motion**, beside
the `prefers-color-scheme` block, and **focus never waits for a transition** — `Shell.focusInto`
moves it on the same task, and a `transitionend` handler that made it wait would leave a keyboard
player looking at controls they cannot reach yet.

### Naming seats, and building DOM

**Seats are named by `SlotLabels`, not by the registry directly.** A seat is a *configured* bot, so
two of them can be the same bot at two allowances or two evaluations, and the display name alone
cannot say so. The qualifier is `Contestant.suffix` from `:match` — the very string the win-rate
matrix uses — so the seat list, the hover label, the winner line and the table cannot start disagreeing
about what `4k` means. `PUCT - 1k/survival` is a full seat: allowance first, because that is what
strength scales on, then the settings.

What is *in* that suffix is the one thing `:ui` decides for itself, because it is the one thing that
needs the registry: an allowance is named when the bot declares a `BotKnob.Search`, an offered
`BotKnob.Choice` is named at its default too — a mode is what a seat *is* — and everything else is
named only when it has been moved off its declared default. The matrix cannot make that call, since a
`Contestant` has no registry to ask.

The numbering does differ on purpose: `TournamentTable` leaves the first of a repeated column bare
because it has a legend under it, while a list of four rows reads better as `Random ·1` and
`Random ·2`.

The static skeleton lives in `app/.../index.html`. Kotlin looks elements up by id once and then only
writes text, values and `hidden`; do not start constructing structure there. The win-rate matrix is
the case that most invites breaking that rule and does not: `TournamentTable.toString()` lays it out
in `:match` and the chrome writes the text into one `<pre>`.

**There are exactly two exceptions, and both come off `BotRegistry.entries`**, and both live behind
`#panel-setup`: the `<option>` list in each picker, and the knob rows inside each seat's
`<details class="knobs">`. Both exist to keep
"fork, add a file, register it, open a PR" from also meaning "and edit the markup". A pre-written pool
of rows would have been the doctrinal answer and is the wrong one — the day a bot declares one knob
more than the pool holds, it silently loses it, which is the exact coupling the rule is there to
prevent. The *containers* are still static, and adding a third exception needs a better reason than
either of these had.

**Still exactly two**, and the list of things that look like they should have joined them is worth
writing down, because every one of them was considered and none qualifies. The test is not "is it a
list" — it is **does it come off a registry**:

- **The three screens, the four panels, the backdrop and the result dialog** are a fixed set the
  page's own design fixes. A fifth panel is a markup change *and* a `Panel` entry *and* a button, and
  that is the honest cost of adding one.
- **The map `<option>`s and the theme `<option>`s** enumerate `MapShape.entries` and `Theme.ALL`,
  neither of which is a registry and neither of which has a "fork, add a file, register it" workflow
  behind it. `SetupPanel` and `SettingsPanel` check the markup against the enum at boot instead, so a
  shape or a theme with no `<option>` fails at startup naming itself — which is the *cheaper* half of
  what building the list would have bought, without the DOM construction.
- **The seven gauntlet tiles** are static markup like the four scoreboard cards. `Gauntlet.levels` is a
  fixed table in `:match`, not something a contributor extends, and `GauntletScreen` writes only each
  tile's text, state and picture.
- **The overlay canvas, the hover label, the steering pad and the seat portraits** are static markup
  too; Kotlin only ever writes their size, text, position, class and `src`.

`SlotForm` owns all of that, one per seat under `SetupPanel`, and nothing in it dispatches a
`UiIntent`. Which bot is picked and what its knobs are set to is **form state**, like the reseed
button writing `#seed`; it becomes app state only when Start match calls `read()`. Three things there
are load-bearing, and `SetupPanelTest` pins each one:

- **A value is corrected in the field, not just in the read.** `SlotForm` runs `BotKnob.reject`
  first, falls back to the declared default, and writes the correction back — a match that quietly
  played at a number nobody typed would be worse than one that refused to start.
- **Values equal to the declared default are omitted**, so an untouched seat yields
  `BotParams.EMPTY`, `MatchSetup.configured` stays false, and the replay URL of a stock match is
  byte-identical to the one the codec produced before any of this existed.
- **The form shows `BotEntry.offered` and reads `BotEntry.params`**, and those are different lists —
  see [Bots.md](Bots.md#declaring-one-is-not-the-same-as-offering-one). A knob no row exists for still
  has a value: a replay carried one in, or somebody measured one in `:lab` and shared the link. So
  `read()` walks the bot's whole declaration and falls back to what `applySetup` put in `remembered`.
  Read the rows instead and a replay of `uct` at `rolloutDepth=25` rematches at `0` — the same
  half-built feature `applySetup` exists to prevent, one level down.

## Deployment

GitHub Pages, static files, no backend. GitHub Pages serves `.wasm` with the correct `application/wasm`
MIME type on live sites. A `.nojekyll` file is required. Replays travel in the URL **hash** (`#r=<payload>`)
because Pages has no server-side routing and a hash change causes no reload.

Kotlin/Wasm is Beta and needs WasmGC: Chrome 119+, Firefox 120+, Safari 18.2+. `index.html` already
handles this by watching for a boot *failure* (a thrown error, a rejected promise, a failed script
load, or a 15s timeout) rather than probing wasm features — a byte-level WasmGC probe is easy to get
subtly wrong and would then lock out perfectly good browsers. Kotlin signals success by adding
`booted` to `<body>`.

Keep the four pure modules platform-free so that adding a Kotlin/JS fallback target later is a
build-config change, not a rewrite.

## Browser gotchas already hit — don't rediscover these

- **`.board-wrap` needs `touch-action: none`, or a finger dragged across the board scrolls the
  page.** The browser decides whether a touch is a gesture *before* it delivers a `pointermove`, so
  there is no `preventDefault` that gets there in time — the declaration is the only way to say the
  board wants the drag. `overscroll-behavior: contain` stops the rubber band at the edges, and
  `user-select: none` answers the mouse rather than the finger: without it the same drag reads as a
  text selection and lights up every label behind the canvas.
- **`100dvh`, never `100vh`.** On a phone `vh` is measured against the viewport with the address bar
  *retracted*, which is the taller one — so a `100vh` column puts its bottom bar underneath the
  browser's own chrome, on the device where the transport matters most. `dvh` tracks the bar. The
  bars also carry `env(safe-area-inset-*)` inside `max()`, so a display reporting zero still gets the
  padding the layout was drawn with.
- **`setPointerCapture` on `pointerdown`, and treat `lostpointercapture` as a release.** `.board-wrap`
  clips its overflow, so a drag that leaves the canvas simply stops arriving and the snake keeps
  walking a route nobody is holding. Capture routes the rest of the gesture to the element whatever
  it crosses. The browser can take capture back — a context menu, a system gesture — and it announces
  that with `lostpointercapture` rather than with `pointerup`, so a handler that listens only for the
  latter leaves a snake moving. `PathInput` listens for both, plus `pointercancel`.
- **Reveal `#app` before the first paint.** It starts `display: none`, and a hidden element reports
  `clientWidth == 0`, so measuring the board container first sizes every board to the minimum cell
  size. `document.body.classList.add("booted")` must stay ahead of `session.start()` in `Main.kt`.
  The same arithmetic is why the page opening on `#screen-home` is not a problem: the board is
  measured again on the way to the game screen, and `navigate` renders before it measures.
- **The board container's width must not depend on the canvas.** The canvas measures the container to
  find out how much room it has; with `flex: 1 1 auto` that was circular and the board came out a
  different size on each load. `#screen-game` is a CSS grid whose one column is `minmax(0, 1fr)` so
  the track width is definite, and `.board-wrap` is a one-cell grid that centres the canvas without
  shrink-wrapping it. Don't switch either back to flexbox, and don't put a shrink-to-fit box around
  the canvas.
- **The board frame is inset, never a border or external outline.** `#board-overlay` carries inset
  shadows inside the exact CSS box Kotlin wrote, plus a drop shadow that may be clipped without
  losing an edge. A border would squeeze the backing store and move the hit-test; an external ring
  could disappear at a tight container edge.
- **`[hidden] { display: none !important; }` is load-bearing.** The chrome hides things by setting
  `hidden`, and an author `display: flex`/`grid` outranks the user agent's `[hidden]` rule — so
  hidden rows stayed on screen while reporting `hidden == true`. Kotlin cannot see that; the fix
  belongs in `styles.css` and it is already there.
- **`BoardRenderer` draws in device pixels and never scales the context.** The backing store is
  `cellSize * cols + 1` device pixels with a *fractional* CSS size, rather than a CSS-pixel size with
  `context.scale(dpr, dpr)`. On a fractional `devicePixelRatio` — 1.25, 1.35 and 1.5 are all ordinary
  on Windows — the scaled version puts every coordinate between two device pixels and a 1px gridline
  antialiases into a two-pixel smear. Verified: sampling the backing store now yields exactly two
  colours across a row. If you do re-introduce `scale`, note that setting `canvas.width` resets the
  transform, so it must come *after* the resize.
- In `wasmJs`, `fillStyle`/`strokeStyle` take `JsAny?`, so a Kotlin `String` needs `.toJsString()`.
  `snakewarz.browser` opts into `kotlin.js.ExperimentalWasmJsInterop` once so this is not a warning
  at every call site.
- **There is no `console` in Kotlin/Wasm.** Use `println`, which lands in the browser console.
- **`requestAnimationFrame` does not fire in a hidden tab at all** — which is exactly why the
  scheduler uses it. Automated checks against a backgrounded tab will see a frozen match; drive
  `Step`, or replace `window.requestAnimationFrame` and pump the callback with synthetic timestamps.
- A harmless configure-time warning — `Kotlin does not yet support 26 JDK target, falling back to
  Kotlin JVM_25` — comes from `:app`, which emits no JVM bytecode. `:core` correctly compiles to Java
  21 bytecode via `jvmToolchain`.
