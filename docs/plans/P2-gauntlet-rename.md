# P2 — Ladder becomes Gauntlet

**Modules:** `:match`, `:ui`, `:app`, `:lab`, `docs/`
**Depends on:** nothing. Lands **before** [P4](P4-maps-and-levels.md) so the level work is written once.
**Read first:** [`../Coding-Standards.md`](../Coding-Standards.md) SW-05 (released identifiers are
frozen), CC-18.

## Why

`Ladder` names three unrelated things in this repo:

1. **The ten-level single-player campaign** — the only one a player ever sees.
2. **`BotLadderTest`'s bot-strength ordering** of the shipped registry on an empty 12×12.
3. **`lab/strength/Ladder.kt`** — every entrant in a set of logged matches, rated against each other.

`Ladder.kt:18` and `docs/Bots.md:55` both already have to warn that (1) and (2) are different things.
Renaming the one a player sees removes the overload instead of adding to it, and makes the game read
like a game.

**Only (1) is renamed.** (2) and (3) keep the word, and every ambiguous site has to be read rather than
swept.

## The one thing that does not change

`localStorage["snakewarz.ladder.v1"]` — `ui/.../chrome/Preferences.kt:65`. It is somebody's saved place
and SW-05 freezes it; renaming it silently resets every existing player. The **constant** becomes
`GAUNTLET_KEY`, the **string** does not, and a comment says why the two disagree.

The stored value format, `v1:<highest>:<clearedBits>`, contains no occurrence of the word and is
untouched.

## Zero replay or URL impact

Verified: the replay codec has no ladder reference and `app/.../replayHash.kt` handles only `#r=`. A
shared link carries the wall bitmap and bot slugs, never a level number. `docs/Coding-Standards.md:181`
already states it: *"A `LadderLevel.index` is not in a replay either."*

## `:match`

- `match/src/commonMain/kotlin/ao/snakewarz/match/ladder/` → `.../gauntlet/`; package
  `ao.snakewarz.match.ladder` → `ao.snakewarz.match.gauntlet`.
- `Ladder.kt` → `Gauntlet.kt`: `object Ladder` → `object Gauntlet`,
  `toString()` → `"Gauntlet($size levels)"`.
- `LadderLevel.kt` → `GauntletLevel.kt`: `class LadderLevel` → `GauntletLevel`, `toString()` prefix with
  it. **`index` stays frozen** — it is the key inside saved progress.
- `match/src/commonTest/.../ladder/LadderTest.kt` → `.../gauntlet/GauntletTest.kt`.
- `match/CLAUDE.md` — the `ladder/` bullet at lines 17-18.

## `:ui`

| From | To |
|---|---|
| `ui/.../model/ladder/LadderProgress.kt` | `ui/.../model/gauntlet/GauntletProgress.kt`, package to match |
| `ui/.../chrome/LadderScreen.kt` | `chrome/GauntletScreen.kt` (class and its private `Tile`) |
| `Screen.LADDER` | `Screen.GAUNTLET` |
| `Mode.LADDER` | `Mode.GAUNTLET` |
| `UiModel.ladder` | `UiModel.gauntlet` |
| `Preferences.ladder()` / `setLadder()` | `gauntlet()` / `setGauntlet()`; `LADDER_KEY` → `GAUNTLET_KEY`, **value unchanged** |
| `Chrome.ladder` field (line 50) | `gauntlet` |
| `HomeScreen.ladderButton`, `LADDER_CLASS` | `gauntletButton`, `GAUNTLET_CLASS` |
| `GameSession` `"Ladder complete"` (line 659) | `"Gauntlet cleared"` |

`ui/CLAUDE.md:23` mentions `ladder/` being nested out of `model/` — update it.

Tests that follow: `LadderScreenTest` → `GauntletScreenTest`, `LadderProgressTest` →
`GauntletProgressTest`, plus the ladder cases in `PreferencesTest` (line 55, and the `LADDER_KEY`
literal at line 81 — **that literal does not change**) and `ShellTest` (line 96, the `"Ladder complete"`
assertion at 133, and `"screen-ladder"` at 250 and 327).

## `:app` — page shell

