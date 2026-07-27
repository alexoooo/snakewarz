package ao.snakewarz.core

/**
 * The canonical game state: a mutable arena with an undo journal, and the one place the rules live.
 *
 * Every move is applied in place and can be taken back exactly, so a search node costs **zero**
 * allocation — [apply] touches at most two squares and pushes one `Long`. That is the single
 * highest-impact decision in the engine: the legacy design allocated a fresh persistent board per
 * node, which is the main reason its MCTS bot is slow, and the gap it opens dwarfs the wasm-versus-
 * JVM platform gap entirely.
 *
 * The immutable view of the same state is [MatchState], derived by [snapshot] at most once per turn
 * for the driver, replay and stats. Rules logic exists once, here; the snapshot has none.
 *
 * ### Rules
 *
 * - A move into any occupied square is fatal. Walls, other snakes and your own body are the same
 *   array read, so there is no separate "off the board" case and no bounds check.
 * - Legality is evaluated **before** the tail retracts, so a snake cannot move into the square its
 *   own tail is about to leave. This matches the legacy engine, which tested the destination against
 *   a board built before the retraction.
 * - There is no "cannot reverse" rule. Reversing is fatal because your own neck is there — and at
 *   length 1 there is no neck, so a first-move reversal is perfectly legal. That is intended.
 * - A dead snake's body stays on the board as an obstacle. In a three-way match, the first casualty
 *   leaves a wall behind, exactly as in the legacy engine.
 * - The last snake standing wins immediately, even if it is itself trapped.
 */
