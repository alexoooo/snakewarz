package ao.snakewarz.match

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.RulesConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MatchSetupTest {
    @Test
    fun `two snakes start in opposite corners, as they always have`() {
        // The legacy special case, and the reason the old README said "you always start in the
        // bottom right".
        val spawns = mostDistantSpawns(Grid(20, 20), IntArray(0), 2)

        assertEquals(listOf(0, 399), spawns.toList())
    }

    @Test
    fun `further snakes are placed away from everyone already down`() {
        val grid = Grid(9, 9)
        val spawns = mostDistantSpawns(grid, IntArray(0), 4)

        assertEquals(spawns.size, spawns.toSet().size, "no two snakes share a square")
        for (spawn in spawns) {
            assertTrue(spawn in 0 until grid.playableCount)
        }

        // Penalising by the nearest occupant rather than the average is what keeps these apart; a
        // centroid-based score would pile the third and fourth snakes into the middle.
        val third = spawns[2] / 9 to spawns[2] % 9
        assertTrue(third.first > 2 || third.second > 2, "third spawn $third crowded the first")
    }

    @Test
    fun `placement is deterministic, and stated to be`() {
        assertEquals(
            mostDistantSpawns(Grid(13, 17), IntArray(0), 4).toList(),
            mostDistantSpawns(Grid(13, 17), IntArray(0), 4).toList(),
        )
    }

    @Test
    fun `a board too small for the field is refused`() {
        assertFailsWith<IllegalArgumentException> { mostDistantSpawns(Grid(1, 2), IntArray(0), 3) }
    }

    @Test
    fun `turn order is shuffled from the seed, so acting first is not always slot zero`() {
        val slots = List(4) { BotId("bot$it") }

        val orders = (1L..40L).map { MatchSetup.create(10, 10, slots, seed = it).turnOrder().toList() }

        assertTrue(orders.toSet().size > 1, "every seed produced the same turn order")
        for (order in orders) {
            assertEquals(setOf(0, 1, 2, 3), order.toSet(), "$order is not a permutation")
        }
    }

    @Test
    fun `the same seed sets up the same match`() {
        val slots = List(3) { BotId("bot$it") }

        val first = MatchSetup.create(11, 13, slots, seed = 5150)
        val second = MatchSetup.create(11, 13, slots, seed = 5150)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `shuffling the turn order does not disturb the per-slot streams`() {
        // Setup draws from its own stream precisely so that adding a slot, or reordering who moves
        // first, cannot shift the randomness a bot already recorded a match with.
        val two = MatchSetup.create(10, 10, List(2) { BotId("bot$it") }, seed = 42)
        val three = MatchSetup.create(10, 10, List(3) { BotId("bot$it") }, seed = 42)

        assertEquals(two.seed, three.seed)
        assertEquals(listOf(0, two.rows * two.cols - 1), two.spawns().toList())
        assertEquals(listOf(0, three.rows * three.cols - 1), three.spawns().take(2))
    }

    @Test
    fun `an impossible setup is refused at construction, not at the first move`() {
        val slots = listOf(BotId("a"), BotId("b"))

        assertFailsWith<IllegalArgumentException>("duplicate spawn") {
            MatchSetup(1, 5, 5, ao.snakewarz.core.rules.RulesConfig(), 0, slots, intArrayOf(0, 1), intArrayOf(3, 3))
        }
        assertFailsWith<IllegalArgumentException>("turn order names a slot twice") {
            MatchSetup(1, 5, 5, ao.snakewarz.core.rules.RulesConfig(), 0, slots, intArrayOf(1, 1), intArrayOf(0, 3))
        }
        assertFailsWith<IllegalArgumentException>("spawn off the board") {
            MatchSetup(1, 5, 5, ao.snakewarz.core.rules.RulesConfig(), 0, slots, intArrayOf(0, 1), intArrayOf(0, 999))
        }
        assertFailsWith<IllegalArgumentException>("negative budget") {
            MatchSetup(1, 5, 5, ao.snakewarz.core.rules.RulesConfig(), -1, slots, intArrayOf(0, 1), intArrayOf(0, 3))
        }
    }

    @Test
    fun `an unconfigured match hands every slot the match default`() {
        val setup = MatchSetup.create(10, 10, List(3) { BotId("bot$it") }, seed = 1, budgetPerTurn = 40_000)

        assertEquals(listOf(40_000, 40_000, 40_000), setup.budgets().toList())
        assertEquals(40_000, setup.budgetFor(1))
        assertEquals(BotParams.EMPTY, setup.paramsFor(1))
        assertFalse(setup.configured)
    }

    @Test
    fun `a per-slot allowance is honoured, and each slot gets its own`() {
        val setup = MatchSetup.create(
            10,
            10,
            List(2) { BotId("bot$it") },
            seed = 1,
            budgets = intArrayOf(40_000, 4_000),
        )

        assertEquals(40_000, setup.budgetFor(0))
        assertEquals(4_000, setup.budgetFor(1))
        assertTrue(setup.configured)
    }

    @Test
    fun `spelling the default out for every slot is the same setup as leaving it alone`() {
        // Load-bearing for the codec: an unconfigured payload decodes into the broadcast form and
        // every round trip asserts the result equals the record it came from.
        val slots = List(2) { BotId("bot$it") }
        val implicit = MatchSetup.create(10, 10, slots, seed = 7, budgetPerTurn = 1_000)
        val explicit = MatchSetup.create(
            10,
            10,
            slots,
            seed = 7,
            budgetPerTurn = 1_000,
            budgets = intArrayOf(1_000, 1_000),
            slotParams = listOf(BotParams.EMPTY, BotParams.EMPTY),
        )

        assertEquals(implicit, explicit)
        assertEquals(implicit.hashCode(), explicit.hashCode())
        assertFalse(explicit.configured)
    }

    @Test
    fun `setups differing only in one slot's configuration, or in the map, are not equal`() {
        // MatchSetup.equals enumerates every field by hand, so a new one that nobody added there
        // would make two different matches compare the same and quietly break every round trip.
        val slots = List(2) { BotId("bot$it") }
        val plain = MatchSetup.create(10, 10, slots, seed = 7, budgetPerTurn = 1_000)
        val budgeted = MatchSetup.create(10, 10, slots, seed = 7, budgetPerTurn = 1_000, budgets = intArrayOf(1_000, 9))
        val tuned = MatchSetup.create(
            10,
            10,
            slots,
            seed = 7,
            budgetPerTurn = 1_000,
            slotParams = listOf(BotParams.EMPTY, BotParams(mapOf("exploration" to "1.5"))),
        )
        val mapped = MatchSetup.create(10, 10, slots, seed = 7, budgetPerTurn = 1_000, walls = intArrayOf(44, 45))
        val elsewhere = MatchSetup.create(10, 10, slots, seed = 7, budgetPerTurn = 1_000, walls = intArrayOf(54, 55))

        assertNotEquals(plain, budgeted)
        assertNotEquals(plain, tuned)
        assertNotEquals(budgeted, tuned)
        assertNotEquals(plain, mapped)
        assertNotEquals(mapped, elsewhere)
        assertNotEquals(mapped.hashCode(), elsewhere.hashCode())
        assertTrue(tuned.configured)
        assertTrue(mapped.mapped)
        assertFalse(plain.mapped)
    }

    @Test
    fun `a map is recorded rather than derived, and comes back as it went in`() {
        val setup = MatchSetup.create(6, 6, List(2) { BotId("bot$it") }, seed = 3, walls = intArrayOf(14, 15, 20, 21))

        assertEquals(listOf(14, 15, 20, 21), setup.walls().toList())
        assertEquals(4, setup.wallCount)
        assertEquals(
            listOf(2 to 2, 2 to 3, 3 to 2, 3 to 3).map { setup.grid().cellAt(it.first, it.second).index },
            setup.wallCells(setup.grid()).toList(),
        )
    }

    @Test
    fun `a map that is not a canonical ascending set is refused`() {
        // Ascending and repeat-free is what makes `equals` compare maps rather than orderings, so a
        // payload that spells the same map two ways has to be refused rather than accepted twice.
        assertFailsWith<IllegalArgumentException>("walls out of order") { setupWalled(intArrayOf(7, 6)) }
        assertFailsWith<IllegalArgumentException>("a repeated wall") { setupWalled(intArrayOf(6, 6)) }
        assertFailsWith<IllegalArgumentException>("a wall off the board") { setupWalled(intArrayOf(6, 25)) }
        assertFailsWith<IllegalArgumentException>("a negative wall") { setupWalled(intArrayOf(-1)) }
    }

    @Test
    fun `a spawn standing on a wall is refused at construction`() {
        // Slot 1 starts on 24, the far corner of a 5x5, so a wall there is the collision.
        assertFailsWith<IllegalArgumentException> { setupWalled(intArrayOf(24)) }
    }

    @Test
    fun `a configuration that does not fit the field is refused`() {
        val slots = List(2) { BotId("bot$it") }

        assertFailsWith<IllegalArgumentException>("too few allowances") {
            MatchSetup.create(10, 10, slots, seed = 1, budgets = intArrayOf(10))
        }
        assertFailsWith<IllegalArgumentException>("too few parameter sets") {
            MatchSetup.create(10, 10, slots, seed = 1, slotParams = listOf(BotParams.EMPTY))
        }
        assertFailsWith<IllegalArgumentException>("negative allowance") {
            MatchSetup.create(10, 10, slots, seed = 1, budgets = intArrayOf(10, -1))
        }
    }

    @Test
    fun `a board larger than the ceiling is refused before anything allocates`() {
        // A crafted #r= link is the one input to this program that arrives from a stranger, and the
        // geometry is the field in it that allocates most. Refusing it has to be an
        // IllegalArgumentException, because that is what :app catches to fall back to a fresh match.
        val slots = List(2) { BotId("bot$it") }

        assertFailsWith<IllegalArgumentException> {
            MatchSetup(
                seed = 1,
                rows = MatchSetup.MAX_SIDE + 1,
                cols = 10,
                rules = RulesConfig(),
                budgetPerTurn = 0,
                slots = slots,
                turnOrder = intArrayOf(0, 1),
                spawns = intArrayOf(0, 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MatchSetup(
                seed = 1,
                rows = 10,
                cols = 5_000,
                rules = RulesConfig(),
                budgetPerTurn = 0,
                slots = slots,
                turnOrder = intArrayOf(0, 1),
                spawns = intArrayOf(0, 1),
            )
        }

        // And the ceiling itself is allowed, so the bound is a bound rather than an accident.
        MatchSetup.create(MatchSetup.MAX_SIDE, MatchSetup.MAX_SIDE, slots, seed = 1)
    }

    @Test
    fun `the shipped allowance is copied by hand into bots, so moving it is a two-file change`() {
        // Nothing links this figure to the tests that certify the ladder at it. `:bots` may not
        // depend on `:match` — not in production and not in a test either, and `:bots`'
        // `checkModulePurity` walks the test classpath — so `:bots` types the number out, in
        // `ShippedBudget.kt`'s `SHIPPED_BUDGET`. Raising this constant without following it there
        // leaves `BotLadderTest` certifying a rung nobody plays and `ThroughputTest` timing one,
        // both of them green, which is worse than a red test.
        //
        // So the pin is the tripwire, and answering it is the whole procedure: change
        // `SHIPPED_BUDGET` in `:bots`' test sources, re-measure `BotLadderTest`'s thresholds at the
        // new figure, then move this number. `ReplayCodecTest.SHIPPED_BUDGET` is not one of those
        // copies — it records what this constant was when that suite's payload was captured, and it
        // must stay where it is.
        assertEquals(
            1_000,
            MatchSetup.DEFAULT_BUDGET_PER_TURN,
            "the shipped allowance moved; :bots' SHIPPED_BUDGET has to move with it",
        )
    }

    @Test
    fun `spawns are playable indices, not the engine's padded ones`() {
        // The replay format must not encode the padded-grid layout, or changing the padding would
        // invalidate every recorded match.
        val setup = MatchSetup.create(4, 4, List(2) { BotId("bot$it") }, seed = 1)

        assertEquals(listOf(0, 15), setup.spawns().toList())
        assertEquals(
            listOf(setup.grid().cellAt(0, 0).index, setup.grid().cellAt(3, 3).index),
            setup.spawnCells(setup.grid()).toList(),
        )
    }

    /** A 5x5 two-slot setup, spawned in opposite corners, whose map is the thing under test. */
    private fun setupWalled(walls: IntArray): MatchSetup =
        MatchSetup(
            seed = 1,
            rows = 5,
            cols = 5,
            rules = RulesConfig(),
            budgetPerTurn = 0,
            slots = List(2) { BotId("bot$it") },
            turnOrder = intArrayOf(0, 1),
            spawns = intArrayOf(0, 24),
            walls = walls,
        )
}
