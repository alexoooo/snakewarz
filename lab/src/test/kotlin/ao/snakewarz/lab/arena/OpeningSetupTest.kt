package ao.snakewarz.lab.arena

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
import ao.snakewarz.match.openRegionFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpeningSetupTest {
    @Test
    fun `complete is exactly the forty oriented mirrored starts on an empty 8x8`() {
        val openings = (0 until Openings.COMPLETE_POPULATION).map(::completeOpeningSpawns)

        assertEquals(Openings.COMPLETE_POPULATION, openings.map { it[0] }.toSet().size)
        for ((index, spawns) in openings.withIndex()) {
            val first = spawns[0]
            val second = spawns[1]
            assertEquals(7 - first / 8, second / 8, "opening $index")
            assertEquals(7 - first % 8, second % 8, "opening $index")

            val separation = kotlin.math.abs(first / 8 - second / 8) +
                kotlin.math.abs(first % 8 - second % 8)
            assertTrue(separation >= 7, "opening $index is only $separation steps apart")
            assertTrue(openings.any { it[0] == second && it[1] == first }, "opening $index has no rho mate")
        }
    }

    @Test
    fun `every old mirrored empty-8x8 sample belongs to complete`() {
        val population = (0 until Openings.COMPLETE_POPULATION)
            .mapTo(LinkedHashSet()) { completeOpeningSpawns(it).toList() }

        for (seed in 1L..1_000L) {
            val setup = MatchSetup.create(
                rows = Openings.COMPLETE_ROWS,
                cols = Openings.COMPLETE_COLS,
                slots = SLOTS.map(::BotId),
                seed = seed,
                budgetPerTurn = 0,
            )
            val sampled = openingSetup(setup, Openings.MIRRORED).spawns().toList()
            assertTrue(sampled in population, "seed $seed sampled $sampled outside complete")
        }
    }

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
        // This function rebuilds the header field by field, so a field it forgets is a batch that
        // plays a different match from the one the log records. Nothing else would notice.
        val setup = setupOf(seed = 5, walls = ROOMS)
        val opened = openingSetup(setup, Openings.MIRRORED)

        assertEquals(setup.seed, opened.seed)
        assertEquals(setup.slots, opened.slots)
        assertEquals(setup.turnOrder().toList(), opened.turnOrder().toList())
        assertEquals(setup.budgets().toList(), opened.budgets().toList())
        assertEquals(setup.rules, opened.rules)
        assertEquals(setup.walls().toList(), opened.walls().toList())
    }

    @Test
    fun `a map survives the opening rather than being dropped on the way through`() {
        for (shape in MapShape.entries) {
            if (ROWS < shape.minimumSide || COLS < shape.minimumSide) {
                continue
            }
            val walls = generateMap(ROWS, COLS, shape).walls()

            for (seed in 1L..20L) {
                val opened = openingSetup(setupOf(seed, walls = walls), Openings.MIRRORED)
                assertEquals(walls.toList(), opened.walls().toList(), "${shape.slug} at seed $seed")
            }
        }
    }

    @Test
    fun `a drawn opening never seats a snake on a wall or out of reach of the other`() {
        // The two ways a drawn square can be unplayable, and they fail differently: a wall is
        // refused by MatchSetup and ends the batch, while a sealed pocket is accepted and plays a
        // match nobody could win.
        val walls = generateMap(ROWS, COLS, MapShape.ROOMS).walls()

        for (seed in 1L..60L) {
            val opened = openingSetup(setupOf(seed, walls = walls), Openings.MIRRORED)
            val spawns = opened.spawns()

            val reached = openRegionFrom(ROWS, COLS, walls, spawns[0])
            for (spawn in spawns) {
                assertTrue(spawn !in walls, "seed $seed opened on a wall at $spawn")
                assertTrue(reached[spawn], "seed $seed opened at $spawn, sealed off from the other snake")
            }
        }
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

    private fun setupOf(
        seed: Long,
        slots: List<String> = SLOTS,
        walls: IntArray = IntArray(0),
    ): MatchSetup = MatchSetup.create(
        rows = ROWS,
        cols = COLS,
        slots = slots.map { BotId(it) },
        seed = seed,
        budgetPerTurn = 0,
        walls = walls,
    )

    private companion object {
        /** Every shape in the catalogue draws at this size, which is what the sweep below wants. */
        const val ROWS = 14
        const val COLS = 14
        val SLOTS = listOf("space", "wallhug")

        val ROOMS: IntArray = generateMap(ROWS, COLS, MapShape.ROOMS).walls()
    }
}
