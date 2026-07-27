package ao.snakewarz.app

import ao.snakewarz.match.MatchRecord
import ao.snakewarz.match.ReplayCodec
import kotlinx.browser.window

/**
 * Replay routing, which on this site means the URL **fragment** and nothing else.
 *
 * GitHub Pages serves static files and has no server-side routing, so a path like `/r/<payload>`
 * would 404 for everyone who followed the link. A fragment is never sent to the server at all, and
 * changing it causes no reload — which is also what lets a shared link be published mid-match
 * without interrupting it.
 */
private const val REPLAY_PARAMETER = "r="

/** The replay in the current URL, or `null` if there is none — or if what is there is not one. */
internal fun readReplay(): MatchRecord? = replayIn(window.location.hash)

/**
 * [readReplay] with the address bar handed in, which is the half of it worth testing.
 *
 * This and `MatchSetup`'s geometry check are the two places a stranger's bytes land, so what matters
 * is that **every** way of being wrong ends at `null` rather than at an exception: a truncated link,
 * a hand-edited one, a fragment that is about something else entirely. A blank page is a worse
 * answer to a typo than a fresh match is.
 */
internal fun replayIn(hash: String): MatchRecord? {
    val payload = hash
        .removePrefix("#")
        .split('&')
        .firstOrNull { it.startsWith(REPLAY_PARAMETER) }
        ?.removePrefix(REPLAY_PARAMETER)
        ?: return null

    return try {
        ReplayCodec.decode(payload)
    } catch (malformed: IllegalArgumentException) {
        // A truncated or hand-edited link is a wrong address, not a crash. Say so in the console and
        // open on a fresh match, because a blank page would be a worse answer to a typo.
        println("[snakewarz] ignoring an unreadable replay link: ${malformed.message}")
        null
    }
}

/**
 * Puts [payload] in the address bar and hands back the link that now points at it.
 *
 * `replaceState` rather than assigning to `location.hash`: assigning pushes a history entry and
 * fires `hashchange`, so copying a link would both litter the back button and reload the very match
 * being shared.
 */
internal fun publishReplay(payload: String): String {
    window.history.replaceState(null, "", "#$REPLAY_PARAMETER$payload")
    return window.location.href
}
