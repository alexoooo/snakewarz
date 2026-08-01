# :match

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/Match.md`](../docs/Match.md) before changing anything
here.

The match guide documents where the human seat lives, why it is composed outside `ShippedBots`, why
a match with a person runs a clock only while a drawn route is held, and why `MatchStats` is derived
rather than accumulated. Adding a counter to `Match` for a statistic gives the scoreboard a second
source of truth.

Three packages here each carry a non-obvious constraint:

- **`human/`: the clearance off-by-one is `i - 1`, and it means two things.** A square is passable at
  plan index `i` when it is free now or its owner retracts past it within `i - 1` of that snake's own
  moves. For your body this preserves `Board.apply` checking `isFree(target)` before tail retraction.
  For another body it is conservative by one because `toAct` cycles from the current round position,
  not slot 0. Optimism makes `InputBuffer.take` skip a route leg instead of stopping. The oracle in
  `ClearanceTest` protects this arithmetic.
- **`map/`: a map travels as squares, never as a name.** `MatchSetup.walls` is the wall set and the
  replay codec carries its bitmap, so a `MapShape` can change without breaking shared links.
  Symmetry and connectivity are guarantees of `generateMap`, not `BoardMap`; fixtures and decoded
  maps may legitimately be neither. Read [`../docs/Maps.md`](../docs/Maps.md) before adding a shape.
- **`gauntlet/`: the levels live here so `:lab` can measure them.** A `GauntletLevel` is a complete
  match configuration, not a difficulty number. Its released `index` is a persistent saved-progress
  key. Opponents are slugs resolved through `BotRegistry`; this module must not see bot classes.

`:match` resolves bots only through the `BotRegistry` interface and `:app` injects the implementation.
This keeps replay code free of bot classes, and `checkModulePurity` enforces the boundary.
