package ao.snakewarz.bots.search.learned

import ao.snakewarz.botapi.Decision
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.bots.boardOf
import ao.snakewarz.bots.cornerSpawns
import ao.snakewarz.bots.setupFor
import ao.snakewarz.bots.turnOn
import ao.snakewarz.core.Budget
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.EliminationReason
import ao.snakewarz.core.snake.SnakeId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the feature row is what a model can actually be fitted on: fixed length, bounded, and the
 * same reading on every geometry.
 *
 * Those three are the whole contract, and each is load-bearing for a different reason. **Fixed
 * length** because the trainer and the bot share one definition and a row written by one is read by
 * the other. **Bounded** because a linear model over unbounded inputs is a model whose coefficients
 * mean something different on a 12x12 than on a 200x200. And **geometry-independent** because
 * `BotContractTest` seats a match on eleven board shapes and `MatchSetup.MAX_SIDE` is 256, so a
 * reading that scales with the board is a reading the model would have to unlearn per board.
 */
class PositionFeaturesTest {
    @Test
    fun `the row is as long as it says and every reading is named`() {
        assertEquals(PositionFeatures.LENGTH, PositionFeatures.NAMES.size, "a reading without a name")

        // Every declared index used exactly once. The indices are hand-assigned, so a duplicate is
        // two readings writing one column and the second silently wins.
        val indices = listOf(
            PositionFeatures.USABLE_SHARE, PositionFeatures.USABLE_MARGIN, PositionFeatures.OWNED_SHARE,
            PositionFeatures.CHAIN_EFFICIENCY, PositionFeatures.REGION_SHARE, PositionFeatures.RIVAL_REGION_SHARE,
            PositionFeatures.SEALED, PositionFeatures.RIVAL_SEALED, PositionFeatures.CHAMBERS,
            PositionFeatures.CONTESTED, PositionFeatures.LIBERTIES, PositionFeatures.TRAPPED,
            PositionFeatures.RIVAL_LIBERTIES, PositionFeatures.RIVAL_TRAPPED, PositionFeatures.GROWS_NEXT,
            PositionFeatures.LENGTH_VS_ROOM, PositionFeatures.LENGTH_MARGIN, PositionFeatures.ISOLATED,
            PositionFeatures.HEAD_WALLS, PositionFeatures.TAIL_DISTANCE, PositionFeatures.RIVAL_DISTANCE,
            PositionFeatures.BOARD_FILL, PositionFeatures.TURN_PROGRESS, PositionFeatures.ISOLATED_MARGIN,
            PositionFeatures.CONTESTED_SHARE, PositionFeatures.FALLBACK_CHAIN, PositionFeatures.CHOKEPOINTS,
            PositionFeatures.COLOUR_IMBALANCE, PositionFeatures.TEMPO_MARGIN,
        )
        assertEquals((0 until PositionFeatures.LENGTH).toList(), indices.sorted())
    }

    @Test
    fun `every reading stays inside the unit range, on every board shape the contract runs`() {
        // The shapes BotContractTest seats a match on, plus the degenerate ones a search reaches at
        // the end of a game: a board with nothing free left is where a division by an empty region
        // would first show up. The walled pair are the same claims on a board that is not all
        // playable, which is where a denominator and the ground a sweep can actually walk come apart.
        val boards = listOf(
            boardOf(1, 1, 0 to 0),
            boardOf(1, 5, 0 to 0, 0 to 4),
            boardOf(2, 2, 0 to 0, 1 to 1),
            boardOf(3, 7, 0 to 0, 2 to 6),
            boardOf(8, 8, 0 to 0, 7 to 7, 0 to 7),
            boardOf(12, 12, 0 to 0, 11 to 11),
            boardOf(20, 20, 0 to 0, 19 to 19),
            boardOf(3, 3, 0 to 0, 2 to 2, walls = listOf(1 to 1)),
            boardOf(12, 12, 0 to 0, 11 to 11, walls = LATTICE),
        )

        for (board in boards) {
            val features = PositionFeatures(board.grid, board.snakeCount)
            features.measure(board)

            val row = DoubleArray(PositionFeatures.LENGTH)
            for (slot in 0 until board.snakeCount) {
                features.into(slot, row)
                for (i in 0 until PositionFeatures.LENGTH) {
                    assertTrue(
                        row[i].isFinite() && row[i] >= -1.0 && row[i] <= 1.0,
                        "${PositionFeatures.NAMES[i]} read ${row[i]} for slot $slot on " +
                            "${board.grid.rows}x${board.grid.cols}",
                    )
                }
            }
        }
    }

