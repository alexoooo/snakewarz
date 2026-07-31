# S15 — Everything, without a mouse

**Modules:** `:ui`, `:app`
**Depends on:** [S14](S14-path-input.md).
**Read first:** `ui/.../chrome/Chrome.kt` `onKeyDown` (`:354-386`) — its two guards and its
cancel-then-act ordering are already right and must survive.

## Goal

Every screen reachable, every match startable, playable and restartable, from the keyboard alone.

---

## What already works, and must not regress

| Key | Does | Why it is the way it is |
|---|---|---|
| Arrows / WASD | steer | `preventDefault` **first**, repeat guard **second** — the other way round, a held arrow steers at our rate and scrolls the page at the keyboard's |
| Space | play / pause a bots-only match | |
| `.` | step one turn | deliberately **not** cancelled: a full stop is not a scroll key and nothing else on the page wants it |

Two guards run before any of that and both stay:

- `document.activeElement?.tagName in EDITABLE_TAGS` — arrows belong to a focused select or slider;
- `event.ctrlKey || altKey || metaKey` — `key` is still `"a"` under Ctrl and `"ArrowLeft"` under Alt,
  so without this the page steers on select-all and swallows Back.

`onKeyUp` deliberately has **no** focus guard: if focus moves mid-hold the key must still be able to
stop, and a release that stops nothing costs nothing. The `blur` listener that calls `repeat.cancel()`
stays for the same reason — a key let go of while the page is not looking never reports it.

Held keys repeat on `KeyRepeat`'s 250 ms clock, not the OS's. That is a statement about how fast the
snake moves and it is not negotiable by a text-editing preference.

---

## What this session adds

| Key | Does |
|---|---|
| **Enter** | activates the focused control; on `#dialog-result`, its default action (Retry, or Next level) |
| **Escape** | closes the top panel or modal; with none open, goes back a screen |

Both come partly free — a native `<button>` already activates on Enter — so the work is:

1. **Every control is a native `<button>`, `<select>`, or `<input>`.** No custom widgets. Every custom
   widget is a keyboard-accessibility bill that has to be paid in full, and nothing here needs one.
2. **The result dialog opens with its default action focused**, so a loss costs exactly one Enter.
   That is what "unlimited lives" means in practice.
3. **A modal traps focus** and sets `inert` on the screen behind it, and restores focus to whatever
   opened it on close.
4. **Only the visible screen is focusable.** Hidden screens use `hidden` — `[hidden] { display: none
   !important }` is already in the stylesheet and makes that real. An off-screen-but-focusable screen
   means Tab walks into nothing, which is the single most common way this goes wrong.
5. **Visible `:focus-visible` rings**, on every interactive element, in both themes and both schemes.
   The current stylesheet has none at all. `:focus-visible` rather than `:focus`, so a mouse click
   does not leave a ring behind.
6. A logical tab order per screen: the primary action first. Achieved by **DOM order**, not by
   `tabindex` — a positive `tabindex` anywhere makes the whole page's order a puzzle.

## Accessibility, which is the same work

- The existing `role="status"` on `#status` and `role="alert"` on `#unsupported` stay. Add
  `aria-live="polite"` to whatever announces a result.
- Every icon-only button needs an `aria-label`. `#reseed` already has one; the new bars will add more.
- Portraits from [S12](S12-portraits.md) are `aria-hidden` and always sit beside the name in text.
- `#board` keeps its `aria-label`; `#board-overlay` keeps `aria-hidden`.
- The `#panel-*` sheets are `role="dialog"` with `aria-modal` only when they actually trap focus. A
  non-modal side panel that claims `aria-modal` hides the rest of the page from a screen reader while
  it is visibly still there.

## The keys note

`index.html` ends with a `<h2>Keys</h2>` section describing arrows, Space and `.`. It moves into
`#panel-settings` and gains Enter and Escape — and it must **actually match** the table above.
CC-18: user-facing text speaks the player's language, and a shortcut list that lies is worse than
none.

---

## Tests

- `KeyRepeatTest` — existing cases pass untouched.
- New cases for Escape's two behaviours (close the top panel; with none open, go back), and for Enter
  activating the result dialog's default.
- Focus-trap: with the result dialog open, Tab from the last control returns to the first.

---

## Done when

```bash
./gradlew build
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

Then **unplug the mouse** — or resolve not to touch it — and do a whole session: home → Custom →
configure a seat → start → play with the arrows → lose → Retry with Enter → Escape back to home →
open settings → change theme → back. Focus must be visible at every step and must never disappear.

Repeat with a screen reader if one is to hand. Failing that, at minimum check that every button
announces something other than "button".
