# Coding standards

Review every change against these rules before finalizing. They capture the failure modes this
codebase actually has — several of which break a match *silently* — plus the general ones that recur
in AI-generated code.

Every rule has an id, so a review or a commit can cite one ("violates SW-01").

- **`SW-NN`** are snakewarz's own. Get one wrong and the code still compiles, still passes most of the
  suite, and then reproduces differently in the browser than it did on the JVM.
- **`CC-NN`** are general code-craft rules — the ones that recur in AI-generated code and would apply
  to any Kotlin codebase of this shape. Ids are permanent and never reused, so a citation stays valid
  for as long as the rule does; the numbering has gaps where one was retired.

The architecture these rules serve is in [`../CLAUDE.md`](../CLAUDE.md), which holds the module graph
and the forbidden edges, gives the reasoning behind both, and routes to the per-module detail in this
directory. Where a rule is enforced by a test or a Gradle task rather than by review, that is named
in the rule.

| Id | Rule |
|---|---|
| [SW-01](#sw-01--determinism-is-a-hard-invariant) | Determinism is a hard invariant |
| [SW-02](#sw-02--portable-arithmetic-only-in-bots) | Portable arithmetic only, in `:bots` |
| [SW-03](#sw-03--the-hot-path-does-not-allocate) | The hot path does not allocate |
| [SW-04](#sw-04--module-purity-is-not-negotiable) | Module purity is not negotiable |
| [SW-05](#sw-05--released-identifiers-are-frozen) | Released identifiers are frozen |
| [SW-06](#sw-06--names) | Names |
| [SW-07](#sw-07--a-search-pays-for-its-own-work) | A search pays for its own work |
| [SW-08](#sw-08--the-bundle-is-a-budget) | The bundle is a budget |
| [SW-09](#sw-09--a-bound-that-protects-an-allocation-runs-before-the-allocation) | A bound that protects an allocation runs before the allocation |
| [CC-01](#cc-01--magic-constant) | Magic constant |
| [CC-02](#cc-02--comments) | Comments |
| [CC-03](#cc-03--scalability-of-code) | Scalability of code |
| [CC-04](#cc-04--coherence-of-related-concepts) | Coherence of related concepts |
| [CC-05](#cc-05--single-purpose-code-paths) | Single-purpose code paths |
| [CC-06](#cc-06--a-package-is-a-handful-of-files) | A package is a handful of files |
| [CC-07](#cc-07--drive-by-refactoring-and-cleanup) | Drive-by refactoring and cleanup |
| [CC-08](#cc-08--fail-fast-on-unexpected-code-paths) | Fail-fast on unexpected code paths |
| [CC-09](#cc-09--stub-markers-encode-intent) | Stub markers encode intent |
| [CC-10](#cc-10--justify-every-line) | Justify every line |
| [CC-11](#cc-11--kotlin-style) | Kotlin style |
| [CC-12](#cc-12--same-changeset-sibling-duplicates) | Same-changeset sibling duplicates |
| [CC-13](#cc-13--test-colocation) | Test colocation |
| [CC-15](#cc-15--one-public-declaration-per-file) | One public declaration per file |
| [CC-16](#cc-16--never-write-ide-configuration-files) | Never write IDE configuration files |
| [CC-17](#cc-17--generic-code-dispatches-by-capability-never-by-bot-identity) | Generic code dispatches by capability, never by bot identity |
| [CC-18](#cc-18--user-facing-text-speaks-the-players-language) | User-facing text speaks the player's language |


# Project rules


## SW-01 — Determinism is a hard invariant

**A match must reproduce exactly — same seed, same moves, same result, on both targets, for as long
as the replay URLs people have shared keep working.**

- **No global RNG.** Randomness is injected per slot and forked from the match seed
  (`matchRng.fork(slotIndex)`), so one bot's consumption never shifts another's stream. Get it from
  `setup.rng`, never `Random.Default`.
- **`SplitMix64`, never `kotlin.random.Random`.** A persisted URL replay format must not depend on
  stdlib algorithm stability across Kotlin versions and targets.
- **No `HashMap`/`HashSet` *iteration* in `:core` or `:bots`.** Use `LinkedHashMap` or sorted arrays.
  The legacy code iterated a `HashMap` and was only *accidentally* stable, because
  `PlayerAvatar.hashCode()` happened to return a monotonic index.
- **No wall clock in `:core`, `:bot-api`, `:bots` or `:match`.** Budgets are counted in evaluations,
  never milliseconds. Time lives in `:ui` and `:lab` only — and `:lab` sits outside all four rather
  than inside one of them, because a tool that reports how long a batch took has to read a clock and
  a module a match runs through must not be able to.
- **A golden failure is a question, never a hash to update.** `GoldenMoveStreamTest` pins a move
  stream, re-run in real Chrome. If it moves, something changed the arithmetic or the search order,
  and which one has to be named before the hash is touched — "update the golden to make CI green" is
  the exact way this rule and [SW-02](#sw-02--portable-arithmetic-only-in-bots) get defeated without
  anybody noticing.

**Why:** A replay is a URL somebody bookmarked or posted, and CI re-runs recorded matches against the
registry. Every one of these is a way for the same seed to produce a different game later — on a new
Kotlin version, on the other target, or just because a second bot in the match consumed one extra
random number.


## SW-02 — Portable arithmetic only, in `:bots`

**Nothing in `:bots`' main sources may call `kotlin.math.ln`, `exp` or `pow`.**

A test may, and exactly two do: `PortableLogTest` and `PortableExpTest` take `kotlin.math.ln` and
`kotlin.math.exp` as the oracles their portable counterparts are measured against, which is the only
way to check either at all — and is what would notice somebody replacing a series with the stdlib
call for tidiness. The source-set qualifier is what makes the rest of the rule a `grep` rather than a
thing a reviewer has to remember; without it the check fails on day one against the tests that prove
the rule is being kept.

`+ - * / sqrt` are specified bit-identical by IEEE-754. `log` and `exp` are not, so the same
expression can land on a different move in Chrome than it did on the JVM. UCB1 is
`sqrt(log(v) / (5 * cv))`, so `UctBot` takes its logarithm from `portableLog`, which is built from
`+ - * /` alone; the UCT golden hash then reproduces bit-for-bit in the browser.

`PuctBot` needs no logarithm at all — PUCT is `Q + c·P·sqrt(N)/(1+n)` — so its *selection* costs
nothing to keep here. Its **prior** is where the rule bites: a softmax over the move features wants
`exp`, and the answer was to build one rather than to except the bot. `portableExp` reduces the
argument against a two-part `ln 2` and sums a Taylor series, all `+ - * /`, and `MovePrior` calls it
at most three times per expansion. Both portable series are pinned by a raw-bits test that runs in
Chrome as well as on the JVM, which is what turns "these operations are specified" into evidence.

A series pinned to the bit is not the same as an evaluation built out of one, so `puct` at
`eval=learned` is in the cross-target set too — four hundred multiply-adds off a baked literal and a
logistic, which is the only place `portableExp` is reached inside a composed appraisal rather than on
its own.

**The bar for a new transcendental is that test, not an argument.** A branching factor of three is
what makes a bounded series affordable here; a bot that genuinely cannot afford one belongs outside
`GoldenMoveStreamTest`'s cross-target set, saying so in its KDoc.

**Why:** The failure this buys is a golden hash that passes on the JVM and fails in the browser,
which reads as a compiler bug and is not one. Whoever hits it will spend a day on the toolchain
before suspecting a call to `ln`.


## SW-03 — The hot path does not allocate

The engine is called millions of times per turn from inside MCTS. In `:core` and `:bots`:

- Primitive arrays (`IntArray`, `ByteArray`, `LongArray`) over `List<Int>`.
- A `value class` over `Int` unboxes in most positions but **boxes as a generic type argument or when
  nullable** — so `List<Cell>` allocates per element. No hot-path API returns a collection of `Cell`.
- No `Sequence`. No `data class` for hot-path types: the generated `equals`/`hashCode`/`copy` add
  code size (see [SW-08](#sw-08--the-bundle-is-a-budget)) and invite allocation.
- Prefer mutate-and-undo over allocating a new state. The engine's canonical representation is a
  mutable arena with an undo journal; immutable `MatchState` snapshots are derived at most once per
  turn.
- Search buffers are instance fields, allocated once in the bot's constructor from
  `setup.grid.cellCount` and reused forever. A bot instance is created once per slot per match, which
  is exactly what makes that safe.

**And a JVM figure cannot be trusted for its sign.** Folding `SpaceOwnership`'s per-layer word passes
into one measured **1.10–1.17×** on a `puct` turn on the JVM — four paired rounds, control held at
0.95–0.99× — and **0.50–0.91× in Chrome** over two, the gap growing with board size. That is a
disagreement in *direction*, not in degree. `:bots` deploys to wasm and nowhere else, so a hot-path
rewrite is settled by `./gradlew :bots:wasmJsBrowserTest -PbrowserTests=true` and the JVM run is the
smoke test that precedes it. `CellBits` carries the case and the numbers.

**Why:** This is the one place in the project where micro-performance is the product: a bot's strength
is how many rollouts fit in its allowance. An allocation per rollout step is a measurable loss of
playing strength, not a style preference. `:bots`' `ThroughputTest` prints `[bench]` lines on either
target and is how a suspected regression gets settled.


## SW-04 — Module purity is not negotiable

**The forbidden dependency edges in [`../CLAUDE.md`](../CLAUDE.md#forbidden-dependency-edges) are the
load-bearing constraint of the architecture. Do not add one, even temporarily, and do not add one to
make a test compile.**

`checkModulePurity` is wired into `check` for all three convention plugins and walks every
`*CompileClasspath`, test source sets included — so an edge added to a test is a build failure, not a
quiet exception.

Every module has `explicitApi()`. Anything outside a module's contract is `internal`; `:ui` exposes
exactly two things, and `:bots`' search primitives are `internal` on purpose so they can be changed.

**Why:** Adding a dependency is the cheapest-looking way to solve almost any problem and the one that
cannot be undone later. `:match` never having seen a bot class is what keeps the replay codec free of
bot classes; `:core` never having seen `:bot-api` is what keeps the engine ignorant of bots. If a
test seems to need an edge, the test is in the wrong module.


## SW-05 — Released identifiers are frozen

**A `BotId` slug, a `BotKnob` name, a `BotKnob.Choice` value and `PlayableRegistry.HUMAN_ID` are all
part of the replay format. Once released, none of them may be renamed.**

They travel in the URL hash of every match anyone has shared, and nothing in the codec can repair a
name that changed meaning. This is also why a `Choice` holds **names, never ordinals**:
`eval=territory` survives somebody reordering the list the sidebar offers, and `eval=2` does not.

Display names are not identifiers and may be changed freely. Renaming is a *new* id plus whatever
migration you are willing to write — usually not worth it.

`puct`'s `eval=expert` is the one value that has ever been renamed, and it is the exception that
shows the shape of the rule rather than a precedent: `Choice.read` is total, so the old value falls
through to the *default*, and the rename was only defensible because the default is the same
evaluation under its new name. `PuctBot.EVAL` carries the argument and the condition it depends on.
That escape hatch exists for exactly one value at a time and stops working the moment the default
moves.

**Why:** The failure is invisible at the point of the change and total at the point of use: an old
link resolves to a bot that no longer exists, or worse, to a knob whose value now means something
else, and the match plays back wrong rather than failing.


## SW-06 — Names

- Root package **`ao.snakewarz`**; sub-packages mirror module names.
- **No version suffixes, ever.** No `v2` package, no `SnakesGame2`. Name for behaviour: `UctBot` vs
  `FlatMonteCarloBot`.
- **No `Foo`/`FooImpl`.** Either the class is concrete (`Board`, `MatchState`), or the interface names
  the role and each implementation names its mechanism (`Bot`/`UctBot`, `Rng`/`SplitMix64`).
- **No `Util`/`Helper` objects.** Use methods, extensions, or a file-level function named for what it
  does (`nearestOpponent`, `randomPlayout`).
- Value classes for ids: `Cell`, `SnakeId`, `BotId`, `DirectionSet`.
- `rows`/`cols`, `row`/`col`. Not `numRows`, not `getRowCount`.
- **Fix legacy misspellings on sight; never carry one forward.** `obsticles` → `obstacles`;
  `untill`, `seriese`, `demilited` → corrected; `utcSearch` → `uctSearch` (the legacy code
  consistently swaps UCT and UTC, including in its class docs).

**Why:** The names are the API a bot author reads, and a version suffix or an `Impl` is a decision
deferred rather than made. `rows`/`cols` in particular is load-bearing at the call site: the engine
is dense with index arithmetic where a transposed pair is a bug you find visually or not at all.


## SW-07 — A search pays for its own work

**`turn.budget` is an allowance counted in evaluations, which means it has to be a bound rather than
a record.**

One unit buys one judgement of a position — a rollout, a static appraisal, one iteration of a tree
search — and `Scratch.playout(cost)` is what charges it. Charging on the *evaluation* rather than on
the simulated move is what lets one number mean the same amount of search to a bot that plays a
hundred moves out and a bot that sweeps the board once. It also charges **before** the work rather
than after: a refused playout comes back reporting an outcome, so a rollout loop's first line is the
budget check and the search terminates structurally, and an evaluation that has begun always
finishes rather than being cut off half way through a line nobody can credit.

Pass your own `EvaluationCost` entry as the cost. Do not tune it down to make a bot look better in a
matrix; report the wall clock beside the win rate instead — that is what `:lab`'s `time` subcommand
exists for, and every entry is `1` today, so a matrix at "equal allowance" is equal *iterations* and
says nothing about milliseconds.

Enforced by `BotContractTest`: a bot spends budget **if and only if** it declares a `BotKnob.Search`,
and a bot handed an allowance of zero must spend exactly zero and still play well.

**Why:** Without it, `budgetPerTurn` quietly means something different for every bot that declares
one, and the win-rate matrix — the entire point of the testbed — compares two bots that were not
given the same thing. That is not hypothetical: under the previous per-move accounting `puct` at its
hand-written appraisal was charged the board area for a leaf a rollout got for its length, and read
as the weaker bot on a matrix that was measuring the charge rather than the bot.


## SW-08 — The bundle is a budget

**CI fails the build when the gzipped production distribution exceeds 1.5 MiB.** It is a build
failure rather than a review question because Kotlin/Wasm bundle size is a standing risk that grows
one convenience at a time.

That budget is the reason behind several rules that otherwise look like taste: no `data class` on
hot-path types, no reflection, no dependency without a reason, and no library pulled in for something
the stdlib already does. Measure before adding anything large:

```bash
./gradlew :app:wasmJsBrowserDistribution   # then check the CI step's arithmetic locally if in doubt
```

**Why:** This ships as static files to GitHub Pages and the first thing a visitor does is download all
of it. Source maps are excluded from the measurement because browsers only fetch them with devtools
open; everything else is on the critical path to the first frame.


## SW-09 — A bound that protects an allocation runs before the allocation

**A `require` that exists to keep an allocation sane must run *before* that allocation, not beside
it.** In Kotlin that means it cannot live in an `init` block whose class allocates in a property
initializer, because initializers and `init` blocks run in declaration order and the properties come
first. Put the check in an `init` block above them, or in the type that owns the number.

Every field the replay codec decodes carries such a bound already, and each says why in the same
words — *"Bounded so a decoder can reject a corrupt payload before allocating from it"*
(`BotId.MAX_LENGTH`, `BotKnob.MAX_NAME_LENGTH`, `MAX_VALUE_LENGTH`, `MAX_PER_BOT`,
`MatchSetup.MAX_SIDE`). A `#r=` link is the one input to this program that arrives from a stranger,
and the geometry is the field in it that allocates most: `Board` asks for a byte per padded square and
an `Int` per playable square *per slot*.

Two things make the ordering matter rather than merely tidy:

- The exception type changes. An `OutOfMemoryError` is not an `IllegalArgumentException`, so
  `:app`'s "a bad link is a fresh match" fallback does not catch it and the boot watchdog blames the
  reader's browser instead — a confident, wrong diagnosis.
- Integer arithmetic wraps. A size computed into a negative `Int` passes every ceiling downstream and
  fails at the array instead, which is why `Grid` checks its padded extent in `Long` arithmetic.

The parallel with [SW-07](#sw-07--a-search-pays-for-its-own-work) is exact, and is why this is a rule
rather than a fix: charging after the work makes an allowance a record of what already happened, and
checking after the allocation makes a bound a record of what was already asked for.

**Why:** A limit that runs late is not a limit. Everything it was written to prevent has happened by
the time it speaks, and all it changes is which error message the reader gets blamed by.


# Shared rules


## CC-01 — Magic constant

**Don't inline unexplained literals. Bind them to a named `val` that spells out the unit and intent.**

Don't:
```kotlin
if (frameElapsed > 8) return
```

Do:
```kotlin
private const val FRAME_BUDGET_MS = 8
// ...
if (frameElapsed > FRAME_BUDGET_MS) return
```

**A bot's tunables are not constants at all.** Anything worth tuning is declared as a `BotKnob` and
passed to `register`, so the default on the sidebar form and the default in the field initializer are
the same literal and cannot drift. See *Declaring a knob* in [`Bots.md`](Bots.md#declaring-a-knob).

**Why:** A bare `8` forces the reader to guess units (ms? turns? rollouts?) and intent (budget?
threshold? index?). A named binding makes both obvious at the call site — and a knob makes the value
answerable without a rebuild.


## CC-02 — Comments

**Comments carry the *why*. Never the *what*, never the diff, never the conversation.**

This codebase is deliberately comment-*rich* about reasons: `LeafEval`'s KDoc explains why an
evaluation returns one value per slot rather than a scalar, and `TerritoryEval` records that a graded
margin beat a step because a step left the search with no gradient in exactly the phase where this
game is a space-filling puzzle. Neither could be recovered by reading the code. That is the bar — not
volume, but a reason a reader could not have derived.

Don't restate what the next line does:
```kotlin
// Increment the counter
counter += 1
```

Don't reference the current task, PR, or requester — those notes belong in the commit message and rot
in the source tree:
```kotlin
// Added for the tournament ticket — handles the new free-for-all case
```

Do explain a non-obvious why:
```kotlin
// Legality is tested before the tail retracts: a snake may not enter the square its own tail is
// about to leave. Clearing first is a different game, one where a snake chases its own tail forever.
```

**Writing style — the audience is a first-time reader, not the person who requested the change.**
Write every comment for a developer (or AI) reading the file cold, who sees only today's tree — no
diff, no conversation, no memory of what the code used to look like. Concretely:

- **Never narrate the change.** "now", "no longer", "previously", "moved here from X", "replaces the
  former X" describe the *diff*, not the code. The first-time reader has no "before" to compare
  against, so these words carry zero information — and they rot into confusion once the referenced
  history scrolls away. State the present-tense fact or constraint; the history belongs in the commit
  message.
- **Never echo the prompt or the review conversation.** Quoting ad-hoc wording from the request, or
  justifying the change to the person who asked for it ("this avoids the problem you mentioned"), is
  talking to the wrong audience. If the justification encodes a real constraint, restate it as that
  constraint.
- **Never compare against something that was just deleted.** A comparison is only useful when the
  referent is *live*. In this repo the legacy Java **is** live in that sense — it is at the
  `legacy-java-final` tag and [`Legacy.md`](Legacy.md) says how to read it — so "the legacy rule tested the
  destination against a board built before the retraction" is a legitimate comparison, while
  "unlike the helper I removed above" is not.
- **Less is more.** Padding buries the load-bearing detail and taxes every future reader. Say the
  constraint in the fewest words that still carry it. Within reason: not so terse it turns cryptic or
  drops a needed detail — but when a word can go, it goes.

Comparisons that ARE legitimate, because the referent exists outside the diff:
```kotlin
// The legacy AStar ordered its frontier by cost-so-far, so it was breadth-first.  <- readable at the tag
// UCB1 needs a logarithm; ln is not bit-identical across targets.                 <- external spec
// Derived per turn rather than counted as the match runs — the board already knows. <- design alternative
```

**The rewrite test:** would this comment still make sense to someone who checks out only today's
commit, with no access to the diff or the conversation that produced it? If not, either restate it as
a present-tense constraint or delete it.

**Cite a document by filename, never by quoted sentence.** `docs/Bots.md` is a referent that survives
the document being edited; a sentence quoted out of it is one nobody can grep for once the wording
changes, and the citation then rots with no build failure to announce it. This is not hypothetical:
two places in this repo spent a while attributing a rule about golden hashes to a sentence CLAUDE.md
did not contain, one of them a KDoc on `PuctTree`.

**Why:** Well-named identifiers say *what* the code does. A comment adds value only when it captures a
hidden constraint, a measured result, a non-trivial invariant, or behaviour that would surprise a
reader. A comment addressed to the prompter is noise the moment the change merges: its referents (the
request, the deleted code, the "before" state) are gone, so it costs every future reader a lookup
that can never succeed.


## CC-03 — Scalability of code

**Each new feature or extensibility point should cost one line at the same depth — not a new wrapper
layer.**

The canonical instance in this repo is adding a bot: one new file, plus one `register(...)` line in
`ShippedBots`. No HTML change, no `:ui` change, no codec work, no test file. That is not an accident —
the picker `<option>`s and each seat's knob rows are built from `BotRegistry.entries` precisely so
that the per-bot cost stays at one line.

Don't introduce a new wrapper per concern:
```kotlin
scoreboardWithLegend(
    scoreboardWithHover(
        scoreboard(rows)
    )
)
```

Use a single composition point instead, so the third concern is one more line at the same depth as
the second.

**Why:** The amount, nesting and complexity of code should be linear in the amount of functionality.
In the wrapper pattern, every new concern adds a layer that has to re-thread the outer slots through,
so indentation depth and signature surface grow per addition and the per-feature cost is more than
one line.


## CC-04 — Coherence of related concepts

**A feature should be removable in one delete — one file, or one directory tree.**

Code should either be general, or belong to a single particular feature — never both. A little
supporting code elsewhere is acceptable, but the removability test ("what would it take to delete
this?") is the target. `:lab` is the clean case: nothing depends on it, so deleting the module
deletes the feature. A bot is the everyday case: one file and one registration.

The shape to avoid is the inverse — a bot-specific branch in `Match`, a slug named in `:ui`, a
statistic that only one screen wants counted inside the driver.

**Why:** When general code paths carry feature-specific special cases, deleting the feature becomes a
cross-tree archaeology project, and the general code becomes the place where every feature's quirks
pile up.


## CC-05 — Single-purpose code paths

**Every piece of code does one thing. Never overload a generic code path with ad-hoc feature-specific
logic, even when it's locally convenient.**

**Red flag — generic name + feature-specific literal in the body.** If a function or type is named
generically (`Match`, `BoardRenderer`, `applyDecision`) and the body branches on a specific bot,
stop. Either rename the unit to the thing it actually serves, or move the branch out to the caller
that owns that concept.

The human seat is this rule done right: `Match` does not special-case a person. `InteractiveBot` and
`StallPolicy` live in `:match`, `PlayableRegistry` composes the human seat *outside* `ShippedBots`,
and what the driver branches on is `Match.interactive` — a capability — not an identity. Nothing in
`:ui` can tell a wall hugger from a human.

**Same shape — a generic predicate with a feature-specific clause stitched in.** Adding "one more
`||`" to a generic predicate is the same anti-pattern with friendlier syntax. Compose at the layer
that owns the concept: the feature side wraps the generic side, never the reverse.

**Extra red flag — upward import.** If the generic side has to import the feature side to evaluate its
own clause, the dependency direction is inverted too. In this repo that will usually be a
[SW-04](#sw-04--module-purity-is-not-negotiable) failure as well, and the build will say so.

When fixing one instance, re-read the whole function — overloads cluster, and missing the sibling
instance is the common failure mode.

**Why:** Mixing concerns creates surprise: tracing behaviour becomes "read every layer to see what got
injected where" instead of "read one function". The predicate variant adds a second hazard — an
upward import locks the generic layer to one feature, so the generic stops being reusable.


## CC-06 — A package is a handful of files

**A package holds a handful of files. Five is comfortable, ten is a smell, twenty means the split is
overdue. When a package outgrows itself, break it into sub-packages by responsibility, and give a
class cluster a package of its own.**

The module and the package answer different questions, and neither substitutes for the other:

- A **module** is an *enforcement* boundary. It is what turns a forbidden edge into a build failure,
  and it is what `internal` is scoped to. Add one when a responsibility needs a fence — the module
  table in [`../CLAUDE.md`](../CLAUDE.md#module-graph) states each module's one job, and code that
  fits none of those sentences is the signal.
- A **package** is a *navigation* boundary. It is free to add, enforced by nothing, and exists so a
  reader opening a module sees a few named groups instead of one wall of files.

So a module holding twenty-five files is not by itself wrong; twenty-five files in *one* package is.

**Split along what uses what, never by what kind of thing a file is.** A package named for a *kind* —
`eval`, `impls`, `helpers`, `types`, `utils` — reads as organised and is not: it collects files whose
only shared property is a suffix, and it puts each one a package away from the single thing that
calls it. The test is the call graph. `TerritoryEval` is read by `PuctBot` and nothing else, so it
lives beside `PuctBot` in `search.puct`, not in an `eval` package with `MobilityEval` — those two are
siblings in name only. A file with one consumer belongs in that consumer's package; a file with
consumers in several packages belongs in the nearest package enclosing them.

**A package is one kind of thing, and a tight cluster inside it gets nested out.** If some files in a
package are bound to each other and the rest are not, that asymmetry is invisible in a flat listing —
so make it structural. `reactive` lists bots; `ShortestPaths` and `nearestOpponent` are read by
`ChaseBot` and nothing else, so the three of them are `reactive.chase` rather than two helpers shelved
among six unrelated bots. The reader should be able to open a package, see that everything in it
belongs together, and descend only where the names say there is more.

The `internal` primitives stay `internal` and stay equally visible across the new sub-packages, and
that is fine: the split buys browseability and does not pretend to buy a boundary.

**Why:** Browseability and removability. A flat package of twenty-five files has no reading order and
no seam to delete along. The same files under four names say what the module is made of before you
open anything, and a whole concern can be lifted out by its directory.


## CC-07 — Drive-by refactoring and cleanup

**Refactorings are opt-in, not opt-out. Surface the find — don't act on it inline.**

Don't delete commented-out code, rename adjacent symbols, or "while I'm here" tidy in the same
change. Maybe the code was there for debugging and will be uncommented soon; maybe the symbol is used
by something you haven't read yet. Surface it as a discussion item or add it to the active plan.

**Exception:** temporary comments or scaffolding an AI agent added itself can be deleted freely as
part of the same work — "drive-by" means cleaning up code that *predates* the current change.

**Also note:** consolidating sibling helpers *you just wrote in this changeset* is not drive-by — see
[CC-12](#cc-12--same-changeset-sibling-duplicates).

**Why:** Adjacent cleanup expands the diff's scope, hides the actual fix from the reviewer, and makes
the change harder to revert if the primary fix turns out to be wrong.


## CC-08 — Fail-fast on unexpected code paths

**Make the code strict — fail early and loudly. Don't handle a null when null indicates a logic
error.**

Don't:
```kotlin
val slot = slots.getOrNull(index) ?: slots.first()
```

Do:
```kotlin
val slot = slots.getOrNull(index)
    ?: error("No slot at index $index of ${slots.size}")
```

`requireNotNull(x) { "..." }` and `check(condition) { "..." }` are the assertion-style siblings.

**Two carve-outs exist in the tree, and both are documented answers to a state that genuinely
occurs — not licence to soften a check:**

- **`BotKnob.read` is total on purpose.** An unparseable or out-of-range value falls back on the
  declared default rather than throwing, because one route in is whatever somebody pasted into the
  address bar, and `Match` builds its bots in a field initializer *outside* the `try` that guards
  `chooseMove` — a throw there has nothing above it to catch it and takes the page down. Strict
  reading lives in `BotKnob.reject`, which the form calls, and in `:lab`'s entrant parsing, which has
  a `main` to catch it.
- **A bot with nothing legal plays `?: Direction.NORTH`.** Every direction from a trapped position is
  the same death and the engine records `TRAPPED` whichever is played, so that is a doomed move
  rather than a silent fallback.

The test to apply: does this fallback hide a logic error, or is it the documented answer to a state
that really happens? Only the second is allowed, and it gets a comment saying which.

**Why:** A silent fallback turns a logic error into a quietly wrong result, which surfaces much later
and far from the cause. In a deterministic engine that is especially expensive: the wrong result is
reproducible, so it looks like a rule rather than a bug.


## CC-09 — Stub markers encode intent

**`TODO("...")` and `throw UnsupportedOperationException(...)` mean different things. Don't conflate
them.**

- `throw UnsupportedOperationException(...)` = **permanent contract.** This operation will never work
  for this implementation.
- `TODO("...")` / `NotImplementedError` = **real gap.** It could be implemented; it just isn't yet.

The tree currently contains neither, and that is the state to preserve: a shipped bot or module with
a `TODO` in it is unfinished work that passed review.

**Why:** Flattening the two loses the "unfinished, not deliberate" signal a future maintainer needs.


## CC-10 — Justify every line

**Every line must answer "why is this here?" with a concrete observable behaviour. Code that produces
no outcome different from the default is noise.**

The honest test: if I deleted this line, what would observably change? If the answer is "nothing a
player or a test would see" — it isn't pulling its weight.

Delete on sight unless tied to a specific named failure mode:
- Defensive null checks at trusted internal boundaries
- `try/catch` without a specific known thrown source
- "Future-proofing" abstractions with one caller
- Backwards-compat shims, re-exports, or `// removed X` placeholders for code you wrote in the same
  change

On every refactor, re-justify nearby code that referenced what just changed. When the load-bearing
piece is removed or rewritten, the scaffolding around it usually needs to go too — don't preserve it
out of inertia.

**One narrow exception: code kept as measured evidence.** `truncatedPlayout` and `SpaceOwnership`
ship wired and *off*, because they are the evidence behind `UctBot.ROLLOUT_DEPTH` and
`RolloutTruncationTest` re-runs the comparison. That qualifies only when all three are true: the KDoc
says it is evidence, a named test re-runs it, and the code is genuinely reachable from that test.
"Might be useful later" is not this exception.

Divergence from a peer pattern is a forcing function: if one bot or one handler does something the
others don't, either justify the divergence in the code or revert to the peer shape.

**Why:** Unjustified code costs review time, lies about what the system actually does, and survives
the refactors that should have killed it.


## CC-11 — Kotlin style

**Mechanical style is specified by `.editorconfig` and enforced by ktlint, which `check` runs — so
`./gradlew build` and CI fail on it.** 4-space indent, 120 columns, LF, final newline, no trailing
whitespace, no star imports, trailing commas permitted. `./gradlew ktlintFormat` fixes what is
mechanically fixable.

**Negation operator has no space.** Write `!foo`, `!turn.legalMoves.isEmpty`, `!interactive`. Never
`! foo`. This one is called out because it is the slip that reads as deliberate; it is
`standard:unary-op-spacing`, so the build catches it.

Five ktlint rules are switched off, each for a stated reason, in `.editorconfig` beside the
switch — three wrapping rules that fight IntelliJ's formatter, plus `filename` (a file named after
its single top-level *function* is camelCase, per [SW-06](#sw-06--names)) and
`blank-line-between-when-conditions` (which would penalise a commented `when` branch, and
[CC-02](#cc-02--comments) asks for those). Turning one back on is a decision to take deliberately,
with the reformat it implies.

**`build-logic` is linted on the same terms**, minus the `kotlin-dsl` accessors it generates into its
own source set. It applies ktlint directly rather than through a convention plugin — nothing can
apply a convention plugin to the build that compiles it — and the root `check` depends on that
included build's `ktlintCheck`, which is the only edge in the project that crosses a build boundary.
`ktlintFormat` does not cross it: fix those files with `./gradlew -p build-logic ktlintFormat`. A
`.gradle.kts` script has no declaration for a KDoc to attach to, so the convention plugins document
themselves in `/* */` rather than `/** */`.

Two legacy habits not to reproduce, both because they do not survive automated refactoring:
**dash-padded** banner comments, and column-aligned assignments. What the rule objects to is the
padding — a run of dashes filling a comment out to the margin — which carries no information, drifts
out of alignment with its siblings the first time a rename changes a line's length, and then reads as
carelessness rather than as the layout it was. A bare section marker is fine and is the house style
in the longer files:

```kotlin
// -- internals                       // yes
// -- internals ---------------       // no
```

A file needing many of them is usually a file with too many responsibilities, and the markers are the
symptom rather than the disease — but splitting it is a bigger change than a style rule may demand.

**Why:** Mechanical style should be settled once and never discussed again — which means a tool
settles it, and the rules that tool gets wrong for this codebase are turned off once, in writing,
rather than argued about per review.


## CC-12 — Same-changeset sibling duplicates

**Two functions written in the same changeset whose bodies are mostly identical, differing only in 1–3
parameterizable values, should be consolidated into one.**

"No premature abstraction" defends against speculative future generalization. Two siblings written
together are not speculative — both callers exist, both shapes are known, and the cost of the second
body is a copy-paste maintenance burden from the moment it is committed.

The honest test: if I changed one shared line in the first helper, would I have to change it in the
second to keep them consistent? If yes, they aren't two helpers — they're one helper with two call
shapes. `TournamentFormat` is the shape done right: `HEAD_TO_HEAD` and `FREE_FOR_ALL` are a property
of the config feeding one scheduler and one `TournamentTable`, not two drivers, and for two
contestants they produce the same schedule — with a test pinning that identity.

**Threshold.** If the bodies are ≥70% the same and the differences fit in 1–3 parameters,
consolidate. Below that, keep them separate — the shared scaffold isn't load-bearing enough to factor
out.

**Same-changeset means same-changeset.** This does NOT override [CC-07](#cc-07--drive-by-refactoring-and-cleanup)
for *pre-existing* near-duplicates found in adjacent code. Surface those as a follow-up. The
exception is precisely code you yourself just wrote.

**Why:** "Premature" describes time, not similarity. The guidance "three similar lines beat a
premature abstraction" is about *line-level* repetition inside one function, not about whole sibling
functions sharing a scaffold. Function-level duplication committed in the same change forces every
future edit to walk both copies, and silently rewards drift.


## CC-13 — Test colocation

**Tests live in the same Gradle module and the same package as the code they cover** — `Board` in
`:core` under `ao.snakewarz.core.rules` is tested by `BoardRulesTest` in `:core`'s test source set,
under that same sub-package. When [CC-06](#cc-06--a-package-is-a-handful-of-files) splits a package,
the test tree moves with it.

- **`commonTest` by default.** All four pure modules keep their suites there, which means every test
  runs on the JVM for speed *and* proves the code is platform-free.
- **Browser tests are gated behind `-PbrowserTests=true`** and are for what is genuinely
  browser-shaped: paint, DOM, wasm codegen, and the golden hashes that
  [SW-02](#sw-02--portable-arithmetic-only-in-bots) exists to protect. Anything provable on the JVM is
  proven there instead, because Karma startup dominates a small suite.
- **`:ui` and `:app` have nowhere else to go**, so their `wasmJsTest` suites are the browser job's
  own reason to exist. Reach for the seam rather than the DOM where there is one: the two clocks take
  a timestamp so a test can be the clock, the hit-test is arithmetic over a bounding box, and
  `SlotLabels` and `Palette` need no page at all.
- **A new bot needs no new test file.** `BotContractTest` sweeps `BotRegistry.entries`, so registering
  is what enrols a bot in the suite. Write a test of your own for behaviour the contract cannot state.

Don't park a test in a module that merely happens to have the scaffolding you want; move or duplicate
the minimum scaffold instead. Don't file a test under a generic package when the code it covers lives
elsewhere.

**Why:** One-to-one colocation makes the test discoverable from the production file and vice versa,
and keeps test refactors moving in lockstep with code refactors. The `commonTest` preference is worth
more here than in an ordinary KMP project: it is a second compiler continuously proving the four pure
modules have not acquired a platform dependency.


## CC-15 — One public declaration per file

**One public top-level declaration per file, named after it.**

Two exceptions, both narrow: a sealed hierarchy whose variants are only meaningful through the parent
may share the parent's file, and a private or `internal` type that is an implementation detail of the
file's public class may live beside it. "They're related" is not enough.

A cluster of classes that only make sense together gets its own *package*, not one shared file — that
clause belongs to [CC-06](#cc-06--a-package-is-a-handful-of-files).

**Why:** File names are the first index a reader uses, and a class hiding inside a sibling's file is
invisible to file-based navigation.


## CC-16 — Never write IDE configuration files

**Never create or modify IDE-private configuration (`.idea/**`, `*.iml`, `workspace.xml`,
run-configuration XML) — neither as a fix nor as a suggestion.** These files are machine-local,
gitignored, and freely regenerated or discarded by the IDE.

The canonical home for launch and run setup is the build itself — a Gradle task, or `:lab`'s
command-line arguments — plus a documented command in [`../CLAUDE.md`](../CLAUDE.md#commands). If an
IDE run configuration is convenient, the *user* creates it in the IDE.

**Why:** IDE state files are not source. A "fix" written into `.idea/` is invisible to version
control, unreviewable, lost on cache invalidation, and absent on every other machine — it papers over
a gap that should be closed in the build or the docs.


## CC-17 — Generic code dispatches by capability, never by bot identity

**`:match` and `:ui` must never gate behaviour on a bot's identity — a slug compared against a
literal, a class name, an `is` check against a bot type. Dispatch on a declared capability instead,
so a forked bot works without editing either module.**

Don't:
```kotlin
// Chrome — the generic sidebar, for ANY registry entry
val showAllowance = entry.id.slug == "uct" || entry.id.slug == "flat-monte-carlo"
```

Do — the entry declares what it wants, and the generic side reads the declaration:
```kotlin
val showAllowance = entry.knobs.any { it is BotKnob.Search }
```

The seams already in the codebase — imitate these, never add an identity check:

- **`BotRegistry.entries`** — the picker `<option>`s and the tournament contestant list are the
  registry, in order.
- **`BotKnob` declarations** — each seat's settings rows are built from that entry's `knobs`, so a bot
  that declares one more gets one more row and nothing in `:ui` changes. A pre-written pool of rows
  would have been the doctrinal answer and is the wrong one: the day a bot declares one knob past the
  pool size, it silently loses it.
- **`BotKnob.Search`** — declaring one is how the sidebar learns to offer an allowance field, and
  [SW-07](#sw-07--a-search-pays-for-its-own-work) is how that claim is kept honest.
- **`Bot.interactive` / `Match.interactive`** — which clock runs branches on a capability, not on a
  mode flag and not on the human's slug.

A concrete identifier is legitimate only as **self-reference or a stated default**:
`PlayableRegistry.HUMAN_ID` names its own seat inside the class that composes it, and `:ui` opens
slot 2 on the slug `uct` as a preference with a fallback to `entries.first()`, so a registry that
never heard of `uct` still works.

**Why:** "Fork → add a file → register it → open a PR" is the promise this project is built around. A
hard-coded slug caps the system at the bots the author knew about, and the omission is invisible —
no error, the new bot just silently gets nothing.


## CC-18 — User-facing text speaks the player's language

**Every string a player can see — bot display names, the winner line, the scoreboard, the hover
label, the tournament legend, an inline error — is product copy, in the game's vocabulary. Never quote
ad-hoc phrasing from the task that produced the code, and never leak developer-only referents (class
names, module names, internal mechanisms).**

The [CC-02](#cc-02--comments) rewrite test, adapted: would this string still make sense — and still be
true — for a player who never saw the conversation that produced it, in every context this UI reaches?

Don't:
```kotlin
"Slot 2 (UctBot, budget knob per the new config work) — eliminated"
```

Do:
```kotlin
"UCT — trapped on turn 41"
```

The one qualifier that *is* domain vocabulary is a seat's configuration: `uct` beside `uct@4k` comes
from `Contestant.suffix`, and the sidebar, the hover label, the winner line and the win-rate matrix
all take it from there so they cannot start disagreeing about what `@4k` means.

**Why:** CC-02 bans prompt-echo in comments, where the cost falls on the next developer; in UI copy
the same echo ships to the player, who has even less context — and a string naming one internal
mechanism becomes wrong the moment that mechanism changes.
