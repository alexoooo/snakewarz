package ao.snakewarz.ui

import kotlinx.browser.window

/**
 * Whether the reader has asked their system for a dark interface.
 *
 * Read once at construction by both `BoardRenderer` and `GameSession` rather than watched, because
 * the two must agree: the canvas and the chrome pick their colours independently and a theme that
 * changed between the two calls would leave the board lit for one scheme and the panel for the
 * other. Switching themes mid-match is a reload, which is the honest cost of not having a listener.
 */
internal fun prefersDark(): Boolean = window.matchMedia("(prefers-color-scheme: dark)").matches