    @Test
    fun `and it holds through whole games, where the interesting positions are`() {
        // A hand-built board is an opening, and every reading here is about a board somebody has
        // been filling for a hundred moves. This is the only way to reach a shattered region, a
        // trapped rival and a board 70% full without drawing one.
        var seen = 0
        var sealedFired = 0
        var isolatedFired = 0
        var fallbackFired = 0
        var chokeFired = 0
        var imbalanceFired = 0
        var tempoFired = 0

        for (seed in 1L..4L) {
            walk(12, 12, seed) { features, board ->
                val row = DoubleArray(PositionFeatures.LENGTH)
                for (slot in 0 until board.snakeCount) {
                    if (!board.snake(SnakeId(slot)).alive) {
                        continue
                    }
                    features.into(slot, row)
                    seen++
                    for (i in 0 until PositionFeatures.LENGTH) {
                        assertTrue(
                            row[i].isFinite() && row[i] >= -1.0 && row[i] <= 1.0,
                            "${PositionFeatures.NAMES[i]} read ${row[i]} at turn ${board.turnIndex}",
                        )
                    }
                    if (row[PositionFeatures.SEALED] > 0.0) sealedFired++
                    if (row[PositionFeatures.ISOLATED] > 0.0) isolatedFired++
                    if (row[PositionFeatures.FALLBACK_CHAIN] > 0.0) fallbackFired++
                    if (row[PositionFeatures.CHOKEPOINTS] > 0.0) chokeFired++
                    if (row[PositionFeatures.COLOUR_IMBALANCE] > 0.0) imbalanceFired++
                    if (row[PositionFeatures.TEMPO_MARGIN] != 0.0) tempoFired++
                }
            }
        }

        assertTrue(seen > 400, "only $seen positions -- the fixture stopped being a game")
        assertTrue(sealedFired > 0, "no position in four games cut a snake off from its own ground")
        assertTrue(isolatedFired > 0, "no position in four games separated the snakes")

        // A firing rate for the four readings P4 added, in the test rather than in a transcript. A
        // reading that is zero everywhere costs a column of every baked weight and buys nothing, and
        // this is the cheapest place that fact can be asserted. Measured over these four games, of
        // 1,194 slot-positions:
        //
        // | reading | fires on |
        // |---|---|
        // | `fallbackChain` | 87, **7.3%** |
        // | `chokepoints` | 626, 52.4% |
        // | `colourImbalance` | 988, 82.7% |
        // | `tempoMargin` | 894, 74.9% |
        //
        // **The runner-up chain is the sparse one and that is what it is measuring**: it is non-zero
        // only where the head has a second branch worth anything, which is rarer than the region
        // merely coming apart -- `ChamberTree`'s own KDoc puts multi-chamber regions at 52% of
        // positions on the same fixture, which is where `chokepoints` lands. The thresholds below are
        // floors well under each figure; they assert a reading is alive, not that it is at 7.3%.
        assertTrue(fallbackFired > seen / 25, "the runner-up chain read zero in all but $fallbackFired of $seen")
        assertTrue(chokeFired > seen / 3, "the region held a chokepoint in only $chokeFired of $seen")
        assertTrue(imbalanceFired > seen / 2, "the colouring balanced exactly in all but $imbalanceFired of $seen")
        assertTrue(tempoFired > seen / 2, "the tempo margin was flat in all but $tempoFired of $seen")
    }

