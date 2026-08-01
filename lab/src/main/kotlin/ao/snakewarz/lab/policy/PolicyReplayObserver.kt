package ao.snakewarz.lab.policy

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.botapi.registry.BotEntry
import ao.snakewarz.botapi.registry.BotFactory
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.replay.MatchRecord
import ao.snakewarz.match.tournament.Contestant

/** One seat-local probe constructor, adapted from the JVM-only policy bridge. */
internal class PolicyProbeCase(
    val key: String,
    val create: (BotSetup) -> PolicyTurnProbe,
)

internal fun interface PolicyTurnProbe {
    fun choose(turn: Turn): PolicyProbeChoice
}

internal class PolicyProbeChoice(
    val selected: Direction,
    val maxima: DirectionSet,
)

/** Optional seat-local reader for a second instrument sharing the validated replay walk. */
internal fun interface PolicyCaptureFactory {
    fun create(setup: BotSetup): PolicySampleCapture
}

internal fun interface PolicySampleCapture {
    fun capture(turn: Turn, sample: PolicySample, expertMove: Direction)
}

internal class PolicyCaseReading(
    val key: String,
    val tied: Boolean,
    val topOne: Boolean,
    val ceiling: Boolean,
)

/** One sampled position and every case's answer on the exact same live board. */
internal class PolicyObservation(
    val sample: PolicySample,
    val fill: Double,
    val readings: List<PolicyCaseReading>,
)

/**
 * Replays one recorded line and appraises only [targets].
 *
 * The expert and probes are created once per seat and reused, matching their deployment lifetime.
 * Their answers are discarded; [ReplayScript] supplies the move that advances the board, so every
 * case and expert sees the P1 position stream rather than a game it chose for itself.
 */
internal fun observePolicyReplay(
    record: MatchRecord,
    targets: List<PolicySample>,
    expert: Contestant,
    expertEntry: BotEntry,
    cases: List<PolicyProbeCase>,
    capture: PolicyCaptureFactory? = null,
): List<PolicyObservation> {
    val allowance = requireNotNull(expert.budgetPerTurn) { "a policy expert needs an explicit budget" }
    val setup = analysisSetup(record, expert, allowance)
    val registry = ObserverRegistry(record, expertEntry, cases, targets, capture)
    val match = Match(setup, registry)

    while (match.outcome == null) {
        val result = match.step()
        registry.failure?.let { throw it }
        if (result == StepResult.AwaitingInput) {
            break
        }
    }

    val replayed = match.record()
    check(replayed.moves == record.moves) { "policy observer changed the recorded move stream" }
    check(replayed.terminals == record.terminals) { "policy observer changed the recorded terminal events" }
    check(replayed.outcome == record.outcome) { "policy observer changed the recorded outcome" }
    check(registry.observations.size == targets.size) {
        "policy observer appraised ${registry.observations.size} of ${targets.size} selected positions"
    }
    return registry.observations.sortedBy { it.sample.turnIndex }
}

private fun analysisSetup(record: MatchRecord, expert: Contestant, allowance: Int): MatchSetup {
    val original = record.setup
    return MatchSetup(
        seed = original.seed,
        rows = original.rows,
        cols = original.cols,
        rules = original.rules,
        budgetPerTurn = allowance,
        slots = original.slots,
        turnOrder = original.turnOrder(),
        spawns = original.spawns(),
        walls = original.walls(),
        budgets = IntArray(original.slotCount) { allowance },
        slotParams = List(original.slotCount) { expert.params },
    )
}

/** One cursor shared by every observer seat because the replay stream is already in play order. */
private class ReplayScript(private val record: MatchRecord) {
    private var moveIndex = 0

    fun decisionAt(turnIndex: Int, slot: Int): Decision {
        val terminal = record.terminalAt(turnIndex)
        if (terminal != null) {
            check(terminal.slot.index == slot) {
                "recorded terminal names slot ${terminal.slot}, but slot $slot acts on turn $turnIndex"
            }
            return when (terminal.reason) {
                EliminationReason.RESIGNED -> Decision.Resign
                EliminationReason.FORFEIT -> throw RecordedForfeit(turnIndex, slot)
                else -> error("${terminal.reason} is represented by a move, not a terminal event")
            }
        }

        if (moveIndex >= record.moves.size) {
            return Decision.Pending
        }
        return Decision.Move(record.moves[moveIndex++])
    }
}

