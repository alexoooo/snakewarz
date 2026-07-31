# S12 — The `Portraits` seam and eleven SVGs

**Modules:** `:ui`, `:app`
**Depends on:** [S11](S11-themes.md).
**Read first:** `ui/.../model/ReplayLink.kt` — the seam this session copies.

## Goal

Every opponent has a face, and `:ui` still cannot tell a wall hugger from a human.

---

## Step 1 — the seam

`:ui` may never depend on `:bots`. It already names one bot with a bare string —
`Chrome.DEFAULT_OPPONENT = "uct"`, deliberately *"a slug, and a preference rather than a
requirement"* — but a hard-coded table of eleven slugs would be a different thing entirely.

So art is injected, exactly as the replay link is:

`ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/model/Portraits.kt`

```kotlin
/**
 * Where a bot's picture comes from, answered by whoever knows what is deployed beside the page.
 *
 * `:ui` cannot depend on `:bots` and does not want to: it asks by slug and takes a URL or nothing,
 * so a registry it has never heard of still gets faces, and a bot contributed tomorrow still gets
 * one. The fallback for `null` is drawn here and needs no assets at all.
 */
public fun interface Portraits {
    public fun urlFor(slug: String): String?
}
```

This is the **third and last** public declaration of `:ui`, alongside `GameSession` and `ReplayLink`.
`GameSession`'s constructor takes it.

## Step 2 — the art

`app/src/wasmJsMain/resources/portrait/<slug>.svg` — eleven files:

```
random  burninhell  wallhug  space  pressure  chase
flat-monte-carlo  uct  puct  alphabeta  human
```

**The house style is already set by `app/.../favicon.svg`**: chunky flat rectangles on a rounded dark
tile, in the snake ramp (`#2b6046 → #7fe0a8`). Portraits follow it rather than inventing a second
visual language. Square `viewBox`, no gradients, no external references, no text — they render at
~48 px on a player card and ~96 px on a ladder tile.

Draw each one for **how the bot plays**, since that is the only thing a player can learn from it:
`wallhug` spirals, `burninhell` sweeps in columns, `space` is an open field, `chase` is an arrow at a
head, `uct`/`puct` are branching trees, `alphabeta` is a pruned one. `human` is the odd one out and
should read as *you*.

`:app` implements the seam:

```kotlin
Portraits { slug -> if (slug in SHIPPED_PORTRAITS) "portrait/$slug.svg" else null }
```

A relative path, because the page is served from a GitHub Pages subdirectory and an absolute one would
break there.

SW-08: eleven flat SVGs are on the order of a kilobyte each and are negligible against the 1.5 MiB
gzipped ceiling — but check anyway, since this is the session that adds assets.

## Step 3 — the fallback

`ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/render/identicon.kt`

A deterministic mark from the slug, drawn in the slot's theme colour: hash the slug, use the bits to
fill a mirrored 5×5 block grid, emit an SVG `data:` URI. Mirroring is what makes a random mask read as
a *face* rather than as noise, and it is four lines.

Determinism is not decoration here: the same bot must get the same mark in every match, on both
targets, forever. Use a plain integer hash written out in the file — **not** `String.hashCode()`, which
is not specified identical across Kotlin targets.

## Step 4 — where they appear

- **Player cards** in the game screen's bottom bar, beside the name and length.
- **Ladder tiles** — [S17](S17-ladder-screen.md) consumes this.
- **The result dialog**, beside who won.
- The bot pickers in `#panel-setup` are `<select>`s and get nothing. A styled listbox would be a custom
  widget, and every custom widget is a keyboard-accessibility bill; the names are enough.

A portrait is `aria-hidden` and always sits beside the bot's name in text. It is decoration, not
information — a screen reader should hear "PUCT — 1k/territory", not "image".

---

## Tests

- `IdenticonTest` — the same slug yields the same mark twice; two different slugs differ; the output
  parses as an SVG data URI; the hash is target-independent (assert a literal for a known slug, so a
  JVM/wasm divergence fails the build).
- A `Portraits` seam test: an unknown slug falls back to the identicon rather than to a broken image.
- Eleven files exist and every shipped slug resolves — a `:app` test that walks `ShippedBots` plus
  `PlayableRegistry.HUMAN_ID` and asserts a non-null URL for each. This is the test that catches a bot
  added later with no art, which is exactly the case the fallback exists for and the case somebody
  should still be *told* about.

---

## Done when

```bash
./gradlew build
./gradlew :app:wasmJsBrowserDistribution   # SW-08
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

In the browser: every seat shows the right face in both themes and both schemes; a four-way match
shows four distinct ones; and a temporary bogus slug renders an identicon rather than a broken image.
