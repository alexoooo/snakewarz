package ao.snakewarz.bots.reactive.policy

import ao.snakewarz.bots.reactive.chase.ShortestPaths
import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.bots.search.puct.TempoOwnership
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId
import kotlin.math.abs

/**
 * The bounded per-action row used by the learned no-tree policy and its JVM trainer.
 *
 * [measure] reads one live position and all of its legal destinations. [into] then writes the row for
 * one of those destinations. No transition is applied: the room and path sweeps start at the
 * destination and admit the mover's retracting tail, while local readings likewise treat that tail
 * as free. The old head stays occupied as the neck except when a length-one snake retracts it.
 *
 * One instance belongs to one bot seat or one trainer worker. Its arrays are allocated in the
 * constructor and reused; both hot-path methods allocate nothing. Shares use [BoardView.openCount],
 * so interior walls change the ground available rather than making a feature leave its declared
 * range.
 *
 * This type is public deliberately. `:lab` trains the exact extractor the browser runs, instead of
 * carrying a second feature implementation that could silently drift from it.
 */
public class ActionFeatures(
    private val grid: Grid,
    private val slotCount: Int,
) {
    private val fill = FloodFill(grid)
    private val paths = ShortestPaths(grid)
    private val ownership = TempoOwnership(grid, slotCount)
    private val components = MoverOwnedComponents(grid)
    private val local = LocalActionReadings(grid)

    private val destinations = IntArray(Direction.entries.size)
    private val rooms = IntArray(Direction.entries.size)
    private val distances = IntArray(Direction.entries.size)
    private val ownedAreas = IntArray(Direction.entries.size)
    private val liberties = IntArray(Direction.entries.size)
    private val pinchGroups = IntArray(Direction.entries.size)
    private val walls = IntArray(Direction.entries.size)
    private val tailClosing = IntArray(Direction.entries.size)

    private var openCount = 0
    private var bestRoom = 0
    private var bestOwnedArea = 0
    private var legalBits = 0
    private var measured = false

    /** Reads [board] once and retains one row for every direction in [legal]. */
    public fun measure(board: BoardView, mover: SnakeId, legal: DirectionSet) {
        require(board.grid.rows == grid.rows && board.grid.cols == grid.cols) {
            "ActionFeatures($grid) cannot read ${board.grid}"
        }
        require(board.snakeCount == slotCount) {
            "ActionFeatures has $slotCount slots and the board has ${board.snakeCount}"
        }
        require(mover == board.toAct) { "action features are for ${board.toAct}, not $mover" }
        require(legal == board.legalMoves(mover)) { "the supplied legal set $legal is stale" }

        val me = board.snake(mover)
        require(me.alive) { "action features cannot measure a dead mover $mover" }

        measured = false
        legalBits = legal.bits
        openCount = board.openCount
        bestRoom = 0
        bestOwnedArea = 0

        val vacating = if (me.growsOnNextMove) Cell.NONE else me.tail
        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val ordinal = direction.ordinal
            val destination = grid.step(me.head, direction)
            destinations[ordinal] = destination.index

            val room = fill.reachable(board, destination, vacating)
            rooms[ordinal] = room
            if (room > bestRoom) {
                bestRoom = room
            }

            paths.scanFrom(board, destination, vacating)
            distances[ordinal] = nearestOpponentDistance(board, mover)
        }

        ownership.measure(board)
        val blocked = if (vacating == me.head) Cell.NONE else me.head
        components.into(ownership, mover.index, blocked, legal, destinations, ownedAreas)
        for (i in 0 until legal.size) {
            val area = ownedAreas[legal.nth(i).ordinal]
            if (area > bestOwnedArea) {
                bestOwnedArea = area
            }
        }

        local.measure(
            board = board,
            head = me.head,
            tail = me.tail,
            vacating = vacating,
            legal = legal,
            destinations = destinations,
            liberties = liberties,
            pinchGroups = pinchGroups,
            walls = walls,
            tailClosing = tailClosing,
        )
        measured = true
    }

    /** Writes the frozen [SCHEMA] row for a legal [direction] measured most recently. */
    public fun into(direction: Direction, into: DoubleArray) {
        require(measured) { "measure must run before an action row is read" }
        require(legalBits and (1 shl direction.ordinal) != 0) { "$direction was not measured as legal" }
        require(into.size >= LENGTH) { "an action row needs $LENGTH entries, was ${into.size}" }

        val ordinal = direction.ordinal
        val open = openCount.toDouble()
        val distance = distances[ordinal]
        val owned = ownedAreas[ordinal]

        into[ROOM_SHARE] = rooms[ordinal] / open
        into[ROOM_GUARD] = if (rooms[ordinal] * ROOM_SHARE_DENOMINATOR >= bestRoom) 1.0 else 0.0
        into[OPPONENT_DISTANCE] =
            if (distance == ShortestPaths.UNREACHABLE) 1.0 else (distance / open).coerceAtMost(1.0)
        into[OPPONENT_UNREACHABLE] = if (distance == ShortestPaths.UNREACHABLE) 1.0 else 0.0
        into[OWNED_SHARE] = owned / open
        into[OWNED_RELATIVE_BEST] = if (bestOwnedArea == 0) 0.0 else owned.toDouble() / bestOwnedArea
        into[LIBERTIES] = liberties[ordinal] / SIDE_COUNT
        into[PINCH_EXTRA_GROUPS] = pinchGroups[ordinal] / MAX_PINCH_EXTRA_GROUPS
        into[WALLS] = walls[ordinal] / SIDE_COUNT
        into[TAIL_CLOSING] = tailClosing[ordinal].toDouble()
    }

    private fun nearestOpponentDistance(board: BoardView, mover: SnakeId): Int {
        var nearest = ShortestPaths.UNREACHABLE
        for (slot in 0 until slotCount) {
            if (slot == mover.index) {
                continue
            }
            val opponent = board.snake(SnakeId(slot))
            if (!opponent.alive) {
                continue
            }
            val distance = paths.distanceBeside(opponent.head)
            if (distance < nearest) {
                nearest = distance
            }
        }
        return nearest
    }

    override fun toString(): String = "ActionFeatures($SCHEMA)"

    public companion object {
        public const val ROOM_SHARE: Int = 0
        public const val ROOM_GUARD: Int = 1
        public const val OPPONENT_DISTANCE: Int = 2
        public const val OPPONENT_UNREACHABLE: Int = 3
        public const val OWNED_SHARE: Int = 4
        public const val OWNED_RELATIVE_BEST: Int = 5
        public const val LIBERTIES: Int = 6
        public const val PINCH_EXTRA_GROUPS: Int = 7
        public const val WALLS: Int = 8
        public const val TAIL_CLOSING: Int = 9

        /** The number and ordering of inputs are part of every fitted model. */
        public const val LENGTH: Int = 10

        /** Bump when a name, order, scale or meaning above changes. */
        public const val SCHEMA: String = "action-policy-v1"

        /** Diagnostics and trainers use these names; inference uses the indices above. */
        public val NAMES: List<String> = listOf(
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
        )

        private const val ROOM_SHARE_DENOMINATOR = 2
        private const val SIDE_COUNT = 4.0
        private const val MAX_PINCH_EXTRA_GROUPS = 3.0
    }
}

