package ao.snakewarz.lab.endgame

import ao.snakewarz.botapi.registry.BotRegistry
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import ao.snakewarz.lab.arena.Openings
import ao.snakewarz.lab.arena.completeOpeningIdentity
import ao.snakewarz.lab.arena.completeOpeningSpawns
import ao.snakewarz.lab.arena.moveStreamHash
import ao.snakewarz.lab.log.LoggedMatch
import ao.snakewarz.lab.log.MatchLog
import ao.snakewarz.lab.log.RunHeader
import ao.snakewarz.lab.log.expandedSpec
import ao.snakewarz.match.Match
import ao.snakewarz.match.StepResult
import ao.snakewarz.match.replay.MatchRecord
import ao.snakewarz.match.replay.ReplayCodec
import ao.snakewarz.match.tournament.Contestant
import ao.snakewarz.match.tournament.TournamentFormat
import java.nio.file.Path

/** One complete-opening replay containing exactly one seat of the named champion. */
internal class EndgameReplay(
    val key: String,
    val block: String,
    val championSeat: SnakeId,
    val record: MatchRecord,
)

internal class EndgameCorpus(
    val run: RunHeader,
    val replays: List<EndgameReplay>,
)

/** One champion choice at the first turn where a replay crossed [threshold] free cells. */
internal class EndgameSample(
    val replay: EndgameReplay,
    val threshold: Int,
    val turnIndex: Int,
    val remaining: Int,
    val recordedMove: Direction,
    val state: ExactState,
    internal val rank: Long,
)

internal class EndgameSelection(
    val thresholds: IntArray,
    val samples: List<EndgameSample>,
    val candidates: IntArray,
) {
    fun at(threshold: Int): List<EndgameSample> = samples.filter { it.threshold == threshold }
}

/** Loads and cross-checks the replay population before any exact work is allocated. */
internal fun loadEndgameCorpus(directory: Path, championSpec: String, registry: BotRegistry): EndgameCorpus {
    val store = MatchLog(directory)
    val runs = store.runs()
    require(runs.size == 1) { "$directory has ${runs.size} runs; solve-endgame needs one P5 finalist run" }
    val run = runs.single()
    require(run.rows == BOARD_SIDE && run.cols == BOARD_SIDE) {
        "solve-endgame needs empty ${BOARD_SIDE}x$BOARD_SIDE, log is ${run.rows}x${run.cols}"
    }
    require(run.map == EMPTY_MAP) { "solve-endgame needs empty ${BOARD_SIDE}x$BOARD_SIDE, map key is ${run.map}" }
    require(run.openings == Openings.COMPLETE.name) {
        "solve-endgame needs complete openings, log uses ${run.openings}"
    }
    require(run.format == TournamentFormat.HEAD_TO_HEAD.name) {
        "solve-endgame needs a head-to-head finalist run, log uses ${run.format}"
    }
    val targetRules = RulesConfig()
    require(
        run.growEveryNthMove == targetRules.growEveryNthMove &&
            run.maxTurns == targetRules.maxTurns &&
            run.lastSnakeMustBeMoving == targetRules.lastSnakeMustBeMoving,
    ) { "solve-endgame needs the shipped rules $targetRules" }
    require(run.rounds > 0 && run.rounds % Openings.COMPLETE_ROUNDS_PER_REPLICATION == 0) {
        "solve-endgame needs whole 80-state complete replications, run declares ${run.rounds} rounds"
    }
    require(run.contestants.count { it == championSpec } == 1) {
        "champion '$championSpec' occurs ${run.contestants.count { it == championSpec }} times in the run header"
    }
    require(run.contestants.size >= 2) { "solve-endgame finalist run needs at least two contestants" }

    val logged = store.matches().filter { match ->
        match.run == run.id && match.slots.any { it.spec == championSpec }
    }
    require(logged.isNotEmpty()) { "$directory has no matches containing champion '$championSpec'" }
    val expectedChampionMatches = (run.contestants.size - 1).toLong() * run.rounds
    require(logged.size.toLong() == expectedChampionMatches) {
        "champion '$championSpec' has ${logged.size} of $expectedChampionMatches declared finalist matches"
    }
    validateFinalistSchedule(run, logged, championSpec)
    val encoded = store.replays()
    val replays = logged.sortedBy { it.index }.map { match ->
        val key = "${match.run} ${match.index}"
        val payload = encoded[key] ?: error("replay '$key' is missing; P6 needs --replays all")
        val record = ReplayCodec.decode(payload)
        validateEndgameReplay(run, match, record, key, registry)
        val champion = match.slots.filter { it.spec == championSpec }
        require(champion.size == 1) { "replay '$key' seats champion '$championSpec' ${champion.size} times" }
        EndgameReplay(
            key = key,
            block = "opening:${requireNotNull(match.openingIdentity) { "replay '$key' has no complete-opening id" }}",
            championSeat = SnakeId(champion.single().seat),
            record = record,
        )
    }
    val expectedBlocks = (0 until Openings.COMPLETE_POPULATION)
        .mapTo(sortedSetOf()) { "opening:${completeOpeningIdentity(it)}" }
    val actualBlocks = replays.mapTo(sortedSetOf()) { it.block }
    require(actualBlocks == expectedBlocks) {
        "champion '$championSpec' needs all ${Openings.COMPLETE_POPULATION} complete-opening blocks, " +
            "found ${actualBlocks.size}"
    }
    return EndgameCorpus(run, replays)
}

