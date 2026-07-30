package ao.snakewarz.bots.search.puct

import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.bots.search.CellBits
import ao.snakewarz.bots.search.SpaceOwnership
import ao.snakewarz.core.grid.Cell
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
 * ### One half-step at a time, over a bitmap
 *
 * The sweep is a [CellBits] one, and the alternation above is what it looks like there: an even
 * half-step advances the mover's frontier alone and an odd one advances every other snake's at once,
 * against the same set of squares already taken. Because only one snake moves on the even half-steps,
 * a tie is possible only among the waiting ones, which is the same statement the arithmetic above
 * makes. `SpaceOwnership` carries why advancing whole layers reproduces a queue exactly.
 *
 * At **two snakes only one frontier ever moves per half-step** — the mover on an even one, the single
 * waiting snake on an odd one — so [advanceWaiting] is never reached at all, measured at 0.0 calls a
 * sweep on every board. It exists for three seats and up, and anything costed on this sweep at two
 * snakes is costing [advanceAlone] alone.
 *
 * A snake whose frontier lands nowhere is dropped for the rest of the sweep: an empty frontier
 * spreads to nothing forever after, and a snake sealed into a corner is the common case late in a
 * game — which is when this evaluation is being asked the question that decides it.
 *
 * Read the counts, then [ownerOf] per square and [isolated] per slot, before the next sweep.
 */
internal class TempoOwnership(private val grid: Grid, private val snakeCount: Int) {
    /** What the sweep may walk on: every free square, plus every tail retracting within the round. */
    private val open = CellBits(grid)

    /** Every square any frontier has reached, contested ones included. Nothing is claimed twice. */
    private val taken = CellBits(grid)

    /** Where each slot's frontier stands, and where it lands next. Swapped rather than copied. */
    private val frontier = Array(snakeCount) { CellBits(grid) }
    private val landing = Array(snakeCount) { CellBits(grid) }

    /** Everything each slot holds, which is what [isolated] is read off once the sweep is done. */
    private val owned = Array(snakeCount) { CellBits(grid) }

    private val reachedThisLayer = CellBits(grid)
    private val sharedThisLayer = CellBits(grid)

    /** A slot's ground plus the head it spreads from, and what lies one step outside the pair. */
    private val ground = CellBits(grid)
    private val outside = CellBits(grid)

    /** Every live head, because a head is spread from without ever being somewhere to spread to. */
    private val heads = CellBits(grid)

    /** Which slot reached each square, and when — the per-square reading [FillableSpace] walks. */
    private val owner = ByteArray(grid.cellCount)
    private val steps = IntArray(grid.cellCount)

    /** Somewhere to spell a layer out square by square while [owner] and [steps] are written. */
    private val landed = IntArray(grid.cellCount)

    /** Live slots for the readout, and the waiting ones still spreading. Both shrink, differently. */
    private val live = IntArray(snakeCount)
    private var liveCount = 0
    private val waiting = IntArray(snakeCount)
    private var waitingCount = 0

    private var mover = NOBODY
    private var moverSeeded = false

    private val counts = IntArray(snakeCount)
    private val touching = BooleanArray(snakeCount)