/** Raw local facts around each destination, under the same after-move occupancy convention. */
private class LocalActionReadings(private val grid: Grid) {
    private val ring = ringAround(grid)

    fun measure(
        board: BoardView,
        head: Cell,
        tail: Cell,
        vacating: Cell,
        legal: DirectionSet,
        destinations: IntArray,
        liberties: IntArray,
        pinchGroups: IntArray,
        walls: IntArray,
        tailClosing: IntArray,
    ) {
        val following = tail != head
        val tailRow = if (following) grid.rowOf(tail) else 0
        val tailCol = if (following) grid.colOf(tail) else 0
        val distanceNow = if (following) abs(grid.rowOf(head) - tailRow) + abs(grid.colOf(head) - tailCol) else 0

        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val ordinal = direction.ordinal
            val destination = destinations[ordinal]

            var free = 0
            var wallCount = 0
            for (at in 0 until RING_SIZE) {
                val cell = Cell(destination + ring[at])
                if (board.isFree(cell) || cell == vacating) {
                    free = free or (1 shl at)
                }
                if (at and 1 == 0 && board.isWall(cell)) {
                    wallCount++
                }
            }

            liberties[ordinal] = (free and ORTHOGONAL).countOneBits()
            pinchGroups[ordinal] = (groups(free) - 1).coerceAtLeast(0)
            walls[ordinal] = wallCount
            tailClosing[ordinal] = if (!following) {
                0
            } else {
                val row = grid.rowOf(Cell(destination))
                val col = grid.colOf(Cell(destination))
                val distanceAfter = abs(row - tailRow) + abs(col - tailCol)
                when {
                    distanceAfter < distanceNow -> 1
                    distanceAfter > distanceNow -> -1
                    else -> 0
                }
            }
        }
    }

    /** Number of free orthogonal groups around the destination, with diagonals joining neighbours. */
    private fun groups(free: Int): Int {
        var starts = 0
        var at = 0
        while (at < RING_SIZE) {
            if (free and (1 shl at) != 0) {
                val previous = free and (1 shl ((at + RING_SIZE - 2) and (RING_SIZE - 1))) != 0
                val between = free and (1 shl ((at + RING_SIZE - 1) and (RING_SIZE - 1))) != 0
                if (!previous || !between) {
                    starts++
                }
            }
            at += 2
        }
        return if (starts == 0 && free and ORTHOGONAL != 0) 1 else starts
    }

    private companion object {
        /** N, NE, E, SE, S, SW, W, NW as deltas in the padded grid. */
        fun ringAround(grid: Grid): IntArray {
            val north = grid.offsetOf(Direction.NORTH)
            val south = grid.offsetOf(Direction.SOUTH)
            val east = grid.offsetOf(Direction.EAST)
            val west = grid.offsetOf(Direction.WEST)
            return intArrayOf(
                north,
                north + east,
                east,
                south + east,
                south,
                south + west,
                west,
                north + west,
            )
        }

        const val RING_SIZE = 8
        const val ORTHOGONAL = 0b0101_0101
    }
}
