package ao.sw.engine.board;

import java.util.BitSet;

//import ao.sw.util.BitSet;


/**
 * created: Aug 12, 2005  7:39:00 PM
 */
public class BitSetMatrix implements Matrix
{
    private BitSet occupied = new BitSet( );

    private final int numRows;
    private final int numCols;

    public BitSetMatrix( int numRows, int numColumns )
    {
        assert numRows    > 0;
        assert numColumns > 0;

        this.numRows = numRows;
        this.numCols = numColumns;
    }

    public final int getColumnCount()
    {
        return numCols;
    }

    public final int getRowCount()
    {
        return numRows;
    }

    public boolean isAvailable( int row, int column )
    {
        return withinBounds(row, column) &&
                !occupied.get( index(row, column) );
    }

    public boolean occupy( int row, int column )
    {
        if (isAvailable( row, column ))
        {
            occupied.set( index(row, column) );
            return true;
        }
        return false;
    }

    public void clear(int row, int column)
    {
        occupied.clear( index(row, column) );
    }

    public void clear()
    {
        occupied.clear();
    }

    public int occupiedCount()
    {
        return occupied.cardinality();
    }

    public Matrix prototype()
    {
        BitSetMatrix copy = new BitSetMatrix( numRows, numCols );
        copy.occupied = (BitSet) occupied.clone();
        return copy;
    }

    public void logicalOr( Matrix otherMatrix )
    {
        assert otherMatrix instanceof BitSetMatrix;
        occupied.or( ((BitSetMatrix) otherMatrix).occupied );
    }
    public void logicalSubtract( Matrix otherMatrix )
    {
        assert otherMatrix instanceof BitSetMatrix;
        occupied.andNot( ((BitSetMatrix) otherMatrix).occupied );
    }

//    public int traversability()
//    {
//        int count = 0;
//        int connectivity = 0;
//
//        for(int i = occupied.nextSetBit(0); i >=0 ; i = occupied.nextSetBit(i+1))
//        {
//            int row = rowOfIndex( i );
//            int col = columnOfIndex( i );
//
//            if (isOccupied(row - 1, col)) connectivity++;
//            if (isOccupied(row + 1, col)) connectivity++;
//            if (isOccupied(row, col - 1)) connectivity++;
//            if (isOccupied(row, col + 1)) connectivity++;
//
//            connectivity++;
//            count++;
//        }
//
//        return (int) Math.round(count * ( 1.0 - 1.0 / (Math.sqrt(connectivity) + 1)));
//    }

//    private final boolean isOccupied( int row, int column )
//    {
//        return withinBounds(row, column) && occupied.get( index(column, column) );
//    }
//
//    private final int rowOfIndex( int index )
//    {
//        return index / numCols;
//    }
//
//    private final int columnOfIndex( int index )
//    {
//        return index % numCols;
//    }


    //--------------------------------------------------------------------
    public boolean withinBounds( int row, int column )
    {
        return (0 <= row && row < numRows) &&
                (0 <= column && column < numCols);
    }

    private int index( int row, int column )
    {
        return numCols * row + column;
    }


    //--------------------------------------------------------------------
    public boolean equals(Object obj)
    {
        if (this == obj) return true;
        if (obj == null) return false;
        if (! (obj instanceof BitSetMatrix)) return false;

        BitSetMatrix other = (BitSetMatrix) obj;
        return numRows == other.numRows &&
                numCols == other.numCols &&
                occupied.equals( other.occupied );
    }

    public int hashCode()
    {
        int result;
        result = (occupied != null ? occupied.hashCode() : 0);
        result = 31 * result + numRows;
        result = 31 * result + numCols;
        return result;
    }

    public String toString()
    {
        StringBuilder str = new StringBuilder();

        for (int row = 0; row < numRows; row++)
        {
            for (int col = 0; col < numCols; col++)
            {
                if (isAvailable(row, col))
                {
                    str.append(' ');
                }
                else
                {
                    str.append('#');
                }
            }
            str.append('\n');
        }

        return str.toString();
    }
}
