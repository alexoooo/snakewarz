# :bots

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/Bots.md`](../docs/Bots.md) before changing anything
here.

The bot guide carries the registry rules, hot-path constraints, and knob contract. A slug or knob
name you rename sits in every replay URL somebody shared. A bot that reads `playout.outcome` once
throws only on the rollout that happens to end on that exact move.

An allowance is counted in **evaluations**, charged by `Scratch.playout(cost)`, and `EvaluationCost`
is the one place the per-kind figures live.

**Ask the board, and divide by `BoardView.openCount`.** An interior wall costs nothing here—it uses
the padded ring's byte, so `freeNeighbors` already treats it as occupied—but it breaks any reading
that answers a question about the board without asking the board. A row and column cannot reveal an
interior wall, which is why `MovePrior` reads `isWall` at each neighbour. `Grid.playableCount` is
geometry; a share of the board is a share of squares a snake could actually stand on. Both mistakes
are silent, and `PuctBot`'s wall weight defaults to `0.0`, so no golden hash necessarily watches that
feature.

Nothing in `:bots` may call `ln`, `exp`, or `pow`; see SW-02 in
[`../docs/Coding-Standards.md`](../docs/Coding-Standards.md).
