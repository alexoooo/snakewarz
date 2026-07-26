package ao.snakewarz.match

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BoardScratch
import ao.snakewarz.botapi.BotParams
import ao.snakewarz.botapi.BotRegistry
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Scratch
import ao.snakewarz.botapi.Turn
import ao.snakewarz.core.Board
import ao.snakewarz.core.BoardView
import ao.snakewarz.core.Budget
import ao.snakewarz.core.Cell
import ao.snakewarz.core.Direction
import ao.snakewarz.core.EliminationReason
import ao.snakewarz.core.Grid
import ao.snakewarz.core.MatchOutcome
import ao.snakewarz.core.MatchState
import ao.snakewarz.core.MoveOutcome
import ao.snakewarz.core.Rng
import ao.snakewarz.core.SnakeId
import ao.snakewarz.core.SplitMix64

/**
 * Runs a match, one turn at a time, and records it as it goes.
 *
 * [step] advances **at most one turn** and makes **at most one bot call**. That is the entire pacing
 * contract: there is no loop in here, no clock, and nothing that knows what a frame is. The renderer
 * decides how many steps a frame is worth and can stop between any two of them, and because a step's
 * result does not depend on how many steps preceded it in the same frame, a slow frame changes the
 * speed of a match and never its outcome.
 *
 * Bots are resolved through the [BotRegistry] *interface*. `:match` cannot see `:bots` and does not
 * want to: a replay is a list of slugs, and the codec has no opinion about what they mean.
 */
