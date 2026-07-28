package ao.snakewarz.bots.search.puct

import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.snake.SnakeId

/**
 * [SpaceOwnership] with the two approximations it names taken out — who moves first, and which
 * squares are about to clear.
 *
 * A **sibling rather than a mode**, for [PuctTree]'s reason. `SpaceOwnership` is read by
 * [TerritoryEval], by `UctBot.simulate` and by `SpaceOwnershipTest`, and `GoldenMoveStreamTest` pins
 * the move stream that comes out the far end of all three. A flag on it would put a branch in the
 * hottest loop those three share to buy an accuracy only this one wants, and would make every
 * measured figure in [TerritoryEval]'s tables a thing somebody has to re-derive.
 *
 * ### Whose turn it is
 *
 * `SpaceOwnership` calls ignoring turn order "a half-step of accuracy, uniformly applied". It is not
 * uniform: it is worth exactly one square of frontier to whoever is about to move, on every contested
 * boundary on the board, and a search that cannot see it will walk into a head-on trade believing it
 * is even. So distances here are **half-steps**: the snake to act seeds at `0` and everybody else at
 * [BEHIND], and a step costs [STEP]. The mover's arrival times are therefore even and everybody
 * else's odd, so the two can never tie and an equidistant square falls to whoever gets there first —
 * which is the whole correction, stated as arithmetic rather than as a special case.
 *
 * Between two snakes that are *both* waiting, ties still happen and are still contested. Grading them
 * would need the turn order, and [BoardView] deliberately exposes only [BoardView.toAct] — the
 * permutation is `Board`'s private business, and `MatchSetup` shuffles it per match. So this is exact
 * for two snakes and one place short of exact for more, which is the shape of the error, not a
 * uniform blur.
 *
 * ### Which squares are about to clear
 *
 * Snakes here grow at half speed, so a tail retracts on alternating turns and the square under it is
 * free to walk into within the round. [FloodFill] takes one such square as `alsoFree` for exactly
 * this reason; this takes every snake's, because a sweep that treats a whole board of tails as
 * permanent wall under-counts every region on it late in a game — which is precisely when
 * [SurvivalEval] is being asked the question that matters.
 *
 * Read the counts, then [ownerOf] per square and [isolated] per slot, before the next sweep.
 */
internal class TempoOwnership(private val grid: Grid, private val snakeCount: Int) {
    private val stamp = IntArray(grid.cellCount)
    private val steps = IntArray(grid.cellCount)
    private val owner = ByteArray(grid.cellCount)
    private val frontier = IntArray(grid.cellCount)
    private val counts = IntArray(snakeCount)
    private val touching = BooleanArray(snakeCount)

    /** Each slot's retracting tail square this sweep, or [NO_CELL]. Refilled by [measure]. */
    private val vacating = IntArray(snakeCount)

    private val directions = Direction.entries
    private var generation = 0

    /**
     * Squares each slot reaches strictly before every other, indexed by slot.
     *
     * The same array every call — read it or copy it before the next sweep. A dead snake owns
     * nothing; a head is not counted, because it is occupied.
     */
    fun measure(board: BoardView): IntArray {
        nextGeneration()
        counts.fill(0)
        touching.fill(false)

        for (slot in 0 until snakeCount) {
            val snake = board.snake(SnakeId(slot))
            // A length-one snake's tail is its head, and calling that square free would hand the
            // ground under a living snake to whoever is standing next to it.
            vacating[slot] = if (snake.alive && !snake.growsOnNextMove && snake.tail != snake.head) {
                snake.tail.index
            } else {
                NO_CELL
            }
        }

        var tail = 0

        // The mover first, and at zero. Seeding in arrival order is what keeps the frontier a plain
        // FIFO: every push is the parent's time plus STEP, and parents come off in nondecreasing
        // order, so pushes are nondecreasing too and no priority queue is needed.
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
            if (by == NOBODY) {
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

                // Already claimed. A tie on the same half-step takes it off both of them; anything
                // reached later than the incumbent was simply beaten to it.
                val holder = owner[next.index].toInt()
                if (holder != by) {
                    // Contact, and it is frontier adjacency rather than the tie below — see
                    // SpaceOwnership, which found that an even corridor divides with no square ever
                    // contested and so cannot be told apart from a separation by ties alone.
                    touching[by] = true
                    if (holder != NOBODY) {
                        touching[holder] = true
                    }
                }

                if (steps[next.index] == arrival && holder != by && holder != NOBODY) {
                    counts[holder]--
                    owner[next.index] = NOBODY.toByte()
                }
            }
        }

        return counts
    }

    /**
     * Which slot reaches [cell] first, or [NOBODY] for a square nobody reaches or two snakes tie on.
     *
     * A head reads as its own snake's, which is what [FillableSpace] wants: the square a walk starts
     * from is part of the region it starts in, and it is the one square in that region the walk
     * cannot spend.
     */
    fun ownerOf(cell: Cell): Int =
        if (stamp[cell.index] != generation) NOBODY else owner[cell.index].toInt()

    /** Whether [slot] can no longer reach any ground anybody else can. Read after [measure]. */
    fun isolated(slot: Int): Boolean = !touching[slot]

    private fun passable(board: BoardView, cell: Cell): Boolean {
        if (board.isFree(cell)) {
            return true
        }
        // Only reached for an occupied square, which on an open board is a small minority of the
        // tests, and the loop is over two to four snakes.
        for (slot in 0 until snakeCount) {
            if (vacating[slot] == cell.index) {
                return true
            }
        }
        return false
    }

    private fun nextGeneration() {
        if (generation == Int.MAX_VALUE) {
            stamp.fill(0)
            generation = 0
        }
        generation++
    }

    companion object {
        /** Reached by two waiting snakes on the same half-step, so held by neither. Fits in a byte. */
        const val NOBODY: Int = -1

        /** One square, in half-steps, so that a whole turn of tempo is expressible as one unit. */
        private const val STEP = 2

        /** What a snake that is not about to move gives away: half a step, everywhere at once. */
        private const val BEHIND = 1

        private const val NO_CELL = -1
    }
}
