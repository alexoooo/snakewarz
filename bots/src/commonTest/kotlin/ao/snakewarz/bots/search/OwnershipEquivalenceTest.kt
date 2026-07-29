package ao.snakewarz.bots.search

import ao.snakewarz.bots.search.puct.TempoOwnership
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.core.snake.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SpaceOwnership] and [TempoOwnership] against a square-at-a-time sweep of the same specification.
 *
 * Both are [CellBits] sweeps, and both are read by nearly every bot in the tree: a reading that
 * differs from a queue-driven breadth-first search by one square changes what `puct` plays, which
 * changes every match anybody measures. So the guarantee wanted here is not "close" or "the shipped
 * shapes still pass" but *identical*, over a corpus large and awkward enough to have found a
 * difference if there were one — every geometry the contract suite runs, plus the widths where a
 * padded row is exactly a word (62 columns), one bit over (63) and two words (126), which is where a
 * shift-based neighbour step goes wrong if it goes wrong at all.
 *
 * [BreadthFirstOwnership] and [BreadthFirstTempo] are the oracle: the same rules — nearest head wins,
 * a tie belongs to nobody and stops spreading, contact is frontier adjacency — expressed as a queue
 * over squares, which is the obvious way to compute them and the slow one.
 */
class OwnershipEquivalenceTest {
    @Test
    fun `both sweeps agree with a breadth-first search, square for square`() {
        var positions = 0
        var withACorpse = 0
        var withSomebodySeparated = 0
        var withSomebodyEngaged = 0
        var withATie = 0

        for (geometry in GEOMETRIES) {
            val (rows, cols) = geometry
            val grid = Grid(rows, cols)

            for (game in 0 until GAMES_PER_GEOMETRY) {
                val rng = SplitMix64(SEED + game * 7919L + rows * 131L + cols)
                val rules = RulesConfig(growEveryNthMove = if (game % 3 == 0) 1 else 2)
                val board = spawn(grid, rng, rules) ?: continue

                val space = SpaceOwnership(grid, board.snakeCount)
                val spaceOracle = BreadthFirstOwnership(grid, board.snakeCount)
                val tempo = TempoOwnership(grid, board.snakeCount)
                val tempoOracle = BreadthFirstTempo(grid, board.snakeCount)

                positions += walk(board, rng) {
                    val where = describe(board)

                    val measuredSpace = space.measure(board).copyOf()
                    val expectedSpace = spaceOracle.measure(board).copyOf()
                    val measuredTempo = tempo.measure(board).copyOf()
                    val expectedTempo = tempoOracle.measure(board).copyOf()

                    var claimed = 0
                    for (slot in 0 until board.snakeCount) {
                        assertEquals(expectedSpace[slot], measuredSpace[slot], "space owned by $slot on $where")
                        assertEquals(expectedTempo[slot], measuredTempo[slot], "tempo owned by $slot on $where")
                        val alone = spaceOracle.isolated(slot)
                        assertEquals(alone, space.isolated(slot), "space isolation of $slot on $where")
                        assertEquals(tempoOracle.isolated(slot), tempo.isolated(slot), "tempo isolation on $where")

                        claimed += expectedSpace[slot]
                        if (!board.snake(SnakeId(slot)).alive) {
                            withACorpse++
                        }
                        if (alone) withSomebodySeparated++ else withSomebodyEngaged++
                    }

                    for (index in 0 until grid.cellCount) {
                        val cell = Cell(index)
                        val owner = tempoOracle.ownerOf(cell)
                        assertEquals(owner, tempo.ownerOf(cell), "owner of $index on $where")
                        if (owner != TempoOwnership.NOBODY) {
                            val expected = tempoOracle.distanceTo(cell)
                            assertEquals(expected, tempo.distanceTo(cell), "distance to $index on $where")
                        }
                    }

                    if (claimed < reachableFreeSquares(board)) {
                        withATie++
                    }
                }
            }
        }

        assertTrue(positions > 2_000, "only $positions positions were compared, which is not a corpus")

        // A corpus that never reaches a state is a corpus that cannot rule a difference out there,
        // and every one of these is a branch the sweep takes for its own reason.
        assertTrue(withACorpse > 100, "only $withACorpse readings had a dead snake on the board")
        assertTrue(withSomebodySeparated > 100, "only $withSomebodySeparated readings had somebody separated")
        assertTrue(withSomebodyEngaged > 100, "only $withSomebodyEngaged readings had somebody still in a fight")
        assertTrue(withATie > 100, "only $withATie readings left a square to nobody")
    }

    // -- the corpus

