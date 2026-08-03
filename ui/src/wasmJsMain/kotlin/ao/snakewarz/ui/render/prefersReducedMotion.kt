package ao.snakewarz.ui.render

import kotlinx.browser.window

/**
 * Whether the reader has asked their system to keep movement to a minimum.
 *
 * The page's own answer to this is in `styles.css`, where every animation is switched off inside one
 * media block. Canvas has no such block, so anything drawn here has to ask — [BoardRenderer] for the
 * effects it runs inside a paint, and the home screen's demo for whether it should be *playing* at
 * all rather than holding on its last position.
 *
 * Read once, at construction, exactly as `styles.css` is applied once: a reader who changes the
 * setting gets what they asked for on the next load. The scheme is not treated this way — see
 * [prefersDark] — because a theme following the sun going down is worth a listener and a body
 * settling on a board is not.
 */
internal fun prefersReducedMotion(): Boolean = window.matchMedia("(prefers-reduced-motion: reduce)").matches