/** Selects at most one position per complete-opening block at every threshold. */
internal fun selectEndgamePositions(
    replays: List<EndgameReplay>,
    thresholds: IntArray,
    positionsPerThreshold: Int,
    seed: Long,
): EndgameSelection {
    require(thresholds.isNotEmpty()) { "solve-endgame needs at least one threshold" }
    require(thresholds.all { it in 0..INITIAL_FREE_CELLS }) {
        "solve-endgame thresholds must be in 0..$INITIAL_FREE_CELLS"
    }
    require(thresholds.toSet().size == thresholds.size) { "solve-endgame thresholds must not repeat" }
    require(positionsPerThreshold > 0) { "positions-per-threshold must be positive, was $positionsPerThreshold" }

    val orderedThresholds = thresholds.sorted().toIntArray()
    val byThreshold = Array(orderedThresholds.size) { mutableListOf<EndgameSample>() }
    for (replay in replays) {
        val match = Match.playback(replay.record)
        val selected = arrayOfNulls<EndgameSample>(orderedThresholds.size)
        var moveIndex = 0
        while (match.outcome == null) {
            val turnIndex = match.turnIndex
            val terminal = replay.record.terminalAt(turnIndex)
            val mover = match.view.toAct
            val legal = match.view.legalMoves(mover)
            if (mover == replay.championSeat && terminal == null && legal.size >= MINIMUM_CHOICE) {
                val remaining = remainingCells(match.view)
                val recordedMove = replay.record.moves[moveIndex]
                for (i in orderedThresholds.indices) {
                    if (selected[i] == null && remaining <= orderedThresholds[i]) {
                        selected[i] = EndgameSample(
                            replay = replay,
                            threshold = orderedThresholds[i],
                            turnIndex = turnIndex,
                            remaining = remaining,
                            recordedMove = recordedMove,
                            state = ExactStateCodec.snapshot(match.view),
                            rank = sampleRank(seed, replay.block, replay.key, orderedThresholds[i], turnIndex),
                        )
                    }
                }
            }

            val result = match.step()
            if (terminal == null) {
                moveIndex++
            }
            check(result != StepResult.AwaitingInput) { "complete replay '${replay.key}' stopped at turn $turnIndex" }
        }
        check(match.outcome == replay.record.outcome) { "replay '${replay.key}' ended as ${match.outcome}" }
        for (i in selected.indices) {
            selected[i]?.let { byThreshold[i] += it }
        }
    }

    val candidates = IntArray(orderedThresholds.size)
    val retained = mutableListOf<EndgameSample>()
    for (i in orderedThresholds.indices) {
        candidates[i] = byThreshold[i].size
        val onePerBlock = byThreshold[i]
            .groupBy { it.replay.block }
            .values
            .map { block -> block.minWith(compareBy<EndgameSample> { it.rank }.thenBy { it.replay.key }) }
            .sortedWith(compareBy<EndgameSample> { it.rank }.thenBy { it.replay.block }.thenBy { it.replay.key })
        retained += onePerBlock.take(positionsPerThreshold)
    }
    return EndgameSelection(orderedThresholds, retained, candidates)
}

