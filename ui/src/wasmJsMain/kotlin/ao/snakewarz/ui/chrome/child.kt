package ao.snakewarz.ui.chrome

import org.w3c.dom.HTMLElement

/**
 * A part of a static block the chrome writes into, by selector rather than by id.
 *
 * The companion of [elementById], for the pieces that are repeated: a scoreboard card's swatch, a
 * seat's knob grid. Absent means the skeleton lost a line, so it fails at boot with the selector
 * that went missing rather than a page later — and so does a line that is still there but is the
 * wrong element, which is what asking for the type buys over a bare `querySelector`.
 */
internal inline fun <reified T : HTMLElement> HTMLElement.child(selector: String): T =
    querySelector(selector) as? T ?: error("the page skeleton is missing $selector")
