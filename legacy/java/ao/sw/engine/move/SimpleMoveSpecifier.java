package ao.sw.engine.move;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;
import ao.sw.engine.board.RelDirection;
import ao.sw.engine.player.MoveSpecifier;

import java.util.concurrent.atomic.AtomicReference;

/**
 * created: Aug 5, 2005  8:00:12 PM
 */
public class SimpleMoveSpecifier implements MoveSpecifier
{
    private final AtomicReference<Direction> latest =
            new AtomicReference<Direction>( );

    private final AtomicReference<Direction> latestLatest =
            new AtomicReference<Direction>( );

    public Direction latestDirection()
    {
        Direction dir = latest.get();
        latestLatest.set(dir);
        return dir;
    }

    public Direction latestLatestDirection()
    {
        return latestLatest.get();
    }

    public void clearDirection()
    {
        latest.set( null );
    }

    public void setDirection( Direction whereToGo )
    {
//        if (whereToGo != null)
//        {
            latest.set( whereToGo );
//        }
    }

    public Direction setDirection(
            RelDirection     whereToGo,
            BoardArrangement board,
            BoardLocation    fromWhere)
    {
        if (whereToGo == null)
        {
            latest.set( null );
        }
        else
        {
            Direction dir =
                    whereToGo.toAbs(
                            latestLatestDirection(),
                            board,
                            fromWhere);

            latest.set( dir );
        }

        return latest.get();
    }

    public Direction setDirection(RelDirection whereToGo)
    {
        return setDirection( whereToGo, null, null );
    }
}
