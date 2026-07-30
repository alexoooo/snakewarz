package ao.snakewarz.bots

import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.registry.BotId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShippedBotsTest {
    @Test
    fun `the released slugs are these, and they do not change`() {
        // Slugs live in shared replay URLs. Renaming one breaks every link ever posted for it, so
        // this assertion is the reminder, and failing it is the point rather than an inconvenience.
        assertEquals(
            listOf(
                // The ladder. `puct` and `alphabeta` were the experimental section until P3 seated
                // them, which moved them above `burninhell` -- a reorder, not a rename, and nothing
                // reads a registry position.
                "random", "wallhug", "space", "pressure", "chase", "flat-monte-carlo", "uct",
                "puct", "alphabeta",
                // Contributed. `tomsnake` was here and was retired; a slug is not reused.
                "burninhell",
            ),
            ShippedBots.entries.map { it.id.slug },
        )
    }

    @Test
    fun `lookup finds what is registered and admits what is not`() {
        assertEquals(BotId("random"), ShippedBots[BotId("random")]?.id)
        assertEquals(BotId("alphabeta"), ShippedBots[BotId("alphabeta")]?.id, "the top of the ladder")
        assertEquals(BotId("burninhell"), ShippedBots[BotId("burninhell")]?.id, "a contributed bot")
        assertNull(ShippedBots[BotId("no-such-bot")])
        assertNull(ShippedBots[BotId("tomsnake")], "retired, and a replay naming it still plays back")
        assertFailsWith<IllegalArgumentException> { ShippedBots.entryOf(BotId("no-such-bot")) }
    }

    @Test
    fun `entries iterate in registration order`() {
        // Twice, because the failure this guards against — iterating a HashMap — is intermittent by
        // nature, and reordering the registry silently reorders every tournament built on it.
        assertEquals(ShippedBots.entries.map { it.id }, ShippedBots.entries.map { it.id })
    }

    @Test
    fun `the sidebar offers these and only these`() {
        // A knob is offered to a player only when it is a material tradeoff -- no single best value,
        // several valid ones, each a visibly different bot. Everything else is a hyperparameter a
        // sweep settles better than a form does, and stays reachable from `:lab` and from a replay.
        //
        // Pinned rather than derived, because the failure it guards against is a knob quietly
        // arriving on the sidebar: the default is `false`, so this fails only when somebody chose.
        assertEquals(
            mapOf(
                "flat-monte-carlo" to listOf("budget"),
                "uct" to listOf("budget", "exploration"),
                "puct" to listOf("budget", "eval"),
                "alphabeta" to listOf("budget", "eval"),
            ),
            ShippedBots.entries
                .filter { it.offered.isNotEmpty() }
                .associate { entry -> entry.id.slug to entry.offered.map { it.name } },
        )
    }

    @Test
    fun `what a bot can be handed is more than what it is asked`() {
        // The other half of the rule above: hiding a knob must not un-declare it, or `:lab` loses a
        // dimension it measures in and an old replay carrying one stops meaning what it meant.
        val uct = ShippedBots.entryOf(BotId("uct"))
        assertEquals(
            // Appended, never inserted, for the reason spelled out under `puct` below.
            listOf("exploration", "maxNodes", "rolloutDepth", "rolloutPolicy"),
            uct.params.map { it.name },
        )

        val puct = ShippedBots.entryOf(BotId("puct"))
        assertEquals(
            listOf(
                "eval", "cpuct", "territoryWeight", "mobilityWeight", "trapPenalty", "separationBonus", "solver",
                // Appended, never inserted: `:lab` logs an entrant as its knobs in this order and
                // `report` resolves one by a prefix of that string.
                "parityWeight", "frontierPenalty", "sealPenalty",
                "priorLiberty", "priorPinch", "priorWall", "priorTail", "priorTemperature",
                "rave",
            ),
            puct.params.map { it.name },
        )
    }

    @Test
    fun `puct is what the ceiling on a bot's knobs is set by`() {
        // Not a limit anybody is near by accident: one knob past the bound fails BotEntry's own
        // require, and raising it is a decision about the replay payload rather than a number to
        // nudge -- `ReplayCodec` reads the same constant when it decodes a slot. Pinned as an exact
        // count so that adding a knob here is that decision rather than a silent slide toward it.
        assertEquals(17, ShippedBots.entryOf(BotId("puct")).knobs.size)
        assertTrue(ShippedBots.entryOf(BotId("puct")).knobs.size <= BotKnob.MAX_PER_BOT)
    }

    @Test
    fun `every entry has a name a human can read`() {
        assertEquals(
            listOf(
                "Random", "Wall Hugger", "Space Filler", "Pressure", "Chaser", "Flat Monte Carlo", "UCT",
                "PUCT", "Alpha-Beta",
                "Burnin Hell",
            ),
            ShippedBots.entries.map { it.displayName },
        )
    }
}
