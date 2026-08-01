package ao.snakewarz.match.gauntlet

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.human.PlayableRegistry
import ao.snakewarz.match.map.MapShape
import ao.snakewarz.match.map.generateMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claims the level table makes about *itself*, which are the ones a unit test can settle.
 *
 * Whether the levels get harder is not one of them and cannot be: it takes several hundred complete
 * matches on seven different boards, and it is `:lab`'s `gauntlet` subcommand. What is checked here
 * is everything that would make that measurement meaningless — a repeated configuration, a level that
 * cannot be seated, a map a shape refuses to draw.
 *
 * **That every opponent exists in the shipped registry is asserted in `:lab`**, and that is a real
 * constraint rather than an oversight: `:match` has never seen a bot class and adding the edge to
 * satisfy a test would be exactly the failure `checkModulePurity` is wired into `check` to prevent.
 */
class GauntletTest {
    @Test
    fun `seven levels, numbered from one, in order`() {
        assertEquals(EXPECTED_LEVELS, Gauntlet.size)
        assertEquals((1..EXPECTED_LEVELS).toList(), Gauntlet.levels.map { it.index })
        assertEquals(Gauntlet.levels.first(), Gauntlet.levelAt(1))
        assertEquals(Gauntlet.levels.last(), Gauntlet.levelAt(EXPECTED_LEVELS))
    }

    @Test
    fun `seven different exact level configurations`() {
        // A profile may deliberately recur on another wall layout: that exact map is part of the
        // opponent measured for a level, so uniqueness belongs to the complete level configuration.
        val configurations = Gauntlet.levels.map {
            listOf(it.opponent.slug, it.params.toString(), it.rows, it.cols, it.shape.slug, it.mapSeed)
        }
        assertEquals(
            configurations.size,
            configurations.toSet().size,
            "an exact level configuration appears twice: $configurations",
        )

        val titles = Gauntlet.levels.map { it.title }
        assertEquals(titles.size, titles.toSet().size, "a title appears twice: $titles")
    }

    @Test
    fun `the campaign uses every surviving wall shape once before the empty boss`() {
        assertEquals(
            listOf(
                MapShape.PILLARS,
                MapShape.ROOMS,
                MapShape.ARENA,
                MapShape.SCATTER,
                MapShape.ISLANDS,
                MapShape.PINWHEEL,
                MapShape.EMPTY,
            ),
            Gauntlet.levels.map { it.shape },
        )
        assertTrue(Gauntlet.levels.all { it.mapSeed == 0L })
    }

    @Test
    fun `every level's board is big enough for the map it draws`() {
        for (level in Gauntlet.levels) {
            assertTrue(
                level.rows >= level.shape.minimumSide && level.cols >= level.shape.minimumSide,
                "level ${level.index} draws ${level.shape.slug} on ${level.rows}x${level.cols}",
            )

            // And the shape's own guarantees hold at that size: symmetric, one region, ends paired.
            // `generateMap` checks all four, so drawing it is the assertion.
            val map = level.map()
            assertEquals(level.rows, map.rows)
            assertEquals(level.cols, map.cols)
            assertTrue(
                level.shape == MapShape.EMPTY || map.wallCount > 0,
                "level ${level.index} draws ${level.shape.slug} and gets no walls",
            )
        }
    }

    @Test
    fun `a level seats the player first and the opponent second, on its own board`() {
        for (level in Gauntlet.levels) {
            val setup = level.setup(SEED, PlayableRegistry.HUMAN_ID)

            assertEquals(listOf(PlayableRegistry.HUMAN_ID, level.opponent), setup.slots)
            assertEquals(level.rows, setup.rows)
            assertEquals(level.cols, setup.cols)
            assertEquals(level.budgetPerTurn, setup.budgetPerTurn)
            assertEquals(level.map().walls().toList(), setup.walls().toList())
            assertEquals(level.params, setup.paramsFor(OPPONENT_SLOT))
            assertTrue(setup.paramsFor(HUMAN_SLOT).isEmpty, "level ${level.index} configures the player")
        }
    }

    @Test
    fun `a level is playable by any registry's seat, not only by the human one`() {
        // The seat is a BotId and nothing here knows which. `:lab` seats a bot in it to measure the
        // level, `:ui` seats a person, and the level cannot tell -- which is CC-17 at the one place
        // in this module where an identity check would be easy to write.
        val setup = Gauntlet.levelAt(1).setup(SEED, BotId("random"))

        assertEquals(BotId("random"), setup.slots[HUMAN_SLOT])
        assertEquals(2, setup.slotCount)
    }

    @Test
    fun `a retry changes the match seed without redrawing the level`() {
        val level = GauntletLevel(
            index = 1,
            title = "Pinned",
            blurb = "A randomized shape whose fixture is held still.",
            opponent = BotId("random"),
            params = BotParams.EMPTY,
            budgetPerTurn = 0,
            rows = 16,
            cols = 16,
            shape = MapShape.ISLANDS,
            mapSeed = SEED,
        )
        val first = level.setup(SEED, PlayableRegistry.HUMAN_ID)
        val retry = level.setup(SEED + 1, PlayableRegistry.HUMAN_ID)
        val redrawn = generateMap(16, 16, MapShape.ISLANDS, seed = SEED + 1)

        assertEquals(SEED, first.seed)
        assertEquals(SEED + 1, retry.seed)
        assertEquals(first.walls().toList(), retry.walls().toList())
        assertTrue(first.walls().toList() != redrawn.walls().toList(), "fixture seeds drew the same islands")
    }

    @Test
    fun `the levels asking for a search allowance are exactly the ones at the top`() {
        // Allowances are evaluator-specific work units, not difficulty. The fixed-depth Level 3 can
        // therefore carry a larger cap than a later Monte Carlo level; only the zero/non-zero split
        // has a cross-algorithm meaning in this module.
        val allowances = Gauntlet.levels.map { it.budgetPerTurn }
        val firstSearching = allowances.indexOfFirst { it > 0 }

        assertTrue(firstSearching > 0, "every level grants an allowance: $allowances")
        assertTrue(
            allowances.drop(firstSearching).all { it > 0 },
            "a level with no allowance sits above one with an allowance: $allowances",
        )
    }

    private companion object {
        const val EXPECTED_LEVELS = 7
        const val HUMAN_SLOT = 0
        const val OPPONENT_SLOT = 1
        const val SEED = 7L
    }
}
