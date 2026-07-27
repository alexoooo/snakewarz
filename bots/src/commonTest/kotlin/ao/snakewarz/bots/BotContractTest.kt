package ao.snakewarz.bots

import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotParams
import ao.snakewarz.botapi.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate that makes "fork, add a bot, open a PR" safe to accept.
 *
 * Every entry in [ShippedBots] runs through all of it, so a contributed bot is checked by the same
 * suite as a shipped one and nobody has to review a search algorithm line by line to be confident it
 * will not break a tournament, hang a browser tab or quietly destroy determinism.
 */
class BotContractTest {
    @Test
    fun `no bot ever returns an illegal move while a legal one exists`() {
        // Not merely "does not crash": a bot that resigns rather than plays is also failing here.
        // Dying is allowed — every match ends with somebody doing it — but it has to be forced.
        forEachShippedBot { entry ->
            val match = HeadlessMatch(listOf(entry, entry), rows = 12, cols = 12, seed = 20240725)
            match.run()

            for (recorded in match.decisions) {
                if (recorded.legal.isEmpty) {
                    continue
                }

                val decision = recorded.decision
                assertTrue(
                    decision is Decision.Move && decision.direction in recorded.legal,
                    "${entry.id} answered $decision on turn with ${recorded.legal} available",
                )
            }
        }
    }

    @Test
    fun `no bot outruns its budget, even when handed none at all`() {
        // Zero is the interesting case: a search bot that assumes at least one iteration spins
        // forever here, which is exactly the failure a frame-time guard cannot save a page from.
        forEachShippedBot { entry ->
            val match = HeadlessMatch(listOf(entry, entry), rows = 10, cols = 10, seed = 99, budgetPerTurn = 0)
            match.run()

            assertTrue(match.decisions.isNotEmpty(), "${entry.id} played no turns at all")
            for (recorded in match.decisions) {
                assertEquals(0, recorded.budgetConsumed, "${entry.id} spent budget it was not given")
            }
        }
    }

    @Test
    fun `the same seed plays the same match, twice running`() {
        forEachShippedBot { entry ->
            val first = HeadlessMatch(listOf(entry, entry), rows = 14, cols = 14, seed = 4242)
            val second = HeadlessMatch(listOf(entry, entry), rows = 14, cols = 14, seed = 4242)
            first.run()
            second.run()

            assertEquals(first.moves(), second.moves(), "${entry.id} is not deterministic")
        }
    }

    @Test
    fun `a different seed plays a different match, or the seed is being ignored`() {
        // Only meaningful for bots that consume randomness; a deterministic bot is exempt, and
        // saying which is which out loud is more useful than skipping the check.
        val random = ShippedBots.entryOf(ao.snakewarz.botapi.BotId("random"))

        val first = HeadlessMatch(listOf(random, random), rows = 14, cols = 14, seed = 1)
        val second = HeadlessMatch(listOf(random, random), rows = 14, cols = 14, seed = 2)
        first.run()
        second.run()

        assertTrue(first.moves() != second.moves(), "RandomBot ignored its seed")
    }

    @Test
    fun `no bot carries state from one match into the next`() {
        // Run A then B, and check B is the same as B run on its own. This is what catches a `static`
        // counter, a companion-object cache, or a tree that forgot which match it belonged to.
        forEachShippedBot { entry ->
            val alone = HeadlessMatch(listOf(entry, entry), rows = 11, cols = 11, seed = 777)
            alone.run()

            HeadlessMatch(listOf(entry, entry), rows = 9, cols = 13, seed = 31337).run()
            val afterwards = HeadlessMatch(listOf(entry, entry), rows = 11, cols = 11, seed = 777)
            afterwards.run()

            assertEquals(alone.moves(), afterwards.moves(), "${entry.id} remembers the previous match")
        }
    }

    @Test
    fun `no bot claims to be interactive`() {
        // `Pending` is for a human. A search bot that stalls would forfeit, so a shipped bot
        // declaring itself interactive is a mistake worth catching at registration time.
        forEachShippedBot { entry ->
            val match = HeadlessMatch(listOf(entry), rows = 8, cols = 8, seed = 5)
            match.run()

            assertTrue(
                match.decisions.none { it.decision == Decision.Pending },
                "${entry.id} stalled, which only a human player may do",
            )
        }
    }

    @Test
    fun `every match ends, on every board a bot might be handed`() {
        forEachShippedBot { entry ->
            for ((rows, cols) in listOf(1 to 1, 1 to 5, 2 to 2, 3 to 7, 20 to 20)) {
                val seats = if (rows * cols >= 2) 2 else 1
                val match = HeadlessMatch(List(seats) { entry }, rows, cols, seed = rows * 100L + cols)

                match.run()
            }
        }
    }

    @Test
    fun `a bot spends budget if and only if it declares an allowance`() {
        // `BotEntry.search` is what decides whether the sidebar offers an allowance field at all, so
        // it had better describe the bot rather than merely claim something about it. Most answer
        // with a flood fill and consume nothing; a slider for those would change no move they ever
        // play.
        forEachShippedBot { entry ->
            val match = HeadlessMatch(listOf(entry, entry), rows = 10, cols = 10, seed = 606, budgetPerTurn = 2_000)
            match.run()

            val spent = match.decisions.any { it.budgetConsumed > 0 }
            assertEquals(
                entry.search != null,
                spent,
                if (spent) {
                    "${entry.id} spends budget but declares no allowance, so it cannot be tuned"
                } else {
                    "${entry.id} declares an allowance it never spends, so the form offers a dead control"
                },
            )
        }
    }

    @Test
    fun `every knob at its declared default plays the match no knobs at all plays`() {
        // The drift gate. A knob is its own reader precisely so the number on the form and the
        // number in the field initializer cannot disagree — this catches the one way left to break
        // that, which is a bot reading `setup.params.double("exploration", 5.0)` behind the knob's
        // back and putting the literal somewhere it can rot.
        //
        // A non-zero allowance, because at zero UctBot falls back on SpaceBot and never reads a knob.
        forEachShippedBot { entry ->
            val declared = BotParams(entry.params.associate { it.name to it.defaultText })
            if (declared.isEmpty) {
                return@forEachShippedBot
            }

            val stock = HeadlessMatch(listOf(entry, entry), rows = 10, cols = 10, seed = 8191, budgetPerTurn = 2_000)
            val spelledOut = HeadlessMatch(
                listOf(entry, entry),
                rows = 10,
                cols = 10,
                seed = 8191,
                budgetPerTurn = 2_000,
                paramsPerSlot = listOf(declared, declared),
            )
            stock.run()
            spelledOut.run()

            assertEquals(
                stock.moves(),
                spelledOut.moves(),
                "${entry.id} plays differently at its own declared defaults, so one of them is wrong",
            )
        }
    }

    private fun forEachShippedBot(check: (BotEntry) -> Unit) {
        assertTrue(ShippedBots.entries.isNotEmpty(), "there is nothing to gate")
        ShippedBots.entries.forEach(check)
    }
}
