package ao.snakewarz.ui.schedule

import ao.snakewarz.core.grid.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimatedSteeringTest {
    @Test
    fun `rapid directions wait for separate move animations`() {
        var animating = false
        val played = mutableListOf<Direction>()
        val steering = AnimatedSteering({ animating }) { played += it }

        steering.offer(Direction.WEST)
        animating = true
        steering.offer(Direction.SOUTH)
        assertEquals(listOf(Direction.WEST), played)
        assertTrue(steering.running)

        steering.frame(16.0)
        assertEquals(listOf(Direction.WEST), played, "the active glide keeps the next turn queued")

        animating = false
        steering.frame(32.0)
        assertEquals(listOf(Direction.WEST, Direction.SOUTH), played)
        assertFalse(steering.running)
    }

    @Test
    fun `three anticipated turns retain their order`() {
        var animating = true
        val played = mutableListOf<Direction>()
        val steering = AnimatedSteering({ animating }) { direction ->
            played += direction
            animating = true
        }

        steering.offer(Direction.WEST)
        steering.offer(Direction.SOUTH)
        steering.offer(Direction.EAST)

        repeat(3) {
            animating = false
            steering.frame((it * 16).toDouble())
        }
        assertEquals(listOf(Direction.WEST, Direction.SOUTH, Direction.EAST), played)
    }

    @Test
    fun `cancelling controls drops directions waiting behind an animation`() {
        var animating = true
        val played = mutableListOf<Direction>()
        val steering = AnimatedSteering({ animating }) { played += it }

        steering.offer(Direction.WEST)
        steering.clear()
        animating = false
        steering.frame(16.0)

        assertFalse(steering.running)
        assertTrue(played.isEmpty())
    }
}
