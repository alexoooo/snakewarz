package ao.snakewarz.lab.endgame

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId

/** Exhaustive empty-8x8 minimax with every non-root player acting as one paranoid opponent. */
internal class ExactEndgameSolver(
    private val maxNodesPerPosition: Int,
    private val memoryMiB: Int,
) {
    internal val table = ExactTable(maxNodesPerPosition, memoryMiB)

    fun solve(
        board: Board,
        self: SnakeId,
        turnOrder: IntArray,
        nodeLimit: Int = maxNodesPerPosition,
        shouldStop: () -> Boolean = NEVER_STOP,
    ): ExactSolveResult {
        require(nodeLimit in 1..maxNodesPerPosition) {
            "position node cap must be 1..$maxNodesPerPosition, was $nodeLimit"
        }
        validate(board, self)
        require(turnOrder.size == board.snakeCount && turnOrder.toSet().size == board.snakeCount) {
            "exact context needs one turn-order entry per snake"
        }
        require(turnOrder.all { it in 0 until board.snakeCount }) { "exact context has an invalid turn order" }

        table.clear()
        calls = 0
        lookups = 0
        transpositions = 0
        terminalCalls = 0
        maxDepth = 0
        limit = nodeLimit.toLong()
        rootIndex = self.index
        stopRequested = shouldStop

        val rootHash = board.hash
        val rootUndo = board.undoDepth
        val rootState = ExactStateCodec.snapshot(board)
        val context = ExactSolveContext(
            rows = board.grid.rows,
            cols = board.grid.cols,
            rules = board.rules,
            snakeCount = board.snakeCount,
            self = self,
            turnOrder = turnOrder.toList(),
        )
        val depthBound = board.rules.maxTurns.toLong() + 1L
        val pathWords = depthBound * ExactStateCodec.WORDS
        require(pathWords <= Int.MAX_VALUE) {
            "exact endgame path needs $depthBound structural records"
        }
        val pathBytes = pathWords * Long.SIZE_BYTES
        val oldPathBytes = if (::pathKeys.isInitialized) pathKeys.size.toLong() * Long.SIZE_BYTES else 0L
        val allocationPeak = table.allocatedBytes + pathBytes + if (pathWords > pathKeysSize()) oldPathBytes else 0L
        require(allocationPeak <= memoryMiB.toLong() * BYTES_PER_MIB) {
            "exact arrays need ${mib(allocationPeak)} MiB including the search path, cap is $memoryMiB MiB"
        }
        if (pathWords > pathKeysSize()) {
            pathKeys = LongArray(pathWords.toInt())
        }
        pathAllocatedBytes = pathKeys.size.toLong() * Long.SIZE_BYTES
        peakAllocatedBytes = maxOf(peakAllocatedBytes, allocationPeak)

        val result = try {
            val value = search(board, 0)
            val optimalMask = if (board.outcome == null) rootEntry(board).let(table::optimalMaskAt) else 0
            ExactSolveResult(
                solved = true,
                value = value,
                optimalMask = optimalMask,
                stats = stats(),
                rootState = rootState,
                context = context,
                abortReason = null,
            )
        } catch (aborted: SearchAborted) {
            ExactSolveResult(
                solved = false,
                value = 0,
                optimalMask = 0,
                stats = stats(),
                rootState = rootState,
                context = context,
                abortReason = aborted.reason,
            )
        }

        check(board.hash == rootHash) { "exact search changed the board hash from $rootHash to ${board.hash}" }
        check(board.undoDepth == rootUndo) {
            "exact search changed undo depth from $rootUndo to ${board.undoDepth}"
        }
        val restored = LongArray(ExactStateCodec.WORDS)
        ExactStateCodec.encode(board, restored, 0)
        check(rootState.sameAs(restored)) { "exact search did not restore the structural root state" }
        return result
    }

    private lateinit var pathKeys: LongArray
    private var rootIndex = -1
    private var limit = 0L
    private var calls = 0L
    private var lookups = 0L
    private var transpositions = 0L
    private var terminalCalls = 0L
    private var maxDepth = 0
    private var pathAllocatedBytes = 0L
    private var peakAllocatedBytes = 0L
    private lateinit var stopRequested: () -> Boolean

    private fun search(board: Board, depth: Int): Int {
        if (calls == limit) {
            throw SearchAborted(ExactAbortReason.NODE_CAP)
        }
        if ((calls and STOP_CHECK_MASK) == 0L && stopRequested()) {
            throw SearchAborted(ExactAbortReason.STOP_REQUESTED)
        }
        calls++
        if (depth > maxDepth) {
            maxDepth = depth
        }

        board.outcome?.let { outcome ->
            terminalCalls++
            return terminalValue(outcome)
        }

        val offset = depth * ExactStateCodec.WORDS
        ExactStateCodec.encode(board, pathKeys, offset)
        val signature = ExactStateCodec.hash(pathKeys, offset)
        lookups++
        val existing = table.find(pathKeys, offset, signature)
        if (existing >= 0) {
            transpositions++
            return table.valueAt(existing)
        }

        val mover = board.toAct
        val legal = board.legalMoves(mover)
        val maximizing = mover.index == rootIndex
        var best = if (maximizing) LOSS_BELOW else WIN_ABOVE
        var optimalMask = 0
        val count = legal.size.coerceAtLeast(1)
        for (i in 0 until count) {
            val direction = if (legal.isEmpty) Direction.NORTH else legal.nth(i)
            board.apply(mover, direction)
            val value = try {
                search(board, depth + 1)
            } finally {
                board.undo()
            }

            if ((maximizing && value > best) || (!maximizing && value < best)) {
                best = value
                optimalMask = 1 shl direction.ordinal
            } else if (value == best) {
                optimalMask = optimalMask or (1 shl direction.ordinal)
            }
        }

        ExactStateCodec.encode(board, pathKeys, offset)
        val insertion = table.find(pathKeys, offset, signature)
        check(insertion < 0) { "an exact state re-entered itself without advancing the turn" }
        table.put(insertion, pathKeys, offset, signature, best, optimalMask)
        return best
    }

    private fun rootEntry(board: Board): Int {
        ExactStateCodec.encode(board, pathKeys, 0)
        val slot = table.find(pathKeys, 0, ExactStateCodec.hash(pathKeys, 0))
        check(slot >= 0) { "completed exact root is absent from its proof table" }
        return slot
    }

    private fun terminalValue(outcome: MatchOutcome): Int = when {
        outcome.isDraw -> DRAW
        outcome.winner.index == rootIndex -> WIN
        else -> LOSS
    }

    private fun stats(): ExactSolveStats = ExactSolveStats(
        calls = calls,
        uniqueStates = table.size,
        lookups = lookups,
        transpositions = transpositions,
        terminalCalls = terminalCalls,
        maxDepth = maxDepth,
        structuralCollisions = table.structuralCollisions,
        allocatedBytes = maxOf(table.allocatedBytes + pathAllocatedBytes, peakAllocatedBytes),
    )

    private fun validate(board: Board, self: SnakeId) {
        require(board.grid.rows == BOARD_SIDE && board.grid.cols == BOARD_SIDE) {
            "solve-endgame needs an ${BOARD_SIDE}x$BOARD_SIDE, was ${board.grid}"
        }
        require(board.snakeCount in 1..ExactStateCodec.MAX_SNAKES) {
            "solve-endgame supports 1..${ExactStateCodec.MAX_SNAKES} snakes, was ${board.snakeCount}"
        }
        require(self.index in 0 until board.snakeCount) { "root player $self is not on this board" }
        for (row in 0 until BOARD_SIDE) {
            for (col in 0 until BOARD_SIDE) {
                require(!board.isWall(board.grid.cellAt(row, col))) {
                    "solve-endgame needs empty 8x8; ($row, $col) is a wall"
                }
            }
        }
    }

    private fun pathKeysSize(): Long = if (::pathKeys.isInitialized) pathKeys.size.toLong() else 0L

    private class SearchAborted(
        val reason: ExactAbortReason,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        const val BOARD_SIDE = 8
        const val LOSS = -1
        const val DRAW = 0
        const val WIN = 1
        const val LOSS_BELOW = LOSS - 1
        const val WIN_ABOVE = WIN + 1
        const val STOP_CHECK_MASK = 1_023L
        const val BYTES_PER_MIB = 1024L * 1024L

        val NEVER_STOP: () -> Boolean = { false }

        fun mib(bytes: Long): Long = (bytes + BYTES_PER_MIB - 1) / BYTES_PER_MIB
    }
}

internal class ExactSolveResult(
    val solved: Boolean,
    val value: Int,
    val optimalMask: Int,
    val stats: ExactSolveStats,
    val rootState: ExactState,
    val context: ExactSolveContext,
    val abortReason: ExactAbortReason?,
)

internal data class ExactSolveContext(
    val rows: Int,
    val cols: Int,
    val rules: RulesConfig,
    val snakeCount: Int,
    val self: SnakeId,
    val turnOrder: List<Int>,
)

internal enum class ExactAbortReason {
    NODE_CAP,
    STOP_REQUESTED,
}

internal class ExactSolveStats(
    val calls: Long,
    val uniqueStates: Int,
    val lookups: Long,
    val transpositions: Long,
    val terminalCalls: Long,
    val maxDepth: Int,
    val structuralCollisions: Long,
    val allocatedBytes: Long,
)
