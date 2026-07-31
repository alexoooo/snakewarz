# :match

Read [`../docs/Match.md`](../docs/Match.md) before changing anything here.

It carries where the human seat lives and why it is composed outside `ShippedBots`, why a match with
a person in it runs a clock only while a drawn route is held, and why `MatchStats` is derived rather
than accumulated — add a counter to `Match` for a statistic and the scoreboard grows a second source
of truth that can disagree.

Two packages here are newer than that document's oldest parts and each has one thing that bites:

- **`map/` — a map travels as squares, never as a name.** `MatchSetup.walls` is the wall set itself
  and the codec carries the bitmap, which is what lets a `MapShape` be redrawn or deleted without
  breaking a link anybody has shared. Symmetry and connectivity are guarantees of `generateMap` and
  **not** of `BoardMap`: a hand-drawn fixture is legitimately neither, and a decoded map is whatever a
  stranger played on. [`../docs/Maps.md`](../docs/Maps.md) is the catalogue and how to add a shape.
- **`ladder/` — the ten levels are a table here so that `:lab` can measure them.** A `LadderLevel` is
  a whole match configuration rather than a difficulty number, and `LadderLevel.index` is frozen once
  released: it is the key somebody's saved progress is stored under. The opponent is a slug resolved
  through the `BotRegistry` interface, because this module has still never seen a bot class.

This module has never seen a bot class and must not: it resolves bots through the `BotRegistry`
*interface*, and `:app` injects the implementation. That is what keeps the replay codec free of bot
classes, and `checkModulePurity` fails the build on it.
