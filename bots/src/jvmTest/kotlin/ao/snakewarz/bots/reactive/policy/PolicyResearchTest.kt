package ao.snakewarz.bots.reactive.policy

import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.setupFor
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.grid.DirectionSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyResearchTest {
    @Test
    fun `the JVM bridge has one unique case per predeclared unstable key`() {
        assertEquals(
            listOf("guarded-path", "local", "local-room", "full", "full-wall", "full-owned"),
            PolicyResearch.cases.map { it.key },
        )
        for (i in PolicyResearch.cases.indices) {
            for (j in i + 1 until PolicyResearch.cases.size) {
                assertTrue(PolicyResearch.cases[i].key != PolicyResearch.cases[j].key)
            }
            assertEquals(PolicyResearch.cases[i], PolicyResearch.case(PolicyResearch.cases[i].key))
        }
        assertNull(PolicyResearch.case("shipped-looking-but-unknown"))
    }

    @Test
    fun `factories make fresh seat-local bots and mutable probes`() {
        val board = boardOf(5, 5, 2 to 2, 0 to 0)
        val setup = setupFor(board, board.toAct)
        val case = assertNotNull(PolicyResearch.case("full"))

        assertNotSame(case.botFactory.create(setup), case.botFactory.create(setup))

        val first = case.probeFactory.create(setup)
        val second = case.probeFactory.create(setup)
        assertNotSame(first, second)
        assertEquals(DirectionSet.EMPTY, first.rawMaxima)
        assertEquals(DirectionSet.EMPTY, second.rawMaxima)

        val selected = first.choose(turnOn(board))

        assertTrue(selected in first.rawMaxima)
        assertEquals(DirectionSet.EMPTY, second.rawMaxima, "one seat's probe state leaked into another")
    }
}