    /**
     * Squares each slot reaches strictly before every other, indexed by slot.
     *
     * The same array every call — read it or copy it before the next sweep. A dead snake owns
     * nothing; a head is not counted, because it is occupied.
     */
    fun measure(board: BoardView): IntArray {
        open.freeSquaresOf(board)
        taken.clear()
        heads.clear()
        owner.fill(NOBODY.toByte())
        liveCount = 0
        waitingCount = 0

        for (slot in 0 until snakeCount) {
            counts[slot] = 0
            touching[slot] = false
            owned[slot].clear()
            frontier[slot].clear()

            val snake = board.snake(SnakeId(slot))
            // A length-one snake's tail is its head, and calling that square free would hand the
            // ground under a living snake to whoever is standing next to it.
            if (snake.alive && !snake.growsOnNextMove && snake.tail != snake.head) {
                open.add(snake.tail)
            }
        }

        // The mover first, and at zero. Everybody else is half a step behind, everywhere at once.
        mover = board.toAct.index
        moverSeeded = board.snake(board.toAct).alive
        if (moverSeeded) {
            seed(board, mover, 0)
        }
        for (slot in 0 until snakeCount) {
            if (slot != mover && board.snake(SnakeId(slot)).alive) {
                seed(board, slot, BEHIND)
                waiting[waitingCount++] = slot
            }
        }

        spread()

        for (i in 0 until liveCount) {
            val slot = live[i]
            counts[slot] = owned[slot].count()
            touching[slot] = meetsAnybody(board, slot)
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
    fun ownerOf(cell: Cell): Int = owner[cell.index].toInt()

    /** Whether [slot] can no longer reach any ground anybody else can. Read after [measure]. */
    fun isolated(slot: Int): Boolean = !touching[slot]

    /** Squares [slot] reaches first, by index rather than through the array [measure] returns. */
    fun ownedBy(slot: Int): Int = counts[slot]

    /**
     * How many squares the sweep could set foot on at all — free ones plus the tails retracting
     * within the round.
     *
     * Against `Grid.playableCount` that is how full the board is, which is a reading about the
     * *phase* of the game rather than about any one snake and so has nowhere else to come from.
     */
    fun walkableCount(): Int = open.count()

    /**
     * Whether the sweep could set foot on [cell] at all — free, or a tail retracting within the round.
     *
     * What [ownerOf] cannot say on its own. A square nobody reached and a square that is wall both
     * read [NOBODY], and the difference is the whole of what "contested" means: ground somebody else
     * got to first is a boundary, and a wall is a back. [ChamberTree] is what reads the pair.
     */
    fun walkable(cell: Cell): Boolean = open.contains(cell)

    /**
     * Moves from its owner's head to [cell], for a square [ownerOf] gives somebody.
     *
     * The frontier advances in [STEP] half-steps from a seed that is [BEHIND] at worst, so halving
     * recovers whole moves for the mover and for everybody else alike. Read it the way [ownerOf] is
     * read — for a square this sweep gave somebody, before the next sweep.
     */
    fun distanceTo(cell: Cell): Int = steps[cell.index] / STEP

    // -- internals

    private fun seed(board: BoardView, slot: Int, arrival: Int) {
        val head = board.snake(SnakeId(slot)).head
        frontier[slot].add(head)
        taken.add(head)
        heads.add(head)
        owner[head.index] = slot.toByte()
        steps[head.index] = arrival
        live[liveCount++] = slot
    }

    /**
     * Advances the sweep half-step by half-step until nobody has anywhere left to go.
     *
     * The mover's arrivals are even and everybody else's odd, so the two alternate and a half-step
     * with nobody on it costs one test.
     */
    private fun spread() {
        var moving = moverSeeded
        var arrival = BEHIND

        while (moving || waitingCount > 0) {
            arrival++
            if (arrival and 1 == 0) {
                // An even half-step is the snake on the clock, alone, so nothing it lands on is a tie.
                if (moving) {
                    moving = advanceAlone(mover, arrival)
                }
            } else if (waitingCount == 1) {
                // And so is an odd one with a single snake still waiting, which is every two-snake
                // game — the shape everything here is measured on.
                if (!advanceAlone(waiting[0], arrival)) {
                    waitingCount = 0
                }
            } else if (waitingCount > 1) {
                advanceWaiting(arrival)
            }
        }
    }

    /** One half-step for a snake nobody can tie with, which needs none of the machinery below. */
    private fun advanceAlone(slot: Int, arrival: Int): Boolean {
        val next = landing[slot]
        if (!next.spreadFrom(frontier[slot], open, taken)) {
            return false
        }

        taken.addAll(next)
        owned[slot].addAll(next)
        record(next, slot, arrival)

        landing[slot] = frontier[slot]
        frontier[slot] = next
        return true
    }

    /** One odd half-step with several snakes waiting, so squares two of them reach are ties. */
    private fun advanceWaiting(arrival: Int) {
        for (i in 0 until waitingCount) {
            val slot = waiting[i]
            landing[slot].spreadFrom(frontier[slot], open, taken)
        }

        reachedThisLayer.copyFrom(landing[waiting[0]])
        sharedThisLayer.clear()
        for (i in 1 until waitingCount) {
            val next = landing[waiting[i]]
            sharedThisLayer.addShared(reachedThisLayer, next)
            reachedThisLayer.addAll(next)
        }
        taken.addAll(reachedThisLayer)

        var stillSpreading = 0
        for (i in 0 until waitingCount) {
            val slot = waiting[i]
            val next = landing[slot]
            if (!next.settleInto(sharedThisLayer, owned[slot])) {
                continue
            }
            record(next, slot, arrival)

            landing[slot] = frontier[slot]
            frontier[slot] = next
            waiting[stillSpreading++] = slot
        }
        waitingCount = stillSpreading
    }

    /** Spells one layer out square by square, which is the only way a per-square reading gets written. */
    private fun record(layer: CellBits, slot: Int, arrival: Int) {
        val code = slot.toByte()
        val found = layer.cellsInto(landed)
        for (i in 0 until found) {
            val cell = landed[i]
            owner[cell] = code
            steps[cell] = arrival
        }
    }

    /**
     * Whether anything next to [slot]'s ground belongs to somebody else — [SpaceOwnership]'s two
     * questions, over the squares a retracting tail adds to the first of them.
     */
    private fun meetsAnybody(board: BoardView, slot: Int): Boolean {
        val head = board.snake(SnakeId(slot)).head

        ground.copyFrom(owned[slot])
        ground.add(head)
        if (outside.spreadFrom(ground, open, owned[slot])) {
            return true
        }

        ground.copyFrom(heads)
        ground.remove(head)
        return outside.spreadFrom(owned[slot], ground, owned[slot])
    }

    companion object {
        /** Reached by two waiting snakes on the same half-step, so held by neither. Fits in a byte. */
        const val NOBODY: Int = -1

        /** One square, in half-steps, so that a whole turn of tempo is expressible as one unit. */
        private const val STEP = 2

        /** What a snake that is not about to move gives away: half a step, everywhere at once. */
        private const val BEHIND = 1
    }
}