public class Match private constructor(
    public val setup: MatchSetup,
    registry: BotRegistry,
    /** Set by [playback] alone, and read by [interactive] alone. Stepping does not know about it. */
    private val scripted: Boolean,
) {
    /** A match played for real: every slot is resolved through [registry] and decides for itself. */
    public constructor(setup: MatchSetup, registry: BotRegistry) : this(setup, registry, scripted = false)

    public val grid: Grid = setup.grid()

    private val board: Board = Board(grid, setup.spawnCells(grid), setup.rules, setup.turnOrder())
    private val matchRng: Rng = SplitMix64(setup.seed)
    private val budgets: Array<Budget> = Array(setup.slotCount) { Budget(setup.budgetPerTurn) }
    private val scratches: Array<Scratch> = Array(setup.slotCount) { BoardScratch(board, budgets[it]) }

    private val bots: Array<Bot> = Array(setup.slotCount) { slot ->
        registry.entryOf(setup.slots[slot]).factory.create(
            BotSetup(
                self = SnakeId(slot),
                grid = grid,
                rules = setup.rules,
                opponents = IntArray(setup.slotCount - 1) { if (it < slot) it else it + 1 },
                rng = matchRng.fork(slot),
                params = BotParams.EMPTY,
            ),
        )
    }

    private val moves = DirectionStream()
    private val terminals = ArrayList<TerminalEvent>()
    private val turnEvents = TurnEvents()

    /** The live position. Reading it is free; keeping it past the next [step] is not — use [snapshot]. */
    public val view: BoardView get() = board

    /** Individual moves played so far, counting every snake. Not rounds. */
    public val turnIndex: Int get() = board.turnIndex

    public val outcome: MatchOutcome? get() = board.outcome

    /**
     * Whether a slot somebody steers by hand is still in the match.
     *
     * This goes false the moment that player is eliminated, because a dead slot is never asked for
     * a move again — which is exactly what lets `:ui` run a match with a person in it from the
     * keyboard and then hand the ending back to the clock so the survivors can finish it.
     *
     * Always false under [playback], and that is why it cannot simply be `bots.any { interactive }`:
     * a scripted stand-in claims to be interactive too, so that running off the end of a partial
     * recording parks instead of forfeiting. That is a driver mechanism rather than a person, and
     * nobody watching a replay is going to press a key to make it continue.
     */
    public val interactive: Boolean
        get() = !scripted && bots.indices.any { bots[it].interactive && board.snake(SnakeId(it)).alive }

    /**
     * Plays one turn.
     *
     * Every way a bot can behave has a defined consequence and none of them is silent: a legal move
     * moves, an illegal one is a `SUICIDE` or a `TRAPPED` decided by the engine, a resignation is a
     * `RESIGNED`, a thrown exception is a `FORFEIT`, and a `Pending` is a pause for an interactive
     * slot and a `FORFEIT` for any other.
     */
    public fun step(): StepResult {
        val finished = board.outcome
        if (finished != null) {
            turnEvents.clear()
            return StepResult.Finished(finished)
        }

        val id = board.toAct
        val bot = bots[id.index]
        val budget = budgets[id.index]
        budget.reset()

        val decision = try {
            bot.chooseMove(Turn(board, id, board.legalMoves(id), budget, scratches[id.index]))
        } catch (thrown: Throwable) {
            // A bot that throws has forfeited, not crashed the match. Catching it here is what makes
            // a contributed bot safe to accept: it can lose, but it cannot take the page down.
            null
        }

        if (decision == null) {
            return leave(id, EliminationReason.FORFEIT)
        }

        return when (decision) {
            is Decision.Move -> advance(id, decision.direction)

            Decision.Resign -> leave(id, EliminationReason.RESIGNED)

            Decision.Pending ->
                if (bot.interactive) {
                    turnEvents.clear()
                    StepResult.AwaitingInput
                } else {
                    leave(id, EliminationReason.FORFEIT)
                }
        }
    }

    /**
     * Steps until the match ends, and returns how.
     *
     * Termination is guaranteed by `RulesConfig.maxTurns`, so this cannot hang. It fails rather than
     * spins if a slot asks for input, because a headless match has nobody to ask.
     */
    public fun runToCompletion(): MatchOutcome {
        while (true) {
            when (val result = step()) {
                is StepResult.Finished -> return result.outcome
                StepResult.AwaitingInput -> error("slot ${board.toAct} is waiting for input this match cannot supply")
                else -> Unit
            }
        }
    }

    /** The squares the last [step] changed. The same instance every time; valid until the next step. */
    public fun events(): TurnEvents = turnEvents

    /** An immutable copy of the position. O(total snake length), so at most once per turn. */
    public fun snapshot(): MatchState = board.snapshot()

    /**
     * Where the match stands, as numbers. Safe to take mid-match, and cheap enough for once a frame.
     *
     * Nothing is accumulated to produce this — the board already knows every one of these figures,
     * so it is a read rather than a bookkeeping obligation the driver would have to keep correct.
     */
    public fun stats(): MatchStats {
        val outcome = board.outcome
        val winner = outcome?.winner
        return MatchStats(
            setup = setup,
            turnsPlayed = board.turnIndex,
            outcome = outcome,
            slots = List(setup.slotCount) { slot ->
                val snake = board.snake(SnakeId(slot))
                SlotStats(
                    slot = SnakeId(slot),
                    bot = setup.slots[slot],
                    length = snake.length,
                    movesMade = snake.movesMade,
                    alive = snake.alive,
                    fate = snake.eliminationReason,
                    winner = winner != null && winner.index == slot,
                )
            },
        )
    }

    /**
     * The match so far, as a self-contained record. Safe to take mid-match, and safe to keep — the
     * move stream and the terminal table are copied out.
     */
    public fun record(): MatchRecord = MatchRecord(setup, moves.copy(), terminals.toList(), board.outcome)

    override fun toString(): String = "Match(${setup.rows}x${setup.cols}, turn ${board.turnIndex}, ${setup.slots})"

    // -- internals ------------------------------------------------------------------------------

    private fun advance(id: SnakeId, direction: Direction): StepResult {
        // Read before applying: after the move the tail has already gone, and the renderer needs to
        // know which square to clear.
        val snake = board.snake(id)
        val vacated = if (snake.growsOnNextMove) Cell.NONE else snake.tail

        val outcome = board.apply(id, direction)
        moves.add(direction)

        turnEvents.clear()
        if (outcome == MoveOutcome.MOVED) {
            turnEvents.add(snake.head)
            if (!vacated.isNone) {
                turnEvents.add(vacated)
            }
        } else {
            notifyEliminated(id)
        }

        return StepResult.Advanced(id, direction, outcome)
    }

    private fun leave(id: SnakeId, reason: EliminationReason): StepResult {
        // Before eliminate(), which advances the clock.
        terminals.add(TerminalEvent(board.turnIndex, id, reason))
        board.eliminate(id, reason)

        turnEvents.clear()
        notifyEliminated(id)
        return StepResult.Eliminated(id, reason)
    }

    private fun notifyEliminated(id: SnakeId) {
        try {
            bots[id.index].onEliminated()
        } catch (thrown: Throwable) {
            // The snake is already out and the board is already decided. There is nothing this
            // could usefully change, so a bot failing to clean up after itself is not the driver's
            // problem.
        }
    }

    public companion object {
        /**
         * A match that plays [record] back instead of consulting any bot.
         *
         * Every slot is filled with a scripted stand-in, so playback costs no search at all —
         * seeking to turn N is a few microseconds of replaying moves onto a fresh board, followed by
         * one full repaint.
         */
        public fun playback(record: MatchRecord): Match =
            Match(record.setup, ScriptedRegistry(record), scripted = true)
    }
}