    /** Free squares any live head can walk to, which is what a sweep divides up between them. */
    private fun reachableFreeSquares(board: BoardView): Int {
        val grid = board.grid
        val reached = BooleanArray(grid.cellCount)
        val queue = IntArray(grid.cellCount)
        var tail = 0

        for (slot in 0 until board.snakeCount) {
            val snake = board.snake(SnakeId(slot))
            if (snake.alive) {
                reached[snake.head.index] = true
                queue[tail++] = snake.head.index
            }
        }

        var head = 0
        var free = 0
        while (head < tail) {
            val cell = Cell(queue[head++])
            for (direction in Direction.entries) {
                val next = grid.step(cell, direction)
                if (!reached[next.index] && board.isFree(next)) {
                    reached[next.index] = true
                    queue[tail++] = next.index
                    free++
                }
            }
        }
        return free
    }

    /**
     * Places two to four snakes on distinct squares, or `null` for a board too small to hold two.
     *
     * Spawns are drawn rather than spread out: a sweep is asked about positions a search reaches, and
     * a search reaches heads pressed against each other far more often than an opening does.
     */
    private fun spawn(grid: Grid, rng: SplitMix64, rules: RulesConfig): Board? {
        val playable = grid.playableCount
        if (playable < 2) {
            return null
        }

        val wanted = 2 + rng.nextInt(3)
        val slots = if (wanted > playable) playable else wanted
        val taken = IntArray(slots)

        var placed = 0
        while (placed < slots) {
            val cell = grid.cellAt(rng.nextInt(grid.rows), rng.nextInt(grid.cols)).index
            var clash = false
            for (i in 0 until placed) {
                if (taken[i] == cell) {
                    clash = true
                }
            }
            if (!clash) {
                taken[placed++] = cell
            }
        }

        return Board(grid, taken, rules)
    }

    /**
     * Plays a random game, running [check] on the position before every move and once at the end.
     *
     * An illegal direction is played on purpose every so often, because a corpse on the board and a
     * snake sealed behind one are two of the states a sweep has to read correctly and neither shows
     * up in a game where everybody plays legally.
     */
    private fun walk(board: Board, rng: SplitMix64, check: () -> Unit): Int {
        var positions = 0
        var moves = 0

        while (true) {
            check()
            positions++

            if (board.outcome != null || moves >= MOVES_PER_GAME) {
                return positions
            }

            val id = board.toAct
            val legal = board.legalMoves(id)
            val direction = if (legal.isEmpty || rng.nextInt(SUICIDE_IN_N) == 0) {
                Direction.entries[rng.nextInt(Direction.entries.size)]
            } else {
                legal.nth(rng.nextInt(legal.size))
            }

            board.apply(id, direction)
            moves++
        }
    }

    private fun describe(board: BoardView): String {
        val heads = (0 until board.snakeCount).joinToString(",") {
            val snake = board.snake(SnakeId(it))
            if (snake.alive) "${snake.head.index}@${snake.length}" else "dead"
        }
        return "${board.grid} turn ${board.turnIndex} toAct ${board.toAct.index} heads [$heads]"
    }

    private companion object {
        const val SEED = 20_260_728L

        const val GAMES_PER_GEOMETRY = 12
        const val MOVES_PER_GAME = 40

        /** One move in this many is played without looking, which is how a board acquires a corpse. */
        const val SUICIDE_IN_N = 25

        /**
         * Every geometry `BotContractTest` runs, plus the ones a word-based sweep can get wrong.
         *
         * 62 columns is a padded row of exactly 64 bits, so a vertical step crosses whole words with
         * nothing left over — the case a shift of `64 - 0` would silently turn into a shift of zero.
         * 63 is one bit past it and 126 is two whole words, which are the neighbouring failures.
         */
        val GEOMETRIES = listOf(
            1 to 1, 1 to 5, 2 to 2, 3 to 7, 8 to 8, 9 to 13, 10 to 10, 11 to 11, 12 to 12, 14 to 14, 20 to 20,
            1 to 62, 2 to 62, 1 to 63, 3 to 63, 4 to 64, 2 to 126, 6 to 30, 30 to 6, 5 to 65,
        )
    }
}

/**
 * [SpaceOwnership]'s reading, computed one square at a time — the oracle, and the obvious algorithm.
 *
 * A single breadth-first sweep seeded from every live head at once, keeping the first arrival. A
 * square two snakes reach on the same step belongs to nobody and stops spreading; contact is recorded
 * wherever one snake's ground is next to another's, which is frontier adjacency rather than a tie.
 */
internal class BreadthFirstOwnership(private val grid: Grid, private val snakeCount: Int) {
    private val stamp = IntArray(grid.cellCount)
    private val steps = IntArray(grid.cellCount)
    private val owner = ByteArray(grid.cellCount)
    private val frontier = IntArray(grid.cellCount)
    private val counts = IntArray(snakeCount)
    private val touching = BooleanArray(snakeCount)
    private val directions = Direction.entries
    private var generation = 0

