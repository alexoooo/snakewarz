package ao.snakewarz.ui.chrome

import kotlinx.browser.document
import org.w3c.dom.Element

/**
 * The one way this module reaches the page.
 *
 * Every handle is taken once, at construction, so a missing id is a hard failure at boot rather than
 * a null somewhere much later — which matters because the skeleton lives in `index.html` and nothing
 * in Kotlin can check it.
 */
internal inline fun <reified T : Element> elementById(id: String): T =
    document.getElementById(id) as? T ?: error("index.html is missing #$id")