    @Test
    fun `a dead snake reads as nothing at all`() {
        val board = boardOf(6, 6, 0 to 0, 5 to 5)
        board.eliminate(SnakeId(0), EliminationReason.RESIGNED)

        val features = PositionFeatures(board.grid, 2)
        features.measure(board)

        val row = DoubleArray(PositionFeatures.LENGTH)
        features.into(0, row)
        for (i in 0 until PositionFeatures.LENGTH) {
            assertEquals(0.0, row[i], "${PositionFeatures.NAMES[i]} spoke for a corpse")
        }
    }

    @Test
    fun `the same opening reads the same on a small board and a large one`() {
        // The claim the whole vector exists to make. Two snakes in opposite corners of an empty
        // board is the same *position* at 10x10 and at 24x24 -- the squares differ by a factor of
        // six and every reading here is a share, so the rows have to agree.
        val small = rowFor(boardOf(10, 10, 0 to 0, 9 to 9))
        val large = rowFor(boardOf(24, 24, 0 to 0, 23 to 23))

        // The failure this catches is a raw count leaking in among the shares: the large board has
        // nearly six times the squares, so a count would read six times over rather than within a
        // tolerance. What the tolerance leaves room for is two residuals that are real geometry
        // rather than a defect -- the mover's half step of tempo is one line of frontier, which is a
        // smaller *share* of a bigger board, and a boundary is a perimeter where a chamber is an
        // area, so `contested` falls as the board grows.
        for (i in 0 until PositionFeatures.LENGTH) {
            assertTrue(
                abs(small[i] - large[i]) < 0.15,
                "${PositionFeatures.NAMES[i]} reads ${small[i]} on 10x10 and ${large[i]} on 24x24",
            )
        }
    }

    @Test
    fun `more ground reads as a positive margin, and its opponent's as the negative of it`() {
        // 1x6, heads at columns 1 and 5. West owns two squares to east's one -- LeafEvalTest's
        // fixture, so the reading here can be checked against a leaf whose answer is already pinned.
        val board = boardOf(1, 6, 0 to 1, 0 to 5)
        val features = PositionFeatures(board.grid, 2)
        features.measure(board)

        val west = DoubleArray(PositionFeatures.LENGTH)
        val east = DoubleArray(PositionFeatures.LENGTH)
        features.into(0, west)
        features.into(1, east)

        assertTrue(west[PositionFeatures.USABLE_MARGIN] > 0.0, "the wider side reads above even")
        assertEquals(
            west[PositionFeatures.USABLE_MARGIN],
            -east[PositionFeatures.USABLE_MARGIN],
            absoluteTolerance = 1e-12,
            message = "a margin between two snakes is antisymmetric",
        )
    }

    @Test
    fun `the contested share is what the chamber decomposition would have discounted`() {
        // Two snakes a step apart on a narrow board: nearly every square either can reach is one the
        // other is standing next to, so the boundary is most of the region rather than a line round
        // it. On an empty 20x20 from opposite corners it is the other way about.
        val close = rowFor(boardOf(3, 9, 1 to 3, 1 to 5))
        val apart = rowFor(boardOf(20, 20, 0 to 0, 19 to 19))

        assertTrue(
            close[PositionFeatures.CONTESTED] > apart[PositionFeatures.CONTESTED],
            "knife fight ${close[PositionFeatures.CONTESTED]} against opening ${apart[PositionFeatures.CONTESTED]}",
        )
    }

    @Test
    fun `the head's walls are the sides it cannot leave by, map or board edge alike`() {
        assertEquals(0.5, headWallsOn(boardOf(7, 7, 0 to 0)), "a corner is two sides")
        assertEquals(0.25, headWallsOn(boardOf(7, 7, 0 to 3)), "an edge is one")
        assertEquals(0.0, headWallsOn(boardOf(7, 7, 3 to 3)), "and inland is none")
        assertEquals(1.0, headWallsOn(boardOf(1, 1, 0 to 0)), "a board one square across is all four")
        assertEquals(0.5, headWallsOn(boardOf(1, 5, 0 to 2)), "as is the middle of a corridor")

        // The reading a comparison against the board's extent cannot make: the same square, and the
        // same answer, with the wall put there by the map instead of by the edge.
        assertEquals(
            headWallsOn(boardOf(7, 7, 0 to 3)),
            headWallsOn(boardOf(7, 7, 3 to 3, walls = listOf(3 to 2))),
            "a wall beside the head reads as an edge beside it",
        )
    }

