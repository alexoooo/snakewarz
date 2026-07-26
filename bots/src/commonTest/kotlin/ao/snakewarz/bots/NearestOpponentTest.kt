package ao.snakewarz.bots

import ao.snakewarz.core.Direction
import ao.snakewarz.core.EliminationReason
import ao.snakewarz.core.SnakeId
import kotlin.test.Test
import kotlin.test.assertEquals

class NearestOpponentTest {
    @Test
    fun `a walled-off opponent is the farthest away, not the nearest`() {
        // The legacy defect, pinned. `AStar.pathBetween` returned an empty list for an unreachable
        // target and `PvpAi` read its size() as the distance -- so 0, the smallest distance there
        // is, and a snake sealed behind a wall was reliably chosen as the one to duel.
        //
        // Slot 1 sits at (0,0) with slot 2 at (0,1) in front of it, so nothing beside slot 1 can be
        // reached. Slot 2 is four steps away and is the right answer.
        val board = boardOf(1, 5, 0 to 3, 0 to 0, 0 to 1)
        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(0, 3))

        assertEquals(ShortestPaths.UNREACHABLE, paths.distanceBeside(board.snake(SnakeId(1)).head))
        assertEquals(SnakeId(2), nearestOpponent(board, SnakeId(0), paths))
    }

    @Test
    fun `the nearer of two reachable opponents wins`() {
        val board = boardOf(5, 9, 2 to 4, 2 to 8, 2 to 0)
        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(2, 4))

        assertEquals(SnakeId(1), nearestOpponent(board, SnakeId(0), paths), "slot 1 is four east, slot 2 is four west")

        val closer = boardOf(5, 9, 2 to 4, 2 to 8, 2 to 2)
        val closerPaths = ShortestPaths(closer.grid)
        closerPaths.scanFrom(closer, closer.at(2, 4))

        assertEquals(SnakeId(2), nearestOpponent(closer, SnakeId(0), closerPaths), "now slot 2 is two west")
    }

    @Test
    fun `a tie goes to the lower slot, because this is a reduction and not a choice`() {
        val board = boardOf(1, 5, 0 to 2, 0 to 0, 0 to 4)
        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(0, 2))

        assertEquals(2, paths.distanceBeside(board.snake(SnakeId(1)).head))
        assertEquals(2, paths.distanceBeside(board.snake(SnakeId(2)).head))
        assertEquals(SnakeId(1), nearestOpponent(board, SnakeId(0), paths))
    }

    @Test
    fun `a corpse is not an opponent, however conveniently placed`() {
        // Liveness is read off the board rather than from BotSetup.opponents, which is fixed at
        // setup and cannot know who has left. The body stays as an obstacle either way.
        val board = boardOf(2, 4, 0 to 0, 0 to 1)
        board.apply(SnakeId(0), Direction.SOUTH)
        board.eliminate(SnakeId(1), EliminationReason.RESIGNED)

        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.snake(SnakeId(0)).head)

        assertEquals(SnakeId.NONE, nearestOpponent(board, SnakeId(0), paths), "the only opponent is dead")
    }

    @Test
    fun `a solo snake has nobody to chase`() {
        // The contract suite runs solo boards, and this is where a naive port divides by zero.
        val board = boardOf(8, 8, 0 to 0)
        val paths = ShortestPaths(board.grid)
        paths.scanFrom(board, board.at(0, 0))

        assertEquals(SnakeId.NONE, nearestOpponent(board, SnakeId(0), paths))
    }
}
