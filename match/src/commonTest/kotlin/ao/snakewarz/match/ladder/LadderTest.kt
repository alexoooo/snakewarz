package ao.snakewarz.match.ladder

import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.match.human.PlayableRegistry
import ao.snakewarz.match.map.MapShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claims the level table makes about *itself*, which are the ones a unit test can settle.
 *
 * Whether the levels get harder is not one of them and cannot be: it takes several hundred complete
 * matches on ten different boards, and it is `:lab`'s `ladder` subcommand. What is checked here is
 * everything that would make that measurement meaningless — a repeated opponent, a level that cannot
 * be seated, a map a shape refuses to draw.
 *
 * **That every opponent exists in the shipped registry is asserted in `:lab`**, and that is a real
 * constraint rather than an oversight: `:match` has never seen a bot class and adding the edge to
 * satisfy a test would be exactly the failure `checkModulePurity` is wired into `check` to prevent.
 */
class LadderTest {
    @Test
    fun `ten levels, numbered from one, in order`() {
        assertEquals(EXPECTED_LEVELS, Ladder.size)
        assertEquals((1..EXPECTED_LEVELS).toList(), Ladder.levels.map { it.index })
        assertEquals(Ladder.levels.first(), Ladder.levelAt(1))
        assertEquals(Ladder.levels.last(), Ladder.levelAt(EXPECTED_LEVELS))
    }

    @Test
    fun `ten different opponents, which is the whole claim the ladder makes`() {
        // Ten play styles means ten algorithms. One bot at ten allowances would be one opponent
        // thinking for longer, and the move that beats it at level 4 would beat it at level 10.
        val slugs = Ladder.levels.map { it.opponent.slug }
        assertEquals(slugs.size, slugs.toSet().size, "an opponent appears twice: $slugs")

        val titles = Ladder.levels.map { it.title }
        assertEquals(titles.size, titles.toSet().size, "a title appears twice: $titles")
    }

    @Test
    fun `every level's board is big enough for the map it draws`() {
        for (level in Ladder.levels) {
            assertTrue(
                level.rows >= level.shape.minimumSide && level.cols >= level.shape.minimumSide,
                "level ${level.index} draws ${level.shape.slug} on ${level.rows}x${level.cols}",
            )

            // And the shape's own guarantees hold at that size: symmetric, one region, ends paired.
            // `generateMap` checks all four, so drawing it is the assertion.
            val map = level.map(SEED)
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
        for (level in Ladder.levels) {
            val setup = level.setup(SEED, PlayableRegistry.HUMAN_ID)

            assertEquals(listOf(PlayableRegistry.HUMAN_ID, level.opponent), setup.slots)
            assertEquals(level.rows, setup.rows)
            assertEquals(level.cols, setup.cols)
            assertEquals(level.budgetPerTurn, setup.budgetPerTurn)
            assertEquals(level.map(SEED).walls().toList(), setup.walls().toList())
            assertEquals(level.params, setup.paramsFor(OPPONENT_SLOT))
            assertTrue(setup.paramsFor(HUMAN_SLOT).isEmpty, "level ${level.index} configures the player")
        }
    }

    @Test
    fun `a level is playable by any registry's seat, not only by the human one`() {
        // The seat is a BotId and nothing here knows which. `:lab` seats a bot in it to measure the
        // level, `:ui` seats a person, and the level cannot tell -- which is CC-17 at the one place
        // in this module where an identity check would be easy to write.
        val setup = Ladder.levelAt(1).setup(SEED, BotId("random"))

        assertEquals(BotId("random"), setup.slots[HUMAN_SLOT])
        assertEquals(2, setup.slotCount)
    }

    @Test
    fun `the levels asking for a search allowance are exactly the ones at the top`() {
        // Not a claim about which bots search -- this module cannot see a bot -- but about the shape
        // of the curve: an allowance that appeared mid-table and vanished again would mean the table
        // ramps something other than difficulty.
        val allowances = Ladder.levels.map { it.budgetPerTurn }
        val firstSearching = allowances.indexOfFirst { it > 0 }

        assertTrue(firstSearching > 0, "every level grants an allowance: $allowances")
        assertTrue(
            allowances.drop(firstSearching).all { it > 0 },
            "a level with no allowance sits above one with an allowance: $allowances",
        )
        assertEquals(
            allowances.drop(firstSearching).sorted(),
            allowances.drop(firstSearching),
            "the allowance falls somewhere up the ladder: $allowances",
        )
    }

    private companion object {
        const val EXPECTED_LEVELS = 10
        const val HUMAN_SLOT = 0
        const val OPPONENT_SLOT = 1
        const val SEED = 7L
    }
}
