# S06 — Tournaments and `:lab` learn about maps; the fairness probe

**Modules:** `:match`, `:lab`
**Depends on:** [S04](S04-replay-codec-v3.md), [S05](S05-map-catalogue.md).
**Read first:** [`../Workflow.md`](../Workflow.md), [`../Research-Process.md`](../Research-Process.md).

## Goal

Every measurement the project can take, it can take on a map — and every number it records says which
map it was taken on, including "empty".

There are two silent-failure sites in here. Both rebuild a `MatchSetup` field by field, so a new field
nobody adds is a batch that plays on an empty board while the log says otherwise. **No test catches
either today.**

---

## Step 1 — tournaments

`match/src/commonMain/kotlin/ao/snakewarz/match/tournament/TournamentConfig.kt`

`TournamentConfig(..., walls: IntArray = IntArray(0))`, validated as `MatchSetup` validates it.

`TournamentSchedule.setupFor` (`:71`) passes `walls = config.walls` into `MatchSetup.create`. Nothing
else in the schedule changes: the seat swap and the rotation are about seats, and on a ρ-symmetric map
the seats are already equivalent.

## Step 2 — the two silent-failure sites

**`lab/src/main/kotlin/ao/snakewarz/lab/arena/openingSetup.kt:34`** rebuilds `MatchSetup` field by
field and would silently drop the map. Pass `walls = setup.walls()`, and make `spreadSpawns` reject a
draw that lands on a wall or outside spawn 0's open region. `reflectedPair` (`:87`) is already the
half-turn ρ, so a mirrored opening on a map from [S05](S05-map-catalogue.md)'s generator stays fair
for free.

**`lab/src/main/kotlin/ao/snakewarz/lab/log/RunHeader.kt:38` — `comparabilityKey` must carry the
map**, or a batch on `CROSS` pools with a batch on an empty board and every rating conflates two
games. This is the single highest-value line in the session: the research protocol's standing rule is
*"say which map a number was taken on — including 'empty'."*

**Derive the key from the wall array, never from a shape name**, so it cannot disagree with what was
actually played:

```
"empty"  when the array is empty
"${wallCount}w${fingerprint.toString(16).take(8)}"  otherwise
```

## Step 3 — the flag

`lab/src/main/kotlin/ao/snakewarz/lab/LabCommand.kt`

Add `"map"` and `"density"` to `PLAY_FLAGS`, `TIME_FLAGS`, `AB_FLAGS`, `TUNE_FLAGS`, `SPSA_FLAGS`, and
`"map"` to `RATE_FLAGS` **and `RATE_FILTERS`** — a map is a thing that narrows the log, exactly as
`board` and `budget` are.

`--map <shape>` resolves through `MapShape` and generates at the run's `--rows`/`--cols` and `--seed`.
An unknown shape errors with the list of known ones, the way `entryOf` already does for a bot id.
`--map empty` is the default and must produce a run byte-identical to one with no flag at all.

Extend `USAGE`, and mirror it in [`../Workflow.md`](../Workflow.md) (that is [S18](S18-docs.md)'s job,
but note it here so it is not forgotten).

---

## Step 4 — the fairness probe, and it comes before any strength number

An asymmetric map turns seat advantage into map advantage, and a field run on one measures the map.
Run a **null-strength pairing** — two identical entrants — over every shape in the catalogue and read
the seat win-rate:

```bash
./gradlew :lab:run --args="play uct uct --map cross --rows 12 --cols 12 --rounds 100 --openings mirrored"
```

It should sit on 50%. Given [S05](S05-map-catalogue.md)'s ρ-invariance and
[S03](S03-match-header.md)'s min/max-open-index spawn rule, **it should sit there by construction** —
so anything else is a bug in the generator, not a finding. Chase it before recording a single strength
number on that shape.

**Read the distinct-games line the batch prints before you read anything else.** A fixed map plus
fixed spawns plus two bots that draw no randomness is four distinct games however many rounds are
asked for. `--openings mirrored` is the default and is why.

## Step 5 — does the ladder survive a map

Every rung of the shipped ladder was certified on an empty rectangle. Re-run the field on each shape,
one fit per geometry, and **state the field composition beside every rating** — a single Elo fitted
over an intransitive cycle is field-composition-dependent.

Board-size intransitivity is already proven here: `alphabeta:eval=territory` rates +131 above bare
`puct` at 8×8 while **losing** the head-to-head 89–111, where at 12×12 it wins 70.5%. **Map topology
should be expected to be a second axis of the same phenomenon**, so the prior is that it also inverts
something. Record what, if so; do not treat an inversion as a defect.

This step produces a written finding, not a code change. It is the input to
[S16](S16-ladder-table.md)'s level ordering.

---

## Tests

`lab/src/test/kotlin/ao/snakewarz/lab/`
- **`openingSetup` preserves `walls`** — the test that does not exist today and is the reason the field
  gets dropped.
- `openingSetup` never places a spawn on a wall, and never outside spawn 0's region.
- `comparabilityKey` differs for two different maps of the same size, and reads `empty` for none.
- `LabCommandTest`: `--map` parses, an unknown shape errors with the known list, `--map empty` is
  identical to no flag.

`match/src/commonTest/.../tournament/` — `TournamentSchedule.setupFor` carries the map into every
seating.

---

## Done when

```bash
./gradlew build
./gradlew :lab:run --args="play uct uct --map cross --rounds 100 --openings mirrored"
./gradlew :lab:run --args="play uct uct --map rooms --rounds 100 --openings mirrored"
```

Seat win-rate at 50% on every shape in the catalogue, and `rate` reporting the map beside every
figure.
