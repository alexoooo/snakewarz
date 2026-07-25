package ao.snakewarz.botapi

import ao.snakewarz.core.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DecisionTest {
    @Test
    fun `a move allocates nothing, because a rollout policy returns millions of them`() {
        for (direction in Direction.entries) {
            assertSame(
                Decision.Move(direction),
                Decision.Move(direction),
                "$direction must come back as the same cached instance",
            )
        }
    }

    @Test
    fun `a move carries the direction it was asked for`() {
        for (direction in Direction.entries) {
            assertEquals(direction, Decision.Move(direction).direction)
        }
    }

    @Test
    fun `equal moves are equal, and different ones are not`() {
        assertEquals(Decision.Move(Direction.NORTH), Decision.Move(Direction.NORTH))
        assertEquals(
            Decision.Move(Direction.NORTH).hashCode(),
            Decision.Move(Direction.NORTH).hashCode(),
        )
        assertEquals(false, Decision.Move(Direction.NORTH) == Decision.Move(Direction.SOUTH))
    }

    @Test
    fun `the decision hierarchy covers every case a driver must handle`() {
        val decisions: List<Decision> = listOf(Decision.Move(Direction.EAST), Decision.Resign, Decision.Pending)

        val described = decisions.map {
            when (it) {
                is Decision.Move -> "move"
                Decision.Resign -> "resign"
                Decision.Pending -> "pending"
            }
        }

        assertEquals(listOf("move", "resign", "pending"), described)
    }
}
