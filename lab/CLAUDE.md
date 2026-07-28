# :lab

Read [`../docs/Workflow.md`](../docs/Workflow.md) before changing anything here.

This is a measuring instrument: it sits above everything and under nothing, and it is the one place
besides `:ui` where a clock and a `println` are allowed. Nothing may depend on it, and it may not see
`:ui` or `:app`.

Entrant parsing is **strict** on purpose — a mistyped knob name would otherwise quietly measure the
default and waste however many minutes the batch takes. Each subcommand declares the options *it*
takes, for the same reason.

The packages are the loop, in order: `arena` plays a schedule in parallel from diversified openings,
`log` writes every match to a gitignored `.lab/`, `strength` fits ratings and runs the sequential
test, `report` says why a bot is losing, `tune` searches knob space.

Three things here are load-bearing and easy to undo by accident:

- **Openings default to `mirrored`.** Spawns do not depend on the seed, so under `fixed` a pairing of
  bots that draw no randomness plays four distinct games however many rounds are asked for. Every
  batch prints how many of its matches were distinct games; that line is the honest sample size.
- **Results are collected by schedule index, never by which worker finished first.** That is the
  whole of the determinism guarantee for a threaded batch, and `ArenaTest` pins it.
- **`tune` recommends and never edits a default.** Adopting one moves every golden move-stream hash,
  and a tool that could do both would defeat SW-01 quietly. The ritual is in
  [`../docs/Bots.md`](../docs/Bots.md).
