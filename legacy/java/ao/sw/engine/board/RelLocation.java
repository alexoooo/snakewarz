package ao.sw.engine.board;

/**
 * 
 */
public class RelLocation
{
    //--------------------------------------------------------------------
    private BoardLocation center;
    private Direction     relTo;


    //--------------------------------------------------------------------
    private int foreBackOffset;
    private int leftRightOffset;


    //--------------------------------------------------------------------
    public RelLocation(BoardLocation from, Direction relativeTo)
    {
        this( from, relativeTo, 0, 0 );
    }

    private RelLocation(
            BoardLocation from,
            Direction relativeTo,
            int upDown,
            int leftRight)
    {
        center = from;
        relTo  = relativeTo;

        foreBackOffset  = upDown;
        leftRightOffset = leftRight;
    }


    //--------------------------------------------------------------------
    public int leftRightOffset()
    {
        return leftRightOffset;
    }

    public int foreBackOffset()
    {
        return foreBackOffset;
    }


    //--------------------------------------------------------------------
    public RelLocation offset(int foreBack, int leftRight)
    {
        return new RelLocation(
                    center, relTo,
                    foreBackOffset  + foreBack,
                    leftRightOffset + leftRight);
    }


    //--------------------------------------------------------------------
    public RelDirection directionTo(RelLocation there)
    {
        BoardLocation from = toBoardLocation();
        BoardLocation to   = there.toBoardLocation();

        RelDirection closest     = RelDirection.FOREWARD;
        double       closestDist = Double.MIN_VALUE;
        for (RelDirection direction : RelDirection.values())
        {
            Direction absDir = direction.relativeTo( relTo );
            double dist = absDir.translate(from)
                                    .euclidDistTo(to);
            if (dist < closestDist)
            {
                closest     = direction;
                closestDist = dist;
            }
        }

        return closest;
    }


    //--------------------------------------------------------------------
    public BoardLocation toBoardLocation()
    {
        Direction foreward =
                RelDirection.FOREWARD.relativeTo( relTo );
        Direction left =
                RelDirection.LEFT.relativeTo( relTo );

        return left.translate(
                    foreward.translate(center, foreBackOffset),
                    leftRightOffset);
    }


    //--------------------------------------------------------------------
    @Override
    public String toString()
    {
        return "center: " + center + ", " +
               "relTo: "  + relTo  + ", " +
               "^v "      + foreBackOffset + ", " +
               "<>"       + leftRightOffset;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || !(o instanceof RelLocation)) return false;

        RelLocation that = (RelLocation) o;

        return foreBackOffset == that.foreBackOffset &&
               leftRightOffset == that.leftRightOffset &&
               !(center != null
                 ? !center.equals(that.center)
                 : that.center != null)
               && relTo == that.relTo;
    }

    @Override
    public int hashCode()
    {
        int result;
        result = (center != null ? center.hashCode() : 0);
        result = 31 * result + (relTo != null ? relTo.hashCode() : 0);
        result = 31 * result + foreBackOffset;
        result = 31 * result + leftRightOffset;
        return result;
    }
}
