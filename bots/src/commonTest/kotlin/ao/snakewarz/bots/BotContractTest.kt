package ao.snakewarz.bots

import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.knob.BotKnob
import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate that makes "fork, add a bot, open a PR" safe to accept.
 *
 * Every entry in [ShippedBots] runs through all of it, so a contributed bot is checked by the same
 * suite as a shipped one and nobody has to review a search algorithm line by line to be confident it
 * will not break a tournament, hang a browser tab or quietly destroy determinism.
 *
 * Two things are swept beside the registry, and each is a bot the entry alone does not describe:
 *
 * - **Every value of every [BotKnob.Choice] an entry declares**, because a choice names a different
 *   algorithm behind the same slug. `puct` at `eval=chamber` takes a board apart where the default
 *   sweeps it once, and "survives an allowance of zero" is a separate claim about each of them.
 * - **One to four snakes.** A duel is not the game a free-for-all is: the reductions two of these
 *   bots make to reach a scalar, and every reading of "the opponent", only have one answer at two
 *   seats. What a bot does with three is unpinned unless it is played with three.
 */
class BotContractTest {
    @Test
    fun `no bot ever returns an illegal move while a legal one exists`() {
        // Not merely "does not crash": a bot that resigns rather than plays is also failing here.
        // Dying is allowed — every match ends with somebody doing it — but it has to be forced.
        forEachSetting { setting ->
            for (seats in SEAT_COUNTS) {
                val match = setting.match(rows = 12, cols = 12, seats = seats, seed = 20240725)
                match.run()

                for (recorded in match.decisions) {
                    if (recorded.legal.isEmpty) {
                        continue
                    }

                    val decision = recorded.decision
                    assertTrue(
                        decision is Decision.Move && decision.direction in recorded.legal,
                        "$setting at $seats seats answered $decision on a turn with ${recorded.legal} available",
                    )
                }
            }
        }
    }

    @Test
    fun `no bot outruns its budget, even when handed none at all`() {
        // Zero is the interesting case: a search bot that assumes at least one iteration spins
        // forever here, which is exactly the failure a frame-time guard cannot save a page from. It
        // is also where `Scratch.playout(0)` is load-bearing — `Budget.tryConsume(0)` succeeds at a
        // budget of zero, so a bot that wants an arena without buying an evaluation still gets one,
        // and a bot that asks for a real evaluation instead must come back having spent nothing.
        forEachSetting { setting ->
            for (seats in SEAT_COUNTS) {
                val match = setting.match(rows = 10, cols = 10, seats = seats, seed = 99, budgetPerTurn = 0)
                match.run()

                assertTrue(match.decisions.isNotEmpty(), "$setting played no turns at all")
                for (recorded in match.decisions) {
                    assertEquals(0, recorded.budgetConsumed, "$setting at $seats seats spent budget it was not given")
                }
            }
        }
    }

    @Test
    fun `the same seed plays the same match, twice running`() {
        forEachSetting { setting ->
            for (seats in SEAT_COUNTS) {
                val first = setting.match(rows = 14, cols = 14, seats = seats, seed = 4242)
                val second = setting.match(rows = 14, cols = 14, seats = seats, seed = 4242)
                first.run()
                second.run()

                assertEquals(first.moves(), second.moves(), "$setting is not deterministic at $seats seats")
            }
        }
    }

    @Test
    fun `a different seed plays a different match, or the seed is being ignored`() {
        // Only meaningful for bots that consume randomness; a deterministic bot is exempt, and
        // saying which is which out loud is more useful than skipping the check.
        val random = ShippedBots.entryOf(ao.snakewarz.botapi.registry.BotId("random"))

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
        //
        // The match in between seats a different number of snakes as well as a different board,
        // because a buffer sized from the slot count is the other thing a bot allocates once.
        forEachSetting { setting ->
            val alone = setting.match(rows = 11, cols = 11, seats = 2, seed = 777)
            alone.run()

            setting.match(rows = 9, cols = 13, seats = 4, seed = 31337).run()
            val afterwards = setting.match(rows = 11, cols = 11, seats = 2, seed = 777)
            afterwards.run()

            assertEquals(alone.moves(), afterwards.moves(), "$setting remembers the previous match")
        }
    }

    @Test
    fun `no bot claims to be interactive`() {
        // `Pending` is for a human. A search bot that stalls would forfeit, so a shipped bot
        // declaring itself interactive is a mistake worth catching at registration time.
        forEachSetting { setting ->
            val match = setting.match(rows = 8, cols = 8, seats = 1, seed = 5)
            match.run()

            assertTrue(
                match.decisions.none { it.decision == Decision.Pending },
                "$setting stalled, which only a human player may do",
            )
        }
    }

    @Test
    fun `every match ends, on every board a bot might be handed`() {
        forEachSetting { setting ->
            for ((rows, cols) in GEOMETRIES) {
                for (seats in 1..seatsOn(rows, cols)) {
                    setting.match(rows, cols, seats, seed = rows * 100L + cols).run()
                }
            }
        }
    }

