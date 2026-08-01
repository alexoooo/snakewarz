package ao.snakewarz.bots.reactive.policy

import ao.snakewarz.botapi.Bot
import ao.snakewarz.botapi.BotSetup
import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.Turn
import ao.snakewarz.bots.search.puct.TempoOwnership
import ao.snakewarz.core.grid.Cell
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.DirectionSet
import ao.snakewarz.core.grid.Grid

/**
 * The unreleased P2 policy family: read one live board, rank its legal destinations, and return.
 *
 * This deliberately has no search allowance and no playout. Room and path readings treat the
 * mover's retracting tail as free; the old head otherwise stays occupied as the neck it becomes,
 * while a length-one snake correctly frees it because head and tail are the same square. The
 * optional ownership reading runs one tempo sweep, applies the same old-head rule, and attributes
 * each legal destination the size of its mover-owned component. Every buffer belongs to the bot and
 * is reused for the life of the match.
 */
internal class PolicyBot(
    setup: BotSetup,
    private val variant: PolicyVariant,
) : Bot {
    private val self = setup.self
    private val ranker = PolicyRanker(setup.grid, setup.opponentCount + 1, variant)

    /** Directions tied on every enabled reading, before the position-derived tie-break. */
    internal val rawMaxima: DirectionSet get() = ranker.rawMaxima

    override fun chooseMove(turn: Turn): Decision = Decision.Move(chooseDirection(turn))

    internal fun chooseDirection(turn: Turn): Direction = ranker.choose(turn.board, self, turn.legalMoves)

    override fun toString(): String = "PolicyBot(${variant.key})"
}

/** One implementation and six measured declarations; [FULL_OWNED] ships as `cartographer`. */
internal enum class PolicyVariant(
    val key: String,
    val guard: Boolean,
    val path: Boolean,
    val local: Boolean,
    val room: Boolean,
    val wall: Boolean = false,
    val owned: Boolean = false,
) {
    GUARDED_PATH("guarded-path", guard = true, path = true, local = false, room = true),
    LOCAL("local", guard = false, path = false, local = true, room = false),
    LOCAL_ROOM("local-room", guard = true, path = false, local = true, room = true),
    FULL("full", guard = true, path = true, local = true, room = true),
    FULL_WALL("full-wall", guard = true, path = true, local = true, room = true, wall = true),
    FULL_OWNED("full-owned", guard = true, path = true, local = true, room = true, owned = true),
}

/** Labels mover-owned components beside the head in one pass, excluding [blocked] when present. */
internal class MoverOwnedComponents(private val grid: Grid) {
    private val stamp = IntArray(grid.cellCount)
    private val frontier = IntArray(grid.cellCount)
    private val directions = Direction.entries
    private var generation = 0

    fun into(
        ownership: TempoOwnership,
        mover: Int,
        blocked: Cell,
        legal: DirectionSet,
        destinations: IntArray,
        areas: IntArray,
    ) {
        nextGeneration()

        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            areas[ordinal] = 0
        }

        for (i in 0 until legal.size) {
            val ordinal = legal.nth(i).ordinal
            val start = destinations[ordinal]
            if (stamp[start] == generation) {
                continue
            }

            stamp[start] = generation
            frontier[0] = start
            var head = 0
            var tail = 1

            while (head < tail) {
                val cell = Cell(frontier[head++])
                for (j in directions.indices) {
                    val next = grid.step(cell, directions[j])
                    if (next == blocked || stamp[next.index] == generation || ownership.ownerOf(next) != mover) {
                        continue
                    }
                    stamp[next.index] = generation
                    frontier[tail++] = next.index
                }
            }

            for (j in 0 until legal.size) {
                val other = legal.nth(j).ordinal
                if (areas[other] == 0 && stamp[destinations[other]] == generation) {
                    areas[other] = tail
                }
            }
        }
    }

    private fun nextGeneration() {
        if (generation == Int.MAX_VALUE) {
            stamp.fill(0)
            generation = 0
        }
        generation++
    }
}
