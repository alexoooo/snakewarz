package ao.snakewarz.lab.arena

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.MatchSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpeningSetupTest {
    @Test
    fun `a fixed opening is the one the engine picked`() {
        val setup = setupOf(seed = 3)

        assertSame(setup, openingSetup(setup, Openings.FIXED))
    }

    @Test
    fun `a mirrored opening puts the second snake at the image of the first`() {
        for (seed in 1L..40L) {
            val spawns = openingSetup(setupOf(seed), Openings.MIRRORED).spawns()

            val firstRow = spawns[0] / COLS
            val firstCol = spawns[0] % COLS
            assertEquals(ROWS - 1 - firstRow, spawns[1] / COLS, "seed $seed")
            assertEquals(COLS - 1 - firstCol, spawns[1] % COLS, "seed $seed")
        }
    }

    @Test
    fun `the two matches of a pair start from the same squares`() {
        // Which is what keeps a seat swap a swap: the same board, the players exchanged. A placement
        // that varied per match would move the board too and throw away the paired comparison the
        // schedule is built around.
        val first = openingSetup(setupOf(seed = 9), Openings.MIRRORED)
        val swapped = openingSetup(setupOf(seed = 9, slots = listOf("wallhug", "space")), Openings.MIRRORED)

        assertEquals(first.spawns().toList(), swapped.spawns().toList())
    }

    @Test
    fun `different seeds mostly start from different squares`() {
        val distinct = (1L..60L).mapTo(LinkedHashSet()) { openingSetup(setupOf(it), Openings.MIRRORED).spawns()[0] }

        assertTrue(distinct.size > 30, "sixty seeds produced only ${distinct.size} openings")
    }

    @Test
    fun `snakes never start on top of each other or too close to play a game`() {
        // A pair that starts adjacent plays a short, drawish match that says very little about
        // either of them, and a batch of those is noise with a large sample size.
        for (seed in 1L..60L) {
            val spawns = openingSetup(setupOf(seed), Openings.MIRRORED).spawns()

            assertNotEquals(spawns[0], spawns[1], "seed $seed")
            val gap = kotlin.math.abs(spawns[0] / COLS - spawns[1] / COLS) +
                kotlin.math.abs(spawns[0] % COLS - spawns[1] % COLS)
            assertTrue(gap >= (ROWS - 1 + COLS - 1) / 2, "seed $seed opened $gap apart")
        }
    }

    @Test
    fun `everything but the squares travels through untouched`() {
        val setup = setupOf(seed = 5)
        val opened = openingSetup(setup, Openings.MIRRORED)

        assertEquals(setup.seed, opened.seed)
        assertEquals(setup.slots, opened.slots)
        assertEquals(setup.turnOrder().toList(), opened.turnOrder().toList())
        assertEquals(setup.budgets().toList(), opened.budgets().toList())
        assertEquals(setup.rules, opened.rules)
    }

    @Test
    fun `every board a match can be played on produces an opening a match can be played from`() {
        // A crowded board can genuinely run out of well-separated squares, and the answer then is
        // the placement the engine chose rather than a throw. Either way what comes back has to be
        // seatable: MatchSetup refuses spawns that repeat or fall off the board, so building one is
        // the assertion.
        for (side in 1..6) {
            for (count in 2..minOf(4, side * side)) {
                val slots = List(count) { BotId(if (it % 2 == 0) "space" else "wallhug") }
                for (seed in 1L..6L) {
                    val setup = MatchSetup.create(rows = side, cols = side, slots = slots, seed = seed)
                    val opened = openingSetup(setup, Openings.MIRRORED)

                    assertEquals(count, opened.spawns().toSet().size, "${side}x$side, $count snakes, seed $seed")
                }
            }
        }
    }

    private fun setupOf(seed: Long, slots: List<String> = SLOTS): MatchSetup =
        MatchSetup.create(rows = ROWS, cols = COLS, slots = slots.map { BotId(it) }, seed = seed, budgetPerTurn = 0)

    private companion object {
        const val ROWS = 12
        const val COLS = 12
        val SLOTS = listOf("space", "wallhug")
    }
}
