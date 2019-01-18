package ao.sw.engine.v2;

import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;

import java.util.LinkedList;
import java.util.List;

/**
 *
 */
public class MutableSnake implements Snake
{
    //--------------------------------------------------------------------
    private final LinkedList<BoardLocation> BODY =
            new LinkedList<BoardLocation>();
    private       boolean                   willGrow;


    //--------------------------------------------------------------------
    public MutableSnake(Snake copy)
    {
        BODY.addAll( copy.body() );
        willGrow = copy.willGrow();
    }


    //--------------------------------------------------------------------
    public Snake advance(Direction direction)
    {
        BoardLocation addend =
                direction.translate( BODY.getLast() );

        if (! willGrow)
        {
            BODY.removeFirst();
        }
        BODY.add( addend );
        willGrow = !willGrow;

        return this;
    }

    public List<BoardLocation> body()
    {
        return BODY;
    }

    public int occupiedFor(BoardLocation loc)
    {
        return -1;
    }

    public BoardLocation head()
    {
        return BODY.getLast();
    }

    public BoardLocation tail()
    {
        return BODY.getFirst();
    }

    public boolean willGrow()
    {
        return willGrow;
    }

    public Direction lastMoveDirection()
    {
        if (BODY.size() < 2) return null;

        BoardLocation from = BODY.get( BODY.size() - 2 );
        BoardLocation to   = BODY.get( BODY.size() - 1 );

        return from.directionTo( to );
    }
}
