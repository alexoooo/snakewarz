package ao.sw.engine.board;

import ao.sw.engine.player.MoveSpecifier;

/**
 * Direction relative to current position.
 */
public enum RelDirection
{
    LEFT, RIGHT, FOREWARD, BACKWARD;

    public Direction relativeTo(Direction dir)
    {
        switch (this)
        {
            case FOREWARD:
                return dir;

            case LEFT:
                switch (dir)
                {
                    case NORTH: return Direction.WEST;
                    case WEST:  return Direction.SOUTH;
                    case SOUTH: return Direction.EAST;
                    case EAST:  return Direction.NORTH;
                }

            case RIGHT:
                switch (dir)
                {
                    case NORTH: return Direction.EAST;
                    case EAST:  return Direction.SOUTH;
                    case SOUTH: return Direction.WEST;
                    case WEST:  return Direction.NORTH;
                }

            case BACKWARD:
                switch (dir)
                {
                    case NORTH: return Direction.SOUTH;
                    case SOUTH: return Direction.NORTH;
                    case EAST:  return Direction.WEST;
                    case WEST:  return Direction.EAST;
                }
        }

        assert false : "should never be here.";
        return null;
    }

    public boolean isAvailableIn(
            MoveSpecifier    move,
            BoardArrangement board,
            BoardLocation    relativeTo)
    {
        Direction dir = relativeTo(move.latestLatestDirection());
        return dir.isAvailableIn(board, relativeTo);
    }


    public Direction toAbs(
            Direction        relTo,
            BoardArrangement board,
            BoardLocation    fromWhere)
    {
        if (relTo != null)
        {
            return relativeTo(relTo);
        }
        else if (board != null && fromWhere != null)
        {
            return Direction.availableFrom(
                            board, fromWhere
                    ).iterator().next();
        }

        return null;
    }
}
