package ao.snakewarz.ui.chrome

import org.w3c.dom.HTMLImageElement

/**
 * Points [this] at [url], or puts it away where there is none.
 *
 * Guarded on the attribute rather than written straight through, because both the seat cards and the
 * result dialog are rendered once a *frame* while a portrait changes only when the match does — and
 * a bot with no shipped art carries its whole picture in the URL, so an unguarded write hands the
 * browser a fresh kilobyte of SVG to parse sixty times a second.
 *
 * `getAttribute` and not `src`: the property reports the *resolved* address, so a relative path never
 * compares equal to the one that was written.
 */
internal fun HTMLImageElement.showPortrait(url: String?) {
    hidden = url == null
    if (url != null && getAttribute("src") != url) {
        src = url
    }
}
