package ao.snakewarz.bots.reactive.policy

import ao.snakewarz.bots.boardOf
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionFeaturesTest {
    @Test
    fun `the feature schema is frozen and complete`() {
        assertEquals("action-policy-v1", ActionFeatures.SCHEMA)
        assertEquals(
            listOf(
                "roomShare",
                "roomGuard",
                "opponentDistance",
                "opponentUnreachable",
                "ownedShare",
                "ownedRelativeBest",
                "liberties",
                "pinchExtraGroups",
                "walls",
                "tailClosing",
            ),
            ActionFeatures.NAMES,
        )
        assertEquals(ActionFeatures.LENGTH, ActionFeatures.NAMES.size)
        assertContentEquals(
            IntArray(ActionFeatures.LENGTH) { it },
            intArrayOf(
                ActionFeatures.ROOM_SHARE,
                ActionFeatures.ROOM_GUARD,
                ActionFeatures.OPPONENT_DISTANCE,
                ActionFeatures.OPPONENT_UNREACHABLE,
                ActionFeatures.OWNED_SHARE,
                ActionFeatures.OWNED_RELATIVE_BEST,
                ActionFeatures.LIBERTIES,
                ActionFeatures.PINCH_EXTRA_GROUPS,
                ActionFeatures.WALLS,
                ActionFeatures.TAIL_CLOSING,
            ),
        )
    }

    @Test
    fun `every frozen coordinate distinguishes legal actions somewhere`() {
        val varies = BooleanArray(ActionFeatures.LENGTH)

        fun record(board: Board): Array<DoubleArray> {
            val legal = board.legalMoves(board.toAct)
            require(legal.size >= 2) { "variation fixtures need at least two legal actions" }
            val features = ActionFeatures(board.grid, board.snakeCount)
            val rows = Array(Direction.entries.size) { DoubleArray(ActionFeatures.LENGTH) }
            features.measure(board, board.toAct, legal)
            for (i in 0 until legal.size) {
                features.into(legal.nth(i), rows[legal.nth(i).ordinal])
            }
            for (feature in varies.indices) {
                val first = rows[legal.nth(0).ordinal][feature]
                for (i in 1 until legal.size) {
                    if (rows[legal.nth(i).ordinal][feature] != first) {
                        varies[feature] = true
                    }
                }
            }
            return rows
        }

        record(boardOf(4, 4, 1 to 1, 3 to 3, walls = listOf(0 to 2)))

        val growingCorridor = boardOf(1, 7, 0 to 1)
        growingCorridor.apply(growingCorridor.toAct, Direction.EAST)
        record(growingCorridor)

        record(
            boardOf(
                5,
                5,
                1 to 2,
                4 to 4,
                walls = listOf(2 to 0, 2 to 2, 2 to 3, 2 to 4),
            ),
        )

        val followingTail = boardOf(5, 5, 3 to 1)
        for (direction in listOf(
            Direction.EAST,
            Direction.EAST,
            Direction.NORTH,
            Direction.NORTH,
            Direction.WEST,
            Direction.WEST,
        )) {
            followingTail.apply(followingTail.toAct, direction)
        }
        record(followingTail)

        val distanceRows = record(boardOf(5, 7, 2 to 3, 2 to 6))
        assertTrue(
            distanceRows[Direction.EAST.ordinal][ActionFeatures.OPPONENT_DISTANCE] !=
                distanceRows[Direction.WEST.ordinal][ActionFeatures.OPPONENT_DISTANCE],
            "moving toward and away from a reachable opponent must change opponentDistance",
        )

        val barrier = listOf(0 to 1, 1 to 1, 3 to 1, 4 to 1)
        val split = boardOf(5, 5, 2 to 0, 4 to 4, walls = barrier)
        split.apply(split.toAct, Direction.EAST)
        split.apply(split.toAct, Direction.NORTH)
        assertTrue(split.snake(split.toAct).growsOnNextMove)
        val splitRows = record(split)
        assertEquals(0.0, splitRows[Direction.EAST.ordinal][ActionFeatures.OPPONENT_UNREACHABLE])
        assertEquals(1.0, splitRows[Direction.WEST.ordinal][ActionFeatures.OPPONENT_UNREACHABLE])

        val missing = ActionFeatures.NAMES.filterIndexed { index, _ -> !varies[index] }
        assertTrue(missing.isEmpty(), "coordinates that never vary between legal actions: $missing")
    }

    @Test
    fun `shares exclude walls and local facts ask the board about them`() {
        val board = boardOf(4, 4, 1 to 1, 3 to 3, walls = listOf(0 to 2))
        val features = ActionFeatures(board.grid, board.snakeCount)
        val legal = board.legalMoves(board.toAct)
        features.measure(board, board.toAct, legal)

        val east = row(features, Direction.EAST)
        val south = row(features, Direction.SOUTH)

        assertEquals(15, board.openCount)
        assertEquals(14.0 / 15.0, east[ActionFeatures.ROOM_SHARE])
        assertEquals(0.75, east[ActionFeatures.LIBERTIES])
        assertEquals(0.25, east[ActionFeatures.WALLS], "the interior wall north of the destination")
        assertEquals(0.0, south[ActionFeatures.WALLS])
        assertBounded(features, legal)
    }

    @Test
    fun `a retracting length-one head opens and a growing one stays as the neck`() {
        val retracting = boardOf(1, 5, 0 to 2)
        val retractingFeatures = ActionFeatures(retracting.grid, retracting.snakeCount)
        retractingFeatures.measure(retracting, retracting.toAct, retracting.legalMoves(retracting.toAct))
        val openHead = row(retractingFeatures, Direction.EAST)

        assertEquals(1.0, openHead[ActionFeatures.ROOM_SHARE])
        assertEquals(0.5, openHead[ActionFeatures.LIBERTIES])

        val growing = boardOf(1, 7, 0 to 1)
        growing.apply(growing.toAct, Direction.EAST)
        assertTrue(growing.snake(growing.toAct).growsOnNextMove)

        val growingFeatures = ActionFeatures(growing.grid, growing.snakeCount)
        val legal = growing.legalMoves(growing.toAct)
        growingFeatures.measure(growing, growing.toAct, legal)
        val west = row(growingFeatures, Direction.WEST)
        val east = row(growingFeatures, Direction.EAST)

        assertEquals(2.0 / 7.0, west[ActionFeatures.ROOM_SHARE])
        assertEquals(4.0 / 7.0, east[ActionFeatures.ROOM_SHARE])
        assertEquals(1.0, west[ActionFeatures.ROOM_GUARD], "exactly half of the best room passes")
        assertEquals(2.0 / 7.0, west[ActionFeatures.OWNED_SHARE])
        assertEquals(4.0 / 7.0, east[ActionFeatures.OWNED_SHARE])
        assertEquals(0.5, west[ActionFeatures.OWNED_RELATIVE_BEST])
        assertEquals(1.0, east[ActionFeatures.OWNED_RELATIVE_BEST])
        assertEquals(0.25, west[ActionFeatures.LIBERTIES], "the old head remains an occupied neck")
    }

    @Test
    fun `pinch groups and signed tail closing vary by destination`() {
        val pinched = boardOf(
            5,
            5,
            1 to 2,
            4 to 4,
            walls = listOf(2 to 0, 2 to 2, 2 to 3, 2 to 4),
        )
        val pinchFeatures = ActionFeatures(pinched.grid, pinched.snakeCount)
        pinchFeatures.measure(pinched, pinched.toAct, pinched.legalMoves(pinched.toAct))

        assertEquals(1.0 / 3.0, row(pinchFeatures, Direction.WEST)[ActionFeatures.PINCH_EXTRA_GROUPS])
        assertEquals(0.0, row(pinchFeatures, Direction.EAST)[ActionFeatures.PINCH_EXTRA_GROUPS])

        val following = boardOf(5, 5, 3 to 1)
        for (direction in listOf(
            Direction.EAST,
            Direction.EAST,
            Direction.NORTH,
            Direction.NORTH,
            Direction.WEST,
            Direction.WEST,
        )) {
            following.apply(following.toAct, direction)
        }
        val tailFeatures = ActionFeatures(following.grid, following.snakeCount)
        tailFeatures.measure(following, following.toAct, following.legalMoves(following.toAct))

        assertEquals(1.0, row(tailFeatures, Direction.SOUTH)[ActionFeatures.TAIL_CLOSING])
        assertEquals(-1.0, row(tailFeatures, Direction.NORTH)[ActionFeatures.TAIL_CLOSING])
    }

    @Test
    fun `an unreachable opponent has a separate bounded flag`() {
        val barrier = (0 until 5).map { row -> row to 2 }
        val board = boardOf(5, 5, 2 to 0, 2 to 4, walls = barrier)
        val features = ActionFeatures(board.grid, board.snakeCount)
        val legal = board.legalMoves(board.toAct)
        features.measure(board, board.toAct, legal)

        for (i in 0 until legal.size) {
            val row = row(features, legal.nth(i))
            assertEquals(1.0, row[ActionFeatures.OPPONENT_DISTANCE])
            assertEquals(1.0, row[ActionFeatures.OPPONENT_UNREACHABLE])
        }
        assertBounded(features, legal)
    }

    @Test
    fun `measurement leaves the complete live position unchanged`() {
        val board = boardOf(6, 7, 2 to 2, 4 to 5, walls = listOf(1 to 3, 2 to 3, 4 to 3))
        board.apply(board.toAct, Direction.WEST)
        board.apply(board.toAct, Direction.NORTH)

        val beforeHash = board.hash
        val beforeTurn = board.turnIndex
        val beforeActor = board.toAct
        val beforeBodies = Array(board.snakeCount) { slot ->
            val snake = board.snake(SnakeId(slot))
            IntArray(snake.length) { part -> snake.cellAt(part).index }
        }
        val legal = board.legalMoves(board.toAct)

        val features = ActionFeatures(board.grid, board.snakeCount)
        features.measure(board, board.toAct, legal)
        assertBounded(features, legal)

        assertEquals(beforeHash, board.hash)
        assertEquals(beforeTurn, board.turnIndex)
        assertEquals(beforeActor, board.toAct)
        assertEquals(legal, board.legalMoves(board.toAct))
        for (slot in beforeBodies.indices) {
            val snake = board.snake(SnakeId(slot))
            assertEquals(beforeBodies[slot].size, snake.length)
            for (part in beforeBodies[slot].indices) {
                assertEquals(beforeBodies[slot][part], snake.cellAt(part).index)
            }
        }
    }

    private fun row(features: ActionFeatures, direction: Direction): DoubleArray =
        DoubleArray(ActionFeatures.LENGTH).also { features.into(direction, it) }

    private fun assertBounded(features: ActionFeatures, legal: ao.snakewarz.core.grid.DirectionSet) {
        val row = DoubleArray(ActionFeatures.LENGTH)
        for (i in 0 until legal.size) {
            features.into(legal.nth(i), row)
            for (feature in row.indices) {
                assertTrue(
                    row[feature] in -1.0..1.0,
                    "${ActionFeatures.NAMES[feature]} for ${legal.nth(i)} was ${row[feature]}",
                )
            }
        }
    }
}