    fun measure(board: BoardView): IntArray {
        generation++
        counts.fill(0)
        touching.fill(false)

        var tail = 0
        for (slot in 0 until snakeCount) {
            val snake = board.snake(SnakeId(slot))
            if (!snake.alive) {
                continue
            }
            val head = snake.head
            stamp[head.index] = generation
            steps[head.index] = 0
            owner[head.index] = slot.toByte()
            frontier[tail++] = head.index
        }

        var head = 0
        while (head < tail) {
            val cell = Cell(frontier[head++])
            val by = owner[cell.index].toInt()
            if (by == CONTESTED) {
                continue
            }

            val distance = steps[cell.index] + 1
            for (i in directions.indices) {
                val next = grid.step(cell, directions[i])
                if (!board.isFree(next)) {
                    continue
                }

                if (stamp[next.index] != generation) {
                    stamp[next.index] = generation
                    steps[next.index] = distance
                    owner[next.index] = by.toByte()
                    counts[by]++
                    frontier[tail++] = next.index
                    continue
                }

                val holder = owner[next.index].toInt()
                if (holder != by) {
                    touching[by] = true
                    if (holder != CONTESTED) {
                        touching[holder] = true
                    }
                }

                if (steps[next.index] == distance && holder != by && holder != CONTESTED) {
                    counts[holder]--
                    owner[next.index] = CONTESTED.toByte()
                }
            }
        }

        return counts
    }

    fun isolated(slot: Int): Boolean = !touching[slot]

    private companion object {
        const val CONTESTED = -1
    }
}

/**
 * [TempoOwnership]'s reading, computed one square at a time.
 *
 * [BreadthFirstOwnership] with the two corrections that class exists for: distances are half-steps so
 * that the snake to act arrives first at an otherwise equidistant square, and a tail that retracts
 * within the round is ground rather than wall.
 */
internal class BreadthFirstTempo(private val grid: Grid, private val snakeCount: Int) {
    private val stamp = IntArray(grid.cellCount)
    private val steps = IntArray(grid.cellCount)
    private val owner = ByteArray(grid.cellCount)
    private val frontier = IntArray(grid.cellCount)
    private val counts = IntArray(snakeCount)
    private val touching = BooleanArray(snakeCount)
    private val vacating = IntArray(snakeCount)
    private val directions = Direction.entries
    private var generation = 0

    fun measure(board: BoardView): IntArray {
        generation++
        counts.fill(0)
        touching.fill(false)

        for (slot in 0 until snakeCount) {
            val snake = board.snake(SnakeId(slot))
            vacating[slot] = if (snake.alive && !snake.growsOnNextMove && snake.tail != snake.head) {
                snake.tail.index
            } else {
                NO_CELL
            }
        }

        var tail = 0

        val onTheClock = board.toAct.index
        val mover = board.snake(board.toAct)
        if (mover.alive) {
            val head = mover.head
            stamp[head.index] = generation
            steps[head.index] = 0
            owner[head.index] = onTheClock.toByte()
            frontier[tail++] = head.index
        }

        for (slot in 0 until snakeCount) {
            if (slot == onTheClock) {
                continue
            }
            val snake = board.snake(SnakeId(slot))
            if (!snake.alive) {
                continue
            }
            val head = snake.head
            stamp[head.index] = generation
            steps[head.index] = BEHIND
            owner[head.index] = slot.toByte()
            frontier[tail++] = head.index
        }

        var head = 0
        while (head < tail) {
            val cell = Cell(frontier[head++])
            val by = owner[cell.index].toInt()
            if (by == TempoOwnership.NOBODY) {
                continue
            }

            val arrival = steps[cell.index] + STEP
            for (i in directions.indices) {
                val next = grid.step(cell, directions[i])
                if (!passable(board, next)) {
                    continue
                }

                if (stamp[next.index] != generation) {
                    stamp[next.index] = generation
                    steps[next.index] = arrival
                    owner[next.index] = by.toByte()
                    counts[by]++
                    frontier[tail++] = next.index
                    continue
                }

                val holder = owner[next.index].toInt()
                if (holder != by) {
                    touching[by] = true
                    if (holder != TempoOwnership.NOBODY) {
                        touching[holder] = true
                    }
                }

                if (steps[next.index] == arrival && holder != by && holder != TempoOwnership.NOBODY) {
                    counts[holder]--
                    owner[next.index] = TempoOwnership.NOBODY.toByte()
                }
            }
        }

        return counts
    }

    fun ownerOf(cell: Cell): Int =
        if (stamp[cell.index] != generation) TempoOwnership.NOBODY else owner[cell.index].toInt()

    fun isolated(slot: Int): Boolean = !touching[slot]

    fun distanceTo(cell: Cell): Int = steps[cell.index] / STEP

    private fun passable(board: BoardView, cell: Cell): Boolean {
        if (board.isFree(cell)) {
            return true
        }
        for (slot in 0 until snakeCount) {
            if (vacating[slot] == cell.index) {
                return true
            }
        }
        return false
    }

    private companion object {
        const val STEP = 2
        const val BEHIND = 1
        const val NO_CELL = -1
    }
}
