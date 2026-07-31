# S04 — `ReplayCodec` v3, and the bound that has to move

**Module:** `:match`
**Depends on:** [S03](S03-match-header.md).
**Read first:** [`../Match.md`](../Match.md) §*"Replays arrive from strangers"*, and **SW-09** in
[`../Coding-Standards.md`](../Coding-Standards.md).

## Goal

A mapped match survives a URL. A mapless one encodes to exactly the bytes it always did.

## Why the bitmap travels, and why it is raw

The map is recorded, not named. `MatchSetup`'s KDoc already argues the general case for the spawns:
*"Deriving them would save a dozen bytes and stake every replay ever shared on the placement algorithm
never changing again."* The same holds here, and it buys something worth writing down: **because the
bitmap travels, a map shape can be redesigned or deleted without breaking a single shared link.** A
`MapShape` id in the payload would buy the opposite.

**Raw and bit-packed, not run-length encoded.** RLE was measured and is worse on the shape most likely
to ship first: a 20×20 pillar lattice (`.#.#.#…`) is ~20 runs a row → ~400 varints → **400 bytes**,
against a raw bitmap's **50**. The raw form is also:

- the convention `DirectionStream` already uses — `shift = (index and 0b11) shl 1`, item 0 in the low
  bits — and the same `ceil` idiom the codec already reads with, `input.bytes((moveCount + 3) shr 2)`.
  **No new primitive.**
- **bounded by construction**: its length is computed from an already-validated `rows`/`cols`, so the
  field cannot lie about its own size.

Sizes: 8 bytes on 8×8, 18 on 12×12, 50 on 20×20 (+67 base64 characters), 200 on 40×40. Comfortably
inside the existing budget — `ReplayCodecTest.kt:221` asserts a 20×20 match encodes under 400
characters.

---

## Step 1 — constants

`match/src/commonMain/kotlin/ao/snakewarz/match/replay/ReplayCodec.kt`

```kotlin
/** Bumped only for a layout change. A decoder rejects anything it does not recognise. */
public const val FORMAT_VERSION: Int = 3

/** The oldest layout still written, for a record with nothing per-slot to say and no map. */
private const val UNCONFIGURED_VERSION: Int = 1

/** What a configured record on an empty board is written at, which is what it always was. */
public const val CONFIGURED_VERSION: Int = 2

private const val CONFIGURED: Int = 1   // flags bit 0
private const val MAPPED: Int = 2       // flags bit 1
private const val KNOWN_FLAGS: Int = CONFIGURED or MAPPED

/** The oldest version that can express [flags]. A future bit 2 is one more line here and nowhere else. */
private fun versionFor(flags: Int): Int = when {
    flags and MAPPED != 0 -> FORMAT_VERSION
    flags and CONFIGURED != 0 -> CONFIGURED_VERSION
    else -> UNCONFIGURED_VERSION
}
```

`CONFIGURED_VERSION` is **public** because `ReplayCodecTest.kt:180` needs it (below), and because
"the version a configured record writes" is a fact about the format rather than a private detail.

## Step 2 — the wire layout

The KDoc block at `:27-41` gains two lines. The map block sits **with the geometry and before the
configuration block**, so a decoder rejects a bad board before it walks a knob table.

```
version : byte           flags : byte -- bit 0: per-slot configuration, bit 1: an obstacle map
rows-1  : varint         cols-1  : varint          seed : 8 bytes, little endian
growEveryNthMove, maxTurns, budgetPerTurn, slotCount : varint
per slot : varint slug length, slug bytes
per slot : varint spawn (row * cols + col)
per slot : varint turn order entry
if MAPPED:
    ceil(rows * cols / 8) bytes -- bit (i and 7) of byte (i shr 3) is set when playable square i
    is wall, low bit first, exactly as DirectionStream packs a move
if CONFIGURED:
    ... unchanged ...
... unchanged ...
```

## Step 3 — encoder

```kotlin
val flags = (if (setup.configured) CONFIGURED else 0) or (if (setup.mapped) MAPPED else 0)
out.byte(versionFor(flags))
out.byte(flags)
```

A mapless match therefore writes version 1 or 2 exactly as today. This is the whole reason
`SHIPPED_PAYLOAD` is untouched.

## Step 4 — decoder, and the bound that has to move

