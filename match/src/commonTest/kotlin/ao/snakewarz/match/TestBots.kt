package ao.snakewarz.match

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotFactory
import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotParams
import ao.snakewarz.botapi.BotRegistry
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Direction
import ao.snakewarz.core.Grid
import ao.snakewarz.core.RulesConfig

/**
 * Bots for testing the driver, defined here rather than borrowed from `:bots`.
 *
 * `:match` must not depend on `:bots` — the whole point of resolving slots through the registry
 * *interface* is that the driver and the replay codec never see a bot class — and a test dependency
 * would be that edge all the same. It costs nothing: what the driver needs exercised is every branch
 * of its decision handling, and a bot that always resigns does that better than a real one anyway.
 */
internal class TestRegistry(entries: List<BotEntry>) : BotRegistry {
    override val entries: List<BotEntry> = entries

    private val byId = entries.associateByTo(LinkedHashMap()) { it.id }

    override fun get(id: BotId): BotEntry? = byId[id]

    companion object {
        /** Everything the driver tests use, so a setup can name any of them by slug. */
        val ALL: TestRegistry = TestRegistry(
            listOf(
                entry("cycle") { CyclingBot() },
                entry("last") { LastLegalBot() },
                entry("south") { FixedBot(Direction.SOUTH) },
                entry("north") { FixedBot(Direction.NORTH) },
                entry("east") { FixedBot(Direction.EAST) },
                entry("quitter") { ResigningBot() },
                entry("thrower") { ThrowingBot() },
                entry("staller") { StallingBot(interactive = false) },
                entry("human") { StallingBot(interactive = true) },
                entry("human-east") { FixedBot(Direction.EAST, interactive = true) },
            ),
        )

        private fun entry(slug: String, factory: BotFactory) = BotEntry(BotId(slug), slug, factory)
    }
}

/** A match seated from [TestRegistry.ALL], named by slug — the shorthand every driver test uses. */
internal fun matchOf(rows: Int, cols: Int, vararg slots: String, seed: Long = 1): Match =
    Match(MatchSetup.create(rows, cols, slots.map { BotId(it) }, seed), TestRegistry.ALL)

/**
 * As [matchOf], but with the slots acting in the order they are written.
 *
 * `MatchSetup.create` shuffles the turn order from the seed, which is right for a real match and
 * unhelpful in a test about what the driver does when a particular bot is to act.
 */
internal fun matchInOrder(rows: Int, cols: Int, vararg slots: String): Match =
    Match(
        MatchSetup(
            seed = 1,
            rows = rows,
            cols = cols,
            rules = RulesConfig(),
            budgetPerTurn = 0,
            slots = slots.map { BotId(it) },
            turnOrder = IntArray(slots.size) { it },
            spawns = mostDistantSpawns(Grid(rows, cols), slots.size),
        ),
        TestRegistry.ALL,
    )

/** Plays the lowest-ordinal legal direction, so it survives as long as the board allows. */
internal class CyclingBot : Bot {
    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        return Decision.Move(if (legal.isEmpty) Direction.NORTH else legal.nth(0))
    }
}

/**
 * Plays the highest-ordinal legal direction, so it also survives — and plays differently from
 * [CyclingBot], which is what a tournament test needs to put two survivors in one match.
 */
internal class LastLegalBot : Bot {
    override fun chooseMove(turn: Turn): Decision {
        val legal = turn.legalMoves
        return Decision.Move(if (legal.isEmpty) Direction.NORTH else legal.nth(legal.size - 1))
    }
}

/**
 * Plays one direction forever, legal or not — the shortest route to a recorded suicide.
 *
 * [interactive] makes it a person who only ever presses one key, which is what a test needs to put
 * a player on the board and then get them killed.
 */
internal class FixedBot(
    private val direction: Direction,
    override val interactive: Boolean = false,
) : Bot {
    override fun chooseMove(turn: Turn): Decision = Decision.Move(direction)
}

internal class ResigningBot : Bot {
    override fun chooseMove(turn: Turn): Decision = Decision.Resign
}

internal class ThrowingBot : Bot {
    override fun chooseMove(turn: Turn): Decision = error("this bot is broken, on purpose")
}

internal class StallingBot(override val interactive: Boolean) : Bot {
    override fun chooseMove(turn: Turn): Decision = Decision.Pending
}

/**
 * Survives like [CyclingBot], and remembers what the driver handed it on the way in.
 *
 * The only way to see per-slot configuration arrive: a `BotSetup` is built inside `Match` and handed
 * to a factory, so a test can either assert on what a bot *received* or on nothing at all.
 */
internal class SetupReportingBot(setup: BotSetup) : Bot {
    val params: BotParams = setup.params

    var allowance: Int = -1
        private set

    override fun chooseMove(turn: Turn): Decision {
        allowance = turn.budget.limit

        val legal = turn.legalMoves
        return Decision.Move(if (legal.isEmpty) Direction.NORTH else legal.nth(0))
    }
}

/**
 * A registry of [SetupReportingBot]s under one slug, keeping every instance it makes.
 *
 * One instance per slot per match, in slot order — which is the order `Match` builds them in, and
 * therefore how a test reads back what slot 0 and slot 1 were each given.
 */
internal class ReportingRegistry(slug: String = "reporter") : BotRegistry {
    val made: MutableList<SetupReportingBot> = mutableListOf()

    override val entries: List<BotEntry> = listOf(
        BotEntry(BotId(slug), slug, BotFactory { setup -> SetupReportingBot(setup).also(made::add) }),
    )

    override fun get(id: BotId): BotEntry? = entries.firstOrNull { it.id == id }
}
