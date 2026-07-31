# S10 — Setup, tournament and share move into panels

**Modules:** `:ui`, `:app`
**Depends on:** [S09](S09-game-screen.md).
**Read first:** [`../UI.md`](../UI.md) §*"Naming seats, and building DOM"*.

## Goal

Everything the sidebar does today, done from a panel that slides over the board. **Nothing is lost.**
This is a move, not a redesign — the research chrome stays, it just stops crowding the game.

---

## The panels

| Panel | Holds | Offered in |
|---|---|---|
| `#panel-setup` | board size, map shape, four seats with their knob grids, seed + reseed, Start match | Custom |
| `#panel-tournament` | format, rounds, Run tournament, progress, the `<pre>` matrix, the note | Custom |
| `#panel-share` | Copy replay link, the link field, Watch replay, the note | both modes |
| `#panel-settings` | theme, speed | both modes |

Panels slide from the right on a wide viewport and up from the bottom on a narrow one — one
`transform` and one media query, not two components.

`#panel-setup` is the tall one and is the reason panels scroll: it is `overflow-y: auto` inside a
`max-height`, and it is the only scrolling region on the game screen.

---

## `chrome/SetupPanel.kt`

`Chrome`'s setup half moves here wholesale: `sizeSelect`, `mapSelect`, `seedInput`, `reseedButton`,
`botSelects`, `seats: List<SlotForm>`, `startButton`, plus `readOptions`, `readTournamentOptions`,
`applySetup` and `fillPickers`.

**Four things must survive the move unchanged.** Each of them is load-bearing and each is easy to lose
in a refactor:

1. **`SlotForm` dispatches no `UiIntent`.** Which bot is picked and what its knobs are set to is *form
   state*, like the reseed button writing `#seed`. It becomes app state only when Start match calls
   `read()`.
2. **A value is corrected in the field, not just in the read.** `SlotForm` runs `BotKnob.reject`, falls
   back to the declared default, and **writes the correction back**. A match that quietly played at a
   number nobody typed would be worse than one that refused to start.
3. **Values equal to the declared default are omitted**, so an untouched seat yields `BotParams.EMPTY`,
   `MatchSetup.configured` stays false, and a stock match's replay URL stays byte-identical to what the
   codec produced before any of this existed.
4. **The form shows `BotEntry.offered` and reads `BotEntry.params`, and those are different lists.** A
   knob with no row still has a value — a replay carried one in, or somebody measured one in `:lab` and
   shared the link. `read()` walks the bot's whole declaration and falls back to what `applySetup` put
   in `remembered`. Read the rows instead and a replay of `uct` at `rolloutDepth=25` rematches at `0`.

The two registry-driven DOM exceptions come along unchanged: the `<option>` list per picker, and the
knob rows inside each `details.knobs`. Both exist to keep *"fork, add a file, register it, open a PR"*
from also meaning *"and edit the markup"*.

Only slot 0 is offered to a person — every interactive slot reads the same keyboard, so a second would
steer by stealing the first one's moves.

---

## `chrome/TournamentPanel.kt`

Format, rounds, the button, the progress output and the `<pre>`. Straight move.

Two things to preserve:

- **`TournamentTable.toString()` lays the matrix out in `:match` and the chrome writes the text into
  one `<pre>`.** This is the case that most invites building DOM in Kotlin and does not. Keep it that
  way.
- **The tournament is no longer a `<details>` inside the board's grid column**, so opening it no longer
  takes room out of the board. The `UiIntent.Relayout` that the `toggle` listener dispatches therefore
  has nothing to do — but **leave the intent and its tier-1 placement in `dispatch`**. It is one line,
  and the next thing that changes the chrome's height will want it. Note in the KDoc that its current
  emitter is gone.

While a batch runs it **owns the arena**, and that is unchanged: `GameSession` paints its current match
and builds the whole `UiModel` from it, the transport is greyed, and `dispatch` drops transport intents
outright — the space bar does not read the DOM's disabled flags.

---

## `chrome/SharePanel.kt`

Copy replay link, the readonly link field, Watch replay, the note.

`copyShareUrl()` must still be called **straight out of the click that asked for it** — the clipboard
is only writable from a user gesture — and must still select the text first and unconditionally, since
selection is the fallback for when the clipboard is not writable at all.

---

## Mode gating

`#panel-setup` and `#panel-tournament` are offered in Custom and not in Ladder: a ladder level *is* its
configuration. Gate by hiding the buttons that open them, not by disabling the panels — a control that
cannot ever apply here should not be present, and `[hidden]` already works.

---

## Tests

- `SetupPanelTest` (new, and the first test `SlotForm`'s read/apply logic has ever had): an untouched
  seat reads `BotParams.EMPTY`; an out-of-range knob is corrected **in the field**; `applySetup`
  round-trips a configured seat; a knob with no offered row survives `applySetup` → `read()`.
  This is worth writing precisely because the move is when those four rules are most likely to be lost.
- The existing `SlotLabelsTest` must pass untouched.

---

## Done when

```bash
./gradlew build
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

In the browser, every one of these still works: configure four seats with knobs and start a match;
run a head-to-head and a free-for-all tournament and read the matrix; copy a replay link, open it in a
fresh tab, watch it back, and hit Start match to rematch under the same conditions. Then the same at
390×844, where the panels are bottom sheets.
