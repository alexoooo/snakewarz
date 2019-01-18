package ao.sw.engine.board;

import ao.sw.engine.v2.Snake;

// zero based access.

public class MatrixBoardArrangement implements BoardArrangement
{
    //--------------------------------------------------------------------
    private final Matrix occupied;


    //--------------------------------------------------------------------
    public MatrixBoardArrangement(
            final int numRows, final int numCols)
    {
        occupied = new BitSetMatrix( numRows, numCols );
    }

    public MatrixBoardArrangement(final Matrix occupied)
    {
        this.occupied = occupied.prototype();
    }

    private MatrixBoardArrangement(
            final Matrix occupied, final BoardLocation toOccupy)
    {
        this.occupied = occupied.prototype();

        toOccupy.occupyIn( this.occupied );
    }


    //--------------------------------------------------------------------
    public final int getColumnCount()
    {
        return occupied.getColumnCount();
    }

    public final int getRowCount()
    {
        return occupied.getRowCount();
    }

    public final int occupiedCount()
    {
        return occupied.occupiedCount();
    }

    public final boolean withinBounds( int row, int column )
    {
        return occupied.withinBounds( row, column );
    }

    public final boolean isAvailable( int row, int column )
    {
        return occupied.isAvailable( row, column );
    }

    public BoardArrangement occupy(int row, int column)
    {
        return new MatrixBoardArrangement(
                occupied, new BoardLocationImpl(row, column) );
    }

//    public BoardArrangement occupy(Snake snake)
//    {
//        return new MatrixBoardArrangement(
//                occupied, snake.head() );
//    }


    //--------------------------------------------------------------------
    public BoardArrangement advance(Snake snake, Direction direction)
    {
        MatrixBoardArrangement advanced =
                new MatrixBoardArrangement(
                        occupied,
                        direction.translate( snake.head() ));

        if (! snake.willGrow())
        {
            advanced.occupied.clear(
                    snake.tail().getRow(),
                    snake.tail().getColumn());
        }

        return advanced;
    }


    //--------------------------------------------------------------------
    public Matrix toMatrix()
    {
        return occupied;
    }


    //--------------------------------------------------------------------
    public String toString()
    {
        return occupied.toString();
    }

    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MatrixBoardArrangement that = (MatrixBoardArrangement) o;

        if (occupied != null ? !occupied.equals(that.occupied) : that.occupied != null) return false;

        return true;
    }

    public int hashCode()
    {
        return (occupied != null ? occupied.hashCode() : 0);
    }
}
