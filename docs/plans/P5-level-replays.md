# P5 — A saved replay per level

**Modules:** `:ui`, `:app`
**Depends on:** [P4](P4-maps-and-levels.md) — see *Sequencing* below. Shares the `keepLevel` change with
[P3](P3-screens-and-setup.md) §3.2.
**Read first:** [`../UI.md`](../UI.md) §"the one thing on this page that is remembered",
[`../Coding-Standards.md`](../Coding-Standards.md) SW-05, CC-08's fail-fast carve-out for storage.

Names below are written as they are after [P2](P2-gauntlet-rename.md).

## Why

Beat a level with a run you were pleased with and it is gone the moment you start another. Every
cleared level should keep the run that cleared it, replayable from its tile any time.

## Storage

**One key per level** — `snakewarz.gauntlet.replay.<n>.v1` — rather than one key holding eleven records.
Writing one level then rewrites nothing else, a value that arrives corrupt costs that one level rather
than all of them, and there is no concatenation format to parse.

`Preferences` grows `levelReplay(n: Int): String?` and `setLevelReplay(n: Int, payload: String)` over
the same silent `read` / `write` it already uses (`Preferences.kt:47-62`), so a browser that refuses
storage — Safari in private browsing throws on the property itself — still plays the game. That is the
existing carve-out from CC-08's fail-fast rule, and it applies here for the same reason: there is a
correct thing to do when storage is unavailable, and it is to offer no replay.

**The payload is not a new format.** It is `ReplayCodec`'s existing base64url string, the same one a
shared `#r=` link carries — already frozen, already round-tripped by its own tests. The only genuinely
new frozen thing is the **key name**, and SW-05 covers it once released. At a `maxTurns` of 4096 the
worst record is on the order of a kilobyte, so eleven of them sit far inside any quota.

## Writing

`GameSession.recordLevelWin()` is already *"the one moment a level is settled"* — called from `advance`
when a verdict appears, rather than from anything derived once a frame — so the write goes there beside
the progress write. Store `ReplayCodec.encode(match.record())` under the level's own key.

Only on a **win**: a level you lost has a replay nobody wants, and writing one would make the tile's ▷
mean something different from its `Cleared` badge.

## Reading and playing

- `GauntletScreen` asks `Preferences` per tile while it renders and reveals a ▷ on the ones that have
  something.
- Pressing it dispatches a new **`UiIntent.WatchLevelReplay(index)`** — a **`Match`** intent, because it
  replaces the match on the board and so must pass the guards about whose board that is.
- `GameSession` answers by decoding the payload and calling `load(record, keepLevel = index)`. A payload
  that will not decode is treated as absent — the same rule `GauntletProgress.parse` applies to junk
  from a devtools console.

## Two things this collides with, both already on the plan

- **`load(record)` clears `level`.** That is the same problem [P3](P3-screens-and-setup.md) §3.2 fixes
  for the verdict card's Replay button, and both use the same `keepLevel` path. Watching a level's
  replay must leave you inside that level: the bar still says which one, and `← Gauntlet` still goes
  back to the right screen.
- **A tile is a `<button>`, so a ▷ cannot go inside it.** Nested buttons are invalid and the inner one
  would never receive the click. Each `<li>` gains the replay control as a **sibling** of the level
  button — eleven small edits in `index.html`, a `.level-replay` rule in `styles.css` positioning it
  over the tile's corner, and a lookup by its own id in `GauntletScreen`. A locked or unbeaten level's ▷
  is `hidden`, not `disabled`: a control that can never apply should not be present at all, which is the
  argument `Mode.offers` already makes for the panel openers.

## Sequencing — why this lands after P4

A `MatchRecord` is self-describing, so a replay stored before the level table changed still plays back
correctly. But it would show the **old** map under a tile naming the new one. Shipping the storage after
the table settles means no such record ever exists, and the `v1` in the key stays honest.

If the level table ever changes again after this ships, the key version is the lever: `…replay.<n>.v2`
leaves the old values unread rather than showing somebody a game on a board that no longer exists.

## Tests

`GauntletScreenTest`:

- a level with nothing stored shows no ▷;
- a level with a stored payload shows one, and pressing it dispatches `WatchLevelReplay` with that index;
- a **locked** level never shows one, whatever is in storage.

`PreferencesTest`:

- a payload round-trips under its own per-level key;
- junk under the key reads as nothing rather than throwing;
- one level's value is untouched by writing another's.

## Verification

```bash
./gradlew allTests -PbrowserTests=true
```

By hand: item 16 of the release checklist in [`README.md`](README.md) — beat a level, go back to the
Gauntlet screen, press the ▷ on its tile, confirm your winning run plays back and that you are still
inside that level rather than in Custom. Reload the page and it is still there. Then beat the same level
again and confirm the stored run is the new one.
