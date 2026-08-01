package ao.snakewarz.lab.endgame

import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExactEndgameSolverTest {
    @Test
    fun `ordered bodies distinguish a structural collision in the board fingerprint`() {
        val one = threadedBoard(firstSpawnRow = 0, firstSpawnCol = 0, firstMoves = FIRST_THREAD)
        val other = threadedBoard(firstSpawnRow = 1, firstSpawnCol = 1, firstMoves = SECOND_THREAD)

        assertEquals(one.hash, other.hash, "occupancy, heads, phases and actor are identical")
        assertEquals(one.snake(SnakeId(0)).lastDirection, other.snake(SnakeId(0)).lastDirection)
        assertNotEquals(ExactStateCodec.snapshot(one), ExactStateCodec.snapshot(other))
    }

    @Test
    fun `table verifies structure after a signature collision`() {
        val table = ExactTable(maxEntries = 4, memoryMiB = 1)
        val one = LongArray(ExactStateCodec.WORDS)
        val other = LongArray(ExactStateCodec.WORDS).also { it[ExactStateCodec.WORDS - 1] = 1L }
        val sharedSignature = 7L

        table.put(table.find(one, 0, sharedSignature), one, 0, sharedSignature, value = -1, optimalMask = 1)
        table.put(table.find(other, 0, sharedSignature), other, 0, sharedSignature, value = 1, optimalMask = 2)

        assertEquals(-1, table.valueAt(table.find(one, 0, sharedSignature)))
        assertEquals(1, table.valueAt(table.find(other, 0, sharedSignature)))
        assertTrue(table.structuralCollisions > 0)
    }

    @Test
    fun `all outcome-equal legal moves remain proved optimal`() {
        val board = board(
            spawns = arrayOf(3 to 3, 7 to 7),
            rules = RulesConfig(maxTurns = 1),
        )
        val legal = board.legalMoves(SnakeId(0))
        val solver = ExactEndgameSolver(maxNodesPerPosition = 16, memoryMiB = 1)

        val solved = solver.solve(board, SnakeId(0), order(board))

        assertTrue(solved.solved)
        assertEquals(0, solved.value)
        assertEquals(legal.bits, solved.optimalMask)
        assertEquals(5L, solved.stats.calls)
        val proof = ExactProofVerifier().verify(board.copy(), SnakeId(0), order(board), solved, solver.table)
        assertTrue(proof.complete)
        assertEquals(solved.stats.uniqueStates, proof.verifiedStates)
        assertEquals(legal.size.toLong(), proof.verifiedEdges)
    }

    @Test
    fun `an opponent chooses the root loss as the paranoid minimizer`() {
        val board = board(
            spawns = arrayOf(0 to 0, 3 to 2),
            rules = RulesConfig(growEveryNthMove = 1, maxTurns = 7),
        )
        apply(
            board,
            Direction.EAST,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.WEST,
        )
        val solver = ExactEndgameSolver(maxNodesPerPosition = 32, memoryMiB = 1)

        val solved = solver.solve(board, SnakeId(0), order(board))

        assertTrue(solved.solved)
        assertEquals(-1, solved.value)
        assertEquals(1 shl Direction.WEST.ordinal, solved.optimalMask)
        ExactProofVerifier().verify(board.copy(), SnakeId(0), order(board), solved, solver.table)
    }

    @Test
    fun `a trapped actor has the proved forced north move`() {
        val board = board(
            spawns = arrayOf(0 to 0, 3 to 0),
            rules = RulesConfig(growEveryNthMove = 1, maxTurns = 20),
        )
        apply(
            board,
            Direction.EAST,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.EAST,
        )
        assertTrue(board.legalMoves(SnakeId(0)).isEmpty)
        val solver = ExactEndgameSolver(maxNodesPerPosition = 16, memoryMiB = 1)

        val solved = solver.solve(board, SnakeId(0), order(board))

        assertTrue(solved.solved)
        assertEquals(-1, solved.value)
        assertEquals(1 shl Direction.NORTH.ordinal, solved.optimalMask)
        ExactProofVerifier().verify(board.copy(), SnakeId(0), order(board), solved, solver.table)
    }

    @Test
    fun `node cap abort restores the root and exposes no partial proof`() {
        val board = board(
            spawns = arrayOf(3 to 3, 7 to 7),
            rules = RulesConfig(maxTurns = 20),
        )
        val state = ExactStateCodec.snapshot(board)
        val hash = board.hash
        val solver = ExactEndgameSolver(maxNodesPerPosition = 16, memoryMiB = 1)

        val solved = solver.solve(board, SnakeId(0), order(board), nodeLimit = 1)

        assertFalse(solved.solved)
        assertEquals(ExactAbortReason.NODE_CAP, solved.abortReason)
        assertEquals(hash, board.hash)
        assertEquals(0, board.undoDepth)
        assertEquals(state, ExactStateCodec.snapshot(board))
        assertFailsWith<IllegalArgumentException> {
            ExactProofVerifier().verify(board.copy(), SnakeId(0), order(board), solved, solver.table)
        }
    }

    @Test
    fun `stop request aborts wholesale from inside search and restores the root`() {
        val board = board(
            spawns = arrayOf(3 to 3, 7 to 7),
            rules = RulesConfig(maxTurns = 40),
        )
        val state = ExactStateCodec.snapshot(board)
        val solver = ExactEndgameSolver(maxNodesPerPosition = 2_048, memoryMiB = 1)
        var checks = 0

        val solved = solver.solve(board, SnakeId(0), order(board)) { ++checks >= 2 }

        assertFalse(solved.solved)
        assertEquals(ExactAbortReason.STOP_REQUESTED, solved.abortReason)
        assertEquals(1_024L, solved.stats.calls)
        assertEquals(0, board.undoDepth)
        assertEquals(state, ExactStateCodec.snapshot(board))
    }

    @Test
    fun `proof stop request restores the fresh root and exposes no complete certificate`() {
        val board = board(
            spawns = arrayOf(3 to 3, 7 to 7),
            rules = RulesConfig(maxTurns = 1),
        )
        val solver = ExactEndgameSolver(maxNodesPerPosition = 16, memoryMiB = 1)
        val solved = solver.solve(board, SnakeId(0), order(board))
        val fresh = board.copy()
        val state = ExactStateCodec.snapshot(fresh)

        val proof = ExactProofVerifier().verify(fresh, SnakeId(0), order(board), solved, solver.table) { true }

        assertFalse(proof.complete)
        assertEquals(ExactAbortReason.STOP_REQUESTED, proof.abortReason)
        assertEquals(0L, proof.visits)
        assertEquals(0, fresh.undoDepth)
        assertEquals(state, ExactStateCodec.snapshot(fresh))
    }

    @Test
    fun `proof certificate is bound to rules and root identity`() {
        val board = board(
            spawns = arrayOf(3 to 3, 7 to 7),
            rules = RulesConfig(maxTurns = 1),
        )
        val solver = ExactEndgameSolver(maxNodesPerPosition = 16, memoryMiB = 1)
        val solved = solver.solve(board, SnakeId(0), order(board))

        assertFailsWith<IllegalArgumentException> {
            ExactProofVerifier().verify(board.copy(), SnakeId(1), order(board), solved, solver.table)
        }
        assertFailsWith<IllegalArgumentException> {
            ExactProofVerifier().verify(board.copy(), SnakeId(0), intArrayOf(1, 0), solved, solver.table)
        }
        val otherRules = board(spawns = arrayOf(3 to 3, 7 to 7), rules = RulesConfig(maxTurns = 2))
        assertFailsWith<IllegalArgumentException> {
            ExactProofVerifier().verify(otherRules, SnakeId(0), order(board), solved, solver.table)
        }
        val walled = board(
            spawns = arrayOf(3 to 3, 7 to 7),
            rules = RulesConfig(maxTurns = 1),
            walls = arrayOf(0 to 0),
        )
        assertFailsWith<IllegalArgumentException> {
            ExactProofVerifier().verify(walled, SnakeId(0), order(board), solved, solver.table)
        }
    }

    @Test
    fun `path allocation is included in the declared memory cap`() {
        val board = board(
            spawns = arrayOf(3 to 3, 7 to 7),
            rules = RulesConfig(maxTurns = 20_000),
        )
        val solver = ExactEndgameSolver(maxNodesPerPosition = 4, memoryMiB = 1)

        assertFailsWith<IllegalArgumentException> {
            solver.solve(board, SnakeId(0), order(board))
        }
    }

    @Test
    fun `proof verification rejects an incomplete certificate`() {
        val board = board(
            spawns = arrayOf(3 to 3, 7 to 7),
            rules = RulesConfig(maxTurns = 1),
        )
        val solver = ExactEndgameSolver(maxNodesPerPosition = 16, memoryMiB = 1)
        val solved = solver.solve(board, SnakeId(0), order(board))
        solver.table.clear()

        assertFailsWith<IllegalStateException> {
            ExactProofVerifier().verify(board.copy(), SnakeId(0), order(board), solved, solver.table)
        }
    }

    private fun threadedBoard(
        firstSpawnRow: Int,
        firstSpawnCol: Int,
        firstMoves: Array<Direction>,
    ): Board {
        val board = board(
            spawns = arrayOf(firstSpawnRow to firstSpawnCol, 7 to 7),
            rules = RulesConfig(growEveryNthMove = 1, maxTurns = 100),
        )
        for (i in firstMoves.indices) {
            board.apply(SnakeId(0), firstMoves[i])
            board.apply(SnakeId(1), OPPONENT_THREAD[i])
        }
        return board
    }

    private fun apply(board: Board, vararg moves: Direction) {
        for (move in moves) {
            board.apply(board.toAct, move)
        }
    }

    private fun order(board: Board): IntArray = IntArray(board.snakeCount) { it }

    private fun board(
        spawns: Array<Pair<Int, Int>>,
        rules: RulesConfig,
        order: IntArray = IntArray(spawns.size) { it },
        walls: Array<Pair<Int, Int>> = emptyArray(),
    ): Board {
        val grid = Grid(8, 8)
        return Board(
            grid = grid,
            spawnCells = IntArray(spawns.size) { grid.cellAt(spawns[it].first, spawns[it].second).index },
            rules = rules,
            turnOrder = order,
            wallCells = IntArray(walls.size) { grid.cellAt(walls[it].first, walls[it].second).index },
        )
    }

    private companion object {
        val FIRST_THREAD = arrayOf(
            Direction.SOUTH,
            Direction.EAST,
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
        )
        val SECOND_THREAD = arrayOf(
            Direction.WEST,
            Direction.NORTH,
            Direction.EAST,
            Direction.EAST,
            Direction.SOUTH,
        )
        val OPPONENT_THREAD = arrayOf(
            Direction.WEST,
            Direction.WEST,
            Direction.NORTH,
            Direction.EAST,
            Direction.EAST,
        )
    }
}
