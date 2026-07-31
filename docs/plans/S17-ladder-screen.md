# S17 — Level select, progress, Continue

**Modules:** `:ui`, `:app`
**Depends on:** [S16](S16-ladder-table.md), [S12](S12-portraits.md), [S08](S08-screen-shell.md).

## Goal

A mode you can come back to. Ten tiles, unlimited lives, and progress that survives a reload.

---

## Step 1 — the level select

`#screen-ladder`, filled by `ui/.../chrome/LadderScreen.kt`.

Ten tiles, each showing the opponent's portrait, `LadderLevel.title`, `blurb`, the board size and the
map. Three states: **cleared**, **open** (the highest unlocked), **locked** (everything above it).

**`Ladder.levels` comes off `:match`, which `:ui` already sees**, so this needs no new seam and no
injected registry — unlike bots, whose names have to come through `BotRegistry`. Ten tiles is a fixed
number, so they are **static markup** in `index.html` and Kotlin writes their text and state, exactly
as the four scoreboard rows work today. This is not a third exception to *"Kotlin never constructs
structure"*.

A locked tile is a disabled `<button>`, not a `<div>` — it stays in the tab order's logic, announces
itself as unavailable, and cannot be clicked.

## Step 2 — progress

`ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/chrome/LadderProgress.kt`

`localStorage["snakewarz.ladder.v1"]`, hand-parsed. **Not JSON** — there is no JSON in the bundle
today and pulling one in for two integers is exactly what SW-08 is about.

```
v1:<highest unlocked>:<cleared bits>
```

Everything the theme preference in [S11](S11-themes.md) has to survive, this does too, and more:

- a missing key → level 1, nothing cleared;
- a value from a **future** version → treat as absent, do not crash. Version in the value, not only in
  the key, so a v2 writer and a v1 reader can coexist;
- junk → treat as absent;
- `localStorage` **throwing** → treat as absent. Safari in private browsing throws, and a boot that
  dies looking up progress is a black page for everyone using it.

Reuse [S11](S11-themes.md)'s `Preferences` wrapper for the throwing part rather than writing the
`try` twice (CC-12).

Clamp on read: a stored `highest` above `Ladder.levels.size` is a table that shrank, and it must clamp
rather than index out of bounds.

## Step 3 — the loop

- **Home** shows **Continue — Level N** whenever progress exists, beside **Ladder** which opens the
  grid. Continue starts the highest unlocked level directly, because that is the button somebody came
  back for.
- Starting a level builds `level.setup(seed, PlayableRegistry.HUMAN_ID)` and runs it as an ordinary
  match. **Everything downstream is unchanged** — the same driver, the same renderer, the same replay
  codec. A ladder match is shareable like any other.
- The seed is fresh per attempt. A level that replayed identically after a loss would be a puzzle,
  not a game — and the bots at levels 1-3 draw no randomness, so the seed is the *only* thing that
  varies for them.
- **Losing** opens `#dialog-result` with **Retry** focused. Unlimited lives means a loss costs one
  Enter, and that is the whole of the feature.
- **Winning** marks the level cleared, unlocks the next, writes progress, and offers **Next level**.
  Winning level 10 offers **Home** and says so.

## Step 4 — mode state in the session

`GameSession` gains the current mode. It affects three things and only three:

1. which panels are offered — `#panel-setup` and `#panel-tournament` are Custom-only
   ([S10](S10-panels.md));
2. what the result dialog offers — Retry / Next level in Ladder, Restart / Home in Custom;
3. what the top bar names — the level in Ladder, the opponents in Custom.

**It must not branch the match code path.** Playing and replaying are one code path and a mode is not a
reason to make them two.

An `#r=` replay arriving by hash while a ladder level is open takes over, exactly as it does today —
`load(record)` already stops the scheduler, clears the input and rebuilds. Leaving the level does not
lose progress, because progress is only written on a win.

---

## Tests

`ui/src/wasmJsTest/.../chrome/LadderProgressTest.kt`
- round-trips; a missing, junk, future-version and throwing store all yield level 1;
- a `highest` above the table clamps;
- clearing level N unlocks exactly N+1 and no more.

`LadderScreenTest` — locked tiles are disabled; exactly one tile is "open"; clearing the last level
leaves none locked.

---

## Done when

```bash
./gradlew build
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

In the browser, from a cleared `localStorage`:

- home offers Ladder but not Continue;
- level 1 is open and 2-10 are locked;
- win level 1 → Next level appears, 2 unlocks, Continue appears on home;
- reload → Continue resumes at level 2;
- lose deliberately → Retry costs one Enter and the board is a different game;
- corrupt the stored value by hand → the page still boots, at level 1;
- play a ladder level, copy its replay link, open it in a fresh tab → it plays back.

Then the same on a phone-sized viewport, by touch, using the drag controls from
[S14](S14-path-input.md).
