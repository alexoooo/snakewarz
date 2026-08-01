# P3 — Screens and setup

**Modules:** `:ui`, `:app`
**Depends on:** 3.1 needs [P1](P1-pointer-controls.md) — it is the page that documents those controls.
The other six items are independent of everything and of each other.
**Read first:** [`../UI.md`](../UI.md), and `ui/AGENTS.md` on `model/` being under CC-06 pressure.

Names below are written as they are **after** [P2](P2-gauntlet-rename.md). If this phase lands first,
read `Gauntlet` as `Ladder` throughout.

---

## 3.1 Controls on the home screen

**Why:** nothing on the page says how to play, and P1 gives the pointer three distinct verbs that
nobody would guess at.

`#screen-home` is a flex **column** with one item, `.home` (`max-width: 22rem`, `margin: auto`), inside
an `#app` that allows `max-width: 74rem` — so a desktop already has ~50rem of unused width and a 360px
phone has none at all.

- **`index.html:55-77`** — wrap `.home` and a new `<aside class="controls">` in a `.home-row`. Static
  markup only; Kotlin never constructs structure here and this is not a third exception to that rule.
- **`styles.css`** —
  ```css
  .home-row  { display: flex; flex-wrap: wrap; justify-content: center;
               align-items: flex-start; gap: 2rem; margin: auto; }
  .controls  { flex: 0 1 18rem; min-width: 15rem; text-align: left; }
  ```
  `.home`'s own `margin: auto` moves to `.home-row`.
- **No media query.** The stylesheet has exactly two — `prefers-color-scheme` at line 31, and a
  `max-width: 30rem` block at line 397 that touches only `#screen-game` — and its stated convention
  (`.levels`, lines 244-251) is that *"the number of columns is the width's business and not a set of
  breakpoints to keep in step"*. `flex-wrap` is that convention applied.
- `#screen-home` is already `overflow-y: auto` (lines 175-186, deliberately, so a landscape phone gets a
  scrollbar rather than a cut-off button), so the extra row is safe here where it would not be on
  `#screen-game`.
- Content is a `<dl>`: **Click** a square to take one step · **Hold** to keep going · **Drag** to draw
  the exact route · **Arrow keys or WASD**. CC-18 — no internal referents, and it must describe what P1
  actually shipped.

---

## 3.2 A Replay button on the verdict card

**Why:** you finish a level and there is no way to see what just happened. `Watch replay` exists but is
buried in `#panel-share`, which nobody opens after winning.

`.result-card` (`min(20rem, 100%)`) holds one centred flex row, `.result-actions`, with `#result-again`
("Play again" / "Retry"), `#result-next` ("Next level") and `#result-home`. **The row has no
`flex-wrap`**, so a fourth button squeezed in shrinks the others.

So the cluster keeps Next level / Retry / Home, and Replay gets its own full-width bar at the bottom:

- **`index.html`**, after `.result-actions` (line 520):
  ```html
  <div class="result-replay">
      <button id="result-replay" type="button" class="wide" hidden>Watch replay</button>
  </div>
  ```
  `button.wide` is already `display: block; width: 100%` (styles.css:777-781).
- **`styles.css`**, after `.result-actions` (739-744):
  `.result-replay { margin-top: 1rem; padding-top: 1rem; border-top: 1px solid var(--line); }`
  and `#result-replay { margin-top: 0 }` to cancel `.wide`'s own top margin.
- **`Shell.kt`** — a field beside `dialogAgain` / `dialogNext`, a listener dispatching the **existing**
  `UiIntent.WatchReplay`, and `hidden = !model.canWatchReplay` in `renderResultActions` (197-203). The
  intent (`UiIntent.kt:91`), the model flag (`GameSession.kt:1156`,
  `replay == null && match.outcome != null && !batch.running`) and the session-side guard
  (`watchReplay`, `GameSession.kt:871`) all already exist. This is a second entry point, not new
  behaviour.
- Give it `data-focus` **after** `#result-home` in document order, so `Shell.focusInto` still lands on
  the primary action.

**One thing must change in the session.** `watchReplay()` calls `load(record)`, which sets `level = null`
— so watching a level's replay drops you out of the campaign: the bar reverts to the game name, Setup
and Tournament reappear via `Mode.CUSTOM`, and `← Gauntlet` becomes `← Home`. Add a `keepLevel`
parameter to `load`, defaulting to today's behaviour so a `#r=` URL still clears it, and pass the
current level from `watchReplay`.

The card hiding itself on entry is **correct and wanted**: `resultText()` returns `null` once
`replay != null` (`GameSession.kt:648-663`).

---

## 3.3 Custom starts a fresh game

**Why:** press Custom over a finished match and you get that match back, verdict card and all.

`#home-custom` dispatches `UiIntent.Navigate(Screen.GAME)`, and `navigate` (`GameSession.kt:593-616`)
**never touches `match`** — it stops the clocks, forgets the path, clears `level`, and refits the
position that was already there. A match exists from construction (`GameSession.kt:75`) and that is
deliberate: *"a board that only exists once a mode has been picked would put a first measurement of it
after the first frame it is visible on"*. So Custom is honestly "show me the board" rather than "start
one".

- New **`UiIntent.StartCustom`** — a **`Match`** intent, not a `Shell` one, for the reason
  `StartLevel`'s KDoc gives (`UiIntent.kt:95-105`): it replaces the match, so it must pass the guards
  about whose board is on screen.
- `HomeScreen.kt:33` dispatches it instead of `Navigate`.
- `GameSession.startCustom()` mirrors `startLevel` (778-783):
  ```kotlin
  level = null
  screen = Screen.GAME
  openPanel = null
  chrome.reseed()                                   // a new game, not the last one again
  playFresh(setupFrom(chrome.readOptions()))
  ```
  That needs one new `SetupPanel.reseed()` (the body of the existing `#reseed` listener, line 78)
  exposed through `Chrome`. **`Start match` inside the panel keeps reading whatever seed is typed** —
  that is the deliberate-seed path and must not change.
