package ao.snakewarz.lab.endgame

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.snake.SnakeId

/** Replays every proof edge on a fresh board and recomputes each stored paranoid backup. */
internal class ExactProofVerifier {
    fun verify(
        board: Board,
        self: SnakeId,
        turnOrder: IntArray,
        solved: ExactSolveResult,
        table: ExactTable,
        maxVisits: Long = Long.MAX_VALUE,
        shouldStop: () -> Boolean = NEVER_STOP,
    ): ExactProofResult {
        require(solved.solved) { "an incomplete exact search has no proof to verify" }
        require(self.index in 0 until board.snakeCount) { "root player $self is not on this board" }
        require(maxVisits > 0) { "proof visit cap must be positive, was $maxVisits" }
        for (row in 0 until board.grid.rows) {
            for (col in 0 until board.grid.cols) {
                require(!board.isWall(board.grid.cellAt(row, col))) {
                    "fresh exact replay has an interior wall at ($row, $col)"
                }
            }
        }
        require(
            solved.context == ExactSolveContext(
                rows = board.grid.rows,
                cols = board.grid.cols,
                rules = board.rules,
                snakeCount = board.snakeCount,
                self = self,
                turnOrder = turnOrder.toList(),
            ),
        ) { "fresh replay has a different exact-solver context" }

        val rootHash = board.hash
        val rootUndo = board.undoDepth
        val rootWords = LongArray(ExactStateCodec.WORDS)
        ExactStateCodec.encode(board, rootWords, 0)
        require(solved.rootState.sameAs(rootWords)) { "fresh replay does not reconstruct the solved root" }

        rootIndex = self.index
        words = LongArray(ExactStateCodec.WORDS)
        verifiedStates = 0
        verifiedEdges = 0
        visits = 0
        visitLimit = maxVisits
        stopRequested = shouldStop
        table.clearVerification()

        val abortReason = try {
            val value = verifyState(board, table)
            check(value == solved.value) { "proof root is $value, solver reported ${solved.value}" }
            if (board.outcome == null) {
                ExactStateCodec.encode(board, words, 0)
                val slot = table.find(words, 0, ExactStateCodec.hash(words, 0))
                check(slot >= 0) { "proof table lost its root" }
                check(table.optimalMaskAt(slot) == solved.optimalMask) {
                    "proof root mask is ${table.optimalMaskAt(slot)}, solver reported ${solved.optimalMask}"
                }
            } else {
                check(solved.optimalMask == 0) { "terminal root has move mask ${solved.optimalMask}" }
            }
            check(verifiedStates == table.size) {
                "proof reached $verifiedStates of ${table.size} stored nonterminal states"
            }
            null
        } catch (aborted: ProofAborted) {
            aborted.reason
        }

        check(board.hash == rootHash) { "proof verification changed the board hash" }
        check(board.undoDepth == rootUndo) { "proof verification changed undo depth" }
        ExactStateCodec.encode(board, rootWords, 0)
        check(solved.rootState.sameAs(rootWords)) { "proof verification did not restore the structural root" }
        return ExactProofResult(
            complete = abortReason == null,
            verifiedStates = verifiedStates,
            verifiedEdges = verifiedEdges,
            visits = visits,
            abortReason = abortReason,
        )
    }

    private var rootIndex = -1
    private lateinit var words: LongArray
    private var verifiedStates = 0
    private var verifiedEdges = 0L
    private var visits = 0L
    private var visitLimit = 0L
    private lateinit var stopRequested: () -> Boolean

    private fun verifyState(board: Board, table: ExactTable): Int {
        if (visits == visitLimit) {
            throw ProofAborted(ExactAbortReason.NODE_CAP)
        }
        if ((visits and STOP_CHECK_MASK) == 0L && stopRequested()) {
            throw ProofAborted(ExactAbortReason.STOP_REQUESTED)
        }
        visits++
        board.outcome?.let(::terminalValue)?.let { return it }

        ExactStateCodec.encode(board, words, 0)
        val slot = table.find(words, 0, ExactStateCodec.hash(words, 0))
        check(slot >= 0) { "proof omits a nonterminal state at turn ${board.turnIndex}" }
        if (table.isVerified(slot)) {
            return table.valueAt(slot)
        }

        val mover = board.toAct
        val legal = board.legalMoves(mover)
        val maximizing = mover.index == rootIndex
        var best = if (maximizing) LOSS_BELOW else WIN_ABOVE
        var optimalMask = 0
        val count = legal.size.coerceAtLeast(1)
        for (i in count - 1 downTo 0) {
            val direction = if (legal.isEmpty) Direction.NORTH else legal.nth(i)
            board.apply(mover, direction)
            val value = try {
                verifyState(board, table)
            } finally {
                board.undo()
            }
            verifiedEdges++

            if ((maximizing && value > best) || (!maximizing && value < best)) {
                best = value
                optimalMask = 1 shl direction.ordinal
            } else if (value == best) {
                optimalMask = optimalMask or (1 shl direction.ordinal)
            }
        }

        check(best == table.valueAt(slot)) {
            "proof value at turn ${board.turnIndex} is $best, table stores ${table.valueAt(slot)}"
        }
        check(optimalMask == table.optimalMaskAt(slot)) {
            "proof mask at turn ${board.turnIndex} is $optimalMask, table stores ${table.optimalMaskAt(slot)}"
        }
        table.markVerified(slot)
        verifiedStates++
        return best
    }

    private fun terminalValue(outcome: MatchOutcome): Int = when {
        outcome.isDraw -> DRAW
        outcome.winner.index == rootIndex -> WIN
        else -> LOSS
    }

    private class ProofAborted(
        val reason: ExactAbortReason,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        const val LOSS = -1
        const val DRAW = 0
        const val WIN = 1
        const val LOSS_BELOW = LOSS - 1
        const val WIN_ABOVE = WIN + 1
        const val STOP_CHECK_MASK = 1_023L

        val NEVER_STOP: () -> Boolean = { false }
    }
}

internal class ExactProofResult(
    val complete: Boolean,
    val verifiedStates: Int,
    val verifiedEdges: Long,
    val visits: Long,
    val abortReason: ExactAbortReason?,
)
