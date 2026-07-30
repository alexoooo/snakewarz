# Research process

How a research agenda is written, run and closed in this repository. Everything here was learned by
doing it once — [`research/2026-07-28_Research-Agenda.md`](research/2026-07-28_Research-Agenda.md), ten
workstreams over eight phases — and most of it was learned by getting something wrong first. Where a
rule has a worked case, the case is quoted, because the numbers are what make it stick.

This is a *process* document. The instruments themselves are in
[`Workflow.md`](Workflow.md#deciding-whether-a-change-helped) and are not repeated; what is here is the
order to use them in, what to believe when they disagree, and how to split the work across sessions
without losing it.

---

## The shape of an effort

| Artefact | Where | Lifetime |
|---|---|---|
| The agenda | `docs/research/YYYY-MM-DD_Research-Agenda.md` | forever, as a record |
| Work in progress | `docs/research/wip/` — see its [README](research/wip/README.md) | one session, or one phase |
| A finding | written **into the agenda**, and into the KDoc beside the constant it set | forever |
| A measurement | `.lab/<experiment>` — gitignored, regenerable from the command | until somebody deletes it |

**An agenda is a set of workstreams; a phase is one workstream; a session is one phase.** A session is
one coordinator plus the sub-agents it dispatches. The unit that matters is the *phase*, because a
phase is the smallest thing that can produce a finding worth writing down.

---

## The agenda is a living document

It is written before the work and **rewritten by the work**. The 2026-07-28 agenda roughly doubled in
length while it ran, and every added word was a phase reporting back.

**Every one of its eight phases falsified something it said.** Not a detail — a thesis, a risk rating,
a ground-truth premise. Two of the ten workstream theses argued from mechanics `puct` does not have.
Plan for this: an agenda's job is to be *specific enough to be wrong*, and the falsification is usually
worth more than the Elo.

Each phase writes back three things, in this order of importance:

1. **What it falsified.** The premise, the estimate, the risk line — with the counter-example. This is
   the part a later session cannot re-derive cheaply and the part that stops it repeating the phase.
2. **What it measured**, with the interval and the board count. A number without an interval is an
   anecdote; a number without the board count cannot be compared with the next one.
3. **What it shipped**, and whether it is on by default. Usually it is not — see
   [What never happens in a phase](#what-never-happens-in-a-phase).

The coordinator owns the **Status** table and writes the "What P*n* actually found" sections. Agents
report; they never edit the agenda. Two agents writing one document produces a document that is nobody's
account of anything.

**Closing an agenda.** When the last phase lands, add an **Open at the close** section: the decisions
left to a person, the measurements nobody ran, the follow-ups deliberately dropped. Then stop editing
it. A new agenda gets a new dated file and cites the old one — it does not reopen it.

---

## Writing one — brainstorming

### Ground truth first, and from the code

The 2026-07-28 agenda opens with six facts established by reading `:core` and `:bot-api` rather than the
docs' description of them, and the header claim — *"getting any of these wrong wastes a session"* — was
literally true for at least three of them. Two examples of what this buys:

- `BoardView.hash` keys on `(cell, owner)` with no tail and no body ordering, so two different
  threadings of the same occupied set hash **identically**. That is a structural collision, not a
  probabilistic one. A transposition table built on it would have been subtly wrong and passed its tests.
- `Scratch.playout()` *resets the arena*, so a depth-first search cannot pay per leaf and keep its
  descent. Discovering that during P6 rather than before it would have cost the phase.

And a caution the same section earns: **ground truth is a hypothesis too.** Finding 1's parity premise
went on to lose twice — once by argument (P1, −185 Elo) and once by search (P5a's ablation, worth
nothing). Finding 5's conservative separation predicate turned out **vacuous at two snakes**. Write the
ground truth from the code, then let a phase falsify it like anything else.

### A workstream is a falsifiable thesis, not a good idea

Each row carries **thesis / reuses / size / risk**, and the thesis is the load-bearing field: it says
what would have to be true for the work to pay. Check it against the code *before ranking it* —

> Workstream #1 argued from *"`puct`'s rollouts hit forced lines constantly"*. **`puct` has no
> rollouts.** Workstream #3's row was wrong in both halves: it blamed RAVE's move-independence
> assumption where the real obstacle is visit ordering, and it asked for progressive bias, which PUCT
> already *is*.

Both survived into the agenda because the thesis was written from the shape of the algorithm rather
than from this implementation of it. A thesis that names a mechanism should name the file.

**Risk is about the measurement, not the code.** #1's risk read *"Low. Standard, strictly more
information"*, and the phase falsified exactly that: more information is not monotone in strength. The
useful risk question is *"if this is a null, will I be able to tell?"*

### Where the next candidates come from

In rough order of how well this worked:

- **A diagnostic that bounds something before it is built.** P8's phase map put a phase-dispatch
  portfolio at **~25 Elo** — the only number anyone has on workstream #10 — for the cost of an analysis
  over matches that already existed. Bounding a workstream is as valuable as running one and costs a
  fraction.
- **A fitted model's residual.** `eval=learned`'s train loss equals its holdout loss to five places, so
  it is bounded by its **features**. That single number converts "make the net better" — unfalsifiable
  and expensive — into four named features to try.
- **A cost result re-opening a settled verdict.** P3's bitboards moved every per-millisecond
  comparison in the repo. When something gets 2× cheaper, go back and re-read what was rejected for
  being too dear.
- **The thing the last agenda deliberately dropped.** Carry it in *Open at the close* with enough
  detail to restart, including why it was dropped.

Least well: an idea from the literature, unchecked against this game's rules. Snakes grow at half speed
here, which is what made a canonical Tron parity bound worthless as an evaluation.

### Rank by expected gain, then reorder for reality

Rank by expected Elo per session. Then reorder for two things the ranking cannot see:

- **What makes other phases cheaper.** P3 (bitboards) was ranked third and should arguably have been
  first: it made everything after it affordable and re-priced everything before it.
- **What makes other phases affordable to *measure*.** P4 (SPSA) is pure `:lab` infrastructure with
  zero bot risk, and without it the multi-weight phases after it were not tunable at all.
- **File collisions.** Two phases that both register something on `PuctBot` collide at the registration
  site however different their subject matter is.

**Size it at six to ten workstreams.** Ten filled eight phases and produced roughly one durable finding
per phase. Fewer and the agenda is a task list; more and the later rows are stale before they are read,
because the earlier ones changed the premises.

---

## Running one — sessions and sub-agents

### Phases run sequentially

Even where the agenda says two could be concurrent. Two reasons, and the first is not obvious:

1. **Every phase reports per-turn cost beside its win rate (SW-07), and `:lab`'s arena plays in
   parallel.** A second Gradle build or batch running at the same time corrupts every millisecond in
   the report. Machine drift alone once put a *control* at 0.82× — a 50% error read as signal.
2. **"Different files" usually is not true at the registration site.** P1 and P2 touched entirely
   different subjects and both had to edit `PuctBot.kt`'s knob list.

### Split the agent, not the phase

Within a phase, dispatch **implementation** and **measurement** as separate agents when the batches are
long. Neither agent's context then has to hold both the code and several hours of output, which is the
whole reason the coordination pattern exists.

The split has a second benefit worth engineering for: the implementation agent writes a **handover**,
and a handover written by somebody who has just read the code predicts things the measurement agent
would otherwise discover by wasting a batch. P1's said `horizon` and `survival` would play byte-identical
streams on ~2 boards in 5 because a flat factor of two cancels in a share — and it therefore chose the
instrument for the phase.

Dispatch more than two only when the phase genuinely has independent halves (P5 had a prior and an
eval). A third agent that needs the first two's context is a false economy.

### The coordinator's job

- Hold the **ledger** (`docs/research/wip/<agenda-date>-ledger.md`) and the agenda's Status table.
- Write each agent's brief, including the house rules block below **in every one of them**.
- Read what comes back sceptically. Agents report confidently and are sometimes wrong; the D6 case is
  the pattern — a table the whole phase was read against turned out to carry ±8 points of noise on the
  effect it recorded, and only re-deriving it at 1,000 rounds settled it.
- Write findings into the agenda. Do this **as each phase lands**, not at the end: an agenda written up
  at the end is written from a summary of a summary.

### The brief every agent gets

Paste this block verbatim into each sub-agent prompt. Each line is here because something went wrong
without it.

```
- `git add` a new source file the moment you create it. Never commit. Never push.
- A golden failure is a question, never a hash to update. Name what changed first.
- Forfeits are a defect, never a result. Fix before believing anything else in the run.
- Read the distinct-games line before anything else a batch prints.
- Report a firing rate / coverage rate before any strength claim.
- Long batches: run in the background and redirect to a file. Do not pipe — a shell reaped
  at ~60 minutes loses its stdout while the Gradle daemon runs on.
- Cost is measured by paired `time` runs with an unaffected control, never off a field's
  µs/turn column.
- Do not edit the agenda's Status table. Report; the coordinator writes.
- Never background `wasmJsBrowserDevelopmentRun`. Port 8099, killed when finished, is the
  agent's static server — see CLAUDE.md.
```

---

## Running an experiment — the order operations go in

### 0. Probe whether the mechanism can fire at all

**Before any strength claim, measure how often the new thing does anything.** This is the cheapest
falsification available and it has bounded two whole phases in advance:

| Phase | The probe | What it meant |
|---|---|---|
| P2 | the solver fires on **0.19%** of iterations | whatever the field says, it is saying it about 0.19% of the search |
| P8 | **0.0%** of *unvisited* children ever carry AMAF | RAVE's entire mechanism cannot fire in a rollout-free search — a structural null, no sweep worth running |

A firing rate near zero ends the phase honestly in an hour. A firing rate that is healthy tells you a
null result is about the *idea* and not about the wiring.

### 1. One variable, and make the neutral setting reproduce the incumbent

The cleanest setup of the whole run, and it is worth copying exactly: `ChamberEval` at
`parityWeight=1, frontierPenalty=0, sealPenalty=0` is **bit-identical to `SurvivalEval`**. So the new
code was proven to be a strict superset before a single match was played, and every subsequent
measurement moved exactly one thing.

If the new thing cannot be made to reproduce the old thing, say so in the brief — the phase is then
measuring two changes at once and every result inherits that.

### 2. Cost, paired, with a control

```bash
./gradlew :lab:run --args="time <entrant> --budget 1000"
./gradlew :lab:run --args="time <entrant> --budget 1000 --rows 20 --cols 20"
```

Both boards: the ratio moves with the board. And for a *change* rather than an entrant, use P3's
protocol — **rebuild the baseline commit in a worktree, time the same seed back-to-back across the two
builds, and carry an entrant the change cannot touch as a control.** Its first unpaired block put that
control at 0.82×. Never compare timing blocks; only paired ratios with an unaffected control survive.

**A field's `µs/turn` column is not a cost measurement.** It ordered seven entrants almost exactly *by
rating* in one phase (stronger bot → longer game → fuller board → dearer leaf) and inversely in
another. Unreliable in both directions.

### 3. Head-to-head, knowing what it measures

```bash
./gradlew :lab:run --args="ab <baseline> <candidate> --elo0 0 --elo1 10 --log .lab/<experiment>-ab"
```

**A head-to-head between two settings of the same bot measures a style match-up. Only a common field
converts that into strength.** This is the single most expensive lesson of the run and it superseded
two earlier attempts at the same rule:

| Attempt | Case | Outcome |
|---|---|---|
| "prefer a field" | ChaseBot's `roomShare` | right: 1 Elo ±3 under `ab`, +14 rated |
| "prefer `ab`, fields wash out" (P2) | solver | right *there* — both fields sat saturated at 0.97-1.00, so nothing was contested |
| **the durable form** (P5b) | prior weights | pairings *were* contested and `ab` was **still** wrong: `priorTail=0.8` beat baseline by +250, `0.4` beat `0.8` by +66, and `0.4` **lost** to baseline by −35 |

So: `ab` decides, and then a field checks that what it decided is strength. `rate` printed the tell
itself — `priorTail=0.8` scored 84% off the baseline where its own rating expected 60%.

Two corollaries. **Divergence predicts visibility, never sign**: 6.6% per-decision divergence on a
20×20 against 1.4% on a 12×12 correctly found where more decisions changed and got the sign exactly
backwards. And **`spsa`'s confirming run *is* an `ab`** and inherits the whole blind spot.

### 4. A field, and rate everything in one fit

```bash
./gradlew :lab:run --args="play <candidate> <baseline> puct uct chase pressure space --rounds 200 --log .lab/<experiment>"
./gradlew :lab:run --args="rate --log .lab/<experiment>"
```

**Ablate — but ablate in a field.** A per-coordinate ablation run as a series of `ab`s is exactly the
"ordering built out of one row" the protocol forbids; the instrument is *one* field containing every
ablation, rated together.

**And ablate at all.** P5a's confirming run passed at +54 Elo while one of its three tuned coordinates
was pure drift. A confirming run is necessary and, above one dimension, **not sufficient**: read a
confirmed multi-weight point as *"this point is better"*, never as *"each of these weights is right"*.

**Intransitivity is real here and is not noise.** `alphabeta:eval=chamber` beats `puct:eval=chamber`
head-to-head 108-92 while rating 62 *below* it in a broad field and 48 *above* it in a narrower one —
disjoint intervals both times, with the pairing itself stable. The company moved, not the match-up.
(Both entrants are spelled at `eval=chamber` because that is what was measured; `alphabeta` has since
defaulted to `territory`, so a bare `alphabeta` today is a different bot from the one in this
measurement.) **A single Elo fitted over a cycle is field-composition-dependent**, so state the field
beside any rating that will be quoted later.

**And it is not only the company — it is the board.** `alphabeta:eval=territory` rates +131 above bare
`puct` on an 8×8 while losing its head-to-head to it 89-111, and beats it 70.5% on a 12×12. A rating
is conditioned on the field *and* on the geometry, and cost is not monotone in board size either, so
an allowance measured on one board does not transfer to another.

### 5. Diagnose, whichever way it went

```bash
./gradlew :lab:run --args="report <candidate> --against <baseline> --worst 5 --log .lab/<experiment>"
./gradlew :lab:run --args="phases <candidate> --against <baseline> --log .lab/<experiment>"
```

A negative that is *understood* is a finding; a negative that is merely observed is a phase somebody
will repeat. P1's is the model: `horizon` is oracle-verified and truer than what it replaced, and it
lost 185 Elo, because **a leaf is read as a comparison and never as a quantity** — a doubling exact in
an open room is generous wherever the walk cannot really loop. That sentence is now in
`HorizonEval`'s KDoc, where the next person meets it.

**Predicted signatures come out inverted often enough to be worth checking.** P1 predicted `horizon`
would die later in fuller boards; it died *earlier* (median 85 moves against 96) in *more open* ones
(60% fill against 68%).

### Done means

- The build is green, both targets, `checkModulePurity` and ktlint included; the browser suite too if
  anything touched arithmetic.
- Goldens are **untouched**, or a moved hash is explained by a named change and re-verified in real
  Chrome.
- Cost is reported beside every win rate, from paired runs.
- The finding is in the agenda **and** beside the constant it set.
- New files are staged. Nothing is committed.

---

## What never happens in a phase

- **A default does not move.** Changing one moves `GoldenMoveStreamTest`'s hashes and `BotLadderTest`'s
  thresholds; it is a release decision, and it belongs in *Open at the close* as a decision for a
  person. Eight phases produced three such decisions and moved zero defaults, which is the correct
  ratio.
- **Nothing is committed or pushed.** Work is left staged and said to be staged. A green build is not
  permission.
- **A golden hash is not updated to make a test pass.** A golden failure is a question.
- **A shipped identifier is not renamed.** SW-05: a `BotId`, knob name or `Choice` value sits in the
  replay URL of every match anybody shared. This also sets the bar for *adding* one — `alphabeta`
  declined to offer `eval=horizon` because a value frozen forever should be one somebody would want to
  play, and `horizon` rates 185 Elo behind `survival`.
- **A negative is not deleted.** Register it, leave it off by default, and write why it lost. `horizon`,
  `solver` and `rave` all ship and all lost; the reasons are the agenda's most re-read paragraphs.
- **A forfeit is never reported as a result.**

---

## Reading a knob result

Collected because each cost a batch to learn, and `tune`/`spsa`'s own documentation in
[`Workflow.md`](Workflow.md#tuning-a-knob) explains the mechanics rather than the traps.

- **Both search shapes manufacture a confident wrong point on a flat knob.** `tune` accepted
  `cpuct=2.3` at +112 Elo and confirmed at **−19** over 800 fresh boards; `spsa` walked the same knob to
  its floor and confirmed at **−21**. Only fresh boards catch it, which is why the confirming run has no
  flag to skip it.
- **A knob tuned at one allowance is tuned at that allowance.** +73 Elo at budget 400, −19 at the
  shipped 1000.
- **"Nothing beat the default" is a result**, and writing it down costs twenty minutes once instead of
  twenty minutes each time.
- **A statistical test for "was there a gradient" loses power as a search converges** — past arrival it
  is all noise and no signal. The structural question replaces it: did the recommendation land on a
  declared bound?
- **A weight that never moved has no verdict**, and should be reported as having none rather than as a
  confirmed default.

---

## Where things are written down

| The finding is about | It goes |
|---|---|
| a number that set a constant | in the KDoc **beside that constant** — `UctBot.ROLLOUT_DEPTH`, `PuctBot.CPUCT`, `TerritoryEval` |
| what a phase tried and what it falsified | the agenda's "What P*n* actually found" |
| an instrument, or how to read one | [`Workflow.md`](Workflow.md) |
| a rule a review will cite | [`Coding-Standards.md`](Coding-Standards.md), with an id |
| how a bot behaves, and its knobs | [`Bots.md`](Bots.md) |
| how to run a research effort | here |

A measured number that lives only in a chat transcript did not happen. A measured number that lives
only in `.lab/` is regenerable and therefore also did not happen — `.lab/` is gitignored on purpose.
