package ao.snakewarz.app

import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.match.InputBuffer
import ao.snakewarz.match.PlayableRegistry
import ao.snakewarz.ui.GameSession
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * The entry point, and the one place in the program that sees the whole module graph.
 *
 * That is the whole job. `:match` resolves every slot through the `BotRegistry` *interface* and
 * `:ui` renders a `BoardView` it cannot trace back to a bot, which is what keeps the replay codec
 * free of bot classes and the renderer free of opinions about who is playing. The concrete registry
 * gets injected here, once, and nowhere else could do it.
 */
public fun main() {
    val input = InputBuffer()
    val session = GameSession(
        registry = PlayableRegistry(ShippedBots, input),
        input = input,
        replayLink = ::publishReplay,
    )

    // Reveal the app *before* the first paint. #app starts `display: none`, and a hidden element
    // reports clientWidth 0, so measuring the board container first would size every board to the
    // minimum cell size. This must stay ahead of the session's first render.
    //
    // It also tells the boot watchdog in index.html that wasm started; without it the page would
    // show the unsupported panel once the timeout elapsed.
    document.body?.classList?.add("booted")

    session.start(readReplay())

    // Pasting a replay link into the address bar of an already-open tab changes the fragment without
    // reloading anything, so the game has to notice for itself. replaceState, which is how we
    // publish, deliberately does not fire this -- sharing a link cannot restart your own match.
    window.addEventListener("hashchange") {
        readReplay()?.let(session::load)
    }
}