/** Rebuilds a sampled state from the recorded setup and prefix, without consulting a bot. */
internal fun replayBoardAt(record: MatchRecord, targetTurn: Int): Board {
    require(targetTurn in 0..record.turnCount) {
        "target turn $targetTurn is outside replay length ${record.turnCount}"
    }
    val setup = record.setup
    val grid = Grid(setup.rows, setup.cols)
    val spawns = setup.spawns()
    val walls = setup.walls()
    val board = Board(
        grid = grid,
        spawnCells = IntArray(setup.slotCount) { playableToPadded(grid, setup.cols, spawns[it]) },
        rules = setup.rules,
        turnOrder = setup.turnOrder(),
        wallCells = IntArray(walls.size) { playableToPadded(grid, setup.cols, walls[it]) },
    )

    var moveIndex = 0
    while (board.turnIndex < targetTurn) {
        val terminal = record.terminalAt(board.turnIndex)
        if (terminal == null) {
            board.apply(board.toAct, record.moves[moveIndex++])
        } else {
            require(terminal.slot == board.toAct) {
                "terminal at turn ${board.turnIndex} names ${terminal.slot}, actor is ${board.toAct}"
            }
            board.eliminate(terminal.slot, terminal.reason)
        }
    }
    return board.copy()
}

internal fun validateEndgameReplay(
    run: RunHeader,
    match: LoggedMatch,
    record: MatchRecord,
    key: String,
    registry: BotRegistry,
) {
    val setup = record.setup
    require(setup.rows == BOARD_SIDE && setup.cols == BOARD_SIDE && setup.walls().isEmpty()) {
        "replay '$key' is not empty ${BOARD_SIDE}x$BOARD_SIDE"
    }
    require(setup.slotCount == DUEL_SLOTS) { "replay '$key' has ${setup.slotCount} slots, P6 boss is a duel" }
    val openingIdentity = requireNotNull(match.openingIdentity) { "replay '$key' has no complete-opening id" }
    val openingIndex = (0 until Openings.COMPLETE_POPULATION)
        .firstOrNull { completeOpeningIdentity(it) == openingIdentity }
    require(openingIndex != null) { "replay '$key' has unknown complete-opening id '$openingIdentity'" }
    require(setup.spawns().contentEquals(completeOpeningSpawns(openingIndex))) {
        "replay '$key' spawns disagree with complete opening '$openingIdentity'"
    }
    for (slot in match.slots) {
        require(slot.seat in 0 until setup.slotCount) { "match '$key' has invalid seat ${slot.seat}" }
        require(slot.budget == setup.budgetFor(slot.seat)) {
            "replay '$key' seat ${slot.seat} budget disagrees with its match row"
        }
        val replaySpec = expandedSpec(
            Contestant(
                bot = setup.slots[slot.seat],
                budgetPerTurn = setup.budgetFor(slot.seat),
                params = setup.paramsFor(slot.seat),
            ),
            registry,
            setup.budgetPerTurn,
        )
        require(replaySpec == slot.spec) {
            "replay '$key' seat ${slot.seat} is '$replaySpec', match row says '${slot.spec}'"
        }
    }
    require(setup.rules.growEveryNthMove == run.growEveryNthMove && setup.rules.maxTurns == run.maxTurns) {
        "replay '$key' rules disagree with its run header"
    }
    require(setup.rules.lastSnakeMustBeMoving == run.lastSnakeMustBeMoving) {
        "replay '$key' moving-winner rule disagrees with its run header"
    }
    require(setup.seed == match.seed) { "replay '$key' seed disagrees with its match row" }
    require(setup.turnOrder().contentEquals(match.turnOrder.toIntArray())) {
        "replay '$key' turn order disagrees with its match row"
    }
    require(moveStreamHash(record.moves.toList()) == match.moveStreamHash) {
        "replay '$key' move stream disagrees with its match row"
    }
    val outcome = requireNotNull(record.outcome) { "replay '$key' is partial" }
    require(record.turnCount == match.turnsPlayed) {
        "replay '$key' has ${record.turnCount} turns, match row has ${match.turnsPlayed}"
    }
    require(outcome.end.name == match.end) {
        "replay '$key' ends ${outcome.end}, match row says ${match.end}"
    }
    val loggedWinner = match.winner?.seat ?: SnakeId.NONE.index
    require(outcome.winner.index == loggedWinner) {
        "replay '$key' winner is ${outcome.winner}, match row winner is $loggedWinner"
    }
    require(record.terminals.none { it.reason == EliminationReason.FORFEIT }) { "replay '$key' contains a forfeit" }
    require(match.slots.none { it.fate == EliminationReason.FORFEIT.name }) { "match '$key' logs a forfeit" }
}

