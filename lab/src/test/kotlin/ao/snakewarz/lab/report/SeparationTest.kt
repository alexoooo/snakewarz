package ao.snakewarz.lab.report

import ao.snakewarz.botapi.knob.BotParams
import ao.snakewarz.botapi.registry.BotId
import ao.snakewarz.bots.ShippedBots
import ao.snakewarz.core.grid.Direction
import ao.snakewarz.core.grid.Grid
import ao.snakewarz.core.rules.Board
import ao.snakewarz.core.rules.RulesConfig
import ao.snakewarz.match.Match
import ao.snakewarz.match.MatchSetup
import ao.snakewarz.match.StepResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Separation], and the two claims worth pinning, which are a pair rather than one claim and its
 * caveat.
 *
 * The conservative predicate — the test for *permanent* separation — can never be true in a
 * two-snake match on a rectangle, because such a match ends at the first death and so never holds a
 * corpse. **Seat a third snake and it becomes a real predicate**, and both halves are tested here:
 * the vacuity at two, structurally and over a real game, and the smallest position at three where it
 * is true.
 */
class SeparationTest {
    @Test
    fun `two snakes in the open share ground`() {
        val board = boardOf(5, 5, 0 to 0, 4 to 4)
        val separation = Separation(board.grid)

        assertFalse(separation.naive(board), "nothing has come between them yet")
        assertFalse(separation.permanent(board))
    }

    @Test
    fun `two heads facing across one corridor are not separated, though neither flood enters the other`() {
        // The case a head-to-head collision test gets wrong. A head is occupied, so under the
        // free-squares reading no flood ever walks into one -- the meeting has to be detected on the
        // ground between them, and here that ground is a single square both would claim.
        val board = boardOf(1, 3, 0 to 0, 0 to 2)
        val separation = Separation(board.grid)

        assertFalse(separation.naive(board), "one free square joins them and belongs to both")
    }

    @Test
    fun `a rival head beside this snake's ground is contact, with no ground between them at all`() {
        // `SpaceOwnership.meetsAnybody` reaches the same conclusion by a different route, and it is
        // the subtlety that broke the bitboard conversion: a head is occupied, so no free-square test
        // sees it, and a snake standing one move away has plainly not been separated from anybody.
        val board = boardOf(1, 5, 0 to 0, 0 to 4)
        board.apply(board.toAct, Direction.EAST)
        board.apply(board.toAct, Direction.WEST)
        board.apply(board.toAct, Direction.EAST)

        val separation = Separation(board.grid)
        assertFalse(separation.naive(board), "the two heads are adjacent")
    }

    @Test
    fun `room is the free squares a head can reach, and a snake with none has none`() {
        val board = boardOf(1, 5, 0 to 0, 0 to 4)
        board.apply(board.toAct, Direction.EAST)
        board.apply(board.toAct, Direction.WEST)
        board.apply(board.toAct, Direction.EAST)

        val separation = Separation(board.grid)
        assertEquals(0, separation.roomOf(board, 0), "its own body one side, a rival the other")
        assertEquals(1, separation.roomOf(board, 1), "one square of its own left")
    }

    @Test
    fun `a corpse is a permanent wall, which is what the third seat buys`() {
        // The smallest position that falsifies the vacuity above, and it needs exactly three seats
        // to exist. On a 1x5 corridor the outer two close in, the middle one is trapped between
        // them, and the match runs on with two alive -- which a two-snake match never does. What is
        // left of the third is wall under both readings for the rest of the game.
        //
        // The spawns are `mostDistantSpawns(Grid(1, 5), 3)` exactly: 0, 4, then 2.
        val board = boardOf(1, 5, 0 to 0, 0 to 4, 0 to 2)
        board.apply(board.toAct, Direction.EAST) // slot 0 walks in from the left
        board.apply(board.toAct, Direction.WEST) // slot 1 walks in from the right
        board.apply(board.toAct, Direction.EAST) // slot 2, in the middle, has nowhere to go

        assertEquals(2, board.aliveCount, "the middle snake was walled in by the other two")
        assertNull(board.outcome, "and two of three still standing is not a finished match")

        val separation = Separation(board.grid)
        assertTrue(separation.naive(board), "the free squares lie either side of the corpse")
        assertTrue(separation.permanent(board), "and no retraction can ever join them again")
    }

    @Test
    fun `at three seats a living body is still not a permanent wall`() {
        // The other half: seating a third snake does not make the conservative reading cheap. Before
        // anybody dies the passable set is the whole rectangle again, whatever the free squares say,
        // so the predicate is answering the question it was built to answer rather than tracking
        // `naive` with an extra seat on the board.
        val board = boardOf(1, 5, 0 to 0, 0 to 4, 0 to 2)
        val separation = Separation(board.grid)

        assertFalse(separation.naive(board), "the middle snake is in contact with both")
        assertFalse(separation.permanent(board), "and every body on the board still retracts")
    }

    @Test
    fun `the free squares come apart in a two-snake game and the conservative flood never does`() {
        // The whole reason `PhasesCommand` splits a match with hindsight rather than on a predicate.
        // Under the conservative reading a living body is ground, a rectangle minus its wall ring is
        // connected, and a two-snake match ends at the first death -- so there is never a corpse to
        // disconnect anything and the answer is always the same one.
        val setup = MatchSetup.create(
            rows = 12,
            cols = 12,
            slots = listOf(BotId("chase"), BotId("space")),
            seed = 7,
            budgetPerTurn = 0,
            budgets = intArrayOf(0, 0),
            slotParams = listOf(BotParams.EMPTY, BotParams.EMPTY),
        )
        val match = Match(setup, ShippedBots)
        val separation = Separation(match.grid)

        var contested = 0
        var apart = 0
        while (match.outcome == null) {
            val view = match.view
            if (view.aliveCount > 1) {
                if (separation.naive(view)) apart++ else contested++
                assertFalse(separation.permanent(view), "a living body is not a permanent wall")
            }
            if (match.step() == StepResult.AwaitingInput) {
                break
            }
        }

        assertTrue(contested > 0, "the game started with the board shared")
        assertTrue(apart > 0, "and the free squares came apart before it ended")
    }

    private fun boardOf(rows: Int, cols: Int, vararg spawns: Pair<Int, Int>): Board {
        val grid = Grid(rows, cols)
        return Board(
            grid,
            IntArray(spawns.size) { grid.cellAt(spawns[it].first, spawns[it].second).index },
            RulesConfig(),
        )
    }
}
