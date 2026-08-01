# :lab

Read [`../AGENTS.md`](../AGENTS.md) and [`../docs/Workflow.md`](../docs/Workflow.md) before changing
anything here.

This is a measuring instrument: it sits above everything and under nothing. It is the only place
besides `:ui` where a clock and `println` are allowed. Nothing may depend on it, and it may not see
`:ui` or `:app`.

Entrant parsing is strict on purpose. A mistyped knob otherwise measures the default and wastes an
entire batch. Each subcommand accepts only the options it declares.

The packages form a loop: `arena` plays schedules in parallel from diversified openings; `log`
writes matches to gitignored `.lab/`; `strength` fits ratings and runs sequential tests; `report`
explains losses and `Separation` locates the move a board split for good; `tune` searches small knob
spaces by coordinate descent and larger ones by SPSA; `train` replays logs into positions and fits
`eval=learned` weights through `:bots`' own `PositionFeatures`.

Seven constraints are load-bearing:

- **`RunHeader.comparabilityKey` carries the map, derived from walls rather than a shape name.** A
  walled batch must not pool with a bare batch. `openingSetup` and `TournamentSchedule.setupFor`
  rebuild `MatchSetup`; forgetting a field makes the tool play a different game from the one logged.
- **Openings default to `mirrored`.** With `fixed`, deterministic bots can play only four distinct
  games regardless of requested rounds. Every batch prints the honest distinct-game count.
- **Collect results by schedule index, not worker completion order.** `ArenaTest` pins deterministic
  output from parallel runs.
- **A search searches the complete entrant spec.** Hold pinned values fixed in both arms and in the
  confirming baseline. A knob hidden behind a different `Choice` is otherwise dead code.
- **`tune`, `spsa`, and `train` recommend and never edit.** Adopting an answer moves golden streams;
  the tool prints the proposed literal and leaves the decision to a person.
- **A search's answer is its confirming run, not its journal.** Confirmation uses a disjoint seed
  base at a stricter bound. Journal maxima are maxima of a noise process, not findings.
- **`ab` measures what two entrants do to each other, which may not measure the desired field
  effect.** `AbCommand.blindness` warns when self-play is uninformative; do not remove it as noise.
