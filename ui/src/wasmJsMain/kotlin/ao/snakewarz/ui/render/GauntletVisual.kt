package ao.snakewarz.ui.render

import ao.snakewarz.match.gauntlet.Gauntlet
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/** The illustrated treatment belonging to one frozen Gauntlet index. */
internal data class GauntletVisual(
    val index: Int,
    val stageId: String,
    val accent: String,
    val board: String,
    val grid: String,
    val wall: String,
    val wallEdge: String,
    val enemyBody: String,
    val enemyHead: String,
    val portraitKey: String,
    val defeatedPortraitKey: String,
    val texture: TexturePack,
) {
    fun applyToPage() {
        val root = document.documentElement as? HTMLElement ?: error("the page has no <html> element")
        root.setAttribute("data-stage", stageId)
        root.style.setProperty("--stage-art", "url('art/background/$stageId.webp')")
    }

    companion object {
        val ALL: List<GauntletVisual> = listOf(
            visual(
                1, "hunter", "#d49a45", "#182019", "#30382a", "#46513b", "#68745a", "#c78331", "#ffd08a",
                TexturePack.LATTICE,
            ),
            visual(
                2, "cartographer", "#42a89c", "#29231a", "#443a29", "#695a3d", "#9a8051", "#258d86", "#83e2d7",
                TexturePack.MASONRY,
            ),
            visual(
                3, "lookout", "#68a9d0", "#111a20", "#25333c", "#42515b", "#687986", "#4783a8", "#b8e3ff",
                TexturePack.PLAIN,
            ),
            visual(
                4, "gambler", "#c79a47", "#211619", "#3d292d", "#5c3339", "#8a555c", "#8b3044", "#f2bd67",
                TexturePack.RUBBLE,
            ),
            visual(
                5, "student", "#b58d63", "#1c1714", "#332922", "#4f3b2e", "#755a43", "#765286", "#d8afe0",
                TexturePack.RUBBLE,
            ),
            visual(
                6, "planner", "#5d9eb2", "#141b1e", "#26353a", "#45545a", "#697a80", "#326a79", "#a6e0ed",
                TexturePack.LATTICE,
            ),
            visual(
                7, "final-boss", "#c13a3f", "#160d0f", "#32171b", "#501d23", "#852e35", "#8f1828", "#f5d9cf",
                TexturePack.RUBBLE,
            ),
        )

        init {
            require(ALL.size == Gauntlet.size) { "every Gauntlet level needs one visual" }
            require(ALL.map { it.index } == (1..Gauntlet.size).toList()) {
                "Gauntlet visuals must follow frozen indices"
            }
        }

        fun at(index: Int?): GauntletVisual? = index?.let {
            ALL.getOrNull(it - 1)?.takeIf { visual -> visual.index == it }
        }

        fun clearPage() {
            val root = document.documentElement as? HTMLElement ?: error("the page has no <html> element")
            root.removeAttribute("data-stage")
            root.style.removeProperty("--stage-art")
        }

        private fun visual(
            index: Int,
            id: String,
            accent: String,
            board: String,
            grid: String,
            wall: String,
            wallEdge: String,
            body: String,
            head: String,
            texture: TexturePack,
        ): GauntletVisual = GauntletVisual(
            index,
            id,
            accent,
            board,
            grid,
            wall,
            wallEdge,
            body,
            head,
            "gauntlet-$id",
            "gauntlet-$id-defeated",
            texture,
        )
    }
}