- `index.html:67` — button label `Ladder` → `Gauntlet`.
- `index.html:91` — `<h1>Ladder</h1>` → `<h1>Gauntlet</h1>`.
- ids: `home-ladder` → `home-gauntlet` (`HomeScreen.kt:25`), `screen-ladder` → `screen-gauntlet`
  (`Shell.kt:304`, `GauntletScreen.kt:18` KDoc, `ShellTest.kt:250,327`), `ladder-back` →
  `gauntlet-back` (`Shell.kt:118`, `ShellTest.kt:327`).
- `styles.css:176` — the `#screen-ladder` selector. It is the **only** ladder-bearing selector in the
  file; the other two hits are comments. Tile classes (`level`, `.level-no`, `.levels`, …) are untouched.

There are no `aria-label`, `title`, `alt` or `placeholder` attributes containing the word.

## `:lab`

- `lab/.../LadderCommand.kt` → `GauntletCommand.kt`; class, `DEFAULT_REFERENCE`, `REFERENCE_BUDGET`,
  `toString()`.
- `LabCommand.kt`: subcommand string `"ladder"` → `"gauntlet"` (line 313), `LADDER_FLAGS` →
  `GAUNTLET_FLAGS` (line 71), `ladderOf` → `gauntletOf` (line 694), usage and help text at lines 208,
  226-227, 286, and the error message at 696.
- `lab/src/test/.../LadderCommandTest.kt` → `GauntletCommandTest.kt`; the four `` `ladder …` `` test
  names in `LabCommandTest` at lines 395, 412, 422, 432, and the CLI strings they invoke.

## Do **not** rename — read each site, do not sweep

- `lab/src/main/kotlin/ao/snakewarz/lab/strength/Ladder.kt` and everything reading it: `RateCommand`
  (36 hits), `bootstrapIntervals` (24), `turnCosts` (11), `entrantOf`,
  `lab/src/test/.../strength/LadderTest.kt` (58). A ratings table, not the campaign.
- `bots/src/commonTest/.../BotLadderTest.kt`, `ShippedBots`' prose, and the `LADDER_BOARD = 12`
  constants in `LabCommand.kt:50` and `ThroughputTest.kt:202` — the bot-strength ordering.
- Prose mentions of "the ladder" meaning bot strength in `:bots` and `:match` source comments
  (`AlphaBetaBot`, `PuctBot`, `UctBot`, `TerritoryEval`, `MatchSetup`, `MapShape`, …).

**`LabCommand.kt` is the file where both meanings live and each mention must be judged:** lines 44, 264
and 274 are the *bot* ladder; lines 67 and 286 are the campaign.

## Docs

`CLAUDE.md` (the module table rows at 54-57, the *Current state* prose at 32/41/44/65/80, and the
commands block at 239 — but **not** line 255, which is `BotLadderTest`), `match/CLAUDE.md`,
`ui/CLAUDE.md`, `docs/UI.md` (16 hits including the section heading at line 162), `docs/Match.md`
(8 hits including the heading at 261), `docs/Bots.md:55-71`, `docs/Coding-Standards.md:178-183`,
`docs/Maps.md:27,157-161`, `docs/Workflow.md` (lines 33, 56, 58, 84, 175-176 are the subcommand; the
rest are the bot and ratings ladders).

**One trap.** `docs/Bots.md`'s heading *"### The single-player ladder is a different ordering, and it is
measured per level"* is linked **by anchor** from `docs/Maps.md:161` and `docs/Match.md:289`. Renaming
the heading breaks both links — change all three together.

`docs/Coding-Standards.md`'s frozen-identifier passage now has to say the storage key deliberately kept
the old word, which is a better example of SW-05 than it was before.

`docs/plans/*` (this directory's own history), `docs/research/*` and `docs/audit/*` are records of what
was decided when, and keep the word.

## Verification

```bash
./gradlew build
./gradlew :lab:run --args="gauntlet --rounds 10"
grep -rn --include=*.kt --include=*.html --include=*.css -i ladder .   # every survivor is deliberate
```

By hand: the home screen and level select read **Gauntlet**; beat level 1, reload, and progress is still
there — which is the whole point of leaving the key alone.