private class ObserverRegistry(
    record: MatchRecord,
    private val expert: BotEntry,
    private val cases: List<PolicyProbeCase>,
    targets: List<PolicySample>,
    private val capture: PolicyCaptureFactory?,
) : BotRegistry {
    private val script = ReplayScript(record)
    private val targetsByTurn: Map<Int, PolicySample> = targets.associateBy { it.turnIndex }

    override val entries: List<BotEntry> get() = emptyList()

    val observations: MutableList<PolicyObservation> = mutableListOf()

    var failure: Throwable? = null
        private set

    override fun get(id: BotId): BotEntry = BotEntry(
        id = id,
        displayName = "Policy replay observer",
        factory = BotFactory { setup ->
            ObserverBot(
                expert = expert.factory.create(setup),
                probes = cases.map { candidate -> candidate to candidate.create(setup) },
                capture = capture?.create(setup),
                script = script,
                targets = targetsByTurn,
                observations = observations,
                failed = { cause -> if (failure == null) failure = cause },
            )
        },
    )
}

private class ObserverBot(
    private val expert: Bot,
    private val probes: List<Pair<PolicyProbeCase, PolicyTurnProbe>>,
    private val capture: PolicySampleCapture?,
    private val script: ReplayScript,
    private val targets: Map<Int, PolicySample>,
    private val observations: MutableList<PolicyObservation>,
    private val failed: (Throwable) -> Unit,
) : Bot {
    /** A partial record parks at its end rather than turning the observer into a forfeit. */
    override val interactive: Boolean get() = true

    override fun chooseMove(turn: Turn): Decision {
        try {
            check(turn.budget.consumed == 0) {
                "the expert began turn ${turn.board.turnIndex} after ${turn.budget.consumed} evaluations"
            }
            val hash = turn.board.hash
            val sample = targets[turn.board.turnIndex]
            val choices = sample?.let { probe(turn) }

            // Every turn, sampled or not. Search bots carry an RNG stream and sometimes state, so
            // skipping calls would make a later sampled answer one a deployed expert never gave.
            val expertDecision = expert.chooseMove(turn)
            check(turn.board.hash == hash) {
                "an expert or policy probe changed the live board on turn ${turn.board.turnIndex}"
            }

            if (sample != null) {
                record(turn, sample, checkNotNull(choices), expertDecision)
            }
        } catch (failure: Throwable) {
            failed(failure)
            throw failure
        }
        return script.decisionAt(turn.board.turnIndex, turn.self.index)
    }

    override fun onEliminated() {
        expert.onEliminated()
    }

    private fun probe(turn: Turn): List<Pair<String, PolicyProbeChoice>> =
        probes.map { (candidate, probe) ->
            val choice = probe.choose(turn)
            check(turn.budget.consumed == 0) {
                "policy case ${candidate.key} consumed ${turn.budget.consumed} evaluations"
            }
            check(choice.selected in turn.legalMoves) {
                "policy case ${candidate.key} selected illegal ${choice.selected} from ${turn.legalMoves}"
            }
            check(choice.selected in choice.maxima) {
                "policy case ${candidate.key} selected ${choice.selected} outside its maxima ${choice.maxima}"
            }
            candidate.key to choice
        }

    private fun record(
        turn: Turn,
        sample: PolicySample,
        choices: List<Pair<String, PolicyProbeChoice>>,
        expertDecision: Decision,
    ) {
        val expertMove = (expertDecision as? Decision.Move)?.direction
            ?: error("the expert answered $expertDecision on sampled turn ${turn.board.turnIndex}")
        check(expertMove in turn.legalMoves) {
            "the expert selected illegal $expertMove from ${turn.legalMoves} on turn ${turn.board.turnIndex}"
        }

        capture?.capture(turn, sample, expertMove)

        observations += PolicyObservation(
            sample = sample,
            fill = occupied(turn) / turn.board.openCount.toDouble(),
            readings = choices.map { (key, choice) ->
                PolicyCaseReading(
                    key = key,
                    tied = choice.maxima.size > 1,
                    topOne = choice.maxima.size == 1 && choice.selected == expertMove,
                    ceiling = expertMove in choice.maxima,
                )
            },
        )
    }

    private fun occupied(turn: Turn): Int {
        var total = 0
        for (slot in 0 until turn.board.snakeCount) {
            total += turn.board.snake(ao.snakewarz.core.snake.SnakeId(slot)).length
        }
        return total
    }
}

private class RecordedForfeit(turnIndex: Int, slot: Int) :
    RuntimeException("slot $slot forfeited on turn $turnIndex, as recorded")