The version and flag gates generalise, preserving today's behaviour (a v1 payload claiming
`CONFIGURED` is refused) so that a future flag is one `when` branch (CC-03):

```kotlin
val version = input.byte()
require(version in UNCONFIGURED_VERSION..FORMAT_VERSION) {
    "replay format version $version is not supported"
}

val flags = input.byte()
require(flags and KNOWN_FLAGS.inv() == 0) {
    "replay flags byte $flags holds a bit this decoder does not know"
}
require(version >= versionFor(flags)) {
    "replay flags byte $flags is not recognised at format version $version"
}
```

**Now the SW-09 hazard, which does not exist today.** `ReplayCodec.kt:148-149` reads `rows`/`cols` as
varints, and they are validated against `MAX_SIDE` only inside `MatchSetup.init`, at `:210` — the very
last line. Nothing between allocates from the geometry, which is the *only* reason *"a payload
claiming a huge board is refused rather than allocated for"* passes today. A map block allocates
`(rows * cols + 7) ushr 3` bytes **in the middle**, from an unvalidated geometry a 28-bit varint can
set to 268 million. So the bound moves to the read site:

```kotlin
val rows = input.varint() + 1
val cols = input.varint() + 1
require(rows in 1..MatchSetup.MAX_SIDE && cols in 1..MatchSetup.MAX_SIDE) {
    "a replay claims a ${rows}x$cols board"
}
```

That is SW-09 exactly: *a bound that protects an allocation runs before the allocation*, and its
*integer arithmetic wraps* clause. Then:

```kotlin
val playable = rows * cols                     // safe: both sides <= MAX_SIDE, checked above
val walls = if (flags and MAPPED == 0) IntArray(0) else {
    val bitmap = input.bytes((playable + 7) ushr 3)   // Reader.bytes already bounds against remaining
    // Every bit above the last square must be zero, or two payloads describe one map and
    // encode(decode(x)) stops being x.
    require(
        playable and 7 == 0 ||
            bitmap[bitmap.size - 1].toInt() and (-1 shl (playable and 7)) and 0xFF == 0
    ) { "the obstacle map has bits set past the last square of a ${rows}x$cols board" }

    var count = 0
    for (i in 0 until playable) if (bitmap[i shr 3].toInt() shr (i and 7) and 1 == 1) count++
    IntArray(count).also { /* second pass; ascending by construction */ }
}
```

Two passes, so the allocation is exactly sized and is bounded by an already-validated geometry. That
is the whole of SW-09's *"a check that runs after the array is a check that has already lost"*.

Finally, `MatchSetup(...)` at `:210` becomes a **named-argument** construction with `walls = walls` —
forced by S03's parameter insertion, and a strict improvement on ten positional arguments.

---

## Tests

**`ReplayCodecTest.kt:180` breaks and must be repointed.** It reads

```kotlin
assertEquals(ReplayCodec.FORMAT_VERSION.toByte(), bytes[0], "a configured record is written at version 2")
```

A configured-but-mapless record still writes **2** while `FORMAT_VERSION` becomes 3. The test reads
"the version constant" where it means "the configured version": point it at
`ReplayCodec.CONFIGURED_VERSION`.

New cases:
- a mapped record round-trips;
- a mapped record writes version 3, while an unmapped one still writes 1 or 2;
- a v2 payload claiming `MAPPED` is refused;
- an unknown flag bit is refused;
- **a `MAPPED` payload declaring a huge geometry is refused before the map block allocates.** The
  declared geometry must be one that would ask for gigabytes, and the assertion must be
  `IllegalArgumentException` — that is what `:app` catches, and `OutOfMemoryError` is the failure this
  test exists to prevent;
- a map byte with bits set past the last square is refused;
- a mapped 20×20 payload's length is asserted against its own stated ceiling;
- the fuzz loop at `:55` generates a map about half the time, exactly as it already does for
  `configured`.

---

## Done when

```bash
./gradlew :match:jvmTest
./gradlew build
```

and **`SHIPPED_PAYLOAD` still holds in both directions**:

```
"AQAJCdUHAAAAAAAAAoAgwLgCAgVjeWNsZQVzb3V0aABjAAECBQABAQA"
```

It is deliberately historical. **Do not regenerate it to make a change pass** — the comment above it
at `ReplayCodecTest.kt:190-191` says so, and it is the single assertion standing between this change
and every shared link in existence.