package ao.snakewarz.ui.model

import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.Board
import ao.snakewarz.match.MatchSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the board says about the square under the pointer.
 *
 * Arithmetic over a position rather than a page, which is why it can be asked at all: the label is a
 * reading of a `BoardView`, and `GameSession` only decides which view and which square.
 */
class HoverInfoTest {
    private val grid = Grid(SIDE, SIDE)
    private val wall = grid.cellAt(2, 2)
    private val spawn = grid.cellAt(0, 0)

    private val board = Board(
        grid = grid,
        spawnCells = intArrayOf(spawn.index),
        wallCells = intArrayOf(wall.index),
    )

    private val labels = SlotLabels(
        MatchSetup.create(rows = SIDE, cols = SIDE, slots = listOf(BotId("space")), seed = 1),
        EmptyRegistry,
    )

    @Test
    fun `a wall says what it is, rather than reading as empty board`() {
        // `ownerOf` answers `SnakeId.NONE` for a wall and for an open square alike, so a label built
        // off the owner alone would have the board call a square nobody can ever enter empty.
        val label = hoverInfo(board, wall, labels)

        assertEquals("wall", label?.who)
    }

    @Test
    fun `an open square is worth no label at all`() {
        assertNull(hoverInfo(board, grid.cellAt(SIDE - 1, 0), labels), "empty board")
        assertNull(hoverInfo(board, Cell.NONE, labels), "off the board entirely")
    }

    @Test
    fun `a snake is named and measured`() {
        val label = hoverInfo(board, spawn, labels)

        assertEquals("space", label?.who, "a bot the registry never heard of still names its seat")
        assertTrue(label?.detail?.startsWith("1 square") == true, label?.detail)
    }

    private companion object {
        /** Large enough that the wall, the spawn and an open square are three different places. */
        const val SIDE = 6

        /** Labels fall back to the slug, which is all this needs a registry to be. */
        object EmptyRegistry : BotRegistry {
            override val entries: List<BotEntry> = emptyList()

            override fun get(id: BotId): BotEntry? = null
        }
    }
}
