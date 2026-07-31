package ao.snakewarz.core.rules

import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.random.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A board handed an empty set of walls plays the same game, square for square and hash for hash, as
 * one that was never told walls exist.
 *
 * Every measurement this project has ever taken, every golden move stream and every replay URL
 * anybody has shared was recorded on a board with no map. The wall machinery is only free to land if
 * the mapless case is *bit*-identical rather than merely equivalent, so this compares `signature()`
 * — which folds `hash` and `occupancyHash` together with the whole owner array — on every turn of a
 * full game rather than the result at the end.
 */
class WallNeutralityTest {
    @Test
    fun `an empty wall array plays bit-identically to no wall array at all`() {
        for (geometry in GEOMETRIES) {
            for (seats in 1..MAX_SEATS) {
                val grid = Grid(geometry.first, geometry.second)
                val spawns = cornerSpawns(grid, seats)
                val where = "$grid with $seats"

                val mapless = play(Board(grid, spawns))
                val emptyMap = play(Board(grid, spawns, wallCells = IntArray(0)))

                assertEquals(mapless.size, emptyMap.size, "$where: the two arms played different lengths")
                for (turn in mapless.indices) {
                    assertEquals(mapless[turn], emptyMap[turn], "$where: the boards diverged on turn $turn")
                }
                assertTrue(mapless.size > MIN_INTERESTING_TURNS, "$where: the sample game is too short to mean much")
            }
        }
    }

    private companion object {
        val GEOMETRIES = listOf(8 to 8, 12 to 12, 20 to 20, 13 to 17)
        const val MAX_SEATS = 4
        const val MIN_INTERESTING_TURNS = 8
        const val SEED = 20260730L

        /** One corner per seat, so the two arms start from the same position at every seat count. */
        fun cornerSpawns(grid: Grid, seats: Int): IntArray {
            val corners = listOf(
                0 to 0,
                grid.rows - 1 to grid.cols - 1,
                0 to grid.cols - 1,
                grid.rows - 1 to 0,
            )
            return IntArray(seats) { grid.cellAt(corners[it].first, corners[it].second).index }
        }

        /** The signature of every position a full game passes through, the terminal one included. */
        fun play(board: Board): List<String> {
            val rng = SplitMix64(SEED)
            val signatures = mutableListOf<String>()
            while (board.outcome == null) {
                signatures += board.signature()
                board.apply(board.toAct, chosenMove(board, rng))
            }
            signatures += board.signature()
            return signatures
        }
    }
}
