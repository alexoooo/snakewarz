# S11 — `Theme` replaces `Palette`

**Modules:** `:ui`, `:app`
**Depends on:** [S09](S09-game-screen.md).
**Read first:** `ui/.../render/Palette.kt` — its class KDoc is the argument this session must not
break.

## Goal

Named graphical themes, chosen by the player and remembered, covering both the canvas and the page.

`Palette` today is *"colour, keyed by slot index and by nothing else"*, in two variants selected by
`prefers-color-scheme`. A theme is a second axis over that, not a replacement for it.

---

## Step 1 — the type

`ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/render/Theme.kt`

```kotlin
internal class Theme private constructor(
    val id: String,          // frozen: it is written to localStorage and read back
    val displayName: String,
    val background: String,
    val gridline: String,
    val wall: String,        // from S07
    val wallEdge: String,
    private val heads: Array<String>,
    private val bodies: Array<String>,
) {
    fun head(slot: Int): String = heads[slot % heads.size]
    fun body(slot: Int): String = bodies[slot % bodies.size]

    companion object {
        const val CORPSE_ALPHA: Double = 0.28
        const val AGING_ALPHA: Double = 0.70
        const val DYING_ALPHA: Double = 0.42

        val ALL: List<String>                               // ids, for the picker
        fun of(id: String, dark: Boolean): Theme            // unknown id -> the default
    }
}
```

Three themes × light/dark. Keep it to three: six palettes that all read well on both schemes is real
work, and a fourth adds nothing the first three did not.

**Colours still cycle past the last hue rather than being generated.** Six distinguishable ones are
more than any playable match needs, and a generated palette lands two snakes on adjacent hues about as
often as not. Do not replace the array with a hue function.

The three alpha constants are rules about *the game*, not about a theme — a corpse must be visible but
must not compete with a living snake, and a square about to open is faded in two steps because
`growEveryNthMove` makes it knowable a move ahead. They stay in the companion and do not vary by theme.

## Step 2 — the regression this creates

`Palette.bodyColour(slot)` is a **static** call, and `Chrome.SlotRow` (`:438`) paints its swatch with
it precisely *because* body colour is theme-independent today — which is why the swatches never repaint
on a theme change.

A theme can move body colour. So the player cards must repaint when the theme changes. Either route
the colour through `UiModel` (preferred — it is already built once a frame) or call `render` again on
theme change. **Do not leave the static call and hope**; the symptom is swatches that keep the old
theme's colours until the next frame that happens to redraw them, which is intermittent and looks like
nothing.

## Step 3 — wiring

- `BoardRenderer.applyScheme(dark)` becomes `applyTheme(theme)`. Its caller already follows with
  `fit`, because the gridlines change colour too — keep that.
- `GameSession.start` already registers a `matchMedia("(prefers-color-scheme: dark)")` change listener
  that calls `applyScheme(prefersDark()); refit()`. It now recomputes the theme at the stored id and
  the new scheme. Keep `render/prefersDark.kt` as the scheme source.
- CSS gets `data-theme` on `<html>` and reads its custom properties from
  `:root[data-theme="..."]`, with the existing `@media (prefers-color-scheme: dark)` block kept for the
  scheme half. The canvas colours and the CSS custom properties are two views of one theme and **must
  be generated from one source** — put the values in `Theme` and have Kotlin write the custom
  properties, or duplicate them and add a test that they agree. Do not just write them twice.

## Step 4 — persistence

`ui/src/wasmJsMain/kotlin/ao/snakewarz/ui/chrome/Preferences.kt`

```kotlin
internal object Preferences {
    fun theme(): String?
    fun setTheme(id: String)
}
```

`localStorage["snakewarz.theme.v1"]`, versioned in the key. Reading must survive a missing key, a
value from a future version, and `localStorage` throwing outright — Safari in private browsing does
throw, and a boot that dies on a theme lookup is a black page. Wrap it and fall back to the default
(CC-08's fail-fast rule does not apply to a preference: there is a correct thing to do).

The picker lives in `#panel-settings` from [S10](S10-panels.md), as static markup with static options —
`Theme.ALL` is not a `BotRegistry`, so this is not a third exception to *"Kotlin never constructs
structure"*.

---

## Tests

- `ThemeTest` — every theme, under both schemes: the wall colour differs from `background`, from
  `gridline` and from every body hue; head and body differ per slot; six distinguishable body hues;
  an unknown id falls back to the default.
- `PreferencesTest` — a missing key, a junk value and a throwing `localStorage` all yield the default.
- The existing `PaletteTest` is superseded; port its assertions rather than deleting them.

---

## Done when

```bash
./gradlew build
./gradlew :app:wasmJsBrowserDevelopmentExecutableDistribution
py -m http.server 8099 --bind 127.0.0.1 \
   --directory app/build/dist/wasmJs/developmentExecutable
```

In the browser: switch theme mid-match and confirm the board, the walls, the player-card swatches and
the page chrome all change together; reload and confirm the choice stuck; flip the OS to dark and
confirm the same theme adapts rather than resetting; run a batch tournament and confirm the overlay
decorations survive the per-frame repaints.