- `#home-replay` keeps dispatching `Navigate(Screen.GAME)`: it means "show me the recording I have".

**This fixes a second bug for free.** `navigate` does not reset `resultDismissed`, so today: finish a
match → press Home (the card hides only because `resultText()` guards on `screen`) → press Custom →
**the old verdict card reappears** over the finished board. `playFresh` → `begin()` clears it
(`GameSession.kt:712-752`).

---

## 3.4 A disabled map option says what it needs

**Why:** at 8×8 half the map list is greyed with no explanation.

`refreshMapOptions` (`SetupPanel.kt:193-203`) writes only `disabled`; the label is whatever
`index.html` says. Make it append the requirement when it disables and restore the plain label when it
does not — **"Rooms — needs 15 × 15"**.

The base label stays the markup's business: capture `option.textContent` once in the same
`MapShape.entries.map { … }` that already resolves each option at construction (`SetupPanel.kt:49-54`),
so the pair becomes a triple and Kotlin only ever appends a suffix.

`SetupPanelTest`'s existing case *"the map picker offers only the shapes the chosen board can draw"*
(lines 133-141) gains the label assertion. `docs/UI.md:106-118`, which states the picker's contract,
gains the sentence.

---

## 3.5 The setup panel previews its board live

**Why:** you pick a size and a map and cannot see either until you press Start match.

A panel is `position: fixed; z-index: 11` above a **translucent** scrim (`--scrim` is 45%/60% black), so
**the real board is already visible behind `#panel-setup`** on anything wider than the 24rem panel.
Preview on the board itself; do not build a second canvas.

- **`SetupPanel`** grows listeners it does not have today: `change` on `#map` and `#seed`, `click` on
  `#reseed`, and the existing `#size` handler (82-85) gains a dispatch **after**
  `discardReplayMap(); refreshMapOptions()`. That order is load-bearing: `generateMap` throws on a shape
  the board is too small for, and `refreshMapOptions` is the only thing that prevents it. Use `change`
  and not `input` on the seed field so a preview is not rebuilt per keystroke.
- New **`UiIntent.PreviewSetup(options: MatchOptions)`** — a **`Shell`** intent. It changes nothing
  about the match, so it must neither be dropped while a batch owns the board nor take the arena off
  one. **This falsifies `SetupPanel`'s class KDoc** (24-27: *"A form and nothing more. Nothing here
  dispatches until Start match is pressed"*), which has to be rewritten rather than left standing.
- **`GameSession`** gains `private var previewBoard: Match?`, and the two existing
  `val shown = batchBoard ?: match` expressions (`refreshOverlay`, `refit`) become
  `previewBoard ?: batchBoard ?: match`. Building it is `Match(setupFrom(options), registry)` — the
  exact shape of `fitToBatch` (949-954), which already documents the cost as *"one board and no
  search"*. Showing the real spawn squares is a feature: it answers *where will I start on this map*.
- Cleared and refitted by `closeOverlay()` and by `begin()`, so shutting the panel or starting the match
  both put the player's own board back.
- No `try`/`catch` around the build (CC-08): `refreshMapOptions` is what makes `generateMap` safe, and a
  throw here would mean that guarantee had broken.

---

## 3.6 `← Gauntlet` while playing a level

**Why:** backing out of level 7 drops you to the front page instead of to the level select.

`#game-back` (`index.html:210`) reads `← Home`, and `Shell.back()` (264-268) dispatches
`Navigate(Screen.HOME)` whatever is on the board.

- `Shell` keeps `private var level: Int?` set in `render`, beside the `nextLevel` it already keeps for
  the same reason — *"read at the press, not captured at render"*.
- `back()` becomes `Navigate(if (level != null) Screen.GAUNTLET else Screen.HOME)`, and `render` writes
  `#game-back`'s label to match.
- **Escape follows for free, and should:** `Shell.onKeyDown` (270-283) already routes it through
  `back()`.
- **`#result-home` also calls `back()`** (line 122), so its label changes with it — a level's verdict
  card offers `Gauntlet` where a custom match's offers `Home`. That consistency comes from *not* giving
  the two buttons separate destinations.
- `ShellTest`: on a level the back button says Gauntlet and navigates to the level select; off one it
  still says Home.

---

## 3.7 Drop 40 × 40

**Why:** it is too big to play on.

- **`index.html:308`** — delete the option. The size list is declared **only** in the markup; Kotlin's
  only related constant is `SetupPanel.DEFAULT_SIZE = 8`, whose KDoc already binds it to the `selected`
  option, and it does not move.
- **`GenerateMapTest.SIZES`** (`match/src/commonTest/.../map/GenerateMapTest.kt:180`) — drop `40x40`.
  Its own KDoc binds that list to the picker: any size the picker offers and it misses *"is a Start
  match that could throw at a player"*. Keep `28x28` and both non-square cases.
- Comments naming it as the ceiling: `MatchSetup.kt:212`, `styles.css:462-469`, `docs/UI.md:263,268` →
  28 × 28.
- **`BoardRendererTest`'s `Grid(40,40)` case stays.** The renderer still supports up to
  `MatchSetup.MAX_SIDE = 256`, and that case is what pins `MIN_CELL` at an extreme magnification.

---

## Verification

```bash
./gradlew build
./gradlew allTests -PbrowserTests=true    # SetupPanelTest and ShellTest are both touched here
```

Items 8, 10, 11, 12, 13 and 14 of the release checklist in [`README.md`](README.md) are this phase's.
