package ao.sw.engine.move;

import ao.sw.engine.board.BoardArrangement;
import ao.sw.engine.board.BoardLocation;
import ao.sw.engine.board.Direction;
import ao.sw.engine.board.RelDirection;
import ao.sw.engine.player.MoveSpecifier;
import ao.util.math.rand.Rand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

/**
 * Ignores moves in certain directions.
 */
public class RestrictedMoveSpecifier implements MoveSpecifier
{
    //--------------------------------------------------------------------
    private Collection<Direction> allowed;

    //--------------------------------------------------------------------
    private MoveSpecifier DELEGET;


    //--------------------------------------------------------------------
    public RestrictedMoveSpecifier(MoveSpecifier deleget)
    {
        DELEGET = deleget;
        allowed = new HashSet<Direction>(4);
    }


    //--------------------------------------------------------------------
    public void addAllow(Direction direction)
    {
        allowed.add(direction);
    }

    public void removeAllow(Direction direction)
    {
        allowed.remove(direction);
    }

    public void varifyLatest()
    {
        if (allowed.isEmpty()) return;

        if (DELEGET.latestDirection() == null ||
                (! allowed.contains(
                        DELEGET.latestDirection())))
        {
            DELEGET.setDirection(
                    Rand.fromList(
                            new ArrayList<Direction>(allowed)));
        }
    }


    //--------------------------------------------------------------------
    public Direction latestDirection()
    {
        return allowed.contains(
                  DELEGET.latestDirection())
                ? DELEGET.latestDirection()

                : (! allowed.isEmpty())
                ?    allowed.iterator().next()
                
                : null;

//        if (allowed.contains(DELEGET.latestDirection()))
//        {
//            return DELEGET.latestDirection();
//        }
//        else if (! allowed.isEmpty())
//        {
//            return allowed.iterator().next();
//        }
//        else
//        {
//            return null;
//        }
    }


    //--------------------------------------------------------------------
    public Direction latestLatestDirection()
    {
        return DELEGET.latestLatestDirection();
    }


    //--------------------------------------------------------------------
    public void clearDirection()
    {
        DELEGET.clearDirection();
    }


    //--------------------------------------------------------------------
    public void setDirection(
            Direction whereToGo)
    {
        if (whereToGo == null ||
                allowed.contains(whereToGo))
        {
            DELEGET.setDirection(whereToGo);
        }
    }


    //--------------------------------------------------------------------
    public Direction setDirection(
            RelDirection whereToGo)
    {
        return setDirection( whereToGo, null, null );
    }

    public Direction setDirection(
            RelDirection whereToGo,
            BoardArrangement board,
            BoardLocation fromWhere)
    {
        Direction latestLatestDirection =
                DELEGET.latestLatestDirection();

        if (! allowed.contains(
                DELEGET.setDirection(
                        whereToGo, board, fromWhere)))
        {
            DELEGET.setDirection(latestLatestDirection);
        }

        return DELEGET.latestDirection();
    }
}
