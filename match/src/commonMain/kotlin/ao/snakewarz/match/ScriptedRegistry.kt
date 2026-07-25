package ao.snakewarz.match

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotEntry
import ao.snakewarz.botapi.BotFactory
import ao.snakewarz.botapi.BotId
import ao.snakewarz.botapi.BotRegistry
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.EliminationReason

/**
 * A registry that answers every id with a bot reading from a recorded match.
 *
 * This is the whole of playback. The driver has one code path, and a replay is just a match whose
 * slots happen to already know what they are going to do.
 */
internal class ScriptedRegistry(record: MatchRecord) : BotRegistry {
    private val script = Script(record)

    override val entries: List<BotEntry> get() = emptyList()

    override fun get(id: BotId): BotEntry = BotEntry(id, id.slug, BotFactory { ScriptedBot(script) })
}

/**
 * The shared read head over a recorded match.
 *
 * One cursor across every slot, which is correct precisely because the driver makes exactly one bot
 * call per turn: the stream is in play order already, so there is nothing to demultiplex.
 */
private class Script(private val record: MatchRecord) {
    private var moveIndex = 0

    fun decisionAt(turnIndex: Int, slot: Int): Decision {
        val terminal = record.terminalAt(turnIndex)
        if (terminal != null) {
            check(terminal.slot.index == slot) {
                "the record says slot ${terminal.slot} left on turn $turnIndex, but slot $slot is to act"
            }

            // Reproduced through the driver's own mechanisms rather than a private back door: a
            // resignation is a resignation, and a forfeit is what happens when a bot throws.
            return when (terminal.reason) {
                EliminationReason.RESIGNED -> Decision.Resign
                else -> throw ScriptedForfeit(turnIndex, slot)
            }
        }

        if (moveIndex >= record.moves.size) {
            // A record of a match that never finished. Saying so as "waiting for input" lets the
            // caller stop cleanly at the end of what was actually recorded.
            return Decision.Pending
        }
        return Decision.Move(record.moves[moveIndex++])
    }
}

private class ScriptedBot(private val script: Script) : Bot {
    /** So that running past the end of a partial recording parks rather than forfeits. */
    override val interactive: Boolean get() = true

    override fun chooseMove(turn: Turn): Decision = script.decisionAt(turn.board.turnIndex, turn.self.index)

    override fun toString(): String = "ScriptedBot"
}

private class ScriptedForfeit(turnIndex: Int, slot: Int) :
    RuntimeException("slot $slot forfeited on turn $turnIndex, as recorded")
