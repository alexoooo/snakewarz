package ao.snakewarz.core.snake

import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.rules.Board
import kotlin.test.Test
import kotlin.test.assertEquals

class SnakeBodyTest {
    @Test
    fun `a fresh body is a single square that is both head and tail`() {
        val body = SnakeBody(16)
        body.reset(Cell(9))

        assertEquals(1, body.size)
        assertEquals(Cell(9), body.head)
        assertEquals(Cell(9), body.tail)
        assertEquals(Cell(9), body.cellAt(0))
    }

    @Test
    fun `cells are indexed from the tail`() {
        val body = SnakeBody(16)
        body.reset(Cell(1))
        body.pushHead(Cell(2))
        body.pushHead(Cell(3))

        assertEquals(listOf(1, 2, 3), (0 until body.size).map { body.cellAt(it).index })
        assertEquals(Cell(1), body.tail)
        assertEquals(Cell(3), body.head)
    }

    @Test
    fun `pushing a head and popping a tail slides the body along`() {
        val body = SnakeBody(16)
        body.reset(Cell(0))

        body.pushHead(Cell(1))
        assertEquals(Cell(0), body.popTail())

        assertEquals(1, body.size)
        assertEquals(Cell(1), body.head)
        assertEquals(Cell(1), body.tail)
    }

    @Test
    fun `pops at both ends invert the matching pushes`() {
        // This is exactly what Board.undo does, and the reason a growable list would not do: undo
        // needs to pop the head it pushed *and* push back the tail it popped.
        val body = SnakeBody(16)
        body.reset(Cell(5))
        body.pushHead(Cell(6))
        body.pushHead(Cell(7))

        val poppedTail = body.popTail()
        val poppedHead = body.popHead()

        assertEquals(Cell(5), poppedTail)
        assertEquals(Cell(7), poppedHead)

        body.pushHead(poppedHead)
        body.pushTail(poppedTail)

        assertEquals(listOf(5, 6, 7), (0 until body.size).map { body.cellAt(it).index })
    }

    @Test
    fun `the ring survives far more slides than its capacity`() {
        // Wrapping is the only thing a ring buffer can get wrong, and it would show up as a snake
        // teleporting after a few hundred moves rather than as a crash.
        val body = SnakeBody(8)
        body.reset(Cell(0))

        for (step in 1..500) {
            body.pushHead(Cell(step))
            body.popTail()
            assertEquals(1, body.size)
            assertEquals(Cell(step), body.head)
            assertEquals(Cell(step), body.tail)
        }
    }

    @Test
    fun `a wrapped body copies into a normalised one`() {
        val source = SnakeBody(8)
        source.reset(Cell(0))
        // Slide far enough that the live window straddles the end of the backing array.
        for (step in 1..7) {
            source.pushHead(Cell(step))
            source.popTail()
        }
        source.pushHead(Cell(8))
        source.pushHead(Cell(9))

        val copy = SnakeBody(64)
        copy.copyFrom(source)

        assertEquals(source.size, copy.size)
        assertEquals(
            (0 until source.size).map { source.cellAt(it).index },
            (0 until copy.size).map { copy.cellAt(it).index },
        )
    }
}
