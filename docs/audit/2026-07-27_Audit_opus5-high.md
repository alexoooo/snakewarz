# Audit — 2026-07-27

**Model:** Opus 5 (1M context), reasoning effort high.
**Reviewed against:** [`../Coding-Standards.md`](../Coding-Standards.md), plus anything else judged
material. Where a finding is material but no rule covers it, that is said, and a rule is proposed.
**Baseline:** `209ab59`, working tree carrying the uncommitted `## Git` section in `CLAUDE.md`.
**Status: every finding below is closed.** What landed for each is in
[Resolution](#resolution); the findings are kept as written so the reasoning survives the fix.

## What was read

Every main source file in `:core`, `:bot-api`, `:bots`, `:match`, `:ui`, `:app` and `:lab` — 18,123
lines across 180 tracked files — plus `build-logic`, the seven module build files, `.editorconfig`,
`.gitattributes`, `.github/workflows/ci.yml`, `index.html`, and all seven documents under `docs/`.
The test sources were read where a finding turns on them.

Two caveats on method, both worth stating because they bound what this document can claim:

- **No Gradle task was run** during the review pass. So `ktlintCheck`, `checkModulePurity`, the JVM
  suite, the browser conformance job and SW-08's gzipped bundle budget were all *unverified* as this
  was written. Everything below that a build would have caught was instead checked by reading or by
  a scripted scan of the tree, and each such check says which. All five were run during the fix pass
  and their results are under [Verification](#verification).
- **"No caller" claims come from a whole-tree symbol scan**, not from the compiler. Reflection would
  defeat it; there is none in this tree, and SW-08 is the reason there is none, so the claims stand.
  They were spot-checked by hand in every case cited.

**Every `file:line` below is against the baseline commit and most no longer resolve**, because the
fix pass moved them. They are left as written rather than renumbered: a finding is a statement about
the code as it was, and re-pointing it at code that no longer has the defect would make the document
lie in a new way. [Resolution](#resolution) is what describes the tree as it stands.

## What holds

A finding list on its own reads as a worse verdict than this tree deserves, so: the load-bearing
rules hold, and several of them hold by construction rather than by vigilance.

- **SW-01 determinism.** No `HashMap`/`HashSet` *iteration* anywhere in `:core` or `:bots`; the four
  places a map is needed at all use `LinkedHashMap` (`BotParams`, `ShippedBots.byId`,
  `ReplayCodec.decode`, `TournamentTable.headings`). No wall clock below `:ui` — `kotlin.time` is
  imported in exactly three files, all in `:ui` or `:lab`. RNG is forked per slot from the match
  seed in `Match.kt:60` and reaches a bot only through `BotSetup.rng`. `kotlin.random.Random` appears
  once, in `ui/freshSeed.kt`, which picks a *number* that then feeds `SplitMix64` — the KDoc argues
  it correctly.
- **SW-02 portable arithmetic.** No `ln`, `exp` or `pow` in any `:bots` main source. `sqrt` is used
  in four places and is IEEE-exact. One test-source exception, which is finding **A2** and is a
  defect in the rule rather than in the code.
- **SW-03 the hot path.** No `Sequence` anywhere in `:core` or `:bots`. `data class` appears twice
  in `:core` (`MatchOutcome`, `RulesConfig`), neither on a per-move path — `MatchOutcome` is
  allocated once per terminal state and handed out by identity thereafter, which is exactly what the
  `EXHAUSTED`-by-identity checks in `FlatMonteCarloBot`, `UctBot`, `PuctBot` and `RolloutEval` rely
  on. Every search buffer in `:bots` is a constructor-allocated instance field.
- **SW-04 module purity.** The forbidden-edge table is encoded in `snakewarz.pure.gradle.kts` and
  walked case-insensitively across every `*CompileClasspath`, test source sets included; all three
  convention plugins wire it into `check`, and all three call `explicitApi()`. `:ui` exposes exactly
  two declarations, `GameSession` and `ReplayLink`, as `docs/UI.md` says it should.
- **CC-09 stub markers.** Zero `TODO(`, `FIXME`, `NotImplementedError` or `UnsupportedOperationException`
  in the tree. The document claims this state and the claim is still true.
- **CC-11 mechanical style.** Not one line over 120 columns, no trailing whitespace, no missing
  final newline, no star import, in any `.kt` or `.kts`. The non-mechanical half of CC-11 is finding
  **A1**.
- **CC-16.** No `.idea/**` and no `*.iml` tracked.
- **CC-17 capability dispatch.** `:match` and `:ui` were read for identity checks specifically. There
  are two concrete slugs in `:ui`, both legitimate under the rule's own carve-out:
  `Chrome.DEFAULT_OPPONENT = "uct"` falls back to `bots.firstOrNull()`, and
  `PlayableRegistry.HUMAN_ID` is self-reference. Nothing else compares a slug, a class or a type.

The working tree is clean apart from `CLAUDE.md`, and there are no untracked files — so nothing in
this audit is invisible to `git diff`.

---

## Findings

Ordered by severity. The id encodes the kind, not the order: **A** is a violation of a documented
rule, **B** is material with no rule covering it, **C** is a change to the standards document
itself. Cite one as, for example, "audit B1".

| Id | Severity | One line |
|---|---|---|
| [B1](#b1--a-replay-link-allocates-before-the-guard-that-would-reject-it) | High | A crafted `#r=` link allocates hundreds of megabytes before the check that would refuse it |
| [A4](#a4--the-zobrist-hash-has-no-production-consumer) | High | `BoardView.hash` is paid for on the hottest path and read by nothing but tests |
| [B2](#b2--ui-and-app-have-no-tests-at-all) | High | 2,678 lines of `:ui` and 88 of `:app` have no test source set |
| [A1](#a1--banner-comments-cc-11-names-as-a-habit-not-to-reproduce) | Medium | 18 `//-----` banners, which CC-11 explicitly forbids |
| [B3](#b3--botladdertest-does-not-assert-the-ladders-first-rung) | Medium | Three documents claim a rung the suite never checks |
| [A8](#a8--seven-of-the-nine-shipped-bots-there-are-ten) | Medium | Stale bot count in a KDoc and in the contract suite |
| [A5](#a5--internal-search-primitives-with-no-caller) | Medium | Seven `internal` members in `:bots`, plus one in `:ui`, called only by tests or not at all |
| [B4](#b4--matchrecordverify-cannot-succeed-on-a-partial-recording) | Medium | `verify` reports a divergence for every mid-match record, which is what Share publishes |
| [A2](#a2--sw-02-as-written-forbids-the-only-way-to-test-portablelog) | Medium | The rule has no source-set scope, so its own canary test violates it |
| [A9](#a9--claudemd-says-twenty-six-rules-there-are-25) | Low | Off-by-one in the routing table |
| [A3](#a3--prefersdark-hides-in-boardrendererkt) | Low | CC-15: an `internal` function used across files, filed under a sibling's name |
| [A6](#a6--editorconfig-still-configures-the-deleted-legacy-tree) | Low | Dead build configuration |
| [A7](#a7--modulepuritykt-says-both-convention-plugins-there-are-three) | Low | One-word KDoc error, beside a file that gets it right |
| [B5](#b5--a-partial-recording-leaves-the-scheduler-spinning) | Low | End of a shared mid-match replay re-arms rAF forever and the transport says "Pause" |
| [B6](#b6--percent-is-duplicated-across-a-module-boundary) | Low | Legitimate, but unsaid — unlike the two duplications this repo does annotate |
| [B7](#b7--four-small-things) | Low | A pass-through, two unread `catch` bindings, one unnamed literal |
| [C1](#c1--scope-sw-02-to-main-sources) — [C4](#c4--fix-the-rule-count-in-claudemd) | — | Changes to `Coding-Standards.md` and `CLAUDE.md` |

---

### B1 — A replay link allocates before the guard that would reject it

**Severity:** High. **Rule:** none — proposed as [C3](#c3--a-bound-that-protects-an-allocation-is-checked-before-the-allocation).

`MatchSetup` bounds the board only by `require(rows > 0 && cols > 0)` (`MatchSetup.kt:67`) and by the
spawn-range check below it. `ReplayCodec.decode` therefore accepts a payload declaring, say,
5000×5000 — the varints are there, the spawn indices fit in `0 until 25_000_000`, and nothing further
down objects. The engine's own ceiling does exist:

```kotlin
require(grid.cellCount <= MAX_JOURNALED_CELL) {          // Board.kt:73
    "$grid is too large for the undo journal's $MAX_JOURNALED_CELL cell ceiling"
}
```

but it sits in `Board`'s `init` block, and Kotlin runs property initializers in declaration order
*before* the `init` block. By the time line 73 is reached, `Board.kt:40` has already allocated
`Occupancy`'s `ByteArray(grid.cellCount)` — 25 MB at that size — and `Board.kt:41` has allocated one
`SnakeBody` per slot, each of which is `IntArray(powerOfTwoAtLeast(playableCount + 2))`
(`SnakeBody.kt:16`), or 134 MB apiece. Four slots is over half a gigabyte requested before the line
that would have said no.

The failure mode is worse than the allocation. An OOM or a `NegativeArraySizeException` from an
overflowed `cellCount` is not an `IllegalArgumentException`, so `replayHash.kt:28` — which exists
precisely to turn a bad link into a fresh match and a console line — does not catch it. The wasm
module dies during boot, `document.body.classList.add("booted")` never runs, and `index.html`'s
watchdog reveals **"This browser can't run Snake Warz"**. A confident, wrong diagnosis of a bad link,
shown to somebody whose browser is fine.

**Why it matters beyond the crash.** This is the one input to the program that arrives from a
stranger. The codec already knows that and already acts on it everywhere else: `BotId.MAX_LENGTH`,
`BotKnob.MAX_NAME_LENGTH`, `MAX_VALUE_LENGTH` and `MAX_PER_BOT` all carry the same KDoc sentence —
*"Bounded so a decoder can reject a corrupt payload before allocating from it."* The board dimensions
are the one decoded field that got no such bound, and they are the field that allocates most.

**Remediation.** Two independent fixes; take both, because each closes a different half.

1. Bound the geometry in `MatchSetup.init`, in the same idiom as the fields around it — a
   `MAX_ROWS`/`MAX_COLS`, or a single ceiling on `rows * cols`. `Board.MAX_JOURNALED_CELL` is the
   engine's real limit and is a reasonable thing to derive from, but `:match` cannot see a private
   companion constant, so either promote it or pick a figure the UI's 40×40 comfortably clears and
   say what it is.
2. Move `Board`'s cell-count `require` ahead of the allocations it protects. In Kotlin that means
   either a private secondary-constructor guard or moving `occupancy`/`bodies` into the `init` block
   as `lateinit`-free assignments; the simplest honest form is a small `private companion` validator
   called from the property initializer of the first allocated field.

---

### A4 — The Zobrist hash has no production consumer

**Severity:** High. **Rules:** CC-10 (justify every line), CC-02 (comments carry a true *why*).

`BoardView.hash` is a Zobrist fingerprint over occupancy, heads, growth phases, liveness and whose
turn it is. Its KDoc justifies itself thus:

> Equal hashes mean equal positions with overwhelming probability, which is what makes MCTS
> transposition and tree reuse a `Long` compare — the legacy `BiState.equals` compared whole
> `BitSet`s on every node visit.

and `Bot`'s KDoc repeats the claim from the other side — "which is how an MCTS bot keeps its tree
across turns with no extra API at all. `BoardView.hash` makes finding last turn's subtree a `Long`
compare".

**Neither is true of any bot in this tree.** `UctTree` and `PuctTree` do not read `hash`; there is no
transposition table and no subtree carried across turns. Both bots begin `chooseMove` with
`tree.reset()` (`UctBot.kt:67`, `PuctBot.kt:96`), which sets the node count back to one and discards
everything the previous turn learned. A whole-tree scan finds every `.hash` reference outside
`:core`'s own two files in a test — `HeadlessMatch`, `BoardScratchTest`, `UctBotTest`, `PuctBotTest`,
`BoardUndoTest`, `OccupancyTest` — where it is used as a cheap position-identity assertion, which is
a genuine use but not the stated one.

**The cost is on the hottest path in the program.** A surviving `Board.apply` computes seven `mix64`
calls on a growing turn and eight when the tail retracts: two head keys and two growth-phase keys
(`Board.kt:164-167`), two to-act keys through `advanceToAct` (`Board.kt:350`, `:359`), and one or two
inside `Occupancy.occupy`/`vacate` (`Occupancy.kt:67`, `:73`). Each `mix64` is two 64-bit multiplies,
three xors and three shifts (`mix64.kt:15-19`). `undo` pays the same again. This is inside the loop
that SW-03 exists to protect, in the module whose KDoc says a move "touches at most two squares", for
a value nothing reads.

**Why it matters.** Two separate costs, and the second is the larger. The measurable one is playing
strength: SW-03's own rationale is that "a bot's strength is how many rollouts fit in its allowance",
and this is arithmetic per simulated move. The structural one is that two KDocs assert a design that
does not exist — a reader who believes them will spend real time looking for the transposition table,
and a reader who checks will stop trusting the other KDocs, which in this tree are unusually good and
are most of its documentation.

**Remediation.** Three honest options; the point is to pick one deliberately rather than leave the
claim standing.

1. **Make the claim true.** Wire tree reuse into `UctTree`/`PuctTree`: at the top of `chooseMove`,
   find the child whose position hash matches the live board and re-root on it instead of resetting.
   This is the option the KDocs were written for, and it is a real strength gain at the shipped
   allowance, where a turn buys a few hundred iterations and throwing them away every turn is most of
   what the tree could have been worth.
2. **Keep it and say what it is.** `hash` is published `:core` API and a contributed bot may well
   want it — that is a defensible reason to ship it. Then both KDocs must be re-worded from "which is
   what makes tree reuse a `Long` compare" to something that does not describe a bot that exists.
3. **Take it out of the hot path.** Drop `auxHash` and its four key families from `Board`, keeping
   `Occupancy.hash`, which is one `mix64` per square touched and is what the property test actually
   pins.

Whichever is chosen, settle it with numbers rather than argument: `:bots`' `ThroughputTest` prints
`[bench]` lines on both targets and exists for exactly this. Note that options 1 and 3 both move the
`GoldenMoveStreamTest` hashes, and SW-01 makes that a question to answer in the commit message, not a
number to update.

**Related, and deliberately not folded in.** `Playout.undo` and `Playout.undoDepth` are in the same
position — no shipped bot calls either, and `UctBot` and `PuctBot` both say outright in their KDocs
that they never will, because a `playout()` reset is one array copy and makes an off-by-one unwind
impossible. But `Playout` is the bot-author contract, undo costs nothing when nobody calls it, and a
search that descends and backs up without resetting is a normal thing for a contributed bot to want.
That is a live justification and the code stands.

---

### B2 — `:ui` and `:app` have no tests at all

**Severity:** High. **Rule:** CC-13 in spirit; no rule requires coverage.

Test source sets exist in `:core` (12 files), `:bot-api` (6), `:bots` (25), `:match` (12) and `:lab`
(1). `:ui` has none, for 2,678 lines. `:app` has none, for 88.

The gap is sharper than the raw number because of what the browser job is for. CI runs a whole
dedicated `browser-conformance` job — `./gradlew allTests -PbrowserTests=true` — and CC-13 says
browser tests are "for what is genuinely browser-shaped: paint, DOM, wasm codegen". Every test that
job runs is a `commonTest` from one of the four pure modules, re-compiled to wasm. **The module the
browser job exists for contributes nothing to it.** The job earns its place regardless — it is what
re-runs the golden hashes in real Chrome, which is SW-02's whole purpose — but the paint and DOM half
of its stated remit is unexercised.

Five things in `:ui` are testable today without a browser-shaped assertion being hard, and four of
them have a KDoc that all but asks for the test:

- **`TurnScheduler`'s monotonic-timestamp clamp** (`TurnScheduler.kt:83-90`). Its own comment says
  the lower bound is "not decoration" and that an unclamped negative interval produces "a freeze with
  no error and no obvious cause". `frame(timestamp: Double)` is private, but `start`/`stop` and the
  injected `step` lambda make the accumulator arithmetic reachable with a seam no larger than making
  `frame` internal.
- **`KeyRepeat`** (`KeyRepeat.kt`). Its KDoc says "a timestamp handed in from outside is a clock a
  test can drive". Nothing drives it. The take-over-on-second-key rule and the "owed from now rather
  than from when it fell due" rule are both stated behaviours with no check.
- **`BoardRenderer.cellAt`** (`BoardRenderer.kt:163-180`). `docs/UI.md` records that the hit-test was
  a pixel out at fractional device-pixel ratios and explains the fix; a rounding regression there is
  invisible and would be caught by arithmetic alone.
- **`SlotLabels.number`** (`SlotLabels.kt`) — pure list-to-list, no DOM.
- **`Palette`** cycling past the last hue — pure, no DOM.

**Remediation.** Add `ui/src/wasmJsTest`, behind the existing `-PbrowserTests=true` gate that
`snakewarz.browser` already configures, starting with those five. None needs a canvas. `:app` is
sixty lines of wiring plus `replayHash.kt`, and `readReplay`'s fragment parsing — which is the other
place a stranger's input lands — is worth one test of its own.

---

### A1 — Banner comments CC-11 names as a habit not to reproduce

**Severity:** Medium. **Rule:** CC-11.

CC-11 closes with: *"Two legacy habits not to reproduce, both because they do not survive automated
refactoring: `//------` banner comments, and column-aligned assignments."* Column-aligned assignments
are indeed absent. Banner comments are not — there are **18**, and they are a house style rather than
a slip:

| File | Lines |
|---|---|
| `core/.../Board.kt` | 326 |
| `match/.../Match.kt` | 197 |
| `match/.../Tournament.kt` | 160 |
| `match/.../TournamentTable.kt` | 139 |
| `ui/.../Chrome.kt` | 269 |
| `ui/.../SlotForm.kt` | 130 |
| `ui/.../GameSession.kt` | 123, 183, 249, 369, 483, 567 |
| `build-logic/.../snakewarz.pure.gradle.kts` | 46, 52 |
| `bots/src/commonTest/.../ExpertEvalTest.kt` | 187 |
| `bots/src/commonTest/.../PuctBotTest.kt` | 175 |
| `bots/src/commonTest/.../RolloutTruncationTest.kt` | 130 |
| `bots/src/commonTest/.../ThroughputTest.kt` | 86 |

**Why it matters, and why this is not a trivial finding.** A rule with eighteen unremarked violations
is not a rule, and every subsequent citation of CC-11 is weakened by it — a reviewer who cites CC-11
on a wrapping question can be answered with "the tree does not follow CC-11 either". The rule and the
code have to agree; which one moves is a judgement call, so here is the case for each.

*For the code moving:* the trailing dashes are what CC-11 objects to and they are pure noise — they
carry no information, they are the part that rots when a rename changes a line's length, and
`TournamentTable.kt:139` has already drifted six characters longer than its eleven siblings, which is
precisely the failure the rule predicts.

*For the rule moving:* the section markers themselves are doing real work. `GameSession.kt` is 632
lines with six of them, and they are the only navigational structure in it; deleting them makes the
file worse. What the file actually wants is fewer responsibilities, which is a bigger change than an
audit should propose.

**Recommendation:** keep the section markers, drop the dashes — `// -- internals` and
`// -- the batch` on their own — and amend CC-11 to permit exactly that, since a marker with no
padding survives every rename. See [C2](#c2--say-what-cc-11-means-by-a-banner-comment).

---

### B3 — `BotLadderTest` does not assert the ladder's first rung

**Severity:** Medium. **Rule:** none; this is a claim three documents make that no test checks.

`ShippedBots`' KDoc, `docs/Bots.md` and `README.md` all say the same thing in the same words: the
ladder is registered weakest first and "each rung beats the one below it over twenty matches —
`BotLadderTest` is what says so". The ladder is seven bots, so that is six rungs. The test asserts
five (`BotLadderTest.kt:26-30`):

```kotlin
assertBeats("space", "wallhug", atLeast = 14)
assertBeats("pressure", "space", atLeast = 15)
assertBeats("chase", "pressure", atLeast = 11)
assertBeats("flat-monte-carlo", "chase", atLeast = 12)
assertBeats("uct", "flat-monte-carlo", atLeast = 12)
```

`wallhug` beating `random` — the first rung — is nowhere.

**Why it matters.** It is not the rung you would pick to skip. `random` being the documented weakest
is what justifies `ShippedBots`' hard requirement that `random` stay first in the list ("the opening
screen of a game nobody has configured yet should be the weakest opponent there is") and what
`:ui` leans on when it seats slot 2 from the registry. It is also the only rung whose two bots are
both deterministic-ish and cheap, so the check costs almost nothing to run. And `BotLadderTest`'s own
KDoc calls itself "the only assertion here that a *correct and useless* bot would fail" — a bot that
is useless in exactly the way `random` is, is the one case it does not cover.

**Remediation.** One line, plus its measured threshold: run
`./gradlew :bots:jvmTest --tests '*BotLadderTest*'` with the pairing added, take the observed number
and subtract the usual slack, and add `assertBeats("wallhug", "random", atLeast = N)` at the head of
the list.

---

### A8 — "seven of the nine shipped bots"; there are ten

**Severity:** Medium. **Rule:** CC-02 (a comment is worth nothing if it is not true).

Two places still count nine bots:

- `bot-api/.../BotEntry.kt:36` — *"seven of the nine shipped bots answer with a flood fill and
  consume nothing"*.
- `bots/src/commonTest/.../BotContractTest.kt:127` — *"Seven of the nine answer with a flood fill and
  consume nothing"*, in the comment above the test that enforces SW-07.

`ShippedBots` registers ten. Seven of the ten consume no budget — `flat-monte-carlo`, `uct` and
`puct` are the three that do — so the *ratio* is right and only the denominator is stale. `puct`
landed in `8676ae5` and these two did not move with it; `README.md` and `docs/Bots.md` both say
"ten" and are correct.

**Why it matters.** Small, but it is the leading indicator for the class of drift SW-05 is about. A
count in a KDoc is the cheapest possible thing to keep true, and a tree that lets one go stale is a
tree where the expensive ones — a knob name, a `Choice` value — will go stale the same way. It also
sits in the two files a bot author reads first.

**Remediation.** "Seven of the ten" in both. Consider whether the count is worth stating at all:
`BotEntry.search`'s point survives as "most shipped bots answer with a flood fill and consume
nothing", which never needs editing again.

---

### A5 — Internal search primitives with no caller

**Severity:** Medium. **Rule:** CC-10.

CC-10 says to delete on sight "future-proofing abstractions with one caller", and SW-04 says `:bots`'
search primitives "are `internal` on purpose so they can be changed" — which is an argument for
keeping the internal surface at exactly what is used. Eight members are used by nothing but their own
tests, or by nothing at all:

| Member | Site | Called from |
|---|---|---|
| `ShortestPaths.distanceTo` | `ShortestPaths.kt:86` | tests only |
| `UctTree.actorOf` | `UctTree.kt:69` | tests only |
| `UctTree.visitsOf` | `UctTree.kt:71` | tests only |
| `PuctTree.actorOf` | `PuctTree.kt:77` | tests only |
| `PuctTree.visitsOf` | `PuctTree.kt:79` | tests only |
| `PuctTree.averageOf` | `PuctTree.kt:89` | tests only |
| `PuctTree.priorOf` | `PuctTree.kt:92` | tests only |
| `TournamentRunner.clear` | `TournamentRunner.kt:53` | **nothing** |

`PuctTree.averageOf` is the interesting one: `selectPuct` computes the same quantity inline
(`rewardSum[child] / childVisits`, `PuctTree.kt:158`) because it needs a different answer for an
unvisited child, so the two are deliberately not the same function — which leaves `averageOf` with no
production caller and a name that suggests it has one. `UctTree.averageOf` by contrast *is* used, in
both `selectUcb1` and `bestMoveAtRoot`, so the two trees differ here for a real reason that is not
written down.

`TournamentRunner.clear()` is the clearest case: `GameSession` calls `batch.start` and `batch.stop`
and never `clear`, so the "forgets the batch entirely, so the panel goes back to offering a new one"
behaviour its KDoc describes is a behaviour the app does not have.

**Why it matters.** Each of these is a small lie about the surface area a future change has to
preserve. `:bots`' primitives being `internal` is the mechanism by which they stay cheap to rewrite,
and every accessor a test reaches through is one more thing a rewrite has to keep.

**Remediation.** Delete `TournamentRunner.clear` outright. For the seven test-only accessors, decide
per member: either delete and re-express the test through the public path (`bestMoveAtRoot`, `size`,
`selectPuct`), or keep and add a one-line KDoc saying it exists as a test seam — which is a real and
respectable reason, it just has to be stated so the next reader does not go looking for the caller.

---

### B4 — `MatchRecord.verify` cannot succeed on a partial recording

**Severity:** Medium. **Rule:** none; a KDoc claims more than the method delivers.

`MatchRecord.verify` re-runs the real bots from the setup, plays to completion, and compares. After
the shared-prefix loop it does this:

```kotlin
if (actual.moves.size != moves.size) {                   // MatchRecord.kt:86
    return ReplayVerification(false, shared, "the recording holds ... but the replay produced ...")
}
```

A record taken mid-match holds fewer moves than a full replay, so this branch fires every time and
the answer is always "diverged". That is precisely the shape `Share` publishes: `GameSession.share()`
calls `match.record()` at whatever turn the board is on (`GameSession.kt:335`), and `MatchRecord`
explicitly supports `outcome == null` — "`null` if the recording stops before the match ended".

Nothing is broken today, because `verify` has no production caller; it is a test and CI facility, and
SW-01 leans on it in that role ("CI re-runs recorded matches against the registry"). But the KDoc
bills it generally — "Re-runs the **real** bots from the seed and checks that they still play what
was recorded" — and lists four things it catches, none of which announces the restriction.

**Why it matters.** Someone will eventually want the button: paste a link, ask whether it still
reproduces. The first mid-match link they try will report a divergence that is not one, and SW-01
says a divergence "is always a question worth answering" — so it will cost somebody an afternoon
answering a question with no bug behind it, which is the exact failure SW-02's KDoc warns about in a
different context.

**Remediation.** Either state the restriction in the KDoc — one sentence, "a partial recording cannot
be verified this way; the replay always runs longer" — or make it true: when `outcome == null`, drop
the length comparison and verify the recorded prefix only, keeping the strict comparison for a
finished record. The second is a few lines and makes the shared-link case work; prefer it.

---

### A2 — SW-02 as written forbids the only way to test `portableLog`

**Severity:** Medium. **Rule:** SW-02 — the defect is in the rule.

SW-02 reads: *"Nothing in `:bots` may call `kotlin.math.ln`, `exp` or `pow`."* No source-set
qualifier. `bots/src/commonTest/.../PortableLogTest.kt:4` imports `kotlin.math.ln` and uses it as the
accuracy oracle for `portableLog` — which is the only way to test `portableLog` at all, and the test
knows it: its KDoc says it is "one that would notice somebody replacing the series with
`kotlin.math.ln` for tidiness".

So the rule, read literally, forbids its own canary. That is a defect in the rule and not in the
test.

**Why it matters.** A rule that its own guardian test violates cannot be enforced mechanically later
— and SW-02 is a good candidate for exactly that, since a two-line ktlint custom rule or a `grep`
step in CI would close the one gap where a reviewer has to remember. As written, such a check would
fail on day one against the test that proves the rule is being kept.

**Remediation.** See [C1](#c1--scope-sw-02-to-main-sources).

---

### A9 — `CLAUDE.md` says "Twenty-six rules"; there are 25

**Severity:** Low. **Rule:** none.

`CLAUDE.md:22`, in the routing table: *"Twenty-six rules a review cites by id"*.
`docs/Coding-Standards.md` has 25 — SW-01 through SW-08 (8), CC-01 through CC-13 (13), and CC-15
through CC-18 (4). Both the index table and the section headings count 25.

**Remediation.** "Twenty-five". Better still, drop the number: the row's job is to say the document
is a rule set with citable ids, and a count is one more thing to keep true every time a rule is
added.

---

### A3 — `prefersDark` hides in `BoardRenderer.kt`

**Severity:** Low. **Rule:** CC-15.

```kotlin
internal fun prefersDark(): Boolean = window.matchMedia("(prefers-color-scheme: dark)").matches
```

sits at `BoardRenderer.kt:492`, after the class the file is named for. It is called from
`BoardRenderer.kt:77` and from `GameSession.kt:99` — so it is not an implementation detail of this
file's class, which is the narrow exception CC-15 grants (and that exception is written for a *type*,
not a function). In a module where `internal` is the real API surface, this is the case CC-15's
rationale describes: "a class hiding inside a sibling's file is invisible to file-based navigation".

`:ui` already has the pattern this wants — `elementById.kt`, `freshSeed.kt` and `tailClearsNext.kt`
are each one `internal` function in a file named after it, which is also why `.editorconfig` turns
ktlint's `filename` rule off.

**Remediation.** Move it to `ui/prefersDark.kt`. One file, no other change.

Not the same case, and left alone: `Chrome.kt`'s `HTMLElement.child` and `copyToClipboard`, and
`SnakeBody.kt`'s `powerOfTwoAtLeast`, are all `private` and file-local, and
`ScriptedRegistry.kt`'s `Script`/`ScriptedBot`/`ScriptedForfeit` are private types that are
implementation details of the file's class — squarely inside CC-15's exception.

---

### A6 — `.editorconfig` still configures the deleted legacy tree

**Severity:** Low. **Rule:** CC-10.

```ini
# Legacy Java is reference material only. Do not reformat it; do not lint it.
[legacy/**]                                              # .editorconfig:54
```

`CLAUDE.md` states that the legacy Java is deleted and "lives at the `legacy-java-final` tag and
nowhere else". So this section governs a path that cannot exist in a checkout of `master`.
`.gitattributes` carries a matching `legacy/** -text` rule, with a comment that reads as deliberate —
"never rewrites bytes that were committed years ago" — which is a defensible reason to keep *that*
one, since `.gitattributes` is consulted when checking out the tag.

**Remediation.** Delete the `.editorconfig` section, or give it the one-line justification
`.gitattributes` has if the intent is to serve a `legacy-java-final` checkout. Right now the two
files disagree about whether that path is a live concern, and neither says which.

---

### A7 — `ModulePurity.kt` says "both convention plugins"; there are three

**Severity:** Low. **Rule:** CC-02.

`build-logic/.../ModulePurity.kt:8`: *"Architectural enforcement, shared by both convention plugins."*
`snakewarz.pure`, `snakewarz.browser` and `snakewarz.tool` all call `registerModulePurityCheck`.
`Ktlint.kt:8`, the file directly beside it, gets it right — "shared by all three convention plugins".

Worth a line only because `CLAUDE.md` and `docs/Coding-Standards.md` both make the "wired into
`check` for all three convention plugins" claim load-bearing, and this is the file that would be read
to confirm it.

**Remediation.** "all three".

---

### B5 — A partial recording leaves the scheduler spinning

**Severity:** Low. **Rule:** none.

Under playback `Match.interactive` is false, so `GameSession.begin()` starts `TurnScheduler`. When
the scripted slots run off the end of a partial recording, `ScriptedBot` answers `Decision.Pending`,
`Match.step` returns `StepResult.AwaitingInput`, `GameSession.advance` returns `AWAITING_INPUT`, and
the scheduler clamps its accumulator and breaks — then re-arms `requestAnimationFrame` and does it
all again next frame, forever. Meanwhile `statusText` correctly says "end of the recording" while the
Play button reads "Pause", because `scheduler.running` is still true.

The cost is one wasted step per frame, which is nothing. The transport lying about whether anything
is happening is the actual defect, and it lands on the most-shared kind of link — a match somebody
copied mid-game.

**Remediation.** In `GameSession.advance`, stop the scheduler on `AWAITING_INPUT` when
`replay != null`. There is no key that could ever resume it, so parking is the honest state — which
is the same argument `InteractiveBot` makes for playing a fatal move rather than waiting for a key
that cannot come.

---

### B6 — `percent` is duplicated across a module boundary

**Severity:** Low. **Rule:** CC-12 does not reach it; noting for the record.

```kotlin
fun percent(rate: Double): Int = ((rate * 1000).toInt() + 5) / 10
```

appears identically at `match/.../TournamentTable.kt:163` and `ui/.../Chrome.kt:484`, with
near-identical KDocs ("Rounded half-up without touching `kotlin.math`, which is more than this
needs" / "…without `kotlin.math`, which is more than a percentage needs").

This is **fine**. `:ui` may not depend on `:match`'s internals, CC-12 is scoped to same-changeset
siblings, and the function is four tokens. It is listed only because this repo has a habit of
annotating exactly this situation and here it did not: `LabCommand.LADDER_BOARD` says "this is the
third copy of the same twelve", and `BotLadderTest.BUDGET` says "`MatchSetup.DEFAULT_BUDGET_PER_TURN`,
which `:bots` may not import". Both make a duplication legible as a decision rather than an oversight.

**Remediation.** One line on the `:ui` copy naming the other and why they cannot be one.

---

### B7 — Four small things

**Severity:** Low.

- **`Palette.body(slot)`** (`Palette.kt:22`) is `fun body(slot: Int): String = bodyColour(slot)` —
  a pass-through to the companion function with no added behaviour. CC-10's honest test ("if I
  deleted this line, what would observably change?") answers "nothing"; callers can use
  `Palette.bodyColour` directly, as `Chrome.SlotRow.render` already does.
- **Two unread `catch` bindings.** `Match.kt:114` and `Match.kt:234` both bind
  `catch (thrown: Throwable)` and never read `thrown`. `catch (_: Throwable)` says the same thing and
  says it deliberately — which matters here, because both sites are swallowing an exception on
  purpose and the reader should be able to see that the throwable is genuinely not wanted rather than
  accidentally dropped.
- **`index.html`'s boot watchdog inlines `15000`** (`index.html:269`) with no named binding and no
  unit — CC-01's exact shape, in the one file the rule's Kotlin examples do not reach. It is also the
  constant that turns [B1](#b1--a-replay-link-allocates-before-the-guard-that-would-reject-it) into
  the wrong error message, so it is worth a name and a comment saying what the timeout is for.
- **`TournamentRunner.frame(timestamp: Double)`** ignores its parameter, unlike `TurnScheduler.frame`
  and `KeyRepeat.frame` which both use it. That is correct — this runner has no accumulator — but it
  reads as an omission beside its two siblings, and one line saying "no accumulator here; a batch is
  not paced" would close it.

---

## Proposed changes to the standards

These are changes to `docs/Coding-Standards.md` and `CLAUDE.md`, not to code.

### C1 — Scope SW-02 to main sources

Amend SW-02's opening from *"Nothing in `:bots` may call `kotlin.math.ln`, `exp` or `pow`"* to name
the source set and the carve-out. Suggested wording:

> **Nothing in `:bots`' main sources may call `kotlin.math.ln`, `exp` or `pow`.** A test may, and
> exactly one does: `PortableLogTest` uses `kotlin.math.ln` as the oracle `portableLog` is checked
> against, which is the only way to check it — and is what would notice somebody replacing the series
> with `ln` for tidiness.

This also makes the rule mechanically checkable, which it currently is not. Resolves [A2](#a2--sw-02-as-written-forbids-the-only-way-to-test-portablelog).

### C2 — Say what CC-11 means by a banner comment

CC-11's ban reads as absolute and the tree has eighteen violations, so the rule needs to say which
part it objects to. Suggested wording, matching the recommendation in
[A1](#a1--banner-comments-cc-11-names-as-a-habit-not-to-reproduce):

> `//----` **padding** — a run of dashes filling a comment out to the margin — does not survive a
> rename, drifts out of alignment with its siblings and carries no information. A bare section marker
> (`// -- internals`) is fine and is the house style in the longer files.

Then delete the padding from the eighteen sites. If the decision goes the other way and the padding
stays, CC-11 has to say so instead — either is defensible; the current state is not.

### C3 — "A bound that protects an allocation is checked before the allocation"

New rule, or a paragraph under CC-08. `ReplayCodec` and `BotKnob` already state the principle four
times in four KDocs — *"Bounded so a decoder can reject a corrupt payload before allocating from
it"* — and [B1](#b1--a-replay-link-allocates-before-the-guard-that-would-reject-it) is what happens
where it was not applied. Suggested framing:

> A `require` that exists to keep an allocation sane must run **before** that allocation, not
> alongside it. In Kotlin that means the check cannot live in an `init` block whose class allocates
> in a property initializer — property initializers run first. The same argument SW-07 makes about
> `Budget.tryConsume`: charging afterwards makes the limit a record of what already happened.

The parallel with SW-07 is exact and is what makes it worth writing down as a rule rather than fixing
once and moving on.

### C4 — Fix the rule count in `CLAUDE.md`

`CLAUDE.md:22`, "Twenty-six rules" → "Twenty-five", or drop the count.
Resolves [A9](#a9--claudemd-says-twenty-six-rules-there-are-25).

---

## Resolution

Every finding is closed. Where a finding offered several honest options, the one taken and the reason
are given here rather than in the finding, which is left as it was written.

| Id | Sev | What landed |
|---|---|---|
| B1 | High | Three guards, each closing a different half. `MatchSetup.MAX_SIDE = 256` bounds the decoded geometry with the same KDoc sentence the other decoded fields carry. `Board`'s snake-count and cell-count `require`s moved into an `init` block **above** `occupancy` and `bodies`. `Grid` now checks its padded extent in `Long` arithmetic, so `cellCount` can no longer wrap negative and pass every ceiling downstream. Three tests, one of which — a two-billion-square grid — fails with an `OutOfMemoryError` rather than an `IllegalArgumentException` if the ordering ever regresses |
| A4 | High | **Option 2, and the tree-reuse question turned out to be already settled.** `docs/Migration.md` records that reuse was built and benchmarked during the rewrite: about 8 of a turn's 137 nodes survive, plus a soundness wrinkle — `hash` omits `turnIndex`, which is what `maxTurns` terminates on. So option 1 is a rejected design, not an outstanding one. `auxHash` also has a real consumer the KDocs never named: it is what `BoardScratchTest` and `BoardUndoTest` use to assert that undo restores the position bit for bit, which an occupancy-only fingerprint could not do. Both KDocs now say that, and point at the measurement |
| B2 | High | `ui/src/wasmJsTest` and `app/src/wasmJsTest`, 30 tests over the five named units plus the replay fragment. `TurnScheduler.frame` and `KeyRepeat.frame` are `internal` rather than `private`, each saying why; `readReplay` split into a DOM half and a `replayIn(hash)` half. All 30 pass in headless Chrome, so the browser job now runs something only it can run |
| A1 | Med | Padding stripped from all 18. CC-11 amended to name the padding as the thing it objects to and to permit a bare marker — see C2 |
| B3 | Med | Measured at 16 of 20, added as `assertBeats("wallhug", "random", atLeast = 13)` — the same slack of three the other five rungs carry. `BotLadderTest`'s KDoc now says all six rungs are asserted |
| A8 | Med | Count dropped rather than corrected, in both places: "most shipped bots", which never needs editing again. `README.md` and `docs/Bots.md` were already right and are untouched |
| A5 | Med | `TournamentRunner.clear` deleted. The seven accessors kept and labelled as test seams, because the tests reaching through them assert per-actor credit and the trapped-node prior, which the public path cannot express. `PuctTree.averageOf` now says why `selectPuct` computes the same quotient inline — it needs `firstPlay` for an unvisited child — and `ShortestPaths.distanceTo` why `distanceBeside` repeats the read instead of calling it |
| B4 | Med | Made true rather than documented as false. A record with `outcome == null` is verified as a prefix: shorter than the replay is expected, **shorter than the record** is still a divergence, and eliminations are compared inside the recorded turns only. Three tests, and `docs/Match.md` gained a section |
| A2 | Med | See C1 |
| A9 | Low | Count dropped from the routing table |
| A3 | Low | Moved to `ui/prefersDark.kt`, with a KDoc for why both callers read it once rather than watching it |
| A6 | Low | `[legacy/**]` deleted from `.editorconfig`. `.gitattributes` keeps its counterpart, which states its own reason |
| A7 | Low | "all three" |
| B5 | Low | `GameSession.advance` reports `FINISHED` on `AwaitingInput` under a replay, and `play()` treats a parked recording as "again" — without the second half, Play started a scheduler that immediately parked again. `TurnScheduler.Progress.FINISHED` re-worded, and `docs/UI.md` gained the paragraph |
| B6 | Low | Both copies of `percent` now name each other and why they cannot be one |
| B7 | Low | `Palette.body` deleted and its two callers pointed at `Palette.bodyColour`; `catch (_: Throwable)` ×2; `index.html`'s timeout named `BOOT_TIMEOUT_MS` with a comment tying it to B1; `TournamentRunner.frame` says why it ignores its timestamp |
| C1 | — | SW-02 scoped to main sources, naming `PortableLogTest` as the one carve-out and saying that the scope is what makes the rule a `grep` |
| C2 | — | CC-11 now objects to the padding rather than to the marker, with an example of each |
| C3 | — | **New rule [SW-09](../Coding-Standards.md#sw-09--a-bound-that-protects-an-allocation-runs-before-the-allocation)** — a bound that protects an allocation runs before the allocation. Carries both reasons the ordering matters: the exception type changes, and integer arithmetic wraps |
| C4 | — | Done as part of A9 |

### Verification

Everything the original pass could not claim was run, on the same working tree:

| Gate | Result |
|---|---|
| `./gradlew build` | **passes** — 652 JVM tests, 0 failures, plus `ktlintCheck` and `checkModulePurity` across all seven modules and `build-logic` |
| `./gradlew allTests -PbrowserTests=true` | **passes** — 432 tests in headless Chrome, of which 30 are the new `:ui` and `:app` suites |
| SW-08 gzipped bundle | 83,312 bytes against a 1,572,864 budget — 5% of it |

The golden hashes are untouched: nothing here changed a move any bot plays, so `GoldenMoveStreamTest`
and the shipped replay payload reproduce byte for byte. The three new `MatchSetup` and `Grid` bounds
are the only behaviour change reachable from a valid input, and every board the game offers clears
them by an order of magnitude.

## What this audit did not do

The findings above were surfaced without being acted on, per CC-07, and fixed afterwards as a
separate pass on the author's instruction. The *Resolution* section is the record of that second
pass; everything above it is the first, unedited.

Two things were read but not audited, deliberately. `docs/Migration.md` is a phase log and a record
of what was tried and rejected; its statements about the past are outside this pass — though its
record of the tree-reuse measurement is what settled **A4**, which is an argument for reading it
first next time. `styles.css` (545 lines) was read for the load-bearing rules `docs/UI.md` names —
`[hidden]`, the `.arena` grid, `#board`'s outline — all of which are present and correct; the rest of
it is visual design and no rule in `Coding-Standards.md` governs CSS.
