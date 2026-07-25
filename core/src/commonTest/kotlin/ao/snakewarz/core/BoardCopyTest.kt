package ao.snakewarz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class BoardCopyTest {
    @Test
    fun `a copy starts at the same position, down to the hash`() {
        val board = boardOf(8, 8, 0 to 0, 7 to 7)
        val rng = SplitMix64(19)
        repeat(12) { board.apply(board.toAct, chosenMove(board, rng)) }

        val copy = board.copy()

        assertNotSame(board, copy)
        assertEquals(board.signature(), copy.signature())
    }

    @Test
    fun `a copy carries no history, because a search arena has no use for its source's`() {
        val board = boardOf(8, 8, 0 to 0, 7 to 7)
        repeat(4) { board.apply(board.toAct, chosenMove(board, SplitMix64(1))) }

        assertEquals(0, board.copy().undoDepth)
    }

    @Test
    fun `moving the copy does not move the original`() {
        val board = boardOf(8, 8, 0 to 0, 7 to 7)
        val before = board.signature()

        val copy = board.copy()
        val rng = SplitMix64(3)
        repeat(20) { copy.apply(copy.toAct, chosenMove(copy, rng)) }

        assertEquals(before, board.signature())
    }

    @Test
    fun `a copy keeps the turn order and the spawns, so it can be reset like the original`() {
        val board = boardOf(6, 6, 0 to 0, 0 to 5, 5 to 0, turnOrder = intArrayOf(2, 0, 1))
        val opening = board.signature()

        val copy = board.copy()
        repeat(9) { copy.apply(copy.toAct, chosenMove(copy, SplitMix64(8))) }
        copy.reset()

        assertEquals(opening, copy.signature())
    }
}
