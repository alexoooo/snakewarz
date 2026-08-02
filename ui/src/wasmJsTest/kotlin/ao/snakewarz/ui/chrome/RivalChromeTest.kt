package ao.snakewarz.ui.chrome

import ao.snakewarz.ui.model.RivalCard
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RivalChromeTest {
    private val skeleton: HTMLElement = (document.createElement("div") as HTMLElement).also {
        it.innerHTML = SKELETON
        document.body?.appendChild(it)
    }
    private val chrome = RivalChrome()

    @AfterTest
    fun detach() {
        skeleton.remove()
    }

    @Test
    fun `a Gauntlet rival replaces the scoreboard and updates its live facts`() {
        chrome.render(1, RivalCard("Hunter Bot - 1k", "The Hunter", "hunter.webp", 3, "In play"))

        assertTrue(element("scoreboard").hidden)
        assertTrue(!element("rival-card").hidden)
        assertEquals("The Hunter", element("rival-title").textContent)
        assertEquals("Hunter Bot - 1k", element("rival-name").textContent)
        assertEquals("Length 3", element("rival-length").textContent)
        assertEquals("In play", element("rival-status").textContent)

        // A retained level replay has the same level identity; only its changing stats differ.
        chrome.render(1, RivalCard("Hunter Bot - 1k", "The Hunter", "hunter.webp", 7, "Trapped"))

        assertTrue(element("scoreboard").hidden)
        assertEquals("Length 7", element("rival-length").textContent)
        assertEquals("Trapped", element("rival-status").textContent)
    }

    @Test
    fun `a custom match keeps its scoreboard and has no rival card`() {
        chrome.render(null, null)

        assertTrue(!element("scoreboard").hidden)
        assertTrue(element("rival-card").hidden)
    }

    private fun element(id: String): HTMLElement =
        document.getElementById(id) as? HTMLElement ?: error("the test skeleton is missing #$id")

    private companion object {
        val SKELETON = """
            <ol id="scoreboard"></ol>
            <aside id="rival-card" hidden>
              <img id="rival-portrait" alt="" hidden>
              <span id="rival-title"></span>
              <strong id="rival-name"></strong>
              <span id="rival-length"></span>
              <span id="rival-status"></span>
            </aside>
        """.trimIndent()
    }
}
