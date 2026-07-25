package ao.sw.engine.board;

/**
 * created: Jul 28, 2005  12:56:41 AM
 */
public class BoardLocationImpl implements BoardLocation
{
    //--------------------------------------------------------------------
    private final int row;
    private final int column;


    //--------------------------------------------------------------------
    public BoardLocationImpl(int row, int column)
    {
        this.row    = row;
        this.column = column;
    }


    //--------------------------------------------------------------------
    public final int getRow()
    {
        return row;
    }

    public final int getColumn()
    {
        return column;
    }


    //--------------------------------------------------------------------
    public int offsetRow( int byHowMuch )
    {
        return row + byHowMuch;
    }

    public int offsetColumn( int byHowMuch )
    {
        return column + byHowMuch;
    }


    //--------------------------------------------------------------------
    public boolean availableIn( BoardArrangement board )
    {
        return board.isAvailable( row, column );
    }

    public boolean availableIn( Matrix matrix )
    {
        return matrix.isAvailable( row, column );
    }


    //--------------------------------------------------------------------
    public BoardArrangement occupyIn( BoardArrangement board )
    {
        return board.occupy( row, column );
    }

    public void occupyIn( Matrix matrix )
    {
        matrix.occupy( row, column );
    }


    //--------------------------------------------------------------------
    public BoardLocation translate( int howManyRows, int howManyColumns )
    {
        return new BoardLocationImpl(
                        row + howManyRows,
                        column + howManyColumns);
    }


    //--------------------------------------------------------------------
    public int distanceTo(BoardLocation other)
    {
        return  (Math.abs(getColumn() - other.getColumn()) +
                 Math.abs(getRow()    - other.getRow()   ) );
    }

    public double euclidDistTo(BoardLocation other)
    {
        int colDelta = getColumn() - other.getColumn();
        int rowDelta = getRow()    - other.getRow();

        return Math.sqrt(colDelta * colDelta + rowDelta * rowDelta);
    }


    //--------------------------------------------------------------------
    public Direction directionTo(BoardLocation other)
    {
        double    minDistance = Double.MAX_VALUE;
        Direction minDistDir  = null;

        for (Direction dir : Direction.values())
        {
            double distance = dir.translate( this ).euclidDistTo(other);
            if (minDistance > distance)
            {
                minDistance = distance;
                minDistDir  = dir;
            }
        }

        return minDistDir;
    }


    //--------------------------------------------------------------------
    @Override
    public String toString()
    {
        return "row: " + row + " column: " + column;
    }

    @Override
    public final boolean equals( Object obj )
    {
        if (this == obj) return true;
        if (obj == null || !(obj instanceof BoardLocation)) return false;

        final BoardLocation that = (BoardLocation) obj;

        return (row == that.getRow()) && (column == that.getColumn());
    }

    @Override
    public final int hashCode()
    {
        return 29 * row + column;
    }
}

