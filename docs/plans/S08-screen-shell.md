# S08 — Screens, panels, navigation, focus

**Modules:** `:ui`, `:app`
**Depends on:** [S07](S07-walls-on-screen.md).
**Read first:** [`../UI.md`](../UI.md) — all of it. This session changes several things it documents,
and each change has to be *deliberate*.

## Goal

The page stops being one screen. Home offers the modes; the game screen owns the board; everything
dense goes behind a panel that slides over it.

Nothing about the *match* changes in this session. The one-way data flow, the frame-versus-turn
cadence, the three clocks and the overlay-painted-whole obligation all stay exactly as they are.

**`Chrome` is 506 lines and does everything.** This session splits it, and the split is most of the
work. Move code; do not rewrite behaviour.

---

## The structure

`app/src/wasmJsMain/resources/index.html` — three screens as static sections, one visible at a time,
plus panels and one dialog:

```
#screen-home      title, Continue / Ladder / Custom, and Watch replay when #r= is present
#screen-ladder    empty in this session; S17 fills it
#screen-game      the board, a slim top bar, a slim bottom bar. Nothing else.

#panel-setup        today's sidebar: board, map, seats, knob grids, seed   (Custom only)
#panel-tournament   today's <details>: format, rounds, the matrix           (Custom only)
#panel-share        replay link, watch replay
#panel-settings     theme, speed
#dialog-result      won / lost, with Retry, Next level, Home
```

**Kotlin still never constructs structure.** It looks elements up by id and writes text, values and
`hidden`, with the same two registry-driven exceptions as today — the `<option>` lists in the bot
pickers, and the knob rows inside each seat's `<details class="knobs">`. Adding a third needs a better
reason than either of those had, and a screen is not one.

---

## `:ui` — the split

New files under `ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/`:

| File | Owns |
|---|---|
| `model/Screen.kt` | `internal enum class Screen { HOME, LADDER, GAME }` |
| `model/Panel.kt` | `internal enum class Panel { SETUP, TOURNAMENT, SHARE, SETTINGS }` |
| `chrome/Shell.kt` | which screen is shown, which panel is open, Escape, the focus trap, `inert` behind a modal |
| `chrome/HomeScreen.kt` | the home buttons, and which of them are offered |

`Chrome` keeps the game screen — board, transport, scrub, status, player rows, keys — and delegates
the rest. `SlotForm` and `KeyRepeat` are untouched.

`UiIntent` gains `Navigate(screen)`, `OpenPanel(panel)`, `ClosePanel`.
`UiModel` gains `screen: Screen`, `openPanel: Panel?`.

### Where the new intents sit in `dispatch`

`GameSession.dispatch` (`:139`) is ordered in three tiers and **the ordering is the design**:

1. above every guard: `Hover`, `HoverEnded`, `Relayout`;
2. the batch-running guard, which drops everything except `ToggleTournament`;
3. the batch-finished handback.

`Navigate`, `OpenPanel` and `ClosePanel` go in **tier 1, beside `Hover`**, and for `Hover`'s reason:
opening a panel changes nothing about the match, so it neither has to be dropped while a batch owns
the board nor is grounds for taking the board back off one. Put them below either guard and folding a
panel silently swaps a running tournament's board for the player's own game.

`Navigate` **to a different screen** is the exception — leaving the game screen must
`scheduler.stop()` and `batch.stop()`, because a match nobody can see must not keep running.

### The relayout obligation

`docs/UI.md` already carries this and it is the trap in the session: *"Anything that changes the height
of the chrome beside the board therefore changes the board, and the `resize` listener will not hear
about it."* A panel sliding over the board does **not** change the board's box — that is the whole
point of making them overlays rather than columns — but a screen change does. So:

- **`Navigate` ends with a `refit()`**, and it must render the chrome *before* it measures, exactly as
  `begin()` (`:279`) already does for the scrub row.
- A panel that overlays does not `refit`. If a panel is ever made to *push* the board instead, it
  becomes a `Relayout` and must be routed as the tournament disclosure already is.

---

## Focus, and the part that is easy to get wrong

- Every control is a native `<button>`, so Tab and Enter come free. Do not build custom widgets.
- **Escape closes the top panel or modal; with none open, it goes back a screen.**
- **A modal traps focus.** `#dialog-result` sets `inert` on the screen behind it, moves focus to its
  default action on open, and restores focus to whatever opened it on close.
- **Only the visible screen is focusable.** A hidden section must be `hidden` (which `[hidden] {
  display: none !important }` already makes real), not merely off-screen — an off-screen but focusable
  screen means Tab walks into nothing.
- Visible `:focus-visible` rings throughout. The current stylesheet has none.

`Chrome.onKeyDown`'s two existing guards stay and both matter: the `EDITABLE_TAGS` check, so arrows
belong to a focused select or slider, and the modifier check, so Ctrl+A and Alt+Left are not swallowed.

---

## `:app`

`Main.kt` decides the opening screen: `readReplay()` non-null → straight to the game screen in replay
mode, exactly as today. Otherwise → `#screen-home`.

**`document.body.classList.add("booted")` must stay ahead of `session.start()`.** A hidden element
reports `clientWidth == 0`, so revealing `#app` late sizes every board to the minimum cell. It is also
the success signal the inline boot watchdog in `index.html` watches for.

---

## Tests

`ui/src/wasmJsTest/`
- `ShellTest` — navigation shows exactly one screen; Escape closes the top panel, then goes back;
  opening a panel while a batch runs does not stop the batch.
- The existing `KeyRepeatTest`, `SlotLabelsTest`, `BoardRendererTest`, `PaletteTest` and
  `TurnSchedulerTest` must all still pass untouched. If one needs changing, the split moved behaviour
  and it should not have.

`GameSession`, `Chrome`, `SlotForm`'s read/apply logic and `TournamentRunner` have **no tests today**.
Do not try to fix that here; it would double the session.

---

## Done when

```bash
./gradlew build
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

In the browser: home → Custom → start a match → open and close each panel → back to home → into a
match again. Everything the page did before, it still does. Then Tab through every screen with the
mouse untouched, and confirm focus never lands on a hidden control.

Paste an `#r=` link into a fresh tab: it opens on the game screen in replay mode without passing
through home.