    @Test
    fun `a bot spends budget if and only if it declares an allowance`() {
        // `BotEntry.search` is what decides whether the sidebar offers an allowance field at all, so
        // it had better describe the bot rather than merely claim something about it. Most answer
        // with a flood fill and consume nothing; a slider for those would change no move they ever
        // play.
        forEachSetting { setting ->
            for (seats in SEAT_COUNTS) {
                val match = setting.match(rows = 10, cols = 10, seats = seats, seed = 606, budgetPerTurn = 40)
                match.run()

                val spent = match.decisions.any { it.budgetConsumed > 0 }
                assertEquals(
                    setting.entry.search != null,
                    spent,
                    if (spent) {
                        "$setting spends budget but declares no allowance, so it cannot be tuned"
                    } else {
                        "$setting declares an allowance it never spends, so the form offers a dead control"
                    },
                )
            }
        }
    }

    @Test
    fun `every knob at its declared default plays the match no knobs at all plays`() {
        // The drift gate. A knob is its own reader precisely so the number on the form and the
        // number in the field initializer cannot disagree — this catches the one way left to break
        // that, which is a bot reading `setup.params.double("exploration", 5.0)` behind the knob's
        // back and putting the literal somewhere it can rot.
        //
        // The plain entries and not the settings above, because a setting is a knob turned *off* its
        // default and this is the one claim that is about the defaults themselves.
        //
        // A non-zero allowance, because at zero UctBot falls back on SpaceBot and never reads a knob.
        forEachShippedBot { entry ->
            val declared = BotParams(entry.params.associate { it.name to it.defaultText })
            if (declared.isEmpty) {
                return@forEachShippedBot
            }

            val stock = HeadlessMatch(listOf(entry, entry), rows = 10, cols = 10, seed = 8191, budgetPerTurn = 40)
            val spelledOut = HeadlessMatch(
                listOf(entry, entry),
                rows = 10,
                cols = 10,
                seed = 8191,
                budgetPerTurn = 40,
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

    // -- internals

    private fun forEachShippedBot(check: (BotEntry) -> Unit) {
        assertTrue(ShippedBots.entries.isNotEmpty(), "there is nothing to gate")
        ShippedBots.entries.forEach(check)
    }

    /**
     * Every registry entry at its defaults, then once per non-default value of every choice it offers.
     *
     * Read off the declaration rather than off a slug, so a contributed bot's own choices enrol here
     * the day it is registered — the same reason the sidebar builds its rows from `BotEntry.knobs`.
     * Only [BotKnob.Choice] is expanded: its values are named alternatives with nothing between them,
     * where a number's are a range no suite could enumerate.
     */
    private fun forEachSetting(check: (Setting) -> Unit) {
        forEachShippedBot { entry ->
            check(Setting(entry, BotParams.EMPTY, entry.id.slug))

            for (knob in entry.params.filterIsInstance<BotKnob.Choice>()) {
                for (value in knob.values) {
                    if (value != knob.default) {
                        val pinned = BotParams(mapOf(knob.name to value))
                        check(Setting(entry, pinned, "${entry.id.slug}:${knob.name}=$value"))
                    }
                }
            }
        }
    }

    /**
     * How many snakes a board of this shape can seat.
     *
     * A fact about `cornerSpawns` rather than about the rules: it places snakes in the four corners
     * and `Board` refuses two spawns on one square, so a single row has two distinct corners and a
     * 1x1 board has one.
     */
    private fun seatsOn(rows: Int, cols: Int): Int = when {
        rows == 1 && cols == 1 -> 1
        rows == 1 || cols == 1 -> 2
        else -> MOST_SEATS
    }

    /**
     * A registry entry, the knob values it is being gated at, and what a failure calls the pair.
     *
     * [label] is `:lab`'s entrant grammar — `puct:eval=chamber` — so a failure names something that
     * can be pasted straight into a batch to reproduce it.
     */
    private class Setting(val entry: BotEntry, val params: BotParams, private val label: String) {
        fun match(rows: Int, cols: Int, seats: Int, seed: Long, budgetPerTurn: Int = DEFAULT_BUDGET): HeadlessMatch =
            HeadlessMatch(
                List(seats) { entry },
                rows = rows,
                cols = cols,
                seed = seed,
                budgetPerTurn = budgetPerTurn,
                paramsPerSlot = List(seats) { params },
            )

        override fun toString(): String = label
    }

    private companion object {
        /** The most snakes `cornerSpawns` places, and `Occupancy` seats far more than this. */
        const val MOST_SEATS = 4

        /** `HeadlessMatch`'s own, spelled out because a [Setting] passes it through explicitly. */
        const val DEFAULT_BUDGET = 20

        /**
         * Seats a claim about a whole match is made at.
         *
         * Three is where a duel stops describing the game — a reduction to "the opponent" has a
         * choice to make, and a value backed up as "bad for them" stops meaning "good for me" — and
         * four is the most `cornerSpawns` places.
         */
        val SEAT_COUNTS = 2..MOST_SEATS

        /** The shapes a bot might be handed, from degenerate to the largest the app opens on. */
        val GEOMETRIES = listOf(1 to 1, 1 to 5, 2 to 2, 3 to 7, 20 to 20)
    }
}