private fun validateFinalistSchedule(run: RunHeader, matches: List<LoggedMatch>, championSpec: String) {
    val repetitions = run.rounds / Openings.COMPLETE_ROUNDS_PER_REPLICATION
    for (opponent in run.contestants.filter { it != championSpec }) {
        val pairing = matches.filter { match ->
            match.slots.size == DUEL_SLOTS &&
                match.slots.mapTo(mutableSetOf()) { it.spec } == setOf(championSpec, opponent)
        }
        require(pairing.size == run.rounds) {
            "champion/opponent pair '$championSpec'/'$opponent' has ${pairing.size} of ${run.rounds} matches"
        }
        for (opening in 0 until Openings.COMPLETE_POPULATION) {
            val identity = completeOpeningIdentity(opening)
            for (seat in 0 until DUEL_SLOTS) {
                val count = pairing.count { match ->
                    match.openingIdentity == identity && match.of(championSpec)?.seat == seat
                }
                require(count == repetitions) {
                    "pair '$championSpec'/'$opponent' opening '$identity' champion seat $seat has " +
                        "$count of $repetitions repetitions"
                }
            }
        }
    }
}

private fun remainingCells(board: BoardView): Int {
    var occupied = 0
    for (slot in 0 until board.snakeCount) {
        occupied += board.snake(SnakeId(slot)).length
    }
    return board.openCount - occupied
}

private fun sampleRank(seed: Long, block: String, replay: String, threshold: Int, turnIndex: Int): Long {
    var hash = FNV_OFFSET xor seed
    for (character in block) {
        hash = (hash xor character.code.toLong()) * FNV_PRIME
    }
    for (character in replay) {
        hash = (hash xor character.code.toLong()) * FNV_PRIME
    }
    hash = (hash xor threshold.toLong()) * FNV_PRIME
    hash = (hash xor turnIndex.toLong()) * FNV_PRIME
    return SplitMix64(hash).nextLong()
}

private fun playableToPadded(grid: Grid, cols: Int, playable: Int): Int =
    grid.cellAt(playable / cols, playable % cols).index

private const val BOARD_SIDE = 8
private const val DUEL_SLOTS = 2
private const val INITIAL_FREE_CELLS = BOARD_SIDE * BOARD_SIDE - DUEL_SLOTS
private const val MINIMUM_CHOICE = 2
private const val EMPTY_MAP = "empty"
private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
