package ao.snakewarz.bots.reactive.policy

import ao.snakewarz.bots.reactive.chase.ShortestPaths
import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.bots.search.puct.MovePrior
import ao.snakewarz.bots.search.puct.TempoOwnership
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId

/**
 * Allocation-free P2 ordering for whichever living snake is about to act.
 *
 * [PolicyBot] uses this at the live root, while shallow search reuses the same instance on a
 * [ao.snakewarz.botapi.scratch.Playout]. Supplying the mover instead of retaining one seat is the
 * important seam: search positions may belong to any live slot, and the board's actual turn order is
 * the authority. The first direction written by [orderInto] is exactly the move P2 would choose.
 */
internal class PolicyRanker(
    private val grid: Grid,
    private val slotCount: Int,
    private val variant: PolicyVariant = PolicyVariant.FULL_OWNED,
) {
    private val fill = FloodFill(grid)
    private val paths = ShortestPaths(grid)
    private val prior = if (variant.local) {
        MovePrior(
            grid = grid,
            libertyWeight = LIBERTY_WEIGHT,
            pinchPenalty = PINCH_PENALTY,
            wallBonus = if (variant.wall) WALL_BONUS else 0.0,
            tailBias = TAIL_BIAS,
            temperature = 0.0,
        )
    } else {
        null
    }
    private val ownership = if (variant.owned) TempoOwnership(grid, slotCount) else null
    private val components = if (variant.owned) MoverOwnedComponents(grid) else null

    private val destinations = IntArray(DIRECTIONS)
    private val rooms = IntArray(DIRECTIONS)
    private val distances = IntArray(DIRECTIONS)
    private val ownedAreas = IntArray(DIRECTIONS)
    private val priors = DoubleArray(DIRECTIONS)
    private val tieKeys = LongArray(DIRECTIONS)
    private val oneOrder = IntArray(DIRECTIONS)

    private var bestRoom = 0

    /** Directions tied on every enabled reading, before [policyTieKey]. */
    internal var rawMaxima: DirectionSet = DirectionSet.EMPTY
        private set

    /** Returns the first direction in the complete deterministic P2 ordering. */
    internal fun choose(board: BoardView, mover: SnakeId, legal: DirectionSet): Direction {
        orderInto(board, mover, legal, oneOrder, 0)
        return Direction.entries[oneOrder[0]]
    }

    /** Writes every legal action best-first and returns the number written. */
    internal fun orderInto(
        board: BoardView,
        mover: SnakeId,
        legal: DirectionSet,
        into: IntArray,
        offset: Int,
    ): Int {
        require(offset >= 0 && into.size - offset >= legal.size.coerceAtLeast(1)) {
            "an action order needs ${legal.size.coerceAtLeast(1)} entries at $offset, was ${into.size}"
        }
        require(board.grid.rows == grid.rows && board.grid.cols == grid.cols) {
            "PolicyRanker($grid) cannot read ${board.grid}"
        }
        require(board.snakeCount == slotCount) {
            "PolicyRanker has $slotCount slots and the board has ${board.snakeCount}"
        }
        require(board.toAct == mover) { "policy ordering is for ${board.toAct}, not $mover" }

        if (legal.isEmpty) {
            rawMaxima = DirectionSet.EMPTY
            into[offset] = Direction.NORTH.ordinal
            return 1
        }
        require(legal == board.legalMoves(mover)) { "the supplied legal set $legal is stale" }

        measure(board, mover, legal)
        findRawMaxima(legal)

        var remaining = legal
        var count = 0
        while (remaining.isNotEmpty) {
            var pick = remaining.nth(0)
            for (i in 1 until remaining.size) {
                val candidate = remaining.nth(i)
                if (compareOrdered(candidate.ordinal, pick.ordinal) == BETTER) {
                    pick = candidate
                }
            }
            into[offset + count++] = pick.ordinal
            remaining -= pick
        }
        return count
    }

    private fun measure(board: BoardView, mover: SnakeId, legal: DirectionSet) {
        val me = board.snake(mover)
        require(me.alive) { "policy ordering cannot measure a dead mover $mover" }
        val vacating = if (me.growsOnNextMove) Cell.NONE else me.tail

        bestRoom = 0
        for (i in 0 until legal.size) {
            val direction = legal.nth(i)
            val ordinal = direction.ordinal
            val destination = grid.step(me.head, direction)
            destinations[ordinal] = destination.index
            tieKeys[ordinal] = policyTieKey(board, mover, destination)

            if (variant.room) {
                val room = fill.reachable(board, destination, vacating)
                rooms[ordinal] = room
                if (room > bestRoom) {
                    bestRoom = room
                }
            }

            if (variant.path) {
                paths.scanFrom(board, destination, vacating)
                distances[ordinal] = nearestOpponentDistance(board, mover)
            }
        }

        if (variant.local) {
            prior!!.into(board, mover, legal, priors)
        }

        if (variant.owned) {
            val measured = ownership!!
            measured.measure(board)
            val blocked = if (vacating == me.head) Cell.NONE else me.head
            components!!.into(measured, mover.index, blocked, legal, destinations, ownedAreas)
        }
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
        return if (nearest < CLOSE_RANGE) CLOSE_RANGE else nearest
    }

    private fun findRawMaxima(legal: DirectionSet) {
        var bestOrdinal = -1
        var maximaBits = 0
        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            if (!admitted(ordinal)) {
                continue
            }

            if (bestOrdinal < 0) {
                bestOrdinal = ordinal
                maximaBits = 1 shl ordinal
                continue
            }

            when (compareRaw(ordinal, bestOrdinal)) {
                BETTER -> {
                    bestOrdinal = ordinal
                    maximaBits = 1 shl ordinal
                }

                EQUAL -> maximaBits = maximaBits or (1 shl ordinal)
            }
        }
        rawMaxima = DirectionSet(maximaBits)
    }

    private fun compareOrdered(candidate: Int, incumbent: Int): Int {
        val candidateAdmitted = admitted(candidate)
        val incumbentAdmitted = admitted(incumbent)
        if (candidateAdmitted != incumbentAdmitted) {
            return if (candidateAdmitted) BETTER else WORSE
        }

        val raw = compareRaw(candidate, incumbent)
        if (raw != EQUAL) {
            return raw
        }
        return when {
            tieKeys[candidate] > tieKeys[incumbent] -> BETTER
            tieKeys[candidate] < tieKeys[incumbent] -> WORSE
            else -> EQUAL
        }
    }

    private fun admitted(ordinal: Int): Boolean =
        !variant.guard || rooms[ordinal] * ROOM_SHARE_DENOMINATOR >= bestRoom

    /** Path, owned component, local reading, then room. A disabled coordinate is never read. */
    private fun compareRaw(candidate: Int, incumbent: Int): Int {
        if (variant.path && distances[candidate] != distances[incumbent]) {
            return if (distances[candidate] < distances[incumbent]) BETTER else WORSE
        }
        if (variant.owned && ownedAreas[candidate] != ownedAreas[incumbent]) {
            return if (ownedAreas[candidate] > ownedAreas[incumbent]) BETTER else WORSE
        }
        if (variant.local && priors[candidate] != priors[incumbent]) {
            return if (priors[candidate] > priors[incumbent]) BETTER else WORSE
        }
        if (variant.room && rooms[candidate] != rooms[incumbent]) {
            return if (rooms[candidate] > rooms[incumbent]) BETTER else WORSE
        }
        return EQUAL
    }

    private companion object {
        val DIRECTIONS = Direction.entries.size

        const val CLOSE_RANGE = 3
        const val ROOM_SHARE_DENOMINATOR = 2

        const val LIBERTY_WEIGHT = 0.5
        const val PINCH_PENALTY = 0.8
        const val TAIL_BIAS = 0.8
        const val WALL_BONUS = 0.5

        const val BETTER = 1
        const val EQUAL = 0
        const val WORSE = -1
    }
}

/** Every input named in P2 participates separately before the final avalanche. */
internal fun policyTieKey(board: BoardView, mover: SnakeId, destination: Cell): Long {
    var mixed = policyMix64(board.hash xor HASH_DOMAIN)
    mixed = policyMix64(mixed xor board.turnIndex.toLong() * TURN_SALT)
    mixed = policyMix64(mixed xor (mover.index + 1).toLong() * MOVER_SALT)
    return policyMix64(mixed xor (destination.index + 1).toLong() * DESTINATION_SALT)
}

private fun policyMix64(value: Long): Long {
    var mixed = value
    mixed = (mixed xor (mixed ushr 30)) * DESTINATION_SALT
    mixed = (mixed xor (mixed ushr 27)) * MOVER_SALT
    return mixed xor (mixed ushr 31)
}

private const val HASH_DOMAIN = -7046029254386353131L
private const val TURN_SALT = -3335678366873096957L
private const val MOVER_SALT = -7723592293110705685L
private const val DESTINATION_SALT = -4658895280553007687L