    @Test
    fun `a fresh board reads as unfilled, however much of it is map`() {
        // The failure that normalising by the geometry produces, and it is not subtle: the map's own
        // squares read as ground somebody has already filled, so a match on a lattice opens at a fill
        // an empty rectangle only reaches some way in. What fills a board is snakes, and on turn one
        // that is the two heads.
        val bare = rowFor(boardOf(12, 12, 0 to 0, 11 to 11))
        val walled = rowFor(boardOf(12, 12, 0 to 0, 11 to 11, walls = LATTICE))
        val open = 12 * 12 - LATTICE.size

        assertEquals(2.0, bare[PositionFeatures.BOARD_FILL] * 12 * 12, 1e-9, "two heads and nothing else")
        assertEquals(
            2.0,
            walled[PositionFeatures.BOARD_FILL] * open,
            1e-9,
            "the map counted as ${walled[PositionFeatures.BOARD_FILL] * open - 2.0} squares of fill",
        )
    }

    @Test
    fun `a share of the board is a share of the squares that are not wall`() {
        // One snake on a connected map, so it owns everything there is to own and the region is the
        // whole open board bar the square under its own head. Against the geometry the same position
        // would read the map as ground this snake does not have.
        val walls = listOf(2 to 2, 2 to 6, 6 to 2, 6 to 6)
        val row = rowFor(boardOf(9, 9, 4 to 4, walls = walls))
        val open = 9 * 9 - walls.size

        assertEquals((open - 1).toDouble() / open, row[PositionFeatures.REGION_SHARE], 1e-12)
        assertTrue(row[PositionFeatures.REGION_SHARE] <= 1.0, "a share above one is a clamp doing the work")
    }

    // -- internals

    private fun headWallsOn(board: Board): Double = rowFor(board)[PositionFeatures.HEAD_WALLS]

    private fun rowFor(board: Board): DoubleArray {
        val features = PositionFeatures(board.grid, board.snakeCount)
        features.measure(board)
        return DoubleArray(PositionFeatures.LENGTH).also { features.into(0, it) }
    }

    /**
     * Plays a whole game between two chasers, handing [visit] the position before every move.
     *
     * `chase` because it is the strongest bot that spends no allowance, so a game is instant and
     * still ends in the filling endgame every reading here is about.
     */
    private fun walk(rows: Int, cols: Int, seed: Long, visit: (PositionFeatures, Board) -> Unit) {
        val grid = Grid(rows, cols)
        val board = Board(grid, cornerSpawns(grid, 2))
        val entry = ShippedBots.entryOf(BotId("chase"))
        val bots = Array(2) { slot -> entry.factory.create(setupFor(board, SnakeId(slot), seed)) }
        val features = PositionFeatures(grid, 2)

        while (board.outcome == null) {
            features.measure(board)
            visit(features, board)

            val id = board.toAct
            val decision = bots[id.index].chooseMove(turnOn(board, id, Budget(0)))
            when (decision) {
                is Decision.Move -> board.apply(id, decision.direction)
                else -> board.apply(id, Direction.NORTH)
            }
        }
    }

    private companion object {
        /**
         * A map for a 12x12: four pillars and a block in the middle, corners left for the spawns.
         *
         * Connected, so a lone snake reaches all of it, and symmetric under both reflections, so
         * neither corner is the better seat.
         */
        val LATTICE = listOf(
            3 to 3, 3 to 8, 8 to 3, 8 to 8,
            5 to 5, 5 to 6, 6 to 5, 6 to 6,
        )
    }
}
