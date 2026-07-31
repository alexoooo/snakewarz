package ao.snakewarz.ui.chrome.panel

import kotlin.random.Random

/**
 * A seed for a new match, short enough to read out loud.
 *
 * `kotlin.random.Random` is banned below `:ui` because a replay format must not depend on the
 * standard library's algorithm staying stable across Kotlin versions and targets. Nothing about that
 * applies here: this picks a *number*, the number is written into the header, and every match played
 * from it runs on the project's own `SplitMix64`. Where the number came from is not a fact the
 * replay depends on.
 */
internal fun freshSeed(): Long = Random.nextLong(1, 1_000_000_000)
