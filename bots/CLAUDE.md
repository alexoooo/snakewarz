# :bots

Read [`../docs/Bots.md`](../docs/Bots.md) before changing anything here.

It carries the registry rules, the three things that bite when writing a bot, and the knob contract.
A slug or knob name you rename sits in the replay URL of every match somebody shared; a bot that
reads `playout.outcome` once throws only when the allowance lands on that exact move.

Nothing in `:bots` may call `ln`, `exp` or `pow` — see SW-02 in
[`../docs/Coding-Standards.md`](../docs/Coding-Standards.md).
