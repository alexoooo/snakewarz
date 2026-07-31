# Maps

**For:** adding a map shape, or touching `match/map/`.
**Assumes:** [`../CLAUDE.md`](../CLAUDE.md) — the module graph, the forbidden dependency edges and
the five non-obvious facts live there and are **not repeated here**. Fact 5, that an interior wall is
the border ring's byte and that `BoardView.openCount` rather than `Grid.playableCount` is the
denominator, is the one this document is downstream of.
**Read first if you are about to measure anything on a map:**
[`Workflow.md`](Workflow.md#a-map-is-a-different-game-not-a-harder-one).

## A map is a wall set; a shape is how one is made

Two types, and the split between them is what the replay format rests on.

- **`BoardMap`** is a materialised wall set: which squares of a `rows x cols` board are permanently
  impassable, as *playable* indices `row * cols + col`, strictly ascending. That is the same canonical
  form `MatchSetup.walls` is validated in, so `walls()` feeds a setup with no conversion. `BoardMap`
  demands ascending and in-range and **nothing else** — a hand-drawn fixture is legitimately neither
  symmetric nor connected, and a map decoded from a stranger's replay is whatever they played on.
- **`MapShape`** is a recipe: a function of `(rows, cols)`, so the same name means the same *idea* at
  8x8, 12x12, 20x20 and 40x40. That is what lets one research field be run per geometry per map.

**Nothing outside `match/map/` ever sees a shape.** `MatchSetup` takes an `IntArray`, the codec
carries the bitmap itself, and the consequence is the freedom worth protecting: **a shape can be
redesigned or deleted without breaking a single link anybody has shared.** A `MapShape.slug` is still
frozen on release under [SW-05](Coding-Standards.md#sw-05--released-identifiers-are-frozen), because
it reaches a `:lab` flag, a `:ui` picker and a gauntlet level — but not because it is in a URL, because
it is not.

## The catalogue

Eleven shapes, in the order `MapShape` declares them. `minimumSide` is the smallest board the shape can
express itself on; `generateMap` refuses anything smaller **by name** rather than emitting a
degenerate map, because a cross with no arms and a spiral with half a turn both look like bugs in the
*game* rather than like small versions of themselves.

| slug | min side | the picture | what it is *for* |
|---|---|---|---|
| `empty` | 1 | a bare rectangle | the incumbent, and the neutral setting every wall test is measured against |
| `arena` | 8 | a solid block at the centre and four satellite squares | the most open thing here after `empty`: a game about **position** rather than about corridors |
| `pillars` | 5 | lone squares on a lattice of period 3, inset one from the border | barely changes how the board plays and changes its **colour balance**, which is what `ChamberEval`'s parity term is about |
| `pinwheel` | 11 | four straight arms turning about the centre, none of them meeting | wide lanes, and the open positional board a person can read |
| `ring` | 7 | a hollow rectangle inset from the border, one gap a side | an inside and an outside |
| `cross` | 7 | one horizontal and one vertical bar, broken across the middle | four rooms joined at a chokepoint — and the shape that **lifts a room-filler enormously** |
| `diagonals` | 10 | parallel anti-diagonal bars, each opened three or four squares wide at its middle | breaks the axis-aligned assumption in `MovePrior`'s wall reading and in `SurvivalHorizon`'s Manhattan proxy for tail distance |
| `rooms` | 14 | chambers on a grid of corridors, a doorway two or three squares wide per shared wall | where a chamber decomposition earns its keep |
| `double-spiral` | 13 | two interleaved arms winding out from the centre, two squares of corridor between them | one long corridor: close to pure space-filling, parity dominates, and **more search is worse** |
| `scatter` | 5 | isolated squares to a requested density | the randomiser — for a field that is not four games |
| `islands` | 9 | isolated blocks of mixed size to a requested density | `scatter` with something to hide behind, and the second board that is different every seed |

`scatter` and `islands` are the only shapes that read `seed`, and the only ones that read `density`.
Everything else is a function of the geometry alone, which is exactly what makes a level a board a
player learns rather than a fresh one each time. `islands` ships at a lower default density than
`scatter`: a block keeps a free square from every edge, so it has the interior to work in and not the
whole board.

**Two shapes have their KDoc reasons on the geometry itself**, and both are the kind of thing that
looks arbitrary until it is stated:

- **`double-spiral` has two arms and not one** because a single spiral is *chiral* — it cannot be
  invariant under the half turn, so it could not be fair under the rule below. Two arms, each the
  other's image, can be.
- **`diagonals` is the anti-diagonal family and not the main-diagonal one**, for the same reason. A
  bar `row + col == k` maps under ρ to the bar `row + col == rows + cols - 2 - k`, so the family can
  be invariant; a *main*-diagonal bar maps to itself only under a reflection.

## The two guarantees, and where they live

`generateMap` is the only way to *make* a map, and it exists so the guarantees live in one place where
a new shape inherits them rather than having to restate them. It checks four things and every one is
a `check`, not a comment: a shape that breaks one is a defect and must not reach a match.

### ρ-symmetry, and why it is the half turn rather than a mirror

Every placement a shape makes is mirrored through

```
ρ(row, col) = (rows - 1 - row, cols - 1 - col)
```

by `HalfBoard`, which places at `(row, col)` and at its image in the same call. **Symmetry is a
property of the instrument rather than of each shape**, so it cannot be got wrong one shape at a time.

The half turn and not a mirror, because of what it does to a row-major index: `i` maps to
`rows * cols - 1 - i`. So the **lowest and the highest open squares are exact images of each other** —
and those are precisely where `mostDistantSpawns` seats slot 0 and slot 1. **A two-seat opening on a
map from here is fair by construction rather than by measurement.** A vertical mirror maps `(0, 0)` to
`cols - 1` instead, and the corner rule would not be fair under it; that is the whole argument, and it
is why the catalogue ships the half turn and nothing else. `:lab`'s mirrored openings compute the same
ρ, so they stay fair on these maps too.

`generateMap` asserts both halves anyway — `checkSymmetric` that ρ maps the wall set onto itself, and
`checkEndsPair` that the ends pair — even though `HalfBoard` makes the first structurally impossible
to break. A property the opening's fairness rests on is asserted rather than inferred from how it was
built. `checkEndsPair` *is* the fairness claim, restated as an executable sentence.

The recipe is usually described as "draw the top half and reflect", and `HalfBoard.halfRows` is that
half. Mirroring per **placement** is the same set and one constraint weaker: a spiral arm is a single
connected curve that crosses the middle, so it cannot be expressed inside one half at all.

### One region, which is stronger than "the spawns can reach each other"

`checkOneRegion` floods from the lowest open square and demands it reach **every** open square. What
that forbids is a sealed decorative pocket — dead board no snake can enter, which every share of the
board is then quietly taken against, in every appraisal and every rating.

Each shape earns it differently, and the argument matters more than the check:

- `pillars` and `scatter` place only **isolated** squares — `HalfBoard.placeIsolated` refuses any
  placement touching an existing wall in the eight-neighbourhood — and a rectangle cannot be
  disconnected by isolated single squares. There is no retry loop and no resampling: every candidate
  in the board's first half is offered exactly once in a shuffled order, so the walk terminates
  whatever is asked of it. A density the rule cannot reach therefore **fails, reporting what it
  managed**, rather than sampling until it passes.
- `islands` places isolated *blocks*, and two blocks between them **can** cut a rectangle in half, so
  the argument has to be made differently: `HalfBoard.placeIsolatedBlock` also keeps a free square
  between every block and every edge. A free border ring plus rectangles that never touch is enough —
  the ring of squares immediately round a block is entirely open and 4-connected, so a walk blocked
  going up follows it to a strictly lower row and repeats until it reaches row 0. The shape offers
  every block size at each candidate rather than only the one it drew, which is what keeps a density
  it has the room for from failing on a *seed*.
- `arena` places its centre block and its satellites through the same method, so it inherits that
  argument rather than making one. The placements are a function of the size alone, so a board that
  cannot hold them is a `minimumSide` that is wrong: they are `check`s.
- `ring` and `cross` are drawn with their gaps in them.
- `diagonals` opens every bar on its middle, and ρ reverses a bar end for end, so a run centred on
  the middle is the one that lands on the image bar's opening. The openings line up into a corridor
  along the board's other diagonal. A bar no longer than its own opening simply does not appear.
- `rooms` puts a doorway on the middle of every band a wall line crosses, so the chamber graph *is*
  the grid. Connectivity is a construction, not a check that happened to pass.
- `pinwheel`'s four arms stop short of each other at every corner — the horizontal arm ends on the
  centre column and the vertical arm stands past it — so the loop they nearly draw is broken four
  times and encloses nothing.
- `double-spiral`'s arm visits each inset on **one** side only, and its image visits that inset a half
  turn away — so an inset is covered on two opposite sides at most, and an opposite pair encloses
  nothing.

The band count in `rooms` is forced **odd** for a parity reason worth knowing: that puts a *room* at
the centre of the board rather than a wall. A wall through the centre is one square thick on an odd
side and two on an even one, so the middle chamber of a 12-row board would be a different shape from
the middle chamber of a 13-row one.

**A doorway wider than one square is two-or-three and never a flat two, and `diagonals`' opening is
three-or-four for the same reason.** ρ reverses a band or a bar end for end, so only a run *centred*
on its middle lands on its own image. An even-length band has no single middle square, so a centred
run in it has even length; an odd-length one does not. A flat two on an odd band would put its
doorway a square off its own image, and the mirror would open a second doorway rather than the same
one. That is also what sets both shapes' `minimumSide`: a band or a bar no wider than its own opening
leaves no wall behind at all, and a lattice of crossing points is not `rooms`.

## Adding a shape

Seven steps, and the first four are one file each.

1. **Add the enum constant** to `MapShape` with its slug and `minimumSide`. Lowercase letters, digits
   and hyphens — `BotId`'s charset, safe in a URL and in a filename without escaping — and
   deliberately not the enum name lowercased, so the whole project spells an identifier one way. Name
   it for what it **looks like**, never for lineage.
2. **Draw it** in `mapCatalogue.kt`: one private function, one branch in `drawShape`. Draw onto
   `HalfBoard` and let the mirror do the symmetry; do not try to be symmetric by hand.
3. **Add its `<option>`** to the map picker in `index.html`. `SetupPanel` looks each shape's option up
   by slug at boot and **fails with the shape's own name** if it is missing, so this is a startup
   error rather than a picker that quietly offers ten of eleven.
4. **Add the same `<option>` to `SetupPanelTest.SKELETON`.** That skeleton is the page the browser
   suite builds the panel against, and the lookup above runs in its constructor — so a shape missing
   from it fails *every* case in that file, at construction, naming the shape. It is the one step
   that is not obvious from the change and the one a green JVM run will not tell you about.
5. **Nothing else.** `GenerateMapTest` sweeps `MapShape.entries` at every size, so the enum entry is
   what enrols the shape in the symmetry, one-region, ends-pair and determinism tests. `:lab`'s
   `--map` lists it. `rate --map` narrows to it. No codec change, ever — a shape is not in a replay.

Then the two things a new shape has to answer for before a number is quoted on it:

6. **Check what a lattice anchor does.** A period-`p` lattice is ρ-invariant only at the right offset;
   `symmetricAnchor` solves `2a ≡ extent - 1 (mod p)` by trying every residue. Start a pattern at a
   fixed offset instead and the mirrored half lands *between* the drawn half's rows — symmetric, and
   visibly not one lattice. **An even period costs a board whose two sides differ in parity**: `2a` is
   even, so `extent - 1` has to be, and no residue survives where it is not. `diagonals` pays that
   price for a spacing of six — it refuses a 12x13 board by name — and every board the picker, the
   gauntlet and the sweep offer is square.
7. **Measure it, and expect an inversion.** See below.

## A shape is a new question, not a new difficulty

**A map changes which bot is stronger, not merely by how much**, and a new shape should be assumed to
invert something until measured. On `cross` the top pair inverts and the field compresses from 979 Elo
to 479 while `wallhug` gains about 400; on `double-spiral` at 16x16, `puct` at a quarter allowance
beats itself at the full one 77–23 and loses 23–77 on a bare board of the same size.

> **Both numbers were taken before 2026-07-31, and one of the two shapes has been redrawn since.**
> `cross` is unchanged, so its figure stands. `double-spiral`'s corridors alternated one and two
> squares and are now a uniform two — which is precisely the property the 77–23 rested on, a corridor
> tight enough to turn the game into a filling race. Treat that figure as the reason to measure a
> redraw rather than as a reading of the shape that ships today. The same applies to `rooms` and
> `diagonals` wherever a number is quoted on them: three shapes were redrawn and three added in one
> change, and `Gauntlet`'s KDoc carries which of its placements are consequently guesses.

That is why the gauntlet's map assignments are measurements rather than a difficulty ramp —
`Gauntlet`'s KDoc names the two that look misplaced without the run behind them — and why
`RunHeader.comparabilityKey` carries the map so `rate` refuses to pool across it.
[`Workflow.md`](Workflow.md#boards-with-walls-in-them) is what to read before running the batch, and
[`Bots.md`](Bots.md#the-single-player-gauntlet-is-a-different-ordering-and-it-is-measured-per-level)
carries the numbers.
