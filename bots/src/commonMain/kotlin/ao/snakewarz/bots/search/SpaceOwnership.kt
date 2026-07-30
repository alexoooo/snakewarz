package ao.snakewarz.bots.search

import ao.snakewarz.bots.reactive.space.FloodFill
import ao.snakewarz.bots.search.puct.TerritoryEval
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
 * snake. Buffers are allocated once per bot per match.
 *
 * ### A layer at a time, not a square at a time
 *
 * The sweep is a [CellBits] one: every slot's frontier is a bitmap and one breadth-first layer is a
 * shift and a mask over a handful of `Long`s. That is exact rather than approximate, and the reason
 * is the queue this replaces: its frontier is FIFO, so every square at distance `d` is claimed while
 * a square at `d - 1` is being expanded and therefore *before* any square at `d` comes off the queue.
 * All the claims and all the ties of one distance are settled before that distance spreads, which is
 * precisely what advancing every frontier in lockstep does.
 *
 * Contact is read the same way and off the finished sweep: a slot has met somebody when a square next
 * to its own ground is free and is not its own. Every free square next to a claimed one is itself
 * claimed — the sweep would have taken it otherwise — so that is the same test as "one frontier ran
 * into another", without having to catch the moment it happened.
 *
 * **The single-frontier path is not the separated case**, and reading it as one is the mistake to
 * avoid here. A slot leaves [spread]'s active set when its frontier lands nowhere, which says whose
 * room runs out first and nothing about whether the rooms connect: two separated snakes with
 * comparable room advance together for the whole sweep, and one snake sealed into a corner of a
 * board it still shares leaves the other alone within a few layers. Layers per sweep, measured over
 * a `puct` line at the shipped allowance:
 *
 * | | sweeps a turn | multi-frontier layers | single-frontier layers |
 * |---|---|---|---|
 * | 8x8 | 738 | 6.1 | 2.7 |
 * | 12x12 | 784 | 9.9 | 3.7 |
 * | 20x20 | 904 | 15.9 | 5.4 |
 * | 20x20, once the board has come apart | **487** | 5.1 | 2.2 |
 *
 * The split is about 70/30 in both phases. What separation moves is the *size* of a sweep and how
 * many of them a turn runs, and it moves both **down** — so anything that makes a sweep cheaper is
 * worth less after the board comes apart, not more.
 *
 * The one simplification worth naming: it ignores whose turn it is. A snake about to move reaches an
 * equidistant square first in reality, and here the square is contested. That is a half-step of
 * accuracy, uniformly applied, in a heuristic that is already an approximation of the game.
 */
internal class SpaceOwnership(private val grid: Grid, private val snakeCount: Int) {
    /** What the sweep may walk on: every free square of the board it was handed. */
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

    /** Live slots, so a sweep over a field with corpses in it costs what the survivors cost. */
    private val live = IntArray(snakeCount)
    private var liveCount = 0

    /** The live slots whose frontier still has somewhere to go. An empty one never fills again. */
    private val spreading = IntArray(snakeCount)
    private var spreadingCount = 0

    private val counts = IntArray(snakeCount)

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
        open.freeSquaresOf(board)
        taken.clear()
        heads.clear()
        liveCount = 0

        for (slot in 0 until snakeCount) {
            counts[slot] = 0
            touching[slot] = false
            owned[slot].clear()
            frontier[slot].clear()

            val snake = board.snake(SnakeId(slot))
            if (!snake.alive) {
                continue
            }
            frontier[slot].add(snake.head)
            taken.add(snake.head)
            heads.add(snake.head)
            live[liveCount++] = slot
        }

        if (liveCount > 0) {
            spread()
        }

        for (i in 0 until liveCount) {
            val slot = live[i]
            counts[slot] = owned[slot].count()
            touching[slot] = meetsAnybody(board, slot)
        }
        return counts
    }

    /**
     * Whether [slot] can no longer reach any ground anybody else can. Read after [measure].
     *
     * A separated snake's game is decided in a way a shared board's is not: it will fill its own
     * room and die when it runs out, so whoever was left the most ground outlasts the rest and the
     * only thing still in question is the arithmetic. That is a materially different judgement from
     * a share of a contested board, and it is why an evaluation wants to know — see [TerritoryEval].
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
        val share = measure(board)

        var leader = -1
        var best = -1
        var tied = false

        for (slot in 0 until snakeCount) {
            if (!board.snake(SnakeId(slot)).alive) {
                continue
            }
            val held = share[slot]
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

    // -- internals

    /**
     * Advances every live frontier one square at a time until none of them has anywhere left to go.
     *
     * A snake whose frontier lands nowhere is dropped for the rest of the sweep: an empty frontier
     * spreads to nothing forever after, and a snake sealed into a corner while the other fills the
     * board is the common shape of a leaf late in a game.
     */
    private fun spread() {
        spreadingCount = liveCount
        for (i in 0 until liveCount) {
            spreading[i] = live[i]
        }

        while (spreadingCount > 0) {
            if (spreadingCount == 1) {
                // Nobody left to tie with, which needs none of the machinery below.
                if (!advanceAlone(spreading[0])) {
                    spreadingCount = 0
                }
                continue
            }
            advanceTogether()
        }
    }

    private fun advanceAlone(slot: Int): Boolean {
        val landed = landing[slot]
        if (!landed.spreadFrom(frontier[slot], open, taken)) {
            return false
        }

        taken.addAll(landed)
        owned[slot].addAll(landed)

        landing[slot] = frontier[slot]
        frontier[slot] = landed
        return true
    }

    private fun advanceTogether() {
        for (i in 0 until spreadingCount) {
            val slot = spreading[i]
            landing[slot].spreadFrom(frontier[slot], open, taken)
        }

        // A square two frontiers land on this layer is a tie: it goes to neither, and it stops
        // there. It is still taken, so nothing arriving later may have it either.
        reachedThisLayer.copyFrom(landing[spreading[0]])
        sharedThisLayer.clear()
        for (i in 1 until spreadingCount) {
            val landed = landing[spreading[i]]
            sharedThisLayer.addShared(reachedThisLayer, landed)
            reachedThisLayer.addAll(landed)
        }
        taken.addAll(reachedThisLayer)

        var stillSpreading = 0
        for (i in 0 until spreadingCount) {
            val slot = spreading[i]
            val landed = landing[slot]
            if (!landed.settleInto(sharedThisLayer, owned[slot])) {
                continue
            }

            landing[slot] = frontier[slot]
            frontier[slot] = landed
            spreading[stillSpreading++] = slot
        }
        spreadingCount = stillSpreading
    }

    /**
     * Whether anything next to [slot]'s ground belongs to somebody else.
     *
     * Two questions, because a sweep starts on squares it would never walk onto. The first is the
     * one that reads as obvious — is a free square next to this snake's ground somebody else's, or a
     * tie — and the head is spread from as well as the ground, because a snake whose every
     * neighbouring square went to a rival holds nothing at all and is still standing in the fight.
     *
     * The second is the one a free-square test cannot see: another snake's **head** beside this
     * snake's ground. A head is occupied, so it is never a square anybody may take, and it is still
     * a snake standing one move away — and the sweep spreads from it, which is what makes the two
     * frontiers adjacent. A snake whose own ground reaches the square a rival is standing on has
     * plainly not been separated from it.
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
        /** A judged draw: nobody is ahead by enough to call, which is a real reading of a position. */
        private val DRAWN = MatchOutcome(SnakeId.NONE, MatchEnd.TURN_LIMIT)
    }
}
