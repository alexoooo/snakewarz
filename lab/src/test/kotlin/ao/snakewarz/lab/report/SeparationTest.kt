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
import kotlin.test.assertTrue

/**
 * [Separation], and the claim worth pinning: the conservative predicate the research agenda proposes
 * as a test for *permanent* separation can never be true in a two-snake match on a rectangle.
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
    fun `the free squares come apart in a real game and the conservative flood never does`() {
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