public class Board(
    override val grid: Grid,
    spawnCells: IntArray,
    override val rules: RulesConfig = RulesConfig(),
    turnOrder: IntArray = IntArray(spawnCells.size) { it },
) : BoardView {
    override val snakeCount: Int = spawnCells.size

    // The two ceilings that bound what this class is about to ask for, checked here rather than in
    // the init block at the foot of the file (SW-09).
    //
    // Kotlin runs property initializers and init blocks in declaration order, so a require below
    // `occupancy` and `bodies` fires after they have already been allocated. On a board large enough
    // to matter that is hundreds of megabytes requested before the line that would have said no --
    // and the failure is then an OutOfMemoryError rather than the IllegalArgumentException a caller
    // validating a stranger's replay link is catching.
    init {
        require(snakeCount in 1..Occupancy.MAX_SNAKES) {
            "a match needs 1..${Occupancy.MAX_SNAKES} snakes, was $snakeCount"
        }
        require(grid.cellCount <= MAX_JOURNALED_CELL) {
            "$grid is too large for the undo journal's $MAX_JOURNALED_CELL cell ceiling"
        }
    }

    /** Slot indices in the order they act. A permutation of `0 until snakeCount`. */
    private val order: IntArray = turnOrder.copyOf()
    private val spawns: IntArray = spawnCells.copyOf()

    private val occupancy = Occupancy(grid)
    private val bodies: Array<SnakeBody> = Array(snakeCount) { SnakeBody(grid.playableCount) }
    private val alive = BooleanArray(snakeCount)
    private val eliminationCodes = ByteArray(snakeCount)
    private val moveCounts = IntArray(snakeCount)
    private val lastDirectionCodes = ByteArray(snakeCount)
    private val views: Array<SnakeView> = Array(snakeCount) { LiveSnake(SnakeId(it)) }

    /** Position within [order], not a slot index. */
    private var orderPos = 0

    /** Everything in [hash] that is not occupancy: heads, growth phases, liveness, whose turn. */
    private var auxHash = 0L

    private var journal = LongArray(INITIAL_JOURNAL_CAPACITY)
    private var journalTop = 0

    override var turnIndex: Int = 0
        private set

    override var aliveCount: Int = 0
        private set

    override var outcome: MatchOutcome? = null
        private set

    init {
        require(turnOrder.size == snakeCount) {
            "turn order has ${turnOrder.size} entries for $snakeCount snakes"
        }

        val seen = BooleanArray(snakeCount)
        for (slot in order) {
            require(slot in 0 until snakeCount) { "turn order names a slot $slot that does not exist" }
            require(!seen[slot]) { "turn order names slot $slot twice" }
            seen[slot] = true
        }

        for (slot in 0 until snakeCount) {
            val cell = Cell(spawns[slot])
            require(grid.isPlayable(cell)) { "spawn for slot $slot is not a playable square of $grid" }
            for (other in 0 until slot) {
                require(spawns[other] != spawns[slot]) { "slots $other and $slot spawn on the same square" }
            }
        }

        reset()
    }

    override val toAct: SnakeId get() = SnakeId(order[orderPos])

    override val hash: Long get() = occupancy.hash xor auxHash

    override fun isFree(cell: Cell): Boolean = occupancy.isFree(cell)

    override fun ownerOf(cell: Cell): SnakeId = occupancy.ownerOf(cell)

    override fun legalMoves(id: SnakeId): DirectionSet =
        if (alive[id.index]) occupancy.freeNeighbors(bodies[id.index].head) else DirectionSet.EMPTY

    override fun snake(id: SnakeId): SnakeView = views[id.index]

    /** Returns the board to its opening position and discards the undo journal. */
    public fun reset() {
        occupancy.clear()
        for (slot in 0 until snakeCount) {
            val cell = Cell(spawns[slot])
            bodies[slot].reset(cell)
            occupancy.occupy(cell, SnakeId(slot))
            alive[slot] = true
            eliminationCodes[slot] = 0
            moveCounts[slot] = 0
            lastDirectionCodes[slot] = 0
        }

        orderPos = 0
        turnIndex = 0
        aliveCount = snakeCount
        outcome = null
        journalTop = 0

        auxHash = 0L
        auxHash = auxHash xor toActKey()
        for (slot in 0 until snakeCount) {
            auxHash = auxHash xor headKey(slot, bodies[slot].head) xor growthPhaseKey(slot, 0)
        }

        evaluateOutcome()
    }

    /**
     * Moves [id]'s head one square in [direction], eliminating it if the square is not free.
     *
     * Only the snake [toAct] may move, and it always moves: an illegal-looking direction is a
     * recorded, self-describing death rather than an error. That is what lets the replay format spend
     * two bits per turn and no symbol at all on suicides.
     */
    public fun apply(id: SnakeId, direction: Direction): MoveOutcome {
        check(outcome == null) { "the match is over: $outcome" }
        require(id.index == order[orderPos]) { "it is $toAct's turn, not $id's" }

        val slot = id.index
        val body = bodies[slot]
        val from = body.head
        val target = grid.step(from, direction)

        var record = encodeOrderPos(orderPos) or encodeDirection(lastDirectionCodes[slot].toInt())
        val result: MoveOutcome

        if (occupancy.isFree(target)) {
            if (!growsOnNextMove(slot)) {
                val vacated = body.popTail()
                occupancy.vacate(vacated)
                record = record or encodeVacated(vacated.index)
            }
            body.pushHead(target)
            occupancy.occupy(target, id)

            auxHash = auxHash xor headKey(slot, from) xor headKey(slot, target)
            auxHash = auxHash xor growthPhaseKey(slot, moveCounts[slot])
            moveCounts[slot]++
            auxHash = auxHash xor growthPhaseKey(slot, moveCounts[slot])
            lastDirectionCodes[slot] = (direction.ordinal + 1).toByte()

            result = MoveOutcome.MOVED
        } else {
            // The distinction is the only reason this reads the neighbours at all: a snake with
            // nowhere to go was killed by the board, one with somewhere to go killed itself.
            val trapped = occupancy.freeNeighbors(from).isEmpty
            result = if (trapped) MoveOutcome.TRAPPED else MoveOutcome.SUICIDE
            eliminateSlot(slot, if (trapped) EliminationReason.TRAPPED else EliminationReason.SUICIDE)
            record = record or encodeElimination(eliminationCodes[slot].toInt())
        }

        record = finishTurn(record)
        pushJournal(record)
        return result
    }

    /**
     * Removes [toAct] from the match without moving it — a resignation or a forfeit.
     *
     * Collisions are not routed through here; they are the outcome of [apply] and carry their own
     * reason. Keeping the two apart is what stops a driver from quietly turning a bug into a suicide.
     */
    public fun eliminate(id: SnakeId, reason: EliminationReason) {
        check(outcome == null) { "the match is over: $outcome" }
        require(id.index == order[orderPos]) { "it is $toAct's turn, not $id's" }
        require(reason == EliminationReason.RESIGNED || reason == EliminationReason.FORFEIT) {
            "$reason is decided by apply(), not by eliminate()"
        }

        val slot = id.index
        var record = encodeOrderPos(orderPos) or encodeDirection(lastDirectionCodes[slot].toInt())
        eliminateSlot(slot, reason)
        record = record or encodeElimination(eliminationCodes[slot].toInt())

        record = finishTurn(record)
        pushJournal(record)
    }

    /**
     * Takes back the last [apply] or [eliminate], restoring the board bit for bit — [hash] included.
     *
     * This, not board copying, is how a search explores: the undo record is one `Long`, so descending
     * and backing up a tree allocates nothing at all.
     */
    public fun undo() {
        check(journalTop > 0) { "there is nothing to undo" }
        val record = journal[--journalTop]

        if (record and ENDED_BIT != 0L) {
            outcome = null
        }

        auxHash = auxHash xor toActKey()
        orderPos = decodeOrderPos(record)
        auxHash = auxHash xor toActKey()
        turnIndex--

        val slot = order[orderPos]
        if (decodeElimination(record) != 0) {
            alive[slot] = true
            eliminationCodes[slot] = 0
            aliveCount++
            auxHash = auxHash xor aliveKey(slot)
        } else {
            val body = bodies[slot]
            val removed = body.popHead()
            occupancy.vacate(removed)

            val vacated = decodeVacated(record)
            if (vacated >= 0) {
                val cell = Cell(vacated)
                body.pushTail(cell)
                occupancy.occupy(cell, SnakeId(slot))
            }

            auxHash = auxHash xor headKey(slot, removed) xor headKey(slot, body.head)
            auxHash = auxHash xor growthPhaseKey(slot, moveCounts[slot])
            moveCounts[slot]--
            auxHash = auxHash xor growthPhaseKey(slot, moveCounts[slot])
            lastDirectionCodes[slot] = decodeDirection(record).toByte()
        }
    }

    /** Number of moves that can still be taken back. */
    public val undoDepth: Int get() = journalTop

    /**
     * The occupancy half of [hash], on its own.
     *
     * Exists so the invariant this whole optimization rests on — incremental occupancy equals
     * occupancy rebuilt from all bodies — can be property-tested without opening up the arena.
     */
    internal val occupancyHash: Long get() = occupancy.hash

    /**
     * Overwrites this board with [other], reusing every allocation. The undo journal is **not**
     * copied: the copy starts fresh, because a search arena has no interest in its source's history.
     */
    public fun copyFrom(other: Board) {
        require(other.grid.rows == grid.rows && other.grid.cols == grid.cols) {
            "cannot copy a ${other.grid} board into a $grid one"
        }
        require(other.snakeCount == snakeCount) {
            "cannot copy a ${other.snakeCount}-snake board into a $snakeCount-snake one"
        }
        require(other.rules == rules) { "cannot copy a board played under ${other.rules} into $rules" }

        occupancy.copyFrom(other.occupancy)
        for (slot in 0 until snakeCount) {
            bodies[slot].copyFrom(other.bodies[slot])
        }
        other.order.copyInto(order)
        other.spawns.copyInto(spawns)
        other.alive.copyInto(alive)
        other.eliminationCodes.copyInto(eliminationCodes)
        other.moveCounts.copyInto(moveCounts)
        other.lastDirectionCodes.copyInto(lastDirectionCodes)

        orderPos = other.orderPos
        turnIndex = other.turnIndex
        aliveCount = other.aliveCount
        outcome = other.outcome
        auxHash = other.auxHash
        journalTop = 0
    }

    /**
     * An independent board at the same position, with an empty undo journal.
     *
     * This is how a search arena comes into being: allocate one of these once, then [copyFrom] the
     * live board on every rollout reset. Never call it per node — that is the legacy mistake.
     */
    public fun copy(): Board {
        val clone = Board(grid, spawns, rules, order)
        clone.copyFrom(this)
        return clone
    }

    /** An immutable copy of the current position. O(total snake length), so at most once per turn. */
    public fun snapshot(): MatchState {
        val snakes = Array(snakeCount) { slot ->
            val body = bodies[slot]
            SnakeState(
                id = SnakeId(slot),
                alive = alive[slot],
                eliminationReason = reasonOf(eliminationCodes[slot].toInt()),
                movesMade = moveCounts[slot],
                lastDirection = directionOf(lastDirectionCodes[slot].toInt()),
                growsOnNextMove = growsOnNextMove(slot),
                cells = IntArray(body.size) { body.cellAt(it).index },
            )
        }
        return MatchState(grid, rules, turnIndex, toAct, outcome, snakes)
    }

    override fun toString(): String = snapshot().toString()

    // -- internals

    private fun growsOnNextMove(slot: Int): Boolean =
        (moveCounts[slot] + 1) % rules.growEveryNthMove == 0

    private fun eliminateSlot(slot: Int, reason: EliminationReason) {
        alive[slot] = false
        eliminationCodes[slot] = (reason.ordinal + 1).toByte()
        aliveCount--
        auxHash = auxHash xor aliveKey(slot)
    }

    /** Advances the clock and the turn, then stamps the record if the match ended doing so. */
    private fun finishTurn(record: Long): Long {
        turnIndex++
        advanceToAct()
        return if (evaluateOutcome()) record or ENDED_BIT else record
    }

    private fun advanceToAct() {
        if (aliveCount == 0) {
            return
        }

        auxHash = auxHash xor toActKey()
        var position = orderPos
        do {
            position++
            if (position == order.size) {
                position = 0
            }
        } while (!alive[order[position]])
        orderPos = position
        auxHash = auxHash xor toActKey()
    }

    private fun evaluateOutcome(): Boolean {
        outcome = when {
            aliveCount == 0 -> MatchOutcome(SnakeId.NONE, MatchEnd.ALL_ELIMINATED)
            aliveCount == 1 && snakeCount > 1 -> MatchOutcome(SnakeId(soleSurvivor()), MatchEnd.LAST_SNAKE_STANDING)
            turnIndex >= rules.maxTurns -> MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT)
            else -> null
        }
        return outcome != null
    }

    private fun soleSurvivor(): Int {
        for (slot in 0 until snakeCount) {
            if (alive[slot]) {
                return slot
            }
        }
        error("aliveCount says 1, but no snake is alive")
    }

    private fun pushJournal(record: Long) {
        if (journalTop == journal.size) {
            journal = journal.copyOf(journal.size * 2)
        }
        journal[journalTop++] = record
    }

    // Zobrist key families. The family tag occupies the top nibble, the slot the next 28 bits and
    // the payload the low 32, so no two keys anywhere in the engine can share a mix64 input.
    private fun toActKey(): Long = stateKey(FAMILY_TO_ACT, 0, order[orderPos])

    private fun headKey(slot: Int, cell: Cell): Long = stateKey(FAMILY_HEAD, slot, cell.index)

    private fun growthPhaseKey(slot: Int, moves: Int): Long =
        stateKey(FAMILY_GROWTH, slot, moves % rules.growEveryNthMove)

    private fun aliveKey(slot: Int): Long = stateKey(FAMILY_ALIVE, slot, 0)

    private inner class LiveSnake(override val id: SnakeId) : SnakeView {
        private val slot = id.index

        override val alive: Boolean get() = this@Board.alive[slot]
        override val eliminationReason: EliminationReason? get() = reasonOf(eliminationCodes[slot].toInt())
        override val length: Int get() = bodies[slot].size
        override val head: Cell get() = bodies[slot].head
        override val tail: Cell get() = bodies[slot].tail
        override val lastDirection: Direction? get() = directionOf(lastDirectionCodes[slot].toInt())
        override val movesMade: Int get() = moveCounts[slot]
        override val growsOnNextMove: Boolean get() = this@Board.growsOnNextMove(slot)

        override fun cellAt(i: Int): Cell = bodies[slot].cellAt(i)

        override fun toString(): String = "LiveSnake($id, length=$length)"
    }

    private companion object {
        const val INITIAL_JOURNAL_CAPACITY = 64

        // Undo record layout, one Long per turn:
        //   bits  0..23  vacated cell index + 1, or 0 if the body grew instead of retracting
        //   bits 24..26  the mover's previous lastDirection code
        //   bits 27..29  the elimination reason code, or 0 if the move was survived
        //   bits 30..37  the acting position within the turn order
        //   bit  38      the match ended on this turn
        const val VACATED_MASK = 0xFFFFFFL
        const val DIRECTION_SHIFT = 24
        const val DIRECTION_MASK = 0b111L
        const val ELIMINATION_SHIFT = 27
        const val ELIMINATION_MASK = 0b111L
        const val ORDER_POS_SHIFT = 30
        const val ORDER_POS_MASK = 0xFFL
        const val ENDED_BIT = 0x4000000000L // bit 38

        /** The largest cell index the 24-bit vacated-cell field can carry, minus its `+1` bias. */
        const val MAX_JOURNALED_CELL = 0xFFFFFE

        const val FAMILY_TO_ACT = 1L
        const val FAMILY_HEAD = 2L
        const val FAMILY_GROWTH = 3L
        const val FAMILY_ALIVE = 4L

        fun stateKey(family: Long, slot: Int, payload: Int): Long =
            mix64((family shl 60) or (slot.toLong() shl 32) or (payload.toLong() and 0xFFFFFFFFL))

        fun encodeVacated(cellIndex: Int): Long = (cellIndex + 1).toLong() and VACATED_MASK

        fun decodeVacated(record: Long): Int = (record and VACATED_MASK).toInt() - 1

        fun encodeDirection(code: Int): Long = code.toLong() shl DIRECTION_SHIFT

        fun decodeDirection(record: Long): Int = ((record shr DIRECTION_SHIFT) and DIRECTION_MASK).toInt()

        fun encodeElimination(code: Int): Long = code.toLong() shl ELIMINATION_SHIFT

        fun decodeElimination(record: Long): Int = ((record shr ELIMINATION_SHIFT) and ELIMINATION_MASK).toInt()

        fun encodeOrderPos(position: Int): Long = position.toLong() shl ORDER_POS_SHIFT

        fun decodeOrderPos(record: Long): Int = ((record shr ORDER_POS_SHIFT) and ORDER_POS_MASK).toInt()

        fun directionOf(code: Int): Direction? = if (code == 0) null else Direction.entries[code - 1]

        fun reasonOf(code: Int): EliminationReason? = if (code == 0) null else EliminationReason.entries[code - 1]
    }
}
