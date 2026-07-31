# :lab

Read [`../docs/Workflow.md`](../docs/Workflow.md) before changing anything here.

This is a measuring instrument: it sits above everything and under nothing, and it is the one place
besides `:ui` where a clock and a `println` are allowed. Nothing may depend on it, and it may not see
`:ui` or `:app`.

Entrant parsing is **strict** on purpose — a mistyped knob name would otherwise quietly measure the
default and waste however many minutes the batch takes. Each subcommand declares the options *it*
takes, for the same reason.

The packages are the loop, in order: `arena` plays a schedule in parallel from diversified openings,
`log` writes every match to a gitignored `.lab/` and names one back, `strength` fits ratings and runs
the sequential test, `report` says why a bot is losing and `Separation` says when — the move the
board came apart for good, which the `phases` subcommand splits a log along — `tune` searches knob
space two ways —
coordinate descent up to about three knobs, SPSA past that — and `train` replays logged matches back
into positions and fits `eval=learned`'s weights to them, reading each one through `:bots`' own
`PositionFeatures` so that the trainer and the bot cannot drift apart.

Seven things here are load-bearing and easy to undo by accident:

- **`RunHeader.comparabilityKey` carries the map, derived from the walls and never from a shape
  name.** A batch on a walled board differs from one on a bare board in nothing else the header
  records, so without it the two pool and every rating fitted over the pair describes neither. Two
  places rebuild a `MatchSetup` field by field — `openingSetup` and `TournamentSchedule.setupFor` —
  and a field either of them forgets is a batch that plays a different game from the one it logs.
- **Openings default to `mirrored`.** Spawns do not depend on the seed, so under `fixed` a pairing of
  bots that draw no randomness plays four distinct games however many rounds are asked for. Every
  batch prints how many of its matches were distinct games; that line is the honest sample size.
- **Results are collected by schedule index, never by which worker finished first.** That is the
  whole of the determinism guarantee for a threaded batch, and `ArenaTest` pins it.
- **A search searches the entrant it is named, spec and all.** `tune puct:eval=chamber` holds `eval`
  still in both arms *and* in the confirming run's baseline. A weight living under a `Choice` is dead
  code at any other setting, so a search handed a bare slug would report a well-formed answer about a
  bot it was not searching — and the baseline half of that leaves no trace in the output.
- **`tune`, `spsa` and `train` recommend and never edit.** Adopting one moves every golden
  move-stream hash, and a tool that could do both would defeat SW-01 quietly. `train` prints the
  literal `LearnedWeights` should hold and leaves the paste to a person. The ritual is in
  [`../docs/Bots.md`](../docs/Bots.md).
- **A search's answer is its confirming run, never its journal.** Both searches end with an `ab` of
  the point they reached against the shipped defaults, on a disjoint seed base at a stricter bound,
  and print that command so it can be re-run alone. A journal is a record of attempts: the best row
  in one is the maximum of a noise process, and `spsa`'s rows deliberately carry no verdict column
  value at all so that none of them can be read as one.
- **`ab` measures what two entrants do to each other, which is not always the change.** A guard that
  only fires in positions an opponent playing the same way never creates is invisible head to head
  and worth real points against a field — `ChaseBot.ROOM_SHARE` is `1 Elo ±3` under `ab` and `+14`
  under `rate`. `AbCommand.blindness` prints the fingerprint (a `NO_BETTER` verdict on top of boards
  that mostly split exactly); do not delete it because a run looks noisy.
