package ao.snakewarz.bots.search

import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.bots.search.puct.ExpertEval
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.BoardView
import ao.snakewarz.core.rules.MatchEnd
import ao.snakewarz.core.rules.MatchOutcome
import ao.snakewarz.core.snake.SnakeId

/**
 * Who would get where first — the board carved up between the snakes, in one sweep.
 *
 * [FloodFill] answers "how much room does *this* head have", which is the right question for a bot
 * choosing between four moves and the wrong one for judging a position: while two snakes share an
 * open region they both reach every square in it, so both fills return the same number and the
 * comparison says nothing. Seeding a single breadth-first sweep from *every* live head at once and
 * keeping the first arrival instead gives each snake the squares it would reach before anybody else,
 * which discriminates from the opening move onward.
 *
 * Squares two snakes reach on the same step belong to **nobody**, and stop spreading. Claiming one
 * for whichever slot happens to be lower would hand a real advantage to a lower index, and letting a
 * contested square go on propagating would let a snake claim a whole region through ground it cannot
 * actually take.
 *
 * The cost is one sweep of the free area regardless of how many snakes are on it, which is what makes
 * it affordable as a rollout cut-off evaluation — the same order as one [FloodFill], not one per
 * snake. Buffers are allocated once per bot per match, and the visited set is a generation stamp, so
 * a sweep costs the squares it reaches rather than the size of the board.
 *
 * The one simplification worth naming: it ignores whose turn it is. A snake about to move reaches an
 * equidistant square first in reality, and here the square is contested. That is a half-step of
 * accuracy, uniformly applied, in a heuristic that is already an approximation of the game.
 */
internal class SpaceOwnership(private val grid: Grid, private val snakeCount: Int) {
    private val stamp = IntArray(grid.cellCount)
    private val steps = IntArray(grid.cellCount)
    private val owner = ByteArray(grid.cellCount)
    private val frontier = IntArray(grid.cellCount)
    private val counts = IntArray(snakeCount)
    private val directions = Direction.entries
    private var generation = 0

    /**
     * Whether each slot's ground ever ran into somebody else's — see [isolated].
     *
     * A `BooleanArray` rather than a pairwise matrix because the only question worth asking is
     * "meets *anybody*". `Occupancy.MAX_SNAKES` is 126, so a matrix would be sixteen thousand
     * booleans standing ready to answer a question nobody has.
     */
    private val touching = BooleanArray(snakeCount)

    /**
     * Outcomes handed to the tree, one per slot, built once.
     *
     * A judged position is credited exactly as a played-out one is, so nothing downstream has to know
     * the difference — and phrasing the judgement as an outcome rather than a score is what keeps
     * `UctTree.record` a single code path. Cached because a search asks this thousands of times a
     * turn and every one of them would otherwise allocate.
     */
    private val verdicts = Array(snakeCount) { MatchOutcome(SnakeId(it), MatchEnd.LAST_SNAKE_STANDING) }

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

                // Already claimed. A tie on the same step takes it off both of them; anything reached
                // later than the incumbent was simply beaten to it.
                val holder = owner[next.index].toInt()
                if (holder != by) {
                    // Contact, and it is *frontier adjacency* rather than the tie below — two
                    // territories that touch, however lopsidedly. A corridor of even free length
                    // divides cleanly with no square ever contested (1x6 with a head at each end
                    // owns two and two), so a tie-based test would report two snakes about to
                    // collide as separated. See SpaceOwnershipTest.
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

    /**
     * Whether [slot] can no longer reach any ground anybody else can. Read after [measure].
     *
     * A separated snake's game is decided in a way a shared board's is not: it will fill its own
     * room and die when it runs out, so whoever was left the most ground outlasts the rest and the
     * only thing still in question is the arithmetic. That is a materially different judgement from
     * a share of a contested board, and it is why an evaluation wants to know — see [ExpertEval].
     *
     * A dead snake seeds nothing and so is isolated, which reads correctly: nobody is in its way.
     */
    fun isolated(slot: Int): Boolean = !touching[slot]

    /**
     * The position as a result: whoever owns the most ground has won it.
     *
     * A tie is a draw, which is the honest reading — two snakes with equal room have not been
     * separated by anything this can see, and inventing a winner would feed the tree noise.
     */
    fun verdict(board: BoardView): MatchOutcome {
        val owned = measure(board)

        var leader = -1
        var best = -1
        var tied = false

        for (slot in 0 until snakeCount) {
            if (!board.snake(SnakeId(slot)).alive) {
                continue
            }
            val held = owned[slot]
            when {
                held > best -> {
                    best = held
                    leader = slot
                    tied = false
                }

                held == best -> tied = true
            }
        }

        return if (leader < 0 || tied) DRAWN else verdicts[leader]
    }

    private fun nextGeneration() {
        if (generation == Int.MAX_VALUE) {
            stamp.fill(0)
            generation = 0
        }
        generation++
    }

    companion object {
        /** Reached by two snakes on the same step, so held by neither. Fits in the owner byte. */
        private const val CONTESTED = -1

        /**
         * A judged draw.
         *
         * Equal to `BoardScratch.EXHAUSTED` by value and deliberately not the same instance: an
         * exhausted budget is checked for by identity, because "the allowance ran out" carries no
         * information about the position and must not be credited, whereas this is a real reading.
         */
        private val DRAWN = MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT)
    }
}
