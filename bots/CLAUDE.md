# :bots

Read [`../docs/Bots.md`](../docs/Bots.md) before changing anything here.

It carries the registry rules, the three things that bite when writing a bot, and the knob contract.
A slug or knob name you rename sits in the replay URL of every match somebody shared; a bot that
reads `playout.outcome` once throws only on the rollout that happens to end on that exact move.

An allowance is counted in **evaluations**, charged by `Scratch.playout(cost)`, and `EvaluationCost`
is the one place the per-kind figures live.

**Ask the board, and divide by `BoardView.openCount`.** An interior wall costs nothing here — it is
the padded ring's byte, so `freeNeighbors` already treats it as not-free — and what it breaks is any
reading that answered a question about the board without asking the board. A count taken off a row and
a column cannot see one, which is why `MovePrior` reads `isWall` at each neighbour; and
`Grid.playableCount` is pure geometry, where a share of the board is a share of the squares a snake
could ever stand on. Both mistakes are silent, and one of them is worse than silent: `PuctBot`'s wall
weight defaults to `0.0`, so no golden hash was ever watching that feature.

Nothing in `:bots` may call `ln`, `exp` or `pow` — see SW-02 in
[`../docs/Coding-Standards.md`](../docs/Coding-Standards.md).
