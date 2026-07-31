# :match

Read [`../docs/Match.md`](../docs/Match.md) before changing anything here.

It carries where the human seat lives and why it is composed outside `ShippedBots`, why a match with
a person in it runs a clock only while a drawn route is held, and why `MatchStats` is derived rather
than accumulated — add a counter to `Match` for a statistic and the scoreboard grows a second source
of truth that can disagree.

Three packages here each carry one thing that bites:

- **`human/` — the clearance off-by-one is `i - 1`, and it means two different things.** A square is
  passable at plan index `i` if it is free now or its owner retracts past it within `i - 1` of that
  snake's *own* moves. For your own body that is an ordering rule — `Board.apply` reads
  `isFree(target)` before the tail retracts — and for everyone else it is a move count, conservative
  by one because a seat acting before the player in `toAct` order has already made its `i`-th move and
  the planner does not know which seats those are: `toAct` cycles from wherever the round is, not from
  slot 0. Conservative is the only safe direction, because `InputBuffer.take` answers a discarded
  direction with the next legal one from the same route, so optimism makes the snake skip a leg rather
  than stop. `ClearanceTest`'s oracle is what would notice a `+1` drifting.
- **`map/` — a map travels as squares, never as a name.** `MatchSetup.walls` is the wall set itself
  and the codec carries the bitmap, which is what lets a `MapShape` be redrawn or deleted without
  breaking a link anybody has shared. Symmetry and connectivity are guarantees of `generateMap` and
  **not** of `BoardMap`: a hand-drawn fixture is legitimately neither, and a decoded map is whatever a
  stranger played on. [`../docs/Maps.md`](../docs/Maps.md) is the catalogue and how to add a shape.
- **`gauntlet/` — the eleven levels are a table here so that `:lab` can measure them.** A `GauntletLevel`
  is a whole match configuration rather than a difficulty number, and `GauntletLevel.index` is frozen
  once released: it is the key somebody's saved progress is stored under. The opponent is a slug
  resolved through the `BotRegistry` interface, because this module has still never seen a bot class.

This module has never seen a bot class and must not: it resolves bots through the `BotRegistry`
*interface*, and `:app` injects the implementation. That is what keeps the replay codec free of bot
classes, and `checkModulePurity` fails the build on it.
