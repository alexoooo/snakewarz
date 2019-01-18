package ao.sw.engine.v2;

import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;
import ao.sw.engine.board.Matrix;

import java.util.List;

/**
 * Snake thingy.
 */
public interface Snake
{
    Snake advance(Direction direction);

    List<BoardLocation> body();

    /**
     * the number of turns untill the
     *  given location is no longer occupied
     *  by this snake.
     * Returns -1 if the given location is not
     *  occupied by this snake at all.
     * @param loc ...
     * @return ...
     */
    int occupiedFor(BoardLocation loc);

    BoardLocation head();
    BoardLocation tail();

    /**
     * True if the snake will increase in size
     *  the next time advance is called.
     * If not then the tail will be moved foreward
     *  to match the movement of the head.
     *
     * @return see above comment.
     */
    boolean willGrow();

    Direction lastMoveDirection();

    //-----------------------------------------------
    public static class Util
    {
        private Util() {}

        public static void fillOutBody(Snake snake, Matrix in)
        {
            for (BoardLocation location : snake.body())
            {
                location.occupyIn( in );
            }
        }
    }
}
