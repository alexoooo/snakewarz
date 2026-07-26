package ao.snakewarz.ui

/**
 * Publishes an encoded replay somewhere a person can copy it from, and says where that was.
 *
 * `:ui` knows how to encode a match and how to show a link; it deliberately does not know that the
 * link is a URL fragment, that `history.replaceState` exists, or that GitHub Pages has no
 * server-side routing and therefore no other option. Routing is `:app`'s job, and this is the seam.
 */
public fun interface ReplayLink {
    /** Publishes [payload] and returns the address that now leads to it. */
    public fun publish(payload: String): String
}
