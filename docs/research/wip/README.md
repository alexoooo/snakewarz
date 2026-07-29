# Work in progress

Scratch that belongs to a research session and not to the repository: coordination ledgers, agent
handovers, half-analysed batch output, a table somebody is still deciding whether to believe.

**Why it is tracked rather than ignored.** The 2026-07-28 run kept its ledger outside the repo, in the
job's temp directory, and that was the one thing that did not survive a context compaction cleanly. A
ledger the next session can `Read` is worth the diff noise. These files are *notes*, so review them as
notes — nobody is checking a WIP file against
[`../../Coding-Standards.md`](../../Coding-Standards.md).

## The convention

| File | Who owns it | Lives until |
|---|---|---|
| `<agenda-date>-ledger.md` | the coordinator, alone | the agenda closes |
| `<agenda-date>-P<n>-handover.md` | the agent that wrote it | the phase's findings land in the agenda |
| anything else | whoever made it | it is folded in or deleted |

**The ledger is the coordinator's and no agent writes to it.** Two agents editing one file is a merge
conflict in a document whose whole job is to be the single account of what happened. Agents report;
the coordinator writes.

**A handover is for what the *next* agent needs and cannot cheaply re-derive** — not a summary of the
work. The worked example is P1: its implementation agent predicted that `horizon` and `survival` would
play byte-identical move streams on roughly two boards in five, because a flat factor of two cancels in
a share. That one paragraph chose the measurement instrument for the phase that followed it.

**Delete on landing.** When a phase's findings are written into the agenda, its WIP files have no
second reader. A stale handover contradicting a landed finding is worse than no handover: the agenda
is the record, and this directory is the desk.

[`../../Research-Process.md`](../../Research-Process.md) is how a session uses all of this.
