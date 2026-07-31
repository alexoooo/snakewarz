package ao.snakewarz.ui.chrome.panel

import ao.snakewarz.ui.chrome.elementById
import ao.snakewarz.ui.model.UiIntent
import ao.snakewarz.ui.model.UiModel
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement

/**
 * `#panel-share`: the link the match travels in, and the way back into the one just played.
 *
 * The whole match rides in the URL fragment, move by move and with no server involved, so the field
 * below the button is the product rather than a receipt for it — which is why it is a real, readonly
 * `<input>` a person can select by hand.
 */
internal class SharePanel(dispatch: (UiIntent) -> Unit) {
    private val watchButton: HTMLButtonElement = elementById("watch-replay")
    private val copyButton: HTMLButtonElement = elementById("share")
    private val urlInput: HTMLInputElement = elementById("share-url")

    init {
        watchButton.addEventListener("click") { dispatch(UiIntent.WatchReplay) }
        copyButton.addEventListener("click") { dispatch(UiIntent.Share) }
    }

    fun render(model: UiModel) {
        // A batch's matches belong to the tournament, so there is nothing of the player's to publish.
        copyButton.disabled = model.batchRunning
        watchButton.disabled = !model.canWatchReplay

        val url = model.shareUrl
        urlInput.hidden = url == null
        if (url != null) {
            urlInput.value = url
        }
    }

    /**
     * Selects the link and offers it to the clipboard.
     *
     * Called straight out of the click that asked for it, because the clipboard is only writable
     * from a user gesture — and selecting the text is the fallback for when it is not writable at
     * all, which is why it happens first and unconditionally.
     */
    fun copyShareUrl() {
        urlInput.hidden = false
        urlInput.select()
        copyToClipboard(urlInput.value)
    }

    override fun toString(): String = "SharePanel"
}

/**
 * Offers [text] to the clipboard, and shrugs if the browser declines.
 *
 * Hand-written interop because `navigator.clipboard` is not in the typed DOM bindings, and a
 * rejected promise here is a permissions decision rather than a fault — the link is already selected
 * in a visible field either way.
 */
private fun copyToClipboard(text: String): Unit =
    js("{ if (navigator.clipboard) { navigator.clipboard.writeText(text).catch(function () {}); } }")
