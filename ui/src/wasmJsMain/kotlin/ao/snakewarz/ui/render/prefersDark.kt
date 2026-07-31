package ao.snakewarz.ui.render

import kotlinx.browser.window

/**
 * Whether the reader has asked their system for a dark interface — the system's half of a [Theme].
 *
 * Asked by `GameSession` and by nobody else, which is what keeps the canvas and the page in step:
 * there is one `Theme` instance per frame and both are painted from it, so a scheme that flipped
 * between two readings cannot leave the board lit one way and the panel the other. The `matchMedia`
 * listener `GameSession.start` registers is what makes the sun going down recolour the theme the
 * player chose rather than needing a reload.
 */
internal fun prefersDark(): Boolean = window.matchMedia("(prefers-color-scheme: dark)").matches
