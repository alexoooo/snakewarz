package ao.snakewarz.match

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotFactory
import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotRegistry
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
                entry("south") { FixedBot(Direction.SOUTH) },
                entry("north") { FixedBot(Direction.NORTH) },
                entry("east") { FixedBot(Direction.EAST) },
                entry("quitter") { ResigningBot() },
                entry("thrower") { ThrowingBot() },
                entry("staller") { StallingBot(interactive = false) },
                entry("human") { StallingBot(interactive = true) },
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

/** Plays one direction forever, legal or not — the shortest route to a recorded suicide. */
internal class FixedBot(private val direction: Direction) : Bot {
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
