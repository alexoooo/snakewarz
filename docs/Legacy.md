# Legacy Java

**For:** comparing the live code against the pre-rewrite Java, or working out why the rewrite
disagrees with it. Nothing in here is outstanding work.
**Assumes:** [`../CLAUDE.md`](../CLAUDE.md) — the module graph, the forbidden dependency edges and
the four non-obvious facts live there and are **not repeated here**. Fact 4, that legality is
evaluated before the tail retracts, is the one legacy rule the rewrite *does* keep.

## Reaching the deleted tree

**The port is finished and `legacy/` is deleted.** What follows is a record of what was found there,
kept because it is the reasoning behind several decisions in the live code and because somebody will
eventually read the old Java and wonder why the rewrite disagrees with it.

The pre-rewrite Java is one command away, in its original Maven shape:
`git show legacy-java-final:src/main/java/ao/<path>`. Paths written as `legacy/java/ao/…` in older
notes are that, one directory level shifted.

## It was a specification, not code to translate

It has two competing board representations and the wrong performance shape; `:core` is a
from-scratch rewrite. Algorithms were ported semantically and the scaffolding deleted.

**The AI is fully ported and nothing under `ai/` is outstanding.** The sample bots landed in
Phase 4 — `WallHugAi`, `RandomAi`, `ForkAi`, `ForkPathAi`, `PathAi`, `AStar`, `MonteCarloAi`,
`UctAi`/`Node`/`BiState`, `PvpAi`'s reduction and `BoardOccupancy.mostDistant` — and the contributed
`ai/da/` bots in Phase 5, as `BurninHellBot` and `TomSnakeBot`. `OtherSnake` is the one deliberate
omission: its body is `RandomAi`'s body, so it is already shipped as `random`, and a second slug for
one policy is a duplicate picker row and nothing else. Do not "finish the port" by adding it.

All three `ai/da/` bots extended `PvpAi` and **none of them ever read the `opp` it computed**, so the
nearest-opponent reduction is dropped from all three rather than ported.

The single external dependency, `ao.util:util-lang:2.0.0`, is served from a `raw.githubusercontent.com`
Maven repo and drags in log4j 1.2.14. Only `Rand` was ever used. Drop it entirely.

## Known legacy bugs — do not faithfully reproduce these

- `RelLocation.directionTo` is dead-broken: `closestDist = Double.MIN_VALUE` (smallest *positive* double)
  compared with `dist < closestDist`, so it always returns `FOREWARD`. The class is unreferenced; drop it.
- `PvpAi` picks the **walled-off** opponent every time. `AStar.pathBetween` returns an *empty list*
  for an unreachable target, `PvpAi` reads its `size()` as the distance, and `0` beats every real
  distance. `nearestOpponent` uses `ShortestPaths.UNREACHABLE`, and there is a named test for it.
- `AStar` is not A\*: `Path.compareTo` orders by cost-so-far and uses the heuristic only as a
  tie-break, so the frontier comes off in `g` order and the heuristic prunes nothing. On a unit-cost
  4-neighbour grid that is breadth-first search, which is what `ShortestPaths` is.
- `AiUtil.availableArea` checks its `stopAt` cap only *between* search layers, so it overshoots by up
  to a whole frontier — `ForkAi(6)` never meant six squares.
- `ForkPathAi` keys a `TreeMap` on the move appraisal, so two equally-rated directions collapse into
  one entry and one of them silently stops being a candidate; its `Math.random() < 0.5` tie-break is
  non-uniform; and with no opponents left its mean distance is `0 / 0`.
- `MonteCarloAi` divides by `numRuns` having run `numRuns / |legal|` rollouts, and drains one
  candidate at a time — so a budget that expires part-way biases the argmax toward the first
  direction.
- `Node.propagateValue` complements the reward at every step up the path. That is correct for two
  players alternating and wrong the moment a third exists.
- `MoveTracker.retrieveOrCreateSpecifier` seeds a bot's first move with *the first available direction*, so
  a bot that never sets one plays a move it never chose — and then repeats it forever.
- `SnakesRunner.setupGame` wraps `PlayerAvatar` and then `SnakesGame2.addPlayer` wraps it again, burning two
  colour-pool slots and two indices per player.
- `Node`'s static 2-thread `ExecutorService` is entirely dead — its only caller is commented out.
- `NestedSwingInput.queue` is a plain `ArrayList` written from the Swing EDT and read from the game thread
  with no synchronization.
- The 13 `assert` statements are inert (`-ea` is not set), so `Reward`'s `[0,1]` invariant was never enforced.
