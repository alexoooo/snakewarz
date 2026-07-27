package ao.snakewarz.match.replay

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.TerminalEvent

/**
 * A whole match, small enough to travel in a URL.
 *
 * **Records the move stream, not just the seed**, and that is a deliberate and load-bearing choice.
 * A seed-only replay leaks in two independent ways: bots evolve, so one tuned constant invalidates
 * every replay ever shared; and `log`/`exp` are not specified bit-identical across platforms while
 * `+ - * / sqrt` are — UCB1 is `sqrt(log(v) / (5 * cv))`, so an MCTS match recorded by a seed could
 * genuinely diverge between the JVM test target and the browser. Recording the moves sidesteps both.
 *
 * `setup.seed` survives as provenance and as the input [verify] needs, never as the source of truth
 * for playback.
 */
public class MatchRecord(
    public val setup: MatchSetup,
    public val moves: DirectionStream,
    /** At most `slots - 1` entries, ascending by turn. */
    public val terminals: List<TerminalEvent>,
    /** `null` if the recording stops before the match ended. */
    public val outcome: MatchOutcome?,
) {
    init {
        require(terminals.size <= maxTerminals(setup.slotCount)) {
            "${terminals.size} terminal events for ${setup.slotCount} slots, which allows " +
                "at most ${maxTerminals(setup.slotCount)}"
        }

        var previous = -1
        for (event in terminals) {
            require(event.turnIndex > previous) { "terminal events must ascend by turn, saw ${event.turnIndex}" }
            require(event.slot.index < setup.slotCount) {
                "terminal event names slot ${event.slot}, which is not playing"
            }
            previous = event.turnIndex
        }
    }

    /** Total turns played, moves and no-move eliminations together. */
    public val turnCount: Int get() = moves.size + terminals.size

    /** The event on [turnIndex], if that turn was a resignation or a forfeit rather than a move. */
    public fun terminalAt(turnIndex: Int): TerminalEvent? {
        for (event in terminals) {
            if (event.turnIndex == turnIndex) {
                return event
            }
        }
        return null
    }

    /**
     * Re-runs the **real** bots from the seed and checks that they still play what was recorded.
     *
     * Free to run and remarkably strong: it catches accidental non-determinism, an iteration order
     * that shifted, a tuned constant that changed a bot's behaviour, and a codegen difference
     * between two Kotlin versions — all as a plain assertion over a couple of hundred bytes.
     *
     * It re-runs under the configuration the match was *played* under and never under today's
     * defaults, and that comes for free: the per-slot allowances and knob values live on [setup], so
     * `Match` reads them here exactly as it read them the first time. Which is the argument for
     * keeping the allowance on the setup rather than beside it — a tuned match that verified against
     * stock bots would report a divergence that is not one.
     *
     * A divergence is not automatically a bug. It is always a question worth answering.
     *
     * ### A partial recording is verified as far as it goes
     *
     * [outcome] being `null` means the recording stopped before the match did, which is what Share
     * publishes — `record()` is taken at whatever turn the board is on. The replay always runs to
     * completion, so it is *longer* by construction, and holding it to the recorded length would
     * report a divergence for every mid-match link anyone has ever sent. So a partial record is
     * checked against the prefix and no further. A replay that stops **short** of the recording is
     * still a divergence, and so is one whose eliminations differ inside the recorded turns.
     */
    public fun verify(registry: BotRegistry): ReplayVerification {
        val replayed = Match(setup, registry)
        replayed.runToCompletion()
        val actual = replayed.record()

        val shared = minOf(moves.size, actual.moves.size)
        for (i in 0 until shared) {
            if (actual.moves[i] != moves[i]) {
                return ReplayVerification(
                    false,
                    i,
                    "move $i was recorded as ${moves[i]} but replays as ${actual.moves[i]}",
                )
            }
        }

        val partial = outcome == null
        val lengthDiverged =
            if (partial) actual.moves.size < moves.size else actual.moves.size != moves.size
        if (lengthDiverged) {
            return ReplayVerification(
                false,
                shared,
                "the recording holds ${moves.size} moves but the replay produced ${actual.moves.size}",
            )
        }

        // turnIndex counts every turn, moves and no-move eliminations alike, so turnCount is exactly
        // where a partial recording stops and everything the replay did after it is not ours to judge.
        val replayedTerminals =
            if (partial) actual.terminals.filter { it.turnIndex < turnCount } else actual.terminals
        if (replayedTerminals != terminals) {
            return ReplayVerification(
                false,
                -1,
                "terminal events differ: recorded $terminals, replayed $replayedTerminals",
            )
        }

        if (outcome != null && actual.outcome != outcome) {
            return ReplayVerification(false, -1, "outcome was $outcome but replays as ${actual.outcome}")
        }

        return ReplayVerification.OK
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MatchRecord) return false
        return setup == other.setup &&
            moves == other.moves &&
            terminals == other.terminals &&
            outcome == other.outcome
    }

    override fun hashCode(): Int {
        var result = setup.hashCode()
        result = 31 * result + moves.hashCode()
        result = 31 * result + terminals.hashCode()
        result = 31 * result + (outcome?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "MatchRecord($setup, ${moves.size} moves, ${terminals.size} terminals, $outcome)"

    internal companion object {
        /**
         * How many snakes can possibly leave without moving.
         *
         * In a contested match the last survivor wins the moment everyone else is out, so the
         * answer is one fewer than the field. A **solo** match has no survivor to crown and ends
         * with `ALL_ELIMINATED`, so its one snake really can be the one that leaves — which is the
         * case a plain `slots - 1` gets wrong, and the reason a lone player resigning used to be
         * unrecordable.
         */
        fun maxTerminals(slotCount: Int): Int = if (slotCount <= 1) slotCount else slotCount - 1
    }
}
